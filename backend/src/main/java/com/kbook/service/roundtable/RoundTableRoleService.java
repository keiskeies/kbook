package com.kbook.service.roundtable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.exception.BusinessException;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SpeechSanitizer;
import com.kbook.common.util.SseHelper;
import com.kbook.config.ai.AiConfig;
import com.kbook.config.ai.AiConfigProvider;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.config.annotation.RedisLock;
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
import com.kbook.service.ai.rag.RagPipelineComponents;
import com.kbook.service.ai.core.ChatModelManager;
import com.kbook.service.ai.core.AiProviderConfigService;
import com.kbook.service.ai.core.AiSceneConfigService;
import com.kbook.service.ai.core.AiUsageContext;
import com.kbook.constants.ai.RoundTablePrompts;

/**
 * 圆桌派角色推荐服务 — LLM 选角 / ZSet 使用次数缓存 / 角色名册 / 标签回退。
 */
@Slf4j
@Service
@LogModule("圆桌派")
public class RoundTableRoleService {

    private final BookService bookService;
    private final ChatModelManager chatModelManager;
    private final AiConfigProvider aiConfigProvider;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RoundTableRoleService(
            BookService bookService,
            ChatModelManager chatModelManager,
            AiConfigProvider aiConfigProvider,
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper) {
        this.bookService = bookService;
        this.chatModelManager = chatModelManager;
        this.aiConfigProvider = aiConfigProvider;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
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
        String systemPrompt = String.format(RoundTablePrompts.ROUND_TABLE_ROLE_SELECTION_SYSTEM_PROMPT_TEMPLATE, excludeClause, roleList);
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
                        SystemMessage.from(RoundTablePrompts.ROLE_SELECTION_FALLBACK_SYSTEM_PROMPT),
                        UserMessage.from("角色列表：" + roleList + "\n\n书籍信息：\n" + bookInfo)));
    }

    /**
     * 为特定角色生成向量检索查询文本。
     */
    public String callAiForRoleSearchQuery(String roleKey, String bookTitle, String roleName,
                                           String roleTitle, String roleKeywords, String recentDiscussion) {
        String systemPrompt = RoundTablePrompts.ROLE_SEARCH_QUERY_SYSTEM_PROMPT;

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
                RoundTablePrompts.ROLE_SEARCH_QUERIES_SYSTEM_PROMPT_TEMPLATE,
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
     * 角色使用次数 ZSet 前缀 — key = {prefix}{bookId}, member = roleKey, score = 使用次数
     */
    static final String ROLE_SCORES_KEY_PREFIX = "kbook:round-table:role-scores:";

    /**
     * 角色名册 Hash 前缀 — key = {prefix}{bookId}, field = roleKey, value = 该角色名册 JSON
     * <p>
     * 记录 LLM 为本次讨论赋值的 name/title/domainRelevance/languageStyle，
     * 供 ZSet 命中路径与会话创建时复用（ZSet 只存 key，会丢失这些富字段）。
     */
    private static final String ROLE_ROSTER_KEY_PREFIX = "kbook:round-table:role-roster:";

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
            List<RoleVO> zsetRoles = getTopSelectedRolesFromZSet(bookId, scoreKey);
            if (zsetRoles.size() >= 4) {
                log.debug("ZSet 角色推荐命中: bookId={}, roles={}", bookId, zsetRoles.stream().map(RoleVO::getKey).toList());
                return buildRoleListFromSelected(zsetRoles);
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
                    // 缓存 LLM 赋值的名册（domainRelevance/languageStyle），供 ZSet 命中路径与会话创建复用
                    cacheRoleRoster(bookId, llmSelectedRoles);
                    // 记录 LLM 选择到 ZSet
                    for (RoleVO vo : llmSelectedRoles) {
                        stringRedisTemplate.opsForZSet().incrementScore(scoreKey, vo.getKey(), 1);
                    }
                    return buildRoleListFromSelected(llmSelectedRoles);
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
     * <p>
     * ZSet 只存 roleKey，domainRelevance/languageStyle 需从角色名册 Hash 回填，
     * 否则每次 ZSet 命中都会退化成配置默认值（0/空），丢失首轮 LLM 赋值的富字段。
     */
    private List<RoleVO> getTopSelectedRolesFromZSet(Long bookId, String scoreKey) {
        // reverseRangeByScore: score 从 0 开始，返回最多 8 个（含 HOST 则过滤）
        Set<String> topKeys = stringRedisTemplate.opsForZSet()
                .reverseRangeByScore(scoreKey, 0, Double.MAX_VALUE, 0, 8);
        if (topKeys == null || topKeys.isEmpty()) return List.of();

        Map<String, JsonNode> roster = readRoleRoster(bookId);
        List<RoleVO> result = new ArrayList<>();
        for (String key : topKeys) {
            if ("HOST".equals(key)) continue;
            AiConfig.RoundTableRole role = aiConfigProvider.getRoundTableRole(key);
            if (role != null) {
                RoleVO vo = RoleVO.fromConfig(role);
                applyRosterEntry(vo, roster.get(key));
                vo.setSelected(true);
                result.add(vo);
            }
            if (result.size() >= 6) break;
        }
        return result;
    }

    /**
     * 缓存 LLM 赋值的角色名册（Hash：field=roleKey，value=名册 JSON）。
     * <p>
     * 刷新时同 field 覆盖写入；回退路径不写，避免用配置默认值覆盖已有的 LLM 赋值。
     */
    private void cacheRoleRoster(Long bookId, List<RoleVO> roles) {
        if (bookId == null || roles == null || roles.isEmpty()) return;
        try {
            String key = ROLE_ROSTER_KEY_PREFIX + bookId;
            for (RoleVO vo : roles) {
                if (vo == null || vo.getKey() == null || vo.getKey().isBlank()) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("key", vo.getKey());
                entry.put("name", vo.getName());
                entry.put("title", vo.getTitle());
                entry.put("domainRelevance", vo.getDomainRelevance());
                entry.put("languageStyle", vo.getLanguageStyle());
                stringRedisTemplate.opsForHash().put(key, vo.getKey(), objectMapper.writeValueAsString(entry));
            }
        } catch (Exception e) {
            log.debug("缓存角色名册失败: bookId={} - {}", bookId, e.getMessage());
        }
    }

    /**
     * 读取某本书的角色名册（key → 名册 JSON 节点），读取失败返回空表。
     */
    Map<String, JsonNode> readRoleRoster(Long bookId) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        if (bookId == null) return result;
        try {
            Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(ROLE_ROSTER_KEY_PREFIX + bookId);
            for (Map.Entry<Object, Object> e : entries.entrySet()) {
                String key = String.valueOf(e.getKey());
                try {
                    result.put(key, objectMapper.readTree(String.valueOf(e.getValue())));
                } catch (Exception ignored) {
                    // 单个 field 损坏不影响其余角色
                }
            }
        } catch (Exception e) {
            log.debug("读取角色名册失败: bookId={} - {}", bookId, e.getMessage());
        }
        return result;
    }

    /**
     * 用名册条目回填 RoleVO 的富字段（name/title/domainRelevance/languageStyle）。
     */
    private void applyRosterEntry(RoleVO vo, JsonNode entry) {
        if (vo == null || entry == null) return;
        if (entry.hasNonNull("name") && !entry.get("name").asText().isBlank()) {
            vo.setName(entry.get("name").asText());
        }
        if (entry.hasNonNull("title") && !entry.get("title").asText().isBlank()) {
            vo.setTitle(entry.get("title").asText());
        }
        if (entry.has("domainRelevance")) {
            vo.setDomainRelevance(entry.get("domainRelevance").asInt());
        }
        if (entry.hasNonNull("languageStyle")) {
            vo.setLanguageStyle(entry.get("languageStyle").asText());
        }
    }

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
                    return buildRoleListFromSelected(llmRoles.stream()
                            .map(RoleVO::fromConfig).collect(Collectors.toList()));
                }
            }
        } catch (Exception e) {
            log.warn("回退 LLM 角色推荐失败，使用标签匹配: bookId={} - {}", book.getId(), e.getMessage());
        }

        // 最终回退：标签硬匹配
        List<String> tags = parseTags(book.getFormatTags());
        List<AiConfig.RoundTableRole> matchedRoles = selectRolesByTags(tags);
        return buildRoleListFromSelected(matchedRoles.stream()
                .map(RoleVO::fromConfig).collect(Collectors.toList()));
    }

    /**
     * 从已选角色列表构建 20 人名单（HOST 始终包含且选中）
     * <p>
     * 入参为 RoleVO（携带 LLM 赋值的 domainRelevance/languageStyle），
     * 未选中的剩余角色用配置默认值补齐。
     */
    private List<RoleVO> buildRoleListFromSelected(List<RoleVO> selectedRoles) {
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

        for (RoleVO vo : selectedRoles) {
            if (vo == null || vo.getKey() == null || "HOST".equals(vo.getKey()) || addedKeys.contains(vo.getKey())) continue;
            vo.setSelected(true);
            result.add(vo);
            addedKeys.add(vo.getKey());
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

    // ==================== 标签匹配回退逻辑 ====================

    /**
     * 解析书籍标签 JSON 数组
     */
    List<String> parseTags(String formatTags) {
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
