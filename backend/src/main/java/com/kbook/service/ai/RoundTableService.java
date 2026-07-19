package com.kbook.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.exception.BusinessException;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.config.ai.AiConfig;
import com.kbook.config.ai.AiConfigProvider;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.config.annotation.RedisLock;
import com.kbook.constants.AiPromptConstants;
import com.kbook.dto.roundtable.NextSpeakerResult;
import com.kbook.dto.roundtable.RoleVO;
import com.kbook.dto.roundtable.RoundTableSessionFeedVO;
import com.kbook.dto.roundtable.SpeakRequest;
import com.kbook.entity.AiScene;
import com.kbook.entity.AiProviderConfig;
import com.kbook.entity.Book;
import com.kbook.entity.RoundTableCoverage;
import com.kbook.entity.RoundTableMessage;
import com.kbook.entity.RoundTableSession;
import com.kbook.repository.BookRepository;
import com.kbook.repository.RoundTableCoverageRepository;
import com.kbook.repository.RoundTableMessageRepository;
import com.kbook.repository.RoundTableSessionRepository;
import com.kbook.service.ai.core.ChatHistoryCompressor;
import com.kbook.service.ai.core.ExternalKnowledgeGenerator;
import com.kbook.service.ai.streaming.StreamingSseHandler;
import com.kbook.service.book.BookParserService;
import com.kbook.service.book.BookService;
import com.kbook.service.embedding.EmbeddingService;
import com.kbook.service.progress.ReadingProgressService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.kbook.common.util.QueryBuilder.eq;
import static com.kbook.common.util.QueryBuilder.in;

/**
 * 圆桌派服务 — 多角色 AI 讨论功能
 * <p>
 * 新架构：
 * - LLM 根据书籍内容智能推荐角色并赋值 domainRelevance
 * - 会话和消息持久化到数据库，支持历史回放
 * - 消息压缩机制（与 AiConversation 同模式）
 * - 每个角色独立调用 AI 生成发言，前端控制谁先发言（抢麦机制）
 */
@Slf4j
@Service
@LogModule("圆桌派")
public class RoundTableService {

    private final EmbeddingService embeddingService;
    private final BookService bookService;
    private final BookParserService bookParserService;
    private final ChatModelManager chatModelManager;
    private final ChatHistoryCompressor chatHistoryCompressor;
    private final ExternalKnowledgeGenerator externalKnowledgeGenerator;
    private final AiProviderConfigService aiProviderConfigService;
    private final AiSceneConfigService aiSceneConfigService;
    private final RoundTableSessionRepository sessionRepository;
    private final RoundTableMessageRepository messageRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final RoundTableCoverageService coverageService;
    private final ReadingProgressService readingProgressService;
    private final AiConfigProvider aiConfigProvider;
    private final ExecutorService sseExecutor;
    private final BookRepository bookRepository;
    private final RoundTableCoverageRepository roundTableCoverageRepository;

    public RoundTableService(
            EmbeddingService embeddingService,
            BookService bookService,
            BookParserService bookParserService,
            ChatModelManager chatModelManager,
            ChatHistoryCompressor chatHistoryCompressor,
            ExternalKnowledgeGenerator externalKnowledgeGenerator,
            AiProviderConfigService aiProviderConfigService,
            AiSceneConfigService aiSceneConfigService,
            RoundTableSessionRepository sessionRepository,
            RoundTableMessageRepository messageRepository,
            StringRedisTemplate stringRedisTemplate,
            RoundTableCoverageService coverageService,
            ReadingProgressService readingProgressService,
            AiConfigProvider aiConfigProvider,
            @Qualifier("sseExecutor") ExecutorService sseExecutor,
            ObjectMapper objectMapper,
            @Lazy BookRepository bookRepository,
            @Lazy RoundTableCoverageRepository roundTableCoverageRepository) {
        this.embeddingService = embeddingService;
        this.bookService = bookService;
        this.bookParserService = bookParserService;
        this.chatModelManager = chatModelManager;
        this.chatHistoryCompressor = chatHistoryCompressor;
        this.externalKnowledgeGenerator = externalKnowledgeGenerator;
        this.aiProviderConfigService = aiProviderConfigService;
        this.aiSceneConfigService = aiSceneConfigService;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.stringRedisTemplate = stringRedisTemplate;
        this.coverageService = coverageService;
        this.readingProgressService = readingProgressService;
        this.aiConfigProvider = aiConfigProvider;
        this.sseExecutor = sseExecutor;
        this.objectMapper = objectMapper;
        this.bookRepository = bookRepository;
        this.roundTableCoverageRepository = roundTableCoverageRepository;
    }

    // ================================================================
    // 圆桌派域 AI 调用
    // ================================================================

    /**
     * 从书籍信息选择最适合的讨论嘉宾。
     *
     * @return AI 原始响应文本（JSON 数组），由调用方解析
     */
    public String callAiForRoleSelection(long bookId, String bookTitle, String bookInfo, List<String> excludeKeys) {
        String roleList = aiConfigProvider.buildRoundTableRoleListForPrompt();
        String excludeClause = (excludeKeys != null && !excludeKeys.isEmpty())
                ? "4. 【刷新】以下角色已选过，这次必须换一批不同的人：" + String.join(", ", excludeKeys)
                : "";
        String systemPrompt = String.format(AiPromptConstants.ROUND_TABLE_ROLE_SELECTION_SYSTEM_PROMPT_TEMPLATE, excludeClause, roleList);
        return chatModelManager.callAiForScene(AiScene.ROUND_TABLE_ROLE_RECOMMEND, "圆桌派角色推荐",
                String.format("bookId=%d, title=%s", bookId, bookTitle), List.of(
                        SystemMessage.from(systemPrompt),
                        UserMessage.from("书籍信息：\n" + bookInfo)));
    }

    /**
     * 回退模式：纯关键词硬匹配角色推荐。
     *
     * @return AI 原始响应文本（逗号分隔的角色 key 列表）
     */
    public String callAiForRoleSelectionFallback(long bookId, String bookTitle, String bookInfo, String roleList) {
        return chatModelManager.callAiForScene(AiScene.ROUND_TABLE_ROLE_RECOMMEND, "圆桌派角色推荐(回退)",
                String.format("bookId=%d, title=%s", bookId, bookTitle), List.of(
                        SystemMessage.from(AiPromptConstants.ROLE_SELECTION_FALLBACK_SYSTEM_PROMPT),
                        UserMessage.from("角色列表：" + roleList + "\n\n书籍信息：\n" + bookInfo)));
    }

    /**
     * 为特定角色生成向量检索查询文本。
     */
    public String callAiForRoleSearchQuery(String roleKey, String bookTitle, String roleName,
                                           String roleTitle, String roleKeywords, String recentDiscussion) {
        String systemPrompt = AiPromptConstants.ROLE_SEARCH_QUERY_SYSTEM_PROMPT;

        String userPrompt = String.format("""
                【图书】%s
                【角色】%s（%s）
                【角色关注领域】%s
                【最近讨论】
                %s""", bookTitle, roleName, roleTitle, roleKeywords, recentDiscussion);

        return chatModelManager.callAiForScene(AiScene.ROUND_TABLE_ROLE_SEARCH, "圆桌派角色检索查询",
                "role=" + roleKey, List.of(
                        SystemMessage.from(systemPrompt),
                        UserMessage.from(userPrompt)));
    }

    /**
     * 为特定角色生成多个向量检索查询短语（每行一个），用于多子查询 RAG 检索。
     */
    public List<String> callAiForRoleSearchQueries(String roleKey, String bookTitle, String roleName,
                                                   String roleTitle, String roleKeywords,
                                                   String recentDiscussion, int subQueryCount,
                                                   String perspectiveHint) {
        String perspective = (perspectiveHint == null || perspectiveHint.isBlank())
                ? "从该角色的专业视角出发" : perspectiveHint;
        String systemPrompt = String.format(
                AiPromptConstants.ROLE_SEARCH_QUERIES_SYSTEM_PROMPT_TEMPLATE,
                subQueryCount, perspective);

        String userPrompt = String.format("""
                        【图书】%s
                        【角色】%s（%s）
                        【角色关注领域】%s
                        【最近讨论】
                        %s""", bookTitle, roleName, roleTitle, roleKeywords,
                recentDiscussion == null || recentDiscussion.isBlank() ? "（讨论尚未开始）" : recentDiscussion);

        String aiText = chatModelManager.callAiForScene(AiScene.ROUND_TABLE_ROLE_SEARCH, "圆桌派角色检索查询(多)",
                "role=" + roleKey + ", count=" + subQueryCount, List.of(
                        SystemMessage.from(systemPrompt),
                        UserMessage.from(userPrompt)));
        if (aiText == null || aiText.isBlank()) return List.of();

        List<String> queries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : aiText.split("\n")) {
            String q = line.trim()
                    .replaceAll("^(查询|检索|搜索|关键词)[：:]", "")
                    .trim();
            if (q.isBlank() || q.length() > 30 || seen.contains(q)) continue;
            seen.add(q);
            queries.add(q);
        }
        return queries;
    }

    /**
     * JSON 序列化
     */
    private final ObjectMapper objectMapper;

    /**
     * 默认上下文长度（32K tokens）
     */
    private static final int DEFAULT_MAX_TOKENS = 32768;
    /**
     * token → 中文字符换算比例
     */
    private static final double TOKEN_TO_CHAR_RATIO = 1.5;
    /**
     * 压缩触发阈值：历史占比超过此比例开始压缩
     */
    private static final double COMPRESS_TRIGGER_RATIO = 0.8;

    /**
     * 估算字符数
     */
    private static final Function<String, Integer> CHAR_LENGTH_ESTIMATE = s -> s != null ? s.length() : 0;

    /**
     * 角色使用次数 ZSet 前缀 — key = {prefix}{bookId}, member = roleKey, score = 使用次数
     */
    private static final String ROLE_SCORES_KEY_PREFIX = "kbook:round-table:role-scores:";

    // ==================== 角色推荐（LLM 驱动） ====================

    /**
     * 根据使用次数或 LLM 推荐角色列表
     * <p>
     * 优先查 ZSet 中各角色的历史使用次数，取 top 4-6 标记为 selected。
     * ZSet 数据不足 4 个时降级到 LLM（冷启动），LLM 结果也写入 ZSet score+1。
     * refresh=true 时强制走 LLM，结果同样递增。
     *
     * @param bookId 书籍ID
     * @return 推荐角色列表（含 selected 标记）
     */
    @LogAction("推荐角色")
    public List<RoleVO> getRecommendedRoles(Long bookId, boolean refresh) {
        Book book = bookService.getBookById(bookId);
        if (book == null) {
            return getDefaultRoles();
        }

        String scoreKey = ROLE_SCORES_KEY_PREFIX + bookId;

        // 1. 非刷新时优先从 ZSet 取 top 角色
        if (!refresh) {
            List<RoleVO> zsetRoles = getTopSelectedRolesFromZSet(scoreKey);
            if (zsetRoles.size() >= 4) {
                List<AiConfig.RoundTableRole> selectedEnums = zsetRoles.stream()
                        .map(vo -> aiConfigProvider.getRoundTableRole(vo.getKey()))
                        .filter(Objects::nonNull)
                        .toList();
                log.debug("ZSet 角色推荐命中: bookId={}, roles={}", bookId, zsetRoles.stream().map(RoleVO::getKey).toList());
                return buildRoleListFromSelected(selectedEnums);
            }
        }

        // 2. 冷启动 / 强制刷新 → LLM
        try {
            String bookInfo = buildBookInfoForRoleSelection(book);
            // 刷新时排除上次已选角色，避免重复推荐
            List<String> excludeKeys = refresh ? getTopSelectedKeysFromZSet(scoreKey) : List.of();
            String result = callAiForRoleSelection(bookId, book.getTitle(), bookInfo, excludeKeys);

            if (result != null && !result.isBlank()) {
                result = CommonUtils.stripCodeFence(result);
                List<RoleVO> llmSelectedRoles = parseLlmRoleSelection(result);
                if (llmSelectedRoles != null && llmSelectedRoles.size() >= 3) {
                    // 记录 LLM 选择到 ZSet
                    for (RoleVO vo : llmSelectedRoles) {
                        stringRedisTemplate.opsForZSet().incrementScore(scoreKey, vo.getKey(), 1);
                    }
                    List<AiConfig.RoundTableRole> selectedEnums = llmSelectedRoles.stream()
                            .map(vo -> aiConfigProvider.getRoundTableRole(vo.getKey()))
                            .filter(Objects::nonNull)
                            .toList();
                    return buildRoleListFromSelected(selectedEnums);
                }
            }
        } catch (Exception e) {
            log.warn("LLM 角色推荐失败，回退到标签匹配: bookId={} - {}", bookId, e.getMessage());
        }

        // 3. 回退到标签匹配（也写入 ZSet）
        List<RoleVO> fallbackRoles = getFallbackRolesByTags(book);
        for (RoleVO vo : fallbackRoles) {
            if (vo.isSelected() && !"HOST".equals(vo.getKey())) {
                stringRedisTemplate.opsForZSet().incrementScore(scoreKey, vo.getKey(), 1);
            }
        }
        return fallbackRoles;
    }

    /**
     * 从 ZSet 中读取使用次数 Top 的非 HOST 角色 key 列表（用于刷新时排除）。
     */
    private List<String> getTopSelectedKeysFromZSet(String scoreKey) {
        Set<String> topKeys = stringRedisTemplate.opsForZSet()
                .reverseRangeByScore(scoreKey, 0, Double.MAX_VALUE, 0, 8);
        if (topKeys == null || topKeys.isEmpty()) return List.of();
        return topKeys.stream().filter(k -> !"HOST".equals(k)).toList();
    }

    /**
     * 从 ZSet 中读取使用次数 Top 4-6 的非 HOST 角色，返回 RoleVO 列表。
     */
    private List<RoleVO> getTopSelectedRolesFromZSet(String scoreKey) {
        // reverseRangeByScore: score 从 0 开始，返回最多 8 个（含 HOST 则过滤）
        Set<String> topKeys = stringRedisTemplate.opsForZSet()
                .reverseRangeByScore(scoreKey, 0, Double.MAX_VALUE, 0, 8);
        if (topKeys == null || topKeys.isEmpty()) return List.of();

        List<RoleVO> result = new ArrayList<>();
        for (String key : topKeys) {
            if ("HOST".equals(key)) continue;
            AiConfig.RoundTableRole role = aiConfigProvider.getRoundTableRole(key);
            if (role != null) {
                RoleVO vo = RoleVO.fromConfig(role);
                vo.setSelected(true);
                result.add(vo);
            }
            if (result.size() >= 6) break;
        }
        return result;
    }

    // buildRoleListWithSelection 已合并到 buildRoleListFromSelected

    /**
     * 解析 LLM 返回的角色选择结果。
     * 支持两种格式（容错优先级）：
     * 1. 行式 KV：[ROLE] 段 + KEY/DOMAIN_RELEVANCE/LANGUAGE_STYLE 行
     * 2. JSON 数组兜底：LLM 偶发仍输出 JSON 时容错
     */
    private List<RoleVO> parseLlmRoleSelection(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = CommonUtils.stripCodeFence(raw).trim();

        // JSON 数组兜底
        if (text.startsWith("[")) {
            List<RoleVO> jsonResult = parseLlmRoleSelectionJson(text);
            if (jsonResult != null) return jsonResult;
        }

        // 行式 KV 解析：按 [ROLE] 切段
        List<RoleVO> roles = new ArrayList<>();
        String[] blocks = text.split("(?=\\[ROLE\\])");
        Pattern keyPat = Pattern.compile("(?im)^\\s*KEY\\s*[:：]\\s*(.+?)\\s*$");
        Pattern relPat = Pattern.compile("(?im)^\\s*DOMAIN_RELEVANCE\\s*[:：]\\s*([0-9]+)");
        // LANGUAGE_STYLE 行内容可能较长，正则匹配到行尾
        Pattern stylePat = Pattern.compile("(?im)^\\s*LANGUAGE_STYLE\\s*[:：]\\s*(.+?)\\s*$");

        for (String block : blocks) {
            if (!block.contains("[ROLE]")) continue;
            Matcher km = keyPat.matcher(block);
            if (!km.find()) continue;
            String key = km.group(1).trim();
            if (key.isEmpty()) continue;

            AiConfig.RoundTableRole role = aiConfigProvider.getRoundTableRole(key);
            if (role == null || "HOST".equals(role.getKey())) continue;

            RoleVO vo = RoleVO.fromConfig(role);

            Matcher rm = relPat.matcher(block);
            if (rm.find()) {
                try {
                    vo.setDomainRelevance(Integer.parseInt(rm.group(1)));
                } catch (NumberFormatException ignored) {
                }
            }

            Matcher sm = stylePat.matcher(block);
            if (sm.find()) {
                vo.setLanguageStyle(sm.group(1).trim());
            } else {
                vo.setLanguageStyle("");
            }

            roles.add(vo);
            if (roles.size() >= 6) break; // 最多 6 个非主持人角色
        }
        return roles.isEmpty() ? null : roles;
    }

    /** JSON 数组兜底解析：LLM 偶发仍输出 JSON 时容错 */
    private List<RoleVO> parseLlmRoleSelectionJson(String json) {
        try {
            var nodes = objectMapper.readTree(json);
            if (!nodes.isArray()) return null;

            List<RoleVO> roles = new ArrayList<>();
            for (var node : nodes) {
                String key = node.has("key") ? node.get("key").asText() : null;
                int domainRelevance = node.has("domainRelevance") ? node.get("domainRelevance").asInt() : 5;

                if (key == null) continue;
                AiConfig.RoundTableRole role = aiConfigProvider.getRoundTableRole(key);
                if (role == null || "HOST".equals(role.getKey())) continue;

                RoleVO vo = RoleVO.fromConfig(role);
                vo.setDomainRelevance(domainRelevance);
                String languageStyle = node.has("languageStyle") ? node.get("languageStyle").asText() : "";
                vo.setLanguageStyle(languageStyle);
                roles.add(vo);

                if (roles.size() >= 6) break;
            }
            return roles.isEmpty() ? null : roles;
        } catch (Exception e) {
            log.warn("解析 LLM 角色选择 JSON 结果失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建角色推荐用的书籍信息
     */
    private String buildBookInfoForRoleSelection(Book book) {
        StringBuilder sb = new StringBuilder();
        sb.append("书名：《").append(book.getTitle()).append("》\n");
        if (book.getAuthor() != null && !book.getAuthor().isBlank()) {
            sb.append("作者：").append(book.getAuthor()).append("\n");
        }
        if (book.getFormatTags() != null && !book.getFormatTags().isBlank()) {
            String tags = book.getFormatTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("标签：").append(tags).append("\n");
        }
        if (book.getConceptTags() != null && !book.getConceptTags().isBlank()) {
            String concepts = book.getConceptTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("核心概念：").append(concepts).append("\n");
        }
        if (book.getReaderNeedTags() != null && !book.getReaderNeedTags().isBlank()) {
            String needs = book.getReaderNeedTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("读者关注：").append(needs).append("\n");
        }
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            // 放宽到 1500 字：description 通常本身不长，多给 LLM 一些上下文有助于选角
            String desc = CommonUtils.truncateText(book.getDescription(), 1500);
            sb.append("简介：").append(desc).append("\n");
        }
        // 目录：选角的高价值数据——章节标题直接揭示全书核心议题
        // 一本"量子力学史"和"量子力学理论"的书标签可能相同，但 TOC 决定该选 HISTORIAN 还是 SCIENTIST
        String toc = filterTocForPrompt(book.getToc());
        if (!toc.isBlank()) {
            sb.append("目录：\n").append(toc).append("\n");
        }
        String summary = bookService.resolveBookSummary(book);
        if (summary != null && !summary.isBlank()) {
            sb.append("摘要：").append(CommonUtils.truncateText(summary, 2000)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 过滤 TOC 用于角色推荐 prompt：去除封面/目录/前言/后记等非正文章节，只保留有实质内容的章节标题。
     * <p>
     * 逻辑与 RoundTableCoverageService.isNonContentChapter 对齐，但为避免跨服务依赖，
     * 这里实现轻量版——只过滤明显的非正文行，不做 LLM 大纲回退（选角场景不需要那么严格）。
     * 同时合并过长的目录（>60 行截断），避免 token 浪费。
     */
    private String filterTocForPrompt(String toc) {
        if (toc == null || toc.isBlank()) return "";
        Set<String> nonContentKeywords = Set.of(
                "封面", "扉页", "版权页", "版权信息", "版权声明",
                "目录", "目次", "contents", "table of contents", "toc",
                "前言", "序", "序言", "自序", "代序", "编者序",
                "preface", "foreword", "introduction", "prologue",
                "致谢", "鸣谢", "感谢", "acknowledgments",
                "附录", "appendix", "appendices",
                "索引", "index",
                "后记", "跋", "postscript", "afterword", "epilogue",
                "参考文献", "参考书目", "引用文献", "references", "bibliography",
                "术语表", "词汇表", "glossary",
                "注释", "notes", "footnotes",
                "版权", "copyright", "法律声明", "disclaimer"
        );
        List<String> chapters = Arrays.stream(toc.split("\n"))
                .map(String::trim)
                .filter(line -> line.length() >= 2 && !line.matches("^[\\d\\s.]+$"))
                .filter(line -> {
                    String lower = line.toLowerCase();
                    if (nonContentKeywords.contains(lower)) return false;
                    for (String kw : nonContentKeywords) {
                        if (lower.startsWith(kw) || lower.startsWith(kw + "：") || lower.startsWith(kw + ":")
                                || lower.endsWith(kw)) {
                            return false;
                        }
                    }
                    return true;
                })
                .distinct()
                .toList();
        if (chapters.isEmpty()) return "";
        // 截断过长目录：选角只需要看核心议题，60 行足够
        int limit = Math.min(chapters.size(), 60);
        return String.join("\n", chapters.subList(0, limit))
                + (chapters.size() > 60 ? "\n（更多章节省略）" : "");
    }

    /**
     * 回退角色推荐 — 先尝试轻量 LLM 调用，失败再用标签硬匹配
     */
    private List<RoleVO> getFallbackRolesByTags(Book book) {
        // 尝试轻量 LLM 调用（比主流程 prompt 更简单，容忍度更高）
        try {
            String bookInfo = buildBookInfoForRoleSelection(book);
            String roleList = aiConfigProvider.getRoundTableRoles().stream()
                    .map(r -> r.getKey() + "(" + r.getName() + ")")
                    .collect(Collectors.joining(", "));

            String result = callAiForRoleSelectionFallback(
                    book.getId(), book.getTitle(), bookInfo, roleList);

            if (result != null && !result.isBlank()) {
                result = CommonUtils.stripCodeFence(result);
                List<String> keys = Arrays.stream(result.split("[,，\\s]+"))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .map(String::toUpperCase)
                        .distinct()
                        .toList();
                List<AiConfig.RoundTableRole> llmRoles = keys.stream()
                        .map(aiConfigProvider::getRoundTableRole)
                        .filter(Objects::nonNull)
                        .filter(r -> !"HOST".equals(r.getKey()))
                        .toList();
                if (llmRoles.size() >= 3) {
                    return buildRoleListFromSelected(llmRoles);
                }
            }
        } catch (Exception e) {
            log.warn("回退 LLM 角色推荐失败，使用标签匹配: bookId={} - {}", book.getId(), e.getMessage());
        }

        // 最终回退：标签硬匹配
        List<String> tags = parseTags(book.getFormatTags());
        List<AiConfig.RoundTableRole> matchedRoles = selectRolesByTags(tags);
        return buildRoleListFromSelected(matchedRoles);
    }

    /**
     * 从已选角色列表构建 20 人名单（HOST 始终包含且选中）
     */
    private List<RoleVO> buildRoleListFromSelected(List<AiConfig.RoundTableRole> selectedRoles) {
        List<RoleVO> result = new ArrayList<>();
        Set<String> addedKeys = new HashSet<>();

        // HOST 始终选中（HOST 在单独的 host 字段，不在 roles[] 中）
        AiConfig.RoundTableHost hostConfig = aiConfigProvider.getRoundTableHost();
        if (hostConfig != null) {
            RoleVO hostVo = RoleVO.builder()
                    .key(hostConfig.getKey())
                    .name(hostConfig.getName())
                    .title(hostConfig.getTitle())
                    .color(hostConfig.getColor())
                    .icon(hostConfig.getIcon())
                    .roleGroup(hostConfig.getGroup())
                    .grabWeight(hostConfig.getParams() != null ? hostConfig.getParams().getGrabWeight() : 10)
                    .verbosity(hostConfig.getParams() != null ? hostConfig.getParams().getVerbosity() : 3)
                    .opinionated(hostConfig.getParams() != null ? hostConfig.getParams().getOpinionated() : 2)
                    .challenge(hostConfig.getParams() != null ? hostConfig.getParams().getChallenge() : 1)
                    .empathy(hostConfig.getParams() != null ? hostConfig.getParams().getEmpathy() : 5)
                    .humor(hostConfig.getParams() != null ? hostConfig.getParams().getHumor() : 3)
                    .pitch(hostConfig.getTts() != null ? hostConfig.getTts().getPitch() : 1.0)
                    .rate(hostConfig.getTts() != null ? hostConfig.getTts().getRate() : 1.0)
                    .selected(true)
                    .build();
            result.add(hostVo);
            addedKeys.add("HOST");
        }

        for (AiConfig.RoundTableRole role : selectedRoles) {
            if ("HOST".equals(role.getKey()) || addedKeys.contains(role.getKey())) continue;
            RoleVO vo = RoleVO.fromConfig(role);
            vo.setSelected(true);
            result.add(vo);
            addedKeys.add(role.getKey());
        }

        List<AiConfig.RoundTableRole> remaining = aiConfigProvider.getRoundTableRoles().stream()
                .filter(r -> !addedKeys.contains(r.getKey()))
                .collect(Collectors.toList());
        Collections.reverse(remaining);
        for (AiConfig.RoundTableRole role : remaining) {
            RoleVO vo = RoleVO.fromConfig(role);
            vo.setSelected(false);
            result.add(vo);
            addedKeys.add(role.getKey());
        }

        return result;
    }


    /**
     * 获取默认角色列表 — 优先从外部配置读取，回退到枚举默认
     */
    private List<RoleVO> getDefaultRoles() {
        List<RoleVO> result = new ArrayList<>();
        Set<String> addedKeys = new HashSet<>();

        // 从外部配置获取默认选中角色 key 列表
        List<String> defaultSelectedKeys = aiConfigProvider.getRoundTableDefaultSelectedKeys();
        int maxRoles = aiConfigProvider.getRoundTableMaxRoles();

        for (String key : defaultSelectedKeys) {
            if ("HOST".equals(key)) {
                // HOST 在单独的 host 字段
                AiConfig.RoundTableHost hostConfig = aiConfigProvider.getRoundTableHost();
                if (hostConfig != null) {
                    RoleVO vo = RoleVO.builder()
                            .key(hostConfig.getKey())
                            .name(hostConfig.getName())
                            .title(hostConfig.getTitle())
                            .color(hostConfig.getColor())
                            .icon(hostConfig.getIcon())
                            .roleGroup(hostConfig.getGroup())
                            .grabWeight(hostConfig.getParams() != null ? hostConfig.getParams().getGrabWeight() : 10)
                            .verbosity(hostConfig.getParams() != null ? hostConfig.getParams().getVerbosity() : 3)
                            .opinionated(hostConfig.getParams() != null ? hostConfig.getParams().getOpinionated() : 2)
                            .challenge(hostConfig.getParams() != null ? hostConfig.getParams().getChallenge() : 1)
                            .empathy(hostConfig.getParams() != null ? hostConfig.getParams().getEmpathy() : 5)
                            .humor(hostConfig.getParams() != null ? hostConfig.getParams().getHumor() : 3)
                            .selected(true)
                            .build();
                    result.add(vo);
                    addedKeys.add(key);
                }
                continue;
            }
            AiConfig.RoundTableRole configRole = aiConfigProvider.getRoundTableRole(key);
            if (configRole != null) {
                RoleVO vo = RoleVO.fromConfig(configRole);
                vo.setSelected(true);
                result.add(vo);
                addedKeys.add(key);
            }
        }

        // 补充剩余角色到上限
        List<AiConfig.RoundTableRole> remainingRoles = aiConfigProvider.getRoundTableRoles().stream()
                .filter(r -> !addedKeys.contains(r.getKey()))
                .collect(Collectors.toList());
        Collections.reverse(remainingRoles);
        for (AiConfig.RoundTableRole role : remainingRoles) {
            if (result.size() >= maxRoles) break;
            RoleVO vo = RoleVO.fromConfig(role);
            vo.setSelected(false);
            result.add(vo);
        }

        return result;
    }

    // ==================== 会话管理 ====================

    /**
     * 创建圆桌派会话
     *
     * @param userId      用户ID
     * @param bookId      书籍ID
     * @param roleKeys    参与角色键名列表
     * @param roleConfigs 角色配置 JSON（包含 LLM 赋值的 domainRelevance）
     * @return 创建的会话实体
     */
    @LogAction("创建圆桌派会话")
    public RoundTableSession createSession(Long userId, Long bookId, List<String> roleKeys, String roleConfigs) {
        // 确保 HOST 在角色列表中
        if (!roleKeys.contains("HOST")) {
            roleKeys = new ArrayList<>(roleKeys);
            roleKeys.add(0, "HOST");
        }

        // 确保至少 4 个角色（HOST + 3）
        if (roleKeys.size() < 4) {
            List<String> defaults = List.of("PHILOSOPHER", "PSYCHOLOGIST", "SOCIOLOGIST", "EDUCATOR", "COMEDIAN");
            for (String def : defaults) {
                if (roleKeys.size() >= 4) break;
                if (!roleKeys.contains(def)) {
                    roleKeys = new ArrayList<>(roleKeys);
                    roleKeys.add(def);
                }
            }
        }

        Book book = bookService.getBookById(bookId);
        String title = book != null ? "《" + book.getTitle() + "》圆桌派讨论" : "圆桌派讨论";

        String sessionId = "rt-" + bookId + "-" + UUID.randomUUID().toString().substring(0, 8);

        RoundTableSession session = RoundTableSession.builder()
                .userId(userId)
                .bookId(bookId)
                .sessionId(sessionId)
                .title(title)
                .roleKeys(String.join(",", roleKeys))
                .roleConfigs(roleConfigs)
                .status("ACTIVE")
                .visibility("PUBLIC")
                .build();

        RoundTableSession saved = sessionRepository.save(session);

        // 递增 ZSet 分数：用户选择了哪些角色，对应角色的使用次数 +1
        String scoreKey = ROLE_SCORES_KEY_PREFIX + bookId;
        for (String roleKey : roleKeys) {
            if (roleKey != null && !roleKey.isBlank()) {
                stringRedisTemplate.opsForZSet().incrementScore(scoreKey, roleKey.trim(), 1);
            }
        }

        // 将该图书加入阅读历史（标记为讨论行为）
        readingProgressService.reportProgress(userId, bookId, 0.0, "chat");

        return saved;
    }

    /**
     * 获取用户对指定书籍的圆桌派会话列表
     *
     * @param userId 用户ID
     * @param bookId 书籍ID
     * @return 会话列表
     */
    @LogAction("获取圆桌派会话列表")
    public List<RoundTableSession> getSessions(Long userId, Long bookId) {
        return sessionRepository.query()
                .where(RoundTableSession::getUserId, eq(userId))
                .and(RoundTableSession::getBookId, eq(bookId))
                .orderByDesc(RoundTableSession::getUpdatedAt)
                .list();
    }

    /**
     * 获取圆桌派会话
     *
     * @param sessionId 会话ID
     * @return 会话实体
     */
    @LogAction("获取圆桌派会话")
    public RoundTableSession getSession(String sessionId) {
        return sessionRepository.query()
                .where(RoundTableSession::getSessionId, eq(sessionId))
                .list(1)
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException("会话不存在"));
    }

    /**
     * 验证会话归属当前用户，不属于则抛出 BusinessException
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     */
    public void verifySessionOwnership(Long userId, String sessionId) {
        RoundTableSession session = getSession(sessionId);
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该会话");
        }
    }

    /**
     * 获取圆桌派会话历史消息
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return 消息列表
     */
    @LogAction("获取圆桌派历史")
    public List<RoundTableMessage> getHistory(Long userId, String sessionId) {
        return messageRepository.query()
                .where(RoundTableMessage::getSessionId, eq(sessionId))
                .list();
    }

    /**
     * 更新圆桌派会话状态
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param status    新状态（ACTIVE / COMPLETED / ABANDONED）
     */
    @LogAction("更新圆桌派会话状态")
    public void updateSessionStatus(Long userId, String sessionId, String status) {
        RoundTableSession session = getSession(sessionId);
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该会话");
        }
        session.setStatus(status);
        sessionRepository.save(session);
    }

    /**
     * 删除圆桌派会话及其消息
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     */
    @LogAction("删除圆桌派会话")
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(Long userId, String sessionId) {
        // 批量删除该会话的所有消息（避免全量加载后逐条删除）
        messageRepository.delete()
                .where(RoundTableMessage::getUserId, eq(userId))
                .and(RoundTableMessage::getSessionId, eq(sessionId))
                .execute();

        // 删除会话
        sessionRepository.query()
                .where(RoundTableSession::getSessionId, eq(sessionId))
                .list(1)
                .stream().findFirst()
                .filter(s -> s.getUserId().equals(userId))
                .ifPresent(sessionRepository::delete);
    }

    /**
     * 获取全局圆桌派会话列表（发现页）
     */
    public Page<RoundTableSessionFeedVO> getGlobalSessions(int page, int size, String sort, boolean mine) {
        // 安全措施：sort 参数白名单校验，仅接受 "hot" 或 "recent"（默认）
        String safeSort = "hot".equals(sort) ? "hot" : "recent";
        var pageable = PageRequest.of(page, size,
                Sort.by("hot".equals(safeSort)
                        ? Sort.Order.desc("updatedAt")
                        : Sort.Order.desc("createdAt")));

        // 获取当前用户ID（未登录则只能看到公开会话）
        Long currentUserId = getCurrentUserId();

        // 查询公开会话或当前用户的会话；如果 mine=true 则只查当前用户
        var sessions = mine
                ? sessionRepository.findByUserId(currentUserId, pageable)
                : sessionRepository.findPublicOrOwnSessions(pageable);

        // Get book info
        var bookIds = sessions.getContent().stream().map(RoundTableSession::getBookId).distinct().toList();
        var books = bookRepository.findListByIds(bookIds);
        var bookMap = books.stream().collect(Collectors.toMap(Book::getId, b -> b));

        // Get coverage scores
        var sessionIds = sessions.getContent().stream().map(RoundTableSession::getSessionId).toList();
        var coverageMap = new java.util.HashMap<String, Double>();
        if (!sessionIds.isEmpty()) {
            var coverages = roundTableCoverageRepository.query()
                    .where(RoundTableCoverage::getSessionId, in(sessionIds))
                    .list();
            coverageMap.putAll(coverages.stream()
                    .collect(Collectors.toMap(
                            RoundTableCoverage::getSessionId,
                            c -> c.getOverallScore() != null ? c.getOverallScore() : 0.0,
                            (a, b) -> a)));
        }

        // 计算热度分数
        var now = LocalDateTime.now();

        return sessions.map(session -> {
            var book = bookMap.get(session.getBookId());
            var coverageScore = coverageMap.getOrDefault(session.getSessionId(), 0.0);

            // 计算热度分数
            double hotScore = calculateHotScore(coverageScore, session.getStatus(), session.getCreatedAt(), now);

            return RoundTableSessionFeedVO.builder()
                    .id(session.getId())
                    .sessionId(session.getSessionId())
                    .bookId(session.getBookId())
                    .bookTitle(book != null ? book.getTitle() : "未知书籍")
                    .bookCoverUrl(book != null ? book.getCoverUrl() : null)
                    .title(session.getTitle())
                    .roleKeys(session.getRoleKeys())
                    .status(session.getStatus())
                    .visibility(session.getVisibility())
                    .isOwner(currentUserId != null && currentUserId.equals(session.getUserId()))
                    .coverageScore(coverageScore)
                    .hotScore(hotScore)
                    .createdAt(session.getCreatedAt())
                    .updatedAt(session.getUpdatedAt())
                    .build();
        });
    }

    /**
     * 计算热度分数
     *
     * @param score     评分（辩论用avgScore，圆桌用coverageScore）
     * @param status    状态
     * @param createdAt 创建时间
     * @param now       当前时间
     * @return 热度分数
     */
    private double calculateHotScore(double score, String status, LocalDateTime createdAt, LocalDateTime now) {
        // 基础分：评分 * 100（归一化到0-100分）
        double baseScore = score * 100;

        // 完成加分：已完成+50分，进行中+10分
        int completionBonus = "COMPLETED".equals(status) ? 50 : ("ACTIVE".equals(status) ? 10 : 0);

        // 时间衰减：每天减2分，防止旧内容霸榜
        long daysSinceCreated = java.time.Duration.between(createdAt, now).toDays();
        double timeDecay = daysSinceCreated * 2.0;

        return baseScore + completionBonus - timeDecay;
    }

    /**
     * 从 Spring Security 上下文获取当前用户ID
     * 未登录返回 null（只能看到公开会话）
     */
    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Long userId) {
            return userId;
        }
        return null;
    }

    /**
     * 覆盖度触发谢幕阈值：overallScore（0-100）达到此值时强制 shouldEnd=true。
     * 替代旧的 60 轮硬上限——改为基于讨论质量（覆盖度）判断结束，而非机械轮次。
     * 阈值 85 对应 S 级（讨论已充分覆盖书籍核心内容）。
     * 兜底说明：若书籍无概念标签且无内容块，coverage 恒为 0，此时依赖 LLM 结束判断窗口自然结束。
     */
    private static final double COVERAGE_FORCE_END_THRESHOLD = 85.0;
    /**
     * 结束判断频控：轮次是此值的倍数时打开"结束判断窗口"，让 LLM 输出 shouldEnd 字段。
     * 避免每轮都让 LLM 思考"该不该结束"，浪费 token。
     */
    private static final int END_CHECK_INTERVAL_ROUNDS = 3;
    /**
     * 结束判断预热：轮次小于 角色数 × 此值 时不判断结束（讨论还在预热阶段）。
     */
    private static final int END_CHECK_WARMUP_ROLE_MULTIPLIER = 3;

    /**
     * 纯 LLM 判断下一轮发言人（同时判断是否应该结束讨论）
     * <p>
     * 核心设计：
     * 1. 给 LLM 完整的角色信息（含性格参数、专业领域、说话风格）
     * 2. 给加权对话历史——越靠后的发言权重越高，让 LLM 能感知对话流向
     * 3. 给公平性约束——禁止连续发言、鼓励沉默者、主持人控场
     * 4. LLM 返回 nextSpeaker + shouldEnd + closingSummary，不再经过算法二次调整
     * <p>
     * 结束判断频控：
     * - 轮次 < 角色数 × 3：不判断（讨论预热）
     * - 轮次 % 3 == 0：判断窗口打开
     * - 覆盖度 >= 85：强制 shouldEnd=true（基于讨论质量，替代旧的轮次硬上限）
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return 下一发言人结果（含 shouldEnd / closingSummary）
     */
    @LogAction("纯LLM判断下一发言人")
    @RedisLock(key = "'rt:speaker:' + #sessionId", leaseTime = 30)
    public NextSpeakerResult getNextSpeakerOnlyLLM(Long userId, String sessionId) {
        // 1. 加载会话 + 书籍信息
        RoundTableSession session = sessionRepository.query()
                .where(RoundTableSession::getSessionId, eq(sessionId))
                .list(1)
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException("会话不存在"));

        Book book = null;
        if (session.getBookId() != null) {
            try {
                book = bookService.getBookById(session.getBookId());
            } catch (Exception e) {
                log.warn("加载书籍信息失败, bookId={}: {}", session.getBookId(), e.getMessage());
            }
        }

        // 2. 构建消息列表（顺序即 KV-cache 友好顺序：不变消息在前，可变消息在后）
        List<ChatMessage> messages = new ArrayList<>();

        // [消息1 - SystemMessage] 角色定义 + 选择规则（全局不变）
        messages.add(SystemMessage.from(AiPromptConstants.ROUND_TABLE_NEXT_SPEAKER_SYSTEM));

        // [消息2 - UserMessage] 书籍背景 + 角色人设（会话内不变，缓存命中）
        String bookAndRoles = buildBookAndRolesMessage(session, book);
        messages.add(UserMessage.from(bookAndRoles));

        // 3. 加载历史消息并同步压缩
        int currentOverhead = AiPromptConstants.ROUND_TABLE_NEXT_SPEAKER_SYSTEM.length()
                + bookAndRoles.length()
                + 2000; // 约束 + 历史 + 输出格式 + LLM 回复预留
        List<RoundTableMessage> allMessages;
        try {
            allMessages = loadAndCompressHistory(userId, sessionId, currentOverhead);
        } catch (Exception e) {
            log.warn("加载/压缩圆桌派历史失败，回退到直接加载: {}", e.getMessage());
            allMessages = messageRepository.query()
                    .where(RoundTableMessage::getSessionId, eq(sessionId))
                    .orderBy(RoundTableMessage::getId)
                    .list();
        }
        if (CollectionUtils.isEmpty(allMessages)) {
            return NextSpeakerResult.builder().nextSpeaker("HOST").shouldEnd(false).build();
        }

        // 4. 统计发言数据
        String[] roleKeys = session.getRoleKeys().split(",");
        int roleCount = roleKeys.length;
        // 轮次口径：消息总数 / 角色数（与前端 currentRound 显示一致）
        int currentRound = roleCount > 0 ? allMessages.size() / roleCount : allMessages.size();
        Map<String, Long> speakCounts = allMessages.stream()
                .collect(Collectors.groupingBy(RoundTableMessage::getRoleKey, Collectors.counting()));
        String lastSpeaker = allMessages.get(allMessages.size() - 1).getRoleKey();

        // 5. 判断是否打开"结束判断窗口"
        int warmupRounds = Math.max(roleCount * END_CHECK_WARMUP_ROLE_MULTIPLIER, roleCount + 3);
        boolean endCheckWindow = currentRound >= warmupRounds
                && currentRound % END_CHECK_INTERVAL_ROUNDS == 0;

        // 读取覆盖度——基于讨论质量判断结束（替代旧的轮次硬上限）
        RoundTableCoverage coverage = coverageService.getCoverage(sessionId);
        double coverageScore = coverage != null && coverage.getOverallScore() != null
                ? coverage.getOverallScore() : 0.0;
        boolean coverageHighEnough = coverage != null && coverageScore >= COVERAGE_FORCE_END_THRESHOLD;
        if (coverageHighEnough) {
            log.info("覆盖度 {} 达到阈值 {}，触发谢幕: sessionId={}",
                    coverageScore, COVERAGE_FORCE_END_THRESHOLD, sessionId);
        }

        // [消息3 - UserMessage] 讨论历史（每次只追加一条新发言，前缀可大量复用 KV-cache）
        String weightedHistory = buildWeightedHistoryForLLM(allMessages);
        messages.add(UserMessage.from(weightedHistory));

        // [消息3.5 - UserMessage] 话题覆盖度（给 LLM 结束判断提供量化依据）
        if (coverage != null) {
            String coverageBrief = buildCoverageBriefForLLM(coverage);
            if (!coverageBrief.isBlank()) {
                messages.add(UserMessage.from(coverageBrief));
            }
        }

        // [消息4 - UserMessage] 公平性约束 + 输出格式（每轮完全变化，放最后不破坏前缀缓存）
        String fairnessConstraints = buildFairnessConstraints(roleKeys, speakCounts, allMessages);
        // 构建 角色中文名 → key 反向映射，用于解析 LLM 返回中文名时的容错
        Map<String, String> nameToKey = buildNameToKeyMap(session, roleKeys);
        // 明确列出所有合法 KEY，避免 LLM 把中文名当 key 返回（历史/约束里用的是中文名）
        String validKeysList = Arrays.stream(roleKeys).map(String::trim)
                .collect(Collectors.joining(", "));

        // 输出格式：行式 KV（比 JSON 更不容易出格式错误，LLM 训练数据中大量存在）
        String outputFormat;
        if (coverageHighEnough) {
            outputFormat = "【强制结束】话题覆盖度已达 " + String.format("%.0f", coverageScore)
                    + "（阈值 " + COVERAGE_FORCE_END_THRESHOLD + "），讨论已充分覆盖书籍核心内容，"
                    + "必须 END=true，NEXT=HOST，并给出谢幕提纲。\n\n"
                    + "请按以下格式返回（每行一个字段，不要输出 JSON、不要输出 markdown 代码块）：\n\n"
                    + "NEXT: HOST\n"
                    + "END: true\n"
                    + "SUMMARY: 不超过200字的谢幕提纲\n\n"
                    + "规则：\n"
                    + "- KEY 必须是以下英文标识符之一（区分大小写，原样返回）：" + validKeysList + "\n";
        } else if (endCheckWindow) {
            outputFormat = "请同时判断讨论是否应该结束（见系统提示词的【结束判断】规则）。\n\n"
                    + "请按以下格式返回（每行一个字段，不要输出 JSON、不要输出 markdown 代码块）：\n\n"
                    + "NEXT: 候选1, 候选2, 候选3\n"
                    + "END: true|false\n"
                    + "SUMMARY: 不超过200字的谢幕提纲（END=false 时留空）\n\n"
                    + "规则：\n"
                    + "- NEXT 行：列出 top-3 候选角色 key，用英文逗号分隔，按接话意愿从高到低排序\n"
                    + "- END 行：true 或 false\n"
                    + "- SUMMARY 行：END=true 时填写谢幕提纲，END=false 时留空\n"
                    + "- END=true 时 NEXT 必须只包含 HOST\n"
                    + "- KEY 必须是以下英文标识符之一（区分大小写，原样返回）：" + validKeysList + "\n";
        } else {
            outputFormat = "请按以下格式返回（每行一个字段，不要输出 JSON、不要输出 markdown 代码块）：\n\n"
                    + "NEXT: 候选1, 候选2, 候选3\n"
                    + "END: false\n"
                    + "SUMMARY: \n\n"
                    + "规则：\n"
                    + "- NEXT 行：列出 top-3 候选角色 key，用英文逗号分隔，按接话意愿从高到低排序\n"
                    + "- END 行：固定 false（本轮不判断是否结束）\n"
                    + "- SUMMARY 行：留空\n"
                    + "- KEY 必须是以下英文标识符之一（区分大小写，原样返回）：" + validKeysList + "\n";
        }
        messages.add(UserMessage.from(fairnessConstraints + "\n\n" + outputFormat));

        // 6. 调用 LLM
        try {
            String response = chatModelManager.callAiForScene(AiScene.ROUND_TABLE_SPEAKER_SELECT,
                    "圆桌派纯LLM发言人选择",
                    String.format("sessionId=%s", sessionId),
                    messages);
            response = CommonUtils.stripCodeFence(response).trim();

            // 解析候选集（top-3，容错：无 NEXT 行时回退到 parseLlmSpeakerResponse）
            List<String> candidates = parseCandidateKeysFromResponse(response, roleKeys, nameToKey);
            boolean shouldEnd = parseShouldEndFromResponse(response);
            String closingSummary = parseClosingSummaryFromResponse(response);

            // 覆盖度兜底：LLM 没判断 shouldEnd=true 时强制改
            if (coverageHighEnough && !shouldEnd) {
                shouldEnd = true;
                if (closingSummary == null || closingSummary.isBlank()) {
                    closingSummary = "话题覆盖度已达阈值，讨论已充分展开，请做总结谢幕。";
                }
                log.warn("覆盖度触发强制谢幕: sessionId={}, llmShouldEnd=false→true", sessionId);
            }

            // shouldEnd=true 时强制 nextSpeaker=HOST，不走候选过滤
            String selectedKey;
            if (shouldEnd) {
                selectedKey = "HOST";
            } else {
                // 从候选中按公平性过滤选择
                selectedKey = selectSpeakerFromCandidates(candidates, lastSpeaker, speakCounts, roleKeys);
                if (selectedKey == null) {
                    throw new BusinessException("候选集为空，无法选择发言人");
                }
            }

            log.info("纯LLM选择发言人: sessionId={}, selected={}, candidates={}, shouldEnd={}, round={}, speakCounts={}",
                    sessionId, selectedKey, candidates, shouldEnd, currentRound, speakCounts);

            return NextSpeakerResult.builder()
                    .nextSpeaker(selectedKey)
                    .shouldEnd(shouldEnd)
                    .closingSummary(closingSummary != null ? closingSummary : "")
                    .build();
        } catch (Exception e) {
            log.warn("纯LLM发言人选择失败，回退到简单模式: {}", e.getMessage());
            // 覆盖度达标时即使 LLM 失败也强制结束，避免"覆盖度达标→LLM失败→回退→继续→覆盖度仍达标→..."死循环
            if (coverageHighEnough) {
                log.warn("覆盖度达标+LLM失败，强制谢幕: sessionId={}", sessionId);
                return NextSpeakerResult.builder()
                        .nextSpeaker("HOST")
                        .shouldEnd(true)
                        .closingSummary("话题覆盖度已达阈值，讨论已充分展开，请做总结谢幕。")
                        .build();
            }
            String fallbackSpeaker = Arrays.stream(roleKeys)
                    .map(String::trim)
                    .filter(k -> !k.equals(lastSpeaker))
                    .min(Comparator.comparingLong(k -> speakCounts.getOrDefault(k, 0L)))
                    .orElse(roleKeys[0].trim());
            return NextSpeakerResult.builder()
                    .nextSpeaker(fallbackSpeaker)
                    .shouldEnd(false)
                    .closingSummary("")
                    .build();
        }
    }

    /**
     * 从 LLM 返回中解析候选 key 列表（行式 KV 格式：`NEXT: key1, key2, key3`）
     * <p>
     * 容错策略：
     * 1. 有 NEXT 行 → split by [,，]（兼容中英文逗号），逐个 key 容错匹配
     * 2. 无 NEXT 行 → 回退到 parseLlmSpeakerResponse（子串匹配），返回单元素列表
     * 3. 解析出的 key 去重，保持顺序
     */
    private List<String> parseCandidateKeysFromResponse(String response, String[] validRoleKeys,
                                                         Map<String, String> nameToKey) {
        Set<String> validKeys = Arrays.stream(validRoleKeys).map(String::trim).collect(Collectors.toSet());
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 提取 NEXT: 行（大小写不敏感）
        Matcher m = Pattern.compile("(?im)^\\s*NEXT\\s*:\\s*(.+)$").matcher(response);
        if (m.find()) {
            String rest = m.group(1).trim();
            // split by 中英文逗号
            String[] parts = rest.split("[,，]");
            for (String part : parts) {
                String key = part.trim();
                if (key.isEmpty()) continue;
                // 去掉可能的列表前缀（如 "1." "- " "• "）
                key = key.replaceAll("^[\\d]+[.)\\-]\\s*", "").replaceAll("^[-•*]\\s*", "").trim();
                // 1. 直接匹配英文 key
                if (validKeys.contains(key)) {
                    if (seen.add(key)) result.add(key);
                    continue;
                }
                // 2. 反查中文名 → key
                String mapped = nameToKey.get(key);
                if (mapped != null) {
                    if (seen.add(mapped)) result.add(mapped);
                    continue;
                }
                // 3. 子串匹配英文 key（大小写不敏感）
                String upperKey = key.toUpperCase();
                for (String vk : validKeys) {
                    if (upperKey.contains(vk.toUpperCase()) && seen.add(vk)) {
                        result.add(vk);
                        break;
                    }
                }
            }
        }

        // NEXT 行没解析出任何候选 → 回退到旧解析（子串匹配整段文本）
        if (result.isEmpty()) {
            try {
                String single = parseLlmSpeakerResponse(response, validRoleKeys, nameToKey);
                if (single != null) result.add(single);
            } catch (Exception ignored) {
                // 旧解析也失败，返回空列表让上层兜底
            }
        }

        return result;
    }

    /**
     * 从候选列表中按公平性过滤选择发言人
     * <p>
     * 过滤逻辑：
     * 1. 跳过 lastSpeaker（禁止连续发言）
     * 2. 跳过 speakCount > avg × 1.5 的角色（已超标，给其他人机会）
     * 3. 从剩余候选中选 top-1（LLM 最想让他说的）
     * 4. 候选全部超标 → 选 candidates 中 speakCount 最少的
     * 5. 候选为空或全是 lastSpeaker → 回退到全局 speakCount 最少的非 lastSpeaker
     *
     * @param candidates  LLM 返回的 top-3 候选（按意愿排序）
     * @param lastSpeaker 上一位发言人
     * @param speakCounts 各角色发言次数统计
     * @param roleKeys    所有在场角色 key
     * @return 选中的角色 key，null 表示无可用候选（上层兜底）
     */
    private String selectSpeakerFromCandidates(List<String> candidates, String lastSpeaker,
                                                Map<String, Long> speakCounts, String[] roleKeys) {
        // 1. 过滤掉 lastSpeaker
        List<String> filtered = candidates == null ? List.of() : candidates.stream()
                .filter(k -> !k.equals(lastSpeaker))
                .collect(Collectors.toList());

        // 候选为空或全是 lastSpeaker → 回退到全局 speakCount 最少的非 lastSpeaker
        if (filtered.isEmpty()) {
            return Arrays.stream(roleKeys)
                    .map(String::trim)
                    .filter(k -> !k.equals(lastSpeaker))
                    .min(Comparator.comparingLong(k -> speakCounts.getOrDefault(k, 0L)))
                    .orElse(null);
        }

        // 2. 计算 avg speakCount 和超标阈值
        double avg = speakCounts.values().stream().mapToLong(Long::longValue).average().orElse(0);
        double threshold = avg * 1.5;

        // 3. 过滤掉超标的（speakCount > avg × 1.5）
        List<String> notOverweight = filtered.stream()
                .filter(k -> speakCounts.getOrDefault(k, 0L) <= threshold)
                .collect(Collectors.toList());

        if (!notOverweight.isEmpty()) {
            // 从未超标候选中选 top-1
            return notOverweight.get(0);
        }

        // 4. 全部超标 → 选 candidates 中 speakCount 最少的
        return filtered.stream()
                .min(Comparator.comparingLong(k -> speakCounts.getOrDefault(k, 0L)))
                .orElse(filtered.get(0));
    }

    /**
     * 从 LLM 返回中解析 END 字段（行式 KV：`END: true|false`）
     * <p>
     * 容错：缺失或非 true 视为 false（继续讨论）
     */
    private boolean parseShouldEndFromResponse(String response) {
        Matcher m = Pattern.compile("(?im)^\\s*END\\s*:\\s*(\\w+)").matcher(response);
        if (m.find()) {
            String val = m.group(1).trim().toLowerCase();
            return "true".equals(val) || "yes".equals(val) || "1".equals(val) || "是".equals(val);
        }
        return false;
    }

    /**
     * 从 LLM 返回中解析 SUMMARY 字段（行式 KV：`SUMMARY: ...`）
     * <p>
     * 容错：缺失返回空串；支持多行内容（取到下一个 `KEY:` 行或文件末尾）
     */
    private String parseClosingSummaryFromResponse(String response) {
        // 匹配 SUMMARY: 后到下一个 "KEY:" 行开头或字符串末尾
        Matcher m = Pattern.compile("(?im)^\\s*SUMMARY\\s*:\\s*(.*(?:\\r?\\n(?!\\s*[A-Z_]+\\s*:).*)*)",
                Pattern.MULTILINE).matcher(response);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }

    /**
     * 构建「书籍背景 + 角色人设」消息块（会话内不变，放在 SystemMessage 之后作为第二条缓存消息）
     */
    private String buildBookAndRolesMessage(RoundTableSession session, Book book) {
        StringBuilder sb = new StringBuilder();

        // 讨论背景
        sb.append("【讨论背景】\n");
        if (book != null) {
            sb.append("书籍：《").append(book.getTitle()).append("》\n");
        }
        sb.append("主题：").append(session.getTitle()).append("\n\n");

        // 角色人设
        sb.append("【当前在场角色及其人设】\n");
        sb.append("重点关注「社交直觉与张力」中的天然警惕/天然共鸣/接话直觉，");
        sb.append("这些决定了谁在面对某类发言时会本能地想接话或反驳。\n\n");
        sb.append(buildRolesInfoForLLMSpeakerSelection(session));

        return sb.toString();
    }

    /**
     * 构建角色信息文本（用于纯 LLM 发言人选择）
     * <p>
     * 以 session.roleKeys（后端已自动补全 HOST + 默认角色）为准遍历，
     * 从 roleConfigs JSON 中匹配各角色的 session 专属参数（domainRelevance 等），
     * 未在 roleConfigs 中找到的角色使用 AiConfig.RoundTableRole 默认值。
     * <p>
     * 包含角色完整人设描述（从 AiConfig.RoundTableRole 读取），
     * 让 LLM 理解每个角色的身份、社交倾向、与谁冲突/共鸣，
     * 从而能根据【社交直觉与张力】中的「天然警惕」「天然共鸣」「接话直觉」
     * 来判断谁最适合接当前发言。
     */
    private String buildRolesInfoForLLMSpeakerSelection(RoundTableSession session) {
        StringBuilder sb = new StringBuilder();

        // 以 roleKeys 为准遍历（roleKeys 已含后端自动补全的 HOST + 默认角色）
        String[] roleKeys = session.getRoleKeys() != null
                ? session.getRoleKeys().split(",")
                : new String[0];
        if (roleKeys.length == 0) return "";

        // 解析 roleConfigs JSON，建立 key → JsonNode 映射
        Map<String, JsonNode> configMap = new java.util.LinkedHashMap<>();
        try {
            if (session.getRoleConfigs() != null && !session.getRoleConfigs().isBlank()) {
                var configs = objectMapper.readTree(session.getRoleConfigs());
                if (configs.isArray()) {
                    for (var config : configs) {
                        String key = config.has("key") ? config.get("key").asText() : "";
                        if (!key.isBlank()) {
                            configMap.put(key.trim(), config);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 roleConfigs 失败: {}", e.getMessage());
        }

        for (String key : roleKeys) {
            key = key.trim();
            if (key.isBlank()) continue;

            AiConfig.RoundTableRole role = aiConfigProvider.getRoundTableRole(key);
            if (role == null) {
                log.warn("未知角色 key: {}, 跳过", key);
                continue;
            }

            JsonNode config = configMap.get(key);
            String name = (config != null && config.has("name")) ? config.get("name").asText() : role.getName();
            String title = (config != null && config.has("title")) ? config.get("title").asText() : role.getTitle();
            int domainRelevance = (config != null && config.has("domainRelevance")) ? config.get("domainRelevance").asInt() : 0;
            String languageStyle = (config != null && config.has("languageStyle")) ? config.get("languageStyle").asText() : "";

            sb.append("### ").append(key).append("（").append(name).append("，").append(title).append("）\n");
            // 角色人设截断到 1500 字，保留核心身份+社交倾向+说话风格，去掉决策启发式/时间线等冗余内容
            String prompt = role.getPrompt();
            if (prompt != null && prompt.length() > 1500) {
                prompt = prompt.substring(0, 1500) + "……（已截断）";
            }
            sb.append(prompt).append("\n");
            // 本次讨论专属参数
            sb.append("【本次讨论专属】专业相关度=").append(domainRelevance);
            if (!languageStyle.isBlank()) {
                sb.append(" 语言风格=").append(languageStyle);
            }
            sb.append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 构建「发言人选择」专用的对话历史
     * <p>
     * 只保留最近 {@value #SPEAKER_SELECTION_RECENT_COUNT} 条消息的完整内容：
     * - 判断下一发言人主要靠"最近对话流"——谁刚发言、谁被质疑、话题朝哪个方向走
     * - 更早的消息对"谁该接话"判断价值极低（前 80 字符看不出立场，完整内容又占太多 token）
     * - 想知道"之前聊过什么"已有发言次数统计和覆盖度信息兜底
     * <p>
     * 权重标签仍保留：【当前焦点】最后一条 / 【近期讨论】倒数 2-5 条
     */
    private static final int SPEAKER_SELECTION_RECENT_COUNT = 5;

    private String buildWeightedHistoryForLLM(List<RoundTableMessage> allMessages) {
        if (allMessages.isEmpty()) {
            return "（暂无发言记录，这是讨论的开始）";
        }

        int total = allMessages.size();
        int start = Math.max(0, total - SPEAKER_SELECTION_RECENT_COUNT);

        StringBuilder sb = new StringBuilder();
        // 不足 5 条时（讨论初期）省略提示，直接列出即可
        if (start > 0) {
            sb.append("（为节省篇幅，仅展示最近 ").append(SPEAKER_SELECTION_RECENT_COUNT)
                    .append(" 条发言；更早的发言已省略）\n\n");
        }

        for (int i = start; i < total; i++) {
            RoundTableMessage msg = allMessages.get(i);
            String content = msg.getCompressedContent();
            if (content == null || content.isBlank()) {
                content = msg.getContent();
            }

            // 最近一条标"当前焦点"，其余标"近期讨论"
            String weightLabel = (i == total - 1) ? "【当前焦点】" : "【近期讨论】";
            sb.append(weightLabel).append(msg.getRoleName()).append("：").append(content).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 构建给 LLM 的覆盖度概览（用于纯 LLM 发言人选择的结束判断）
     * <p>
     * 提供量化覆盖度数据，让 LLM 判断 shouldEnd 时有量化依据，而非纯靠感觉。
     * 比 buildHostCoverageGuidance 精简——只列总分、概念覆盖、内容块覆盖、未覆盖概念，
     * 不含未覆盖块的摘要/关键词（避免消息过长、KV-cache 失效）。
     */
    private String buildCoverageBriefForLLM(RoundTableCoverage coverage) {
        StringBuilder sb = new StringBuilder();
        sb.append("【话题覆盖度】\n");

        double score = coverage.getOverallScore() != null ? coverage.getOverallScore() : 0.0;
        sb.append(String.format("总分：%.0f/100（等级 %s）\n", score,
                coverage.getGrade() != null ? coverage.getGrade() : "-"));

        // 概念覆盖
        int totalConcepts = coverage.getTotalConcepts() != null ? coverage.getTotalConcepts() : 0;
        int coveredConcepts = coverage.getCoveredConceptsCount() != null ? coverage.getCoveredConceptsCount() : 0;
        if (totalConcepts > 0) {
            sb.append(String.format("概念覆盖：%d/%d\n", coveredConcepts, totalConcepts));
            List<String> missed = parseTags(coverage.getMissedConceptsJson());
            if (!missed.isEmpty()) {
                sb.append("未覆盖概念：").append(String.join("、", missed)).append("\n");
            }
        }

        // 内容块覆盖
        int totalBlocks = coverage.getTotalBlocks() != null ? coverage.getTotalBlocks() : 0;
        int coveredBlocks = coverage.getCoveredBlocks() != null ? coverage.getCoveredBlocks() : 0;
        if (totalBlocks > 0) {
            sb.append(String.format("内容块覆盖：%d/%d\n", coveredBlocks, totalBlocks));
        }

        return sb.toString();
    }

    /**
     * 构建公平性约束说明（用于纯 LLM 发言人选择）
     * <p>
     * 公平性约束仅作为兜底信息提供，不强制 LLM 必须执行。
     * 对话流向是首要选择依据，公平性只在 LLM 无法判断时起参考作用。
     */
    private String buildFairnessConstraints(String[] roleKeys, Map<String, Long> speakCounts,
                                            List<RoundTableMessage> allMessages) {
        StringBuilder sb = new StringBuilder();

        // 1. 禁止连续发言（硬约束）
        if (!allMessages.isEmpty()) {
            String lastSpeaker = allMessages.get(allMessages.size() - 1).getRoleKey();
            AiConfig.RoundTableRole lastRole = aiConfigProvider.getRoundTableRole(lastSpeaker);
            sb.append("- 【硬约束】").append(lastRole != null ? lastRole.getName() : lastSpeaker)
                    .append("刚发过言，不能连续发言\n");
        }

        // 2. 发言次数统计（供参考，从少到多）
        sb.append("- 发言次数（供参考）：");
        Arrays.stream(roleKeys)
                .map(String::trim)
                .sorted(Comparator.comparingLong(k -> speakCounts.getOrDefault(k, 0L)))
                .forEach(key -> {
                    AiConfig.RoundTableRole role = aiConfigProvider.getRoundTableRole(key);
                    long count = speakCounts.getOrDefault(key, 0L);
                    sb.append(role != null ? role.getName() : key).append("=").append(count).append(" ");
                });
        sb.append("\n");

        // 3. 仅当有角色从未发言且讨论已过半程时，才标记为强制优先
        int totalRounds = allMessages.size();
        int nonHostCount = (int) Arrays.stream(roleKeys).filter(k -> !"HOST".equals(k.trim())).count();
        if (totalRounds >= nonHostCount) {
            Set<String> neverSpoken = Arrays.stream(roleKeys)
                    .map(String::trim)
                    .filter(key -> !"HOST".equals(key) && !speakCounts.containsKey(key))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!neverSpoken.isEmpty()) {
                sb.append("- 【强制优先】以下角色至今未发言：");
                for (String key : neverSpoken) {
                    AiConfig.RoundTableRole role = aiConfigProvider.getRoundTableRole(key);
                    if (role != null) sb.append(role.getName()).append("(").append(key).append(") ");
                }
                sb.append("（从其中选最能接话的）\n");
            }
        }

        return sb.toString();
    }

    /**
     * 解析 LLM 返回的发言人选择结果。
     * <p>
     * 支持三种返回形式（容错优先级）：
     * 1. JSON {"nextSpeaker": "KEY"} 中 KEY 为英文标识符 → 直接匹配
     * 2. JSON {"nextSpeaker": "中文名"} → 通过 nameToKey 反查
     * 3. 非 JSON 文本 → 子串匹配英文 key 或中文名
     * <p>
     * 无法解析出有效角色时抛异常，让外层回退到简单模式。
     *
     * @param response     LLM 原始返回（已去代码块围栏）
     * @param validRoleKeys 合法角色 key 数组（来自 session.roleKeys）
     * @param nameToKey    角色中文名 → key 反向映射（预置+自定义角色）
     */
    private String parseLlmSpeakerResponse(String response, String[] validRoleKeys,
                                           Map<String, String> nameToKey) {
        Set<String> validKeys = Arrays.stream(validRoleKeys).map(String::trim).collect(Collectors.toSet());

        // 尝试解析 JSON {"nextSpeaker": "..."}
        try {
            var jsonNode = objectMapper.readTree(response);
            if (jsonNode.has("nextSpeaker")) {
                String key = jsonNode.get("nextSpeaker").asText().trim();
                // 1. 直接匹配英文 key
                if (validKeys.contains(key)) return key;
                // 2. 反查中文名 → key（LLM 可能把历史/约束里的中文名当 key 返回）
                String mapped = nameToKey.get(key);
                if (mapped != null) return mapped;
                // JSON 解析成功但既不是合法 key 也不是已知中文名 → 抛，不进入子串匹配
                throw new BusinessException("LLM返回了不在场角色: " + key);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception ignored) {
            // JSON 解析失败，进入子串匹配兜底
        }

        // 3a. 子串匹配英文 key（大小写不敏感）
        String upperResponse = response.toUpperCase();
        for (String key : validKeys) {
            if (upperResponse.contains(key.toUpperCase())) {
                return key;
            }
        }

        // 3b. 子串匹配中文名
        for (Map.Entry<String, String> entry : nameToKey.entrySet()) {
            if (response.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        // LLM 返回了数据但不在在场角色中 → 抛异常，让外层回退简单模式
        throw new BusinessException("LLM返回的角色不在在场角色列表中: " + response);
    }

    /**
     * 构建 角色中文名 → key 的反向映射（用于 LLM 返回中文名时的容错解析）。
     * <p>
     * 同时覆盖两类角色：
     * - 预置角色：name 来自 AiConfig.RoundTableRole
     * - 自定义角色：name 来自 session.roleConfigs JSON（自定义角色不在 ai-config.json 中）
     * <p>
     * roleConfigs 中的 name 优先于 AiConfig 默认 name（允许会话级覆盖）。
     */
    private Map<String, String> buildNameToKeyMap(RoundTableSession session, String[] roleKeys) {
        Map<String, String> nameToKey = new HashMap<>();

        // 解析 roleConfigs JSON，建立 key → JsonNode 映射（与 buildRolesInfoForLLMSpeakerSelection 同源）
        Map<String, JsonNode> configMap = new LinkedHashMap<>();
        try {
            if (session.getRoleConfigs() != null && !session.getRoleConfigs().isBlank()) {
                var configs = objectMapper.readTree(session.getRoleConfigs());
                if (configs.isArray()) {
                    for (var config : configs) {
                        String key = config.has("key") ? config.get("key").asText() : "";
                        if (!key.isBlank()) {
                            configMap.put(key.trim(), config);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 roleConfigs 失败: {}", e.getMessage());
        }

        for (String key : roleKeys) {
            String k = key.trim();
            if (k.isBlank()) continue;

            AiConfig.RoundTableRole role = aiConfigProvider.getRoundTableRole(k);
            JsonNode config = configMap.get(k);
            // roleConfigs 中的 name 优先，其次 AiConfig 默认 name
            String name = (config != null && config.has("name")) ? config.get("name").asText()
                    : (role != null ? role.getName() : null);
            if (name != null && !name.isBlank()) {
                nameToKey.put(name.trim(), k);
            }
        }
        return nameToKey;
    }

    // ==================== 单角色发言（SSE + DB 持久化） ====================

    /**
     * 单角色发言 SSE：前端指定某个角色发言，后端从 DB 加载历史并调用 AI 生成发言内容
     *
     * @param userId  用户ID
     * @param bookId  书籍ID
     * @param request 发言请求（包含角色键名、会话ID、话题方向）
     * @return SseEmitter 流式发射器
     */
    @LogAction("圆桌派角色发言")
    public SseEmitter streamCharacterSpeak(Long userId, Long bookId, SpeakRequest request) {
        SseEmitter emitter = new SseEmitter(3600_000L);

        // 解析角色
        AiConfig.RoundTableRole role = aiConfigProvider.getRoundTableRole(request.getRoleKey());
        if (role == null) {
            SseHelper.sendErrorAndComplete(emitter, "未知角色: " + request.getRoleKey());
            return emitter;
        }

        // 验证会话
        RoundTableSession session = sessionRepository.query()
                .where(RoundTableSession::getSessionId, eq(request.getSessionId()))
                .list(1)
                .stream().findFirst().orElse(null);
        if (session == null) {
            SseHelper.sendErrorAndComplete(emitter, "会话不存在: " + request.getSessionId());
            return emitter;
        }

        // 验证会话所有权：只有创建者可以发言
        try {
            verifySessionOwnership(userId, request.getSessionId());
        } catch (BusinessException e) {
            SseHelper.sendErrorAndComplete(emitter, e.getMessage());
            return emitter;
        }

        // 获取角色的 domainRelevance（从 roleConfigs JSON 中读取，或使用枚举默认值）
        int domainRelevance = resolveDomainRelevance(role, session.getRoleConfigs());

        // 获取角色的 languageStyle（从 roleConfigs JSON 中读取）
        String languageStyle = resolveLanguageStyle(role, session.getRoleConfigs());

        Future<?> aiFuture = sseExecutor.submit(() -> {
            try {
                Book book = bookService.getBookById(bookId);

                // 加载历史消息（用于判断是否开场以及构建 RAG）
                List<RoundTableMessage> historyMessages = Collections.emptyList();
                if (book != null) {
                    historyMessages = messageRepository.query()
                            .where(RoundTableMessage::getSessionId, eq(request.getSessionId()))
                            .orderBy(RoundTableMessage::getId)
                            .list();
                }
                boolean isOpening = historyMessages.isEmpty();

                // 构建系统提示词（仅共享规则）
                String systemPrompt = buildCharacterSystemPrompt(role, domainRelevance, request.getTopic(), languageStyle, isOpening);

                // 构建角色设定 UserMessage（每个角色不同，KV 缓存在此处分叉）
                String roleSetting = buildRoleSettingPrompt(role, domainRelevance, languageStyle);

                // 构建额外指令（话题方向 / 开场引导）
                String extraInstructions = buildExtraInstructions(role, request.getTopic(), isOpening);

                // 构建书籍上下文（静态信息）和 RAG 内容（每次变化）
                String bookInfo = book != null ? buildBookInfo(book) : "";
                String ragContent = "";
                if (book != null) {
                    if (!"HOST".equals(role.getKey())) {
                        // 嘉宾：RAG 检索原著内容
                        if (!Boolean.TRUE.equals(book.getContentEmbedded())) {
                            boolean embedded = waitForContentEmbedding(bookId);
                            if (embedded) book.setContentEmbedded(true);
                        }
                        ragContent = retrieveRagContextForRole(book, role, historyMessages);
                    } else {
                        // 主持人
                        if (historyMessages.isEmpty()) {
                            // 首轮开场：不注入覆盖度/话题进度，避免覆盖「开场介绍书籍」的指令
                            ragContent = "这是圆桌派讨论的第一轮。请先以主持人的身份做一个完整的开场："
                                    + "欢迎各位嘉宾，然后简要介绍今天要讨论的书籍《" + book.getTitle() + "》的核心主题，"
                                    + "最后向嘉宾抛出第一个讨论问题。不要跳过开场直接讨论具体概念。";
                        } else {
                            // 覆盖度引导（比 RAG 更精准）
                            try {
                                coverageService.updateCoverage(request.getSessionId(), true);
                                String coverageGuidance = coverageService.buildHostCoverageGuidance(request.getSessionId());
                                if (!coverageGuidance.isBlank()) {
                                    ragContent = coverageGuidance;
                                }
                            } catch (Exception e) {
                                log.warn("覆盖度更新/引导生成失败，回退到简单模式: {}", e.getMessage());
                            }
                            if (ragContent.isBlank()) {
                                ragContent = appendHostTopicProgress(book, historyMessages);
                            }
                        }
                    }
                }

                // 消息顺序：SystemMessage(规则) → UserMessage(书籍信息) → History → UserMessage(RAG) → UserMessage(角色设定) → UserMessage(发言指令)
                List<ChatMessage> messages = buildChatMessages(
                        request.getSessionId(), userId, systemPrompt, bookInfo, ragContent, roleSetting, extraInstructions, role.getName(),
                        role, book);

                // 构建流式模型（不使用 thinking 模式）
                StreamingChatModel streamingChatModel = chatModelManager.getStreamingModelForScene(AiScene.ROUND_TABLE_SPEECH);
                if (streamingChatModel == null) {
                    SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                // 构建日志上下文（场景/模型/思考配置），供 StreamingSseHandler 打印统一摘要
                var logContext = chatModelManager.buildLogContext(AiScene.ROUND_TABLE_SPEECH);

                StreamingSseHandler.stream(streamingChatModel, messages, emitter, new StreamingSseHandler.Callback() {
                    @Override
                    public String getOperationName() { return "圆桌派发言"; }

                    @Override
                    public String formatMessageEvent(String text) {
                        try {
                            return objectMapper.writeValueAsString(
                                    Map.of("roleKey", role.getKey(), "text", text));
                        } catch (Exception e) {
                            return text;
                        }
                    }

                    @Override
                    public void onComplete(String content, ChatResponse completeResponse) {
                        // 统一摘要日志已由 StreamingSseHandler.onCompleteResponse 打印

                        if (!content.isBlank()) {
                            saveMessage(userId, request.getSessionId(), bookId, role.getKey(), role.getName(), content);
                            updateSessionTimestamp(request.getSessionId());

                            try {
                                compressHistoryIfNeeded(userId, request.getSessionId(), 0);
                            } catch (Exception e) {
                                log.warn("压缩圆桌派历史失败: sessionId={} - {}", request.getSessionId(), e.getMessage());
                            }
                        }

                        // 异步更新覆盖度
                        if (!"HOST".equals(role.getKey()) && !content.isBlank()) {
                            try {
                                coverageService.updateCoverage(request.getSessionId(), false);
                            } catch (Exception e) {
                                log.warn("覆盖度更新失败: sessionId={} - {}", request.getSessionId(), e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onConnectionClosed(String partialContent) {
                        if (!partialContent.isBlank()) {
                            try {
                                saveMessage(userId, request.getSessionId(), bookId, role.getKey(), role.getName(), partialContent);
                            } catch (Exception e) {
                                log.warn("保存部分消息失败: {}", e.getMessage());
                            }
                        }
                    }
                }, 2, logContext);

            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) return;
                log.error("圆桌派单角色发言异常: bookId={}, roleKey={} - {}", bookId, role.getKey(), e.getMessage(), e);
                SseHelper.sendErrorAndComplete(emitter, "AI 响应异常: " + SseHelper.extractFriendlyError(e));
            }
        });

        emitter.onCompletion(() -> aiFuture.cancel(true));
        emitter.onTimeout(() -> {
            aiFuture.cancel(true);
            log.warn("圆桌派SSE超时: bookId={}, roleKey={}", bookId, role.getKey());
        });
        emitter.onError(e -> {
            aiFuture.cancel(true);
            log.error("圆桌派SSE错误: bookId={}, roleKey={}", bookId, role.getKey(), e);
        });

        return emitter;
    }

    // ==================== 提示词构建 ====================

    /**
     * 谢幕指令前缀 — 前端把 closingSummary 作为 topic 传入时加此前缀，
     * 后端据此切换 HOST 的系统提示词为谢幕 prompt。
     */
    private static final String CLOSING_TOPIC_PREFIX = "[CLOSING]";

    /**
     * 构建单角色系统提示词（含语言风格、性格维度和 domainRelevance）
     * <p>
     * HOST 检测到 topic 以 [CLOSING] 开头时切换为谢幕 prompt。
     */
    private String buildCharacterSystemPrompt(AiConfig.RoundTableRole role, int domainRelevance, String topic, String languageStyle, boolean isOpening) {
        if ("HOST".equals(role.getKey())) {
            if (topic != null && topic.startsWith(CLOSING_TOPIC_PREFIX)) {
                return AiPromptConstants.ROUND_TABLE_HOST_CLOSING_PROMPT;
            }
            return AiPromptConstants.ROUND_TABLE_HOST_PROMPT;
        } else {
            return AiPromptConstants.ROUND_TABLE_CHARACTER_PROMPT;
        }
    }

    /**
     * 构建角色设定 UserMessage（包含人设核心、说话方式、风格、性格参数）
     * 此部分每个角色不同，KV 缓存在此处分叉
     */
    private String buildRoleSettingPrompt(AiConfig.RoundTableRole role, int domainRelevance, String languageStyle) {
        if ("HOST".equals(role.getKey())) {
            String hostStyle = (languageStyle != null && !languageStyle.isBlank()) ? languageStyle : "沉稳大方，善于引导和总结";
            return String.format(AiPromptConstants.ROUND_TABLE_ROLE_SETTING_HOST,
                    hostStyle,
                    role.getParams().getChallenge(), describeChallenge(role.getParams().getChallenge()),
                    role.getParams().getEmpathy(), describeEmpathy(role.getParams().getEmpathy()),
                    role.getParams().getOpinionated(), describeOpinionated(role.getParams().getOpinionated()),
                    role.getParams().getVerbosity(), describeVerbosity(role.getParams().getVerbosity()));
        } else {
            String charStyle = (languageStyle != null && !languageStyle.isBlank()) ? languageStyle : "自然流畅，符合你的专业身份";

            String rolePrompt = role.getPrompt();
            String catchphrase = role.getCatchphrase() != null ? role.getCatchphrase() : "用你自己的方式表达，保持自然";

            return String.format(AiPromptConstants.ROUND_TABLE_ROLE_SETTING_GUEST,
                    rolePrompt,
                    catchphrase,
                    charStyle,
                    role.getParams().getChallenge(), describeChallenge(role.getParams().getChallenge()),
                    role.getParams().getEmpathy(), describeEmpathy(role.getParams().getEmpathy()),
                    role.getParams().getOpinionated(), describeOpinionated(role.getParams().getOpinionated()),
                    role.getParams().getVerbosity(), describeVerbosity(role.getParams().getVerbosity()),
                    role.getParams().getHumor(), describeHumor(role.getParams().getHumor()),
                    domainRelevance, describeDomainRelevance(domainRelevance));
        }
    }

    /**
     * 构建额外指令（话题方向 / 开场引导 / 覆盖度 / 谢幕），作为发言指令的前缀
     * <p>
     * 谢幕指令（topic 以 [CLOSING] 开头）走独立分支：
     * - 系统提示词已在 buildCharacterSystemPrompt 中切换为 ROUND_TABLE_HOST_CLOSING_PROMPT
     * - 这里只提取 closingSummary 主体作为 UserMessage，让 HOST 自由发挥
     */
    private String buildExtraInstructions(AiConfig.RoundTableRole role, String topic, boolean isOpening) {
        if ("HOST".equals(role.getKey())) {
            // 谢幕指令：topic 格式为 "[CLOSING]<closingSummary>"
            if (topic != null && topic.startsWith(CLOSING_TOPIC_PREFIX)) {
                String closingSummary = topic.substring(CLOSING_TOPIC_PREFIX.length()).trim();
                return "【谢幕提纲】\n" + (closingSummary.isBlank()
                        ? "请对本次讨论做总结谢幕。"
                        : closingSummary);
            }
            if (topic != null && !topic.isBlank()) {
                return "【话题方向】\n请围绕以下方向引导讨论：" + topic;
            } else if (isOpening) {
                return "【开场（第一轮）】这是圆桌派讨论的开场。你作为主持人，最重要的任务是做开场介绍："
                        + "首先欢迎各位嘉宾，然后简要介绍今天要讨论的书籍的核心主题和为什么值得讨论，"
                        + "最后向嘉宾抛出第一个讨论问题。此指令优先于「每次发言必须引入新主题」。";
            } else {
                return "请回顾之前的对话。如果讨论陷入僵局、重复或钻牛角尖，请果断抛出一个新的话题或角度来激发讨论。如果讨论还在正常进行，可以简短回应或向某位嘉宾提问。";
            }
        }
        return "";
    }

    /**
     * 挑战倾向描述
     */
    private String describeChallenge(int v) {
        if (v >= 4) return "喜欢质疑和反驳";
        if (v >= 3) return "适度挑战";
        return "较少质疑";
    }

    /**
     * 共情力描述
     */
    private String describeEmpathy(int v) {
        if (v >= 4) return "善于理解和共鸣";
        if (v >= 3) return "适度共情";
        return "理性优先";
    }

    /**
     * 主见程度描述
     */
    private String describeOpinionated(int v) {
        if (v >= 4) return "立场坚定";
        if (v >= 3) return "有一定主见";
        return "立场灵活";
    }

    /**
     * 话量描述
     */
    private String describeVerbosity(int v) {
        if (v >= 4) return "话多";
        if (v >= 3) return "话量适中";
        return "话少精炼";
    }

    /**
     * 幽默感描述
     */
    private String describeHumor(int v) {
        if (v >= 4) return "善于调侃和活跃气氛";
        if (v >= 3) return "适度幽默";
        return "严肃认真";
    }

    /**
     * 专业相关度描述
     */
    private String describeDomainRelevance(int v) {
        if (v >= 7) return "这是你的专业主场，应该自信发言";
        if (v >= 4) return "与你的领域有一定关联";
        return "超出你的专业领域，但可以从你的视角提供独特见解";
    }

    /**
     * 从 roleConfigs JSON 中解析角色的 domainRelevance
     */
    private int resolveDomainRelevance(AiConfig.RoundTableRole role, String roleConfigs) {
        if (roleConfigs == null || roleConfigs.isBlank()) {
            return 0;
        }
        try {
            var nodes = objectMapper.readTree(roleConfigs);
            if (nodes.isArray()) {
                for (var node : nodes) {
                    if (node.has("key") && role.getKey().equals(node.get("key").asText())) {
                        return node.has("domainRelevance") ? node.get("domainRelevance").asInt() : 0;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析 roleConfigs 失败: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 从 roleConfigs JSON 中解析角色的 languageStyle
     */
    private String resolveLanguageStyle(AiConfig.RoundTableRole role, String roleConfigs) {
        if (roleConfigs == null || roleConfigs.isBlank()) {
            return "";
        }
        try {
            var nodes = objectMapper.readTree(roleConfigs);
            if (nodes.isArray()) {
                for (var node : nodes) {
                    if (node.has("key") && role.getKey().equals(node.get("key").asText())) {
                        return node.has("languageStyle") ? node.get("languageStyle").asText() : "";
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析角色语言风格失败: {}", e.getMessage());
        }
        return "";
    }

    // ==================== 消息构建 ====================

    /**
     * 构建消息列表，顺序优化 KV Cache：
     * SystemMessage(共享规则) → UserMessage(书籍信息) → 历史对话 → UserMessage(角色设定，角色内稳定)
     * → UserMessage(RAG，每次不同) → UserMessage(额外指令+发言指令)
     */
    private List<ChatMessage> buildChatMessages(String sessionId, Long userId,
                                                String systemPrompt, String bookInfo, String ragContent,
                                                String roleSetting, String extraInstructions, String roleName,
                                                AiConfig.RoundTableRole role, Book book) {
        List<ChatMessage> messages = new ArrayList<>();

        // 1. 系统提示词（仅共享规则）— 全局静态
        messages.add(SystemMessage.from(systemPrompt));

        // 2. 静态书籍基础信息 — 全局静态
        if (bookInfo != null && !bookInfo.isBlank()) {
            messages.add(UserMessage.from("【书籍信息】\n" + bookInfo));
        }

        // 3. 角色设定 — 同一角色跨轮次不变，通常 2000-3000 字符，尽量靠前以复用缓存
        if (roleSetting != null && !roleSetting.isBlank()) {
            messages.add(UserMessage.from(roleSetting));
        }

        // 4. 发言指令（含额外指令）— 同一角色跨轮次不变
        String speakInstruction;
        if ("HOST".equals(roleName) || "主持人".equals(roleName)) {
            speakInstruction = "请以主持人的身份发言。直接说你的观点或抛出问题，绝对不要以「刚才大家...」「前面几位...」「听了各位...」开头。";
        } else {
            speakInstruction = "请以" + roleName + "的身份发言。直接接话，绝对不要以「刚才大家...」「前面几位...」「听了各位...」开头，直接说你的观点。";
        }
        StringBuilder finalInstruction = new StringBuilder();
        if (extraInstructions != null && !extraInstructions.isBlank()) {
            finalInstruction.append(extraInstructions).append("\n\n");
        }
        finalInstruction.append(speakInstruction);
        messages.add(UserMessage.from(finalInstruction.toString()));

        // ——— 以上 4 条：同一角色跨轮次可命中缓存（SystemMessage + bookInfo + roleSetting + instruction）———
        // ——— 以下为动态内容，每次变化，放在最后以最小化缓存失效 ———

        // 5. 历史对话（每次变化）
        int currentOverhead = systemPrompt.length()
                + (bookInfo != null ? bookInfo.length() : 0)
                + (ragContent != null ? ragContent.length() : 0)
                + (roleSetting != null ? roleSetting.length() : 0)
                + (extraInstructions != null ? extraInstructions.length() : 0)
                + 2000;
        List<RoundTableMessage> history = new ArrayList<>();
        try {
            history = loadAndCompressHistory(userId, sessionId, currentOverhead);
            if (!history.isEmpty()) {
                StringBuilder historyBuilder = new StringBuilder("【之前的讨论内容】\n");
                for (RoundTableMessage msg : history) {
                    String content = msg.getCompressedContent() != null && !msg.getCompressedContent().isBlank()
                            ? msg.getCompressedContent()
                            : msg.getContent();
                    if (content != null && !content.isBlank()) {
                        historyBuilder.append(msg.getRoleName()).append("：").append(content).append("\n\n");
                    }
                }
                messages.add(UserMessage.from(historyBuilder.toString()));
                log.debug("加载圆桌派历史: sessionId={}, totalRecords={}", sessionId, history.size());
            }
        } catch (Exception e) {
            log.warn("加载圆桌派历史失败，继续无历史对话: {}", e.getMessage());
        }

        // 6. RAG 内容（每次变化）
        if (ragContent != null && !ragContent.isBlank()) {
            messages.add(UserMessage.from("【书籍参考内容】\n" + ragContent));
        }

        // 7. 外部知识（每次变化）
        String externalKnowledge = generateExternalKnowledgeForRole(role, book, history);
        if (externalKnowledge != null && !externalKnowledge.isBlank()) {
            messages.add(UserMessage.from("【外部参考知识】\n以下知识可帮助你从专业视角评价讨论话题：\n" + externalKnowledge));
        }

        return messages;
    }

    // ==================== 消息持久化 ====================

    /**
     * 为角色生成外部知识
     */
    private String generateExternalKnowledgeForRole(AiConfig.RoundTableRole role, Book book, List<RoundTableMessage> history) {
        try {
            String domain = getRoleDomain(role);
            String topic = buildDiscussionTopic(book, history);
            return externalKnowledgeGenerator.generateForRoundTable(domain, topic);
        } catch (Exception e) {
            log.debug("生成外部知识失败: role={}, error={}", role.getKey(), e.getMessage());
            return "";
        }
    }

    private String getRoleDomain(AiConfig.RoundTableRole role) {
        return switch (role.getKey()) {
            case "SOCIOLOGIST" -> "社会学：文化资本、规训与惩罚、文化霸权、拟剧论、社会团结、性别操演";
            case "HISTORIAN" -> "历史学：长时段、年鉴学派、二重证据法、历史合力论、世界体系、集体记忆";
            case "PSYCHOLOGIST" -> "心理学：依恋理论、防御机制、双系统理论、认知失调、群体动力、习得性无助、大五人格";
            case "PHILOSOPHER" -> "哲学：概念辨析、逻辑推演、伦理框架、形而上学、语言分析、存在论";
            case "SCIENTIST" -> "科学：可证伪性、控制变量、RCT、统计显著性、范式革命、幸存者偏差";
            case "ENTREPRENEUR" -> "商业：商业模式画布、蓝海战略、精益创业、增长黑客、OKR、护城河理论";
            case "INVESTOR" -> "投资：安全边际、护城河、价值投资、损失厌恶、凯利公式、美林时钟";
            case "ECONOMIST" -> "经济学：边际效用、机会成本、博弈论、科斯定理、交易成本、行为经济学";
            case "LAWYER" -> "法律：三段论推理、归谬法、文义解释、举证责任、无罪推定、程序正义";
            case "JOURNALIST" -> "新闻：5W1H、信源交叉验证、框架理论、议程设置、数据驱动调查、媒介伦理";
            case "CRITIC" -> "文学批评：叙事学、隐喻修辞、形式主义、阐释学、解构主义、女性主义批评";
            case "WRITER" -> "创作：三幕式结构、冰山理论、人物弧光、陌生化、复调小说、意识流";
            case "EDUCATOR" -> "教育：最近发展区、自我决定理论、成长型思维、掌握学习、操作性条件反射";
            case "STUDENT" -> "求知：苏格拉底反诘法、费曼学习法、元认知、布鲁姆提问法、跨学科迁移";
            case "COMEDIAN" -> "幽默：预期违背、错位反差、归谬法、黑色幽默、三翻四抖、荒诞派";
            case "ACTOR" -> "表演：斯坦尼体系、方法派、布莱希特间离、情绪记忆、人物小传、第四面墙";
            case "DIRECTOR" -> "导演：蒙太奇理论、长镜头理论、场面调度、景别逻辑、剪辑节奏、声画关系";
            case "ARTIST" -> "艺术：形式主义、图像学、符号学、极简主义、观念艺术、关系美学";
            case "MUSICIAN" -> "音乐：曲式结构、和声对位、调性无调性、配器法、音色组合、偶然音乐";
            case "POET" -> "诗歌：意象叠加、客观对应物、通感、陌生化、含混多义、语言悖论";
            case "TRANSLATOR" -> "翻译：功能对等、归化异化、文化缺省、目的论、解构主义翻译、文本类型学";
            case "DOCTOR" -> "医学：鉴别诊断、循证医学、安慰剂效应、治疗窗、知情同意、医学伦理四原则";
            case "FARMER" -> "农业：二十四节气、轮作倒茬、地力养护、桑基鱼塘、看天吃饭、间作套种";
            case "FIREFIGHTER" -> "消防：火灾三角形、先控制后消灭、生命优先原则、安全观察员、两进一出、分级响应";
            case "NURSE" -> "护理：SBAR沟通、治疗性沟通、疼痛三阶梯、ADL量表、舒适护理、护理程序";
            case "MEDITATION_TEACHER" -> "冥想：观呼吸、身体扫描、RAIN法、正念减压MBSR、认知解离、四无量心";
            case "PARENT" -> "家庭：依恋理论、权威型教养、非暴力沟通、成长型思维、正强化、延迟满足";
            case "TRAVELER" -> "旅行：文化维度理论、文化相对主义、文化冲击、参与式观察、厚描述、第三文化";
            case "TECH_EXPERT" -> "技术：算法复杂度、系统架构、网络协议、分布式系统、技术伦理、银弹思维";
            case "ENGINEER" -> "工程：FMEA故障模式、约束理论、测试金字塔、混沌工程、SRE、MTBF/MTTR";
            case "EDITOR" -> "出版：叙事结构、杀死汝爱、show don't tell、钩子理论、读者画像、陌生化";
            case "BOOK_REVIEWER" -> "书评：期待视野、隐含读者、文化资本、互文性、原型批评、接受美学";
            case "DIPLOMAT" -> "外交：BATNA、ZOPA、囚徒困境、纳什均衡、均势理论、软实力";
            case "LIBRARIAN" -> "图书管理：杜威分类法、分面分类、版本目录学、引文分析、主题阅读、信息素养";
            case "SOCIAL_WORKER" -> "社会工作：优势视角、增权理论、生态系统理论、人在情境中、社会资本、社会支持网";
            case "SPORTS_COACH" -> "体育：周期化训练、超量恢复、刻意练习、目标设置、心理韧性、贝尔宾团队角色";
            case "ANTHROPOLOGIST" -> "人类学：文化相对主义、厚描述、参与式观察、结构人类学、仪式理论、莫斯礼物之灵";
            case "FEMINIST" -> "女性主义：性别展演、男性凝视、情感劳动、立场理论、交叉性、个人即政治";
            case "ECOLOGIST" -> "生态学：生态系统、能量金字塔、盖亚假说、关键种、生态足迹、公地悲剧";
            case "STRATEGIST" -> "战略：知己知彼、不战而屈人之兵、战争论、核威慑理论、海权论、OODA循环";
            default -> "跨学科：综合分析视角";
        };
    }

    private String buildDiscussionTopic(Book book, List<RoundTableMessage> history) {
        StringBuilder topic = new StringBuilder();
        topic.append("关于《").append(book.getTitle()).append("》的讨论");
        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - 2);
            for (int i = start; i < history.size(); i++) {
                RoundTableMessage msg = history.get(i);
                String content = msg.getCompressedContent() != null ? msg.getCompressedContent() : msg.getContent();
                if (content != null && !content.isBlank()) {
                    topic.append("，最近讨论到：").append(content, 0, Math.min(100, content.length()));
                }
            }
        }
        return topic.toString();
    }

    /**
     * 保存角色发言消息
     */
    private void saveMessage(Long userId, String sessionId, Long bookId,
                             String roleKey, String roleName, String content) {
        try {
            // 只查最后一条的 round 值，避免全量加载历史消息
            RoundTableMessage lastMsg = messageRepository.query()
                    .where(RoundTableMessage::getSessionId, eq(sessionId))
                    .orderBy(RoundTableMessage::getId)
                    .list()
                    .stream().reduce((a, b) -> b).orElse(null);
            int round = 1;
            if (lastMsg != null) {
                Integer lastRound = lastMsg.getRound();
                round = (lastRound != null ? lastRound : 0) + 1;
            }

            RoundTableMessage record = RoundTableMessage.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .bookId(bookId)
                    .roleKey(roleKey)
                    .roleName(roleName)
                    .content(content)
                    .compressedContent(content) // 初始等于原始内容
                    .round(round)
                    .build();
            messageRepository.save(record);
        } catch (Exception e) {
            log.warn("保存圆桌派消息失败: {}", e.getMessage());
        }
    }

    /**
     * 更新会话的最后活跃时间
     */
    private void updateSessionTimestamp(String sessionId) {
        sessionRepository.query()
                .where(RoundTableSession::getSessionId, eq(sessionId))
                .list(1)
                .stream().findFirst()
                .ifPresent(session -> {
                    session.setUpdatedAt(java.time.LocalDateTime.now());
                    sessionRepository.save(session);
                });
    }

    // ==================== 消息加载与压缩 ====================

    /**
     * 加载历史消息并同步压缩，一次 DB 查询完成加载+压缩判断+压缩执行。
     * 避免 {@link #compressHistoryIfNeeded} 加载后调用方再次查询数据库。
     *
     * @param userId               用户ID
     * @param sessionId            会话ID
     * @param currentOverheadChars 当前请求的系统开销字符数（系统提示词、书籍上下文、发言指令等固定部分）
     * @return 压缩后的消息列表，可直接用于构建 ChatMessage
     */
    private List<RoundTableMessage> loadAndCompressHistory(Long userId, String sessionId, int currentOverheadChars) {
        // 1. 一次查询加载所有消息
        List<RoundTableMessage> messages = messageRepository.query()
                .where(RoundTableMessage::getUserId, eq(userId))
                .and(RoundTableMessage::getSessionId, eq(sessionId))
                .orderBy(RoundTableMessage::getId)
                .list();
        if (messages.isEmpty()) return messages;

        // 2. 内存计算总字符数（优先 compressedContent）
        long totalChars = messages.stream()
                .mapToLong(m -> {
                    String c = m.getCompressedContent();
                    return c != null ? c.length() : (m.getContent() != null ? m.getContent().length() : 0);
                })
                .sum();

        // 3. 计算触发阈值（从 ROUND_TABLE_SPEECH 场景绑定配置读，跟随场景路由）
        Integer maxTokens = null;
        try {
            AiProviderConfig sceneConfig = aiSceneConfigService.resolveConfig(AiScene.ROUND_TABLE_SPEECH);
            if (sceneConfig != null) {
                maxTokens = sceneConfig.getMaxTokens();
            }
        } catch (Exception e) {
            log.warn("解析 ROUND_TABLE_SPEECH 场景配置失败，回退到默认 maxTokens: {}", e.getMessage());
        }
        int tokenLimit = maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS;
        int charLimit = (int) (tokenLimit * TOKEN_TO_CHAR_RATIO);

        // 4. 未达触发阈值，直接返回（不再做预算估算——batch 只有一次 LLM 调用，直接告诉比例即可）
        if (totalChars < charLimit * COMPRESS_TRIGGER_RATIO) {
            return messages;
        }

        // 5. 收集所有未压缩的消息，一次性批量压缩
        List<RoundTableMessage> toCompress = messages.stream()
                .filter(m -> {
                    String compressed = m.getCompressedContent();
                    String original = m.getContent();
                    return compressed == null || original == null || compressed.equals(original);
                })
                .collect(Collectors.toList());

        if (toCompress.isEmpty()) {
            return messages;
        }

        // 6. 一次性批量压缩（单次 LLM 调用），替换原有逐条串行压缩
        List<String> originals = toCompress.stream()
                .map(RoundTableMessage::getContent)
                .toList();
        List<String> summaries = chatHistoryCompressor.compressRoundTableContentBatch(originals);
        if (summaries == null || summaries.size() != toCompress.size()) {
            log.warn("圆桌派批量压缩返回异常(跳过): sessionId={}, expected={}, actual={}",
                    sessionId, toCompress.size(),
                    summaries != null ? summaries.size() : "null");
            return messages;
        }

        int compressed = 0;
        for (int i = 0; i < toCompress.size(); i++) {
            String summary = summaries.get(i);
            if (summary == null || summary.isBlank()) {
                log.warn("圆桌派单条压缩结果为空(跳过): sessionId={}, msgId={}",
                        sessionId, toCompress.get(i).getId());
                continue;
            }
            RoundTableMessage target = toCompress.get(i);
            String original = target.getContent();
            target.setCompressedContent(summary);
            messageRepository.save(target);
            totalChars = totalChars - (original != null ? original.length() : 0) + summary.length();
            compressed++;
            log.info("压缩圆桌派消息: sessionId={}, msgId={}, {}→{} chars, totalChars={}",
                    sessionId, target.getId(), original != null ? original.length() : 0, summary.length(), totalChars);
        }

        if (compressed > 0) {
            log.info("圆桌派压缩完成: sessionId={}, 批量压缩 {} 条（单次 LLM 调用）", sessionId, compressed);
        }

        return messages;
    }

    /**
     * 按需压缩会话历史消息（薄封装，无预加载列表时使用）。
     * <p>
     * 调用方已持有消息列表时应直接使用 {@link #loadAndCompressHistory}，
     * 本方法仅在发言完成后等无预加载场景使用，内部仍会加载一次。
     */
    private void compressHistoryIfNeeded(Long userId, String sessionId, int currentOverheadChars) {
        loadAndCompressHistory(userId, sessionId, currentOverheadChars);
    }

    // ==================== 书籍上下文构建 ====================

    /**
     * 构建静态图书基本信息（不含 RAG，用于 KV Cache 前缀复用）
     */
    private String buildBookInfo(Book book) {
        StringBuilder sb = new StringBuilder();
        sb.append("书名：《").append(book.getTitle()).append("》\n");
        if (book.getAuthor() != null && !book.getAuthor().isBlank()) {
            sb.append("作者：").append(book.getAuthor()).append("\n");
        }
        if (book.getFormatTags() != null && !book.getFormatTags().isBlank()) {
            String tags = book.getFormatTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("标签：").append(tags).append("\n");
        }
        if (book.getConceptTags() != null && !book.getConceptTags().isBlank()) {
            String concepts = book.getConceptTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("核心概念：").append(concepts).append("\n");
        }
        if (book.getReaderNeedTags() != null && !book.getReaderNeedTags().isBlank()) {
            String needs = book.getReaderNeedTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("读者关注：").append(needs).append("\n");
        }
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            sb.append("简介：").append(book.getDescription()).append("\n");
        }

        return sb.toString();
    }

    /**
     * 角色视角 RAG 检索 — 每个角色根据自己的专业角度搜索书中相关内容
     * <p>
     * 不同角色关注书中不同方面：
     * - 哲学家关注"意义、本质、伦理"相关内容
     * - 心理学家关注"心理、情感、动机"相关内容
     * - 社会学家关注"社会、权力、阶级"相关内容
     * - 等等
     * <p>
     * 同时结合当前讨论的最新话题，让检索更精准
     */
    private String retrieveRagContextForRole(Book book, AiConfig.RoundTableRole role, List<RoundTableMessage> history) {
        if (!embeddingService.isAvailable()) {
            log.debug("Embedding 不可用，跳过角色视角 RAG 检索");
            return "";
        }

        try {
            if (!waitForContentEmbedding(book.getId())) {
                log.debug("内容向量不可用，跳过角色视角 RAG 检索: bookId={}", book.getId());
                return "";
            }

            AiConfig.RagStrategy strategy = role.getRagStrategy();
            int topN = (strategy != null && strategy.getTopN() > 0) ? strategy.getTopN() : 10;
            int neighborPrev = (strategy != null) ? strategy.getNeighborPrev() : 0;
            int neighborNext = (strategy != null) ? strategy.getNeighborNext() : 0;
            int subQueryCount = (strategy != null && strategy.getSubQueryCount() > 0) ? strategy.getSubQueryCount() : 1;
            int maxChars = (strategy != null && strategy.getMaxChars() > 0) ? strategy.getMaxChars() : 8000;
            List<String> focusKeywords = (strategy != null) ? strategy.getFocusKeywords() : null;
            String perspectiveHint = (strategy != null) ? strategy.getPerspectiveHint() : null;

            // 生成子查询
            List<String> subQueries = buildRoleSubQueries(book, role, history, subQueryCount, perspectiveHint);
            if (subQueries.isEmpty()) {
                log.debug("角色视角 RAG 查询生成失败: bookId={}, role={}", book.getId(), role.getKey());
                return "";
            }
            log.debug("角色视角 RAG 查询: role={}, subQueries={}", role.getKey(), subQueries);

            // 1. 多子查询向量检索
            List<EmbeddingMatch<TextSegment>> allMatches = new ArrayList<>();
            for (String subQuery : subQueries) {
                try {
                    List<EmbeddingMatch<TextSegment>> matches =
                            embeddingService.searchContent(subQuery, topN, book);
                    allMatches.addAll(matches);
                } catch (Exception e) {
                    log.debug("子查询检索失败: role={}, subQuery={} - {}", role.getKey(), subQuery, e.getMessage());
                }
            }
            if (allMatches.isEmpty()) return "";

            // 2. score ≥ 0.1 过滤（保留原有风控）
            allMatches = allMatches.stream()
                    .filter(m -> m.score() >= 0.1)
                    .toList();
            if (allMatches.isEmpty()) return "";

            // 3. 按 chunkIndex 去重（保留 score 更高的）
            List<EmbeddingMatch<TextSegment>> deduped =
                    RagPipelineComponents.dedupByChunkIndex(allMatches);

            // 4. focusKeywords 加权（在 topN 截断前应用，让视角偏好影响排序）
            deduped = RagPipelineComponents.applyFocusKeywordsBoost(deduped, focusKeywords);

            // 5. 按 score 降序 + topN 截断（全局 topN，而非每子查询）
            deduped.sort((a, b) -> Double.compare(b.score(), a.score()));
            List<EmbeddingMatch<TextSegment>> topNMatches = deduped.stream()
                    .limit(topN)
                    .collect(Collectors.toCollection(ArrayList::new));

            // 6. 自适应邻域扩展
            List<EmbeddingMatch<TextSegment>> expanded = RagPipelineComponents.adaptiveNeighborExpand(
                    topNMatches, book.getId(), neighborPrev, neighborNext,
                    (bookId, idx) -> embeddingService.searchContentByChunkIndex(bookId, idx));

            // 7. 合并相邻 + 按 bestScore 重排
            List<EmbeddingMatch<TextSegment>> merged = RagPipelineComponents.mergeAdjacentChunks(expanded);
            merged.sort((a, b) -> Double.compare(b.score(), a.score()));

            // 8. maxChars 截断填充
            String ragContext = RagPipelineComponents.truncateToChars(merged, maxChars);

            log.info("圆桌派RAG role={} | 子查询={}, 原始{}条 → 去重{}条 → topN={} → 邻域扩展={} → 合并{}条 → 最终{}字",
                    role.getKey(), subQueries.size(), allMatches.size(),
                    deduped.size(), topNMatches.size(), expanded.size(),
                    merged.size(), ragContext.length());

            return ragContext;
        } catch (Exception e) {
            log.warn("角色视角 RAG 检索异常: bookId={}, role={} - {}", book.getId(), role.getKey(), e.getMessage());
            return "";
        }
    }

    /**
     * 生成角色专属的检索子查询列表
     * <p>
     * - subQueryCount = 1 时走单查询生成（向后兼容）
     * - subQueryCount > 1 时走多子查询生成（视角覆盖更广）
     * - LLM 失败时回退到关键词拼接
     */
    private List<String> buildRoleSubQueries(Book book, AiConfig.RoundTableRole role,
                                              List<RoundTableMessage> history,
                                              int subQueryCount, String perspectiveHint) {
        if ("HOST".equals(role.getKey())) {
            // 主持人走原有逻辑（关键词拼接，不需要 LLM）
            return List.of(buildHostSearchQuery(book, history));
        }

        // 构建最近两轮发言摘要
        String recentDiscussion = "";
        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - 2);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < history.size(); i++) {
                RoundTableMessage msg = history.get(i);
                String name = msg.getRoleName() != null ? msg.getRoleName() : msg.getRoleKey();
                String content = msg.getCompressedContent();
                sb.append(name).append("：").append(content).append("\n");
            }
            recentDiscussion = sb.toString();
        }

        String roleKeywords = getRoleSearchKeywords(role);

        // subQueryCount == 1 时走原单查询逻辑（向后兼容）
        if (subQueryCount <= 1) {
            String query = callAiForRoleSearchQuery(
                    role.getKey(), book.getTitle(),
                    role.getName(), role.getTitle(), roleKeywords,
                    recentDiscussion.isBlank() ? "（讨论尚未开始）" : recentDiscussion);
            if (query != null && !query.isBlank()) {
                query = query.trim()
                        .replaceAll("^(查询|检索|搜索|关键词)[：:]", "")
                        .trim();
                if (!query.isBlank()) return List.of(query);
            }
            // LLM 失败回退
            return List.of(book.getTitle() + " " + roleKeywords);
        }

        // subQueryCount > 1 时走多子查询生成
        try {
            List<String> queries = callAiForRoleSearchQueries(
                    role.getKey(), book.getTitle(),
                    role.getName(), role.getTitle(), roleKeywords,
                    recentDiscussion, subQueryCount, perspectiveHint);
            if (!queries.isEmpty()) return queries;
        } catch (Exception e) {
            log.warn("多子查询生成失败，回退到单查询: role={} - {}", role.getKey(), e.getMessage());
        }

        // 多子查询失败回退到单查询
        String query = callAiForRoleSearchQuery(
                role.getKey(), book.getTitle(),
                role.getName(), role.getTitle(), roleKeywords,
                recentDiscussion.isBlank() ? "（讨论尚未开始）" : recentDiscussion);
        if (query != null && !query.isBlank()) {
            return List.of(query.trim()
                    .replaceAll("^(查询|检索|搜索|关键词)[：:]", "")
                    .trim());
        }

        // 最终回退：书名 + 角色关键词
        return List.of(book.getTitle() + " " + roleKeywords);
    }

    /**
     * HOST 专用检索查询生成——简化版，直接用书名 + 核心概念 + 读者关注构建查询。
     */
    private String buildHostSearchQuery(Book book, List<RoundTableMessage> history) {
        StringBuilder sb = new StringBuilder(book.getTitle());

        String concepts = book.getConceptTags();
        if (concepts != null && !concepts.isBlank()) {
            String cleaned = concepts.replaceAll("[\\[\\]\"]", "").trim();
            if (!cleaned.isEmpty()) {
                List<String> tags = Arrays.stream(cleaned.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .limit(5)
                        .toList();
                if (!tags.isEmpty()) {
                    sb.append(" ").append(String.join(" ", tags));
                }
            }
        }

        String readerNeeds = book.getReaderNeedTags();
        if (readerNeeds != null && !readerNeeds.isBlank()) {
            String cleaned = readerNeeds.replaceAll("[\\[\\]\"]", "").trim();
            if (!cleaned.isEmpty()) {
                List<String> tags = Arrays.stream(cleaned.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .limit(3)
                        .toList();
                if (!tags.isEmpty()) {
                    sb.append(" ").append(String.join(" ", tags));
                }
            }
        }

        sb.append(" 核心观点 主要内容");
        return sb.toString();
    }

    /**
     * 解析 JSON 数组格式的概念标签
     */
    private Set<String> parseConceptTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) return new LinkedHashSet<>();
        return Arrays.stream(tagsJson.replaceAll("[\\[\\]\"]", "").split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * HOST 话题进度注入：在书籍上下文末尾追加「已讨论/未讨论」概念清单。
     * <p>
     * 这样主持人在发言时能直接看到哪些概念还没聊到，而不是靠自己从长篇历史中推断。
     */
    private String appendHostTopicProgress(Book book, List<RoundTableMessage> history) {
        Set<String> allConcepts = parseConceptTags(book.getConceptTags());
        if (allConcepts.isEmpty()) return "";

        // 仅看最近 10 轮发言，避免遍历全量历史
        Set<String> discussed = new LinkedHashSet<>();
        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - 10);
            StringBuilder recentText = new StringBuilder();
            for (int i = start; i < history.size(); i++) {
                RoundTableMessage msg = history.get(i);
                String content = msg.getCompressedContent() != null && !msg.getCompressedContent().isBlank()
                        ? msg.getCompressedContent() : msg.getContent();
                if (content != null) recentText.append(content);
            }
            String text = recentText.toString();
            for (String concept : allConcepts) {
                if (text.contains(concept)) discussed.add(concept);
            }
        }

        Set<String> undiscussed = new LinkedHashSet<>(allConcepts);
        undiscussed.removeAll(discussed);

        StringBuilder sb = new StringBuilder();
        sb.append("\n【话题进度——主持人专用】\n");

        if (!undiscussed.isEmpty()) {
            sb.append("尚未讨论的概念：").append(String.join("、", undiscussed)).append("\n");
            sb.append("请在本次发言中至少引入一个上述概念，引导嘉宾讨论新话题。\n");
        }
        if (!discussed.isEmpty()) {
            sb.append("已讨论的概念：").append(String.join("、", discussed)).append("\n");
        }
        if (undiscussed.isEmpty()) {
            sb.append("所有概念均已讨论。请换一个全新的切入角度，或引导嘉宾做更深层的交叉关联。\n");
        }

        return sb.toString();
    }

    /**
     * 获取角色的专业检索关键词
     * 不同角色在搜索书中内容时，关注的关键词不同
     */
    private String getRoleSearchKeywords(AiConfig.RoundTableRole role) {
        if (role.getSearchKeywords() != null && !role.getSearchKeywords().isEmpty()) {
            return String.join(" ", role.getSearchKeywords());
        }
        return role.getName();
    }

    /**
     * 等待图书内容向量就绪
     */
    private boolean waitForContentEmbedding(Long bookId) {
        Book book = bookService.getBookById(bookId);
        if (book != null && Boolean.TRUE.equals(book.getContentEmbedded())) {
            return true;
        }

        int maxRetries = 30;
        for (int i = 0; i < maxRetries; i++) {
            Boolean result = bookParserService.ensureContentEmbedded(bookId);
            if (result != null) {
                return result;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ==================== 标签匹配回退逻辑 ====================

    /**
     * 解析书籍标签 JSON 数组
     */
    private List<String> parseTags(String formatTags) {
        if (formatTags == null || formatTags.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(formatTags,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    });
        } catch (Exception e) {
            return Arrays.stream(formatTags.replaceAll("[\\[\\]\"]", "").split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toList());
        }
    }

    /**
     * 根据标签选择推荐角色（回退方案）
     */
    private List<AiConfig.RoundTableRole> selectRolesByTags(List<String> tags) {
        List<AiConfig.RoundTableRole> result = new ArrayList<>();

        Set<AiConfig.RoundTableRole> candidates = new LinkedHashSet<>();
        String allTags = tags.stream().map(String::toLowerCase).collect(Collectors.joining(" "));

        for (AiConfig.RoundTableRole role : aiConfigProvider.getRoundTableRoles()) {
            if (role.getTags() != null) {
                for (String tag : role.getTags()) {
                    if (allTags.contains(tag.toLowerCase())) {
                        candidates.add(role);
                        break;
                    }
                }
            }
        }

        if (candidates.size() < 3) {
            for (String defaultKey : List.of("PHILOSOPHER", "PSYCHOLOGIST", "SOCIOLOGIST", "EDUCATOR", "COMEDIAN")) {
                if (candidates.size() >= 3) break;
                AiConfig.RoundTableRole r = aiConfigProvider.getRoundTableRole(defaultKey);
                if (r != null) candidates.add(r);
            }
        }

        candidates.stream().limit(5).forEach(result::add);
        return result;
    }

}
