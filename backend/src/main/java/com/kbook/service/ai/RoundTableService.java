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
import com.kbook.dto.roundtable.RoleVO;
import com.kbook.dto.roundtable.SpeakRequest;
import com.kbook.entity.Book;
import com.kbook.entity.RoundTableMessage;
import com.kbook.entity.RoundTableSession;
import com.kbook.enums.RoundTableRole;
import com.kbook.repository.RoundTableMessageRepository;
import com.kbook.repository.RoundTableSessionRepository;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.kbook.common.util.QueryBuilder.eq;

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
    private final AiProviderConfigService aiProviderConfigService;
    private final RoundTableSessionRepository sessionRepository;
    private final RoundTableMessageRepository messageRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final RoundTableCoverageService coverageService;
    private final ReadingProgressService readingProgressService;
    private final AiConfigProvider aiConfigProvider;
    private final ExecutorService sseExecutor;

    public RoundTableService(
            EmbeddingService embeddingService,
            BookService bookService,
            BookParserService bookParserService,
            ChatModelManager chatModelManager,
            AiProviderConfigService aiProviderConfigService,
            RoundTableSessionRepository sessionRepository,
            RoundTableMessageRepository messageRepository,
            StringRedisTemplate stringRedisTemplate,
            RoundTableCoverageService coverageService,
            ReadingProgressService readingProgressService,
            AiConfigProvider aiConfigProvider,
            @Qualifier("sseExecutor") ExecutorService sseExecutor) {
        this.embeddingService = embeddingService;
        this.bookService = bookService;
        this.bookParserService = bookParserService;
        this.chatModelManager = chatModelManager;
        this.aiProviderConfigService = aiProviderConfigService;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.stringRedisTemplate = stringRedisTemplate;
        this.coverageService = coverageService;
        this.readingProgressService = readingProgressService;
        this.aiConfigProvider = aiConfigProvider;
        this.sseExecutor = sseExecutor;
    }

    /**
     * JSON 序列化
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

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
     * 压缩目标：历史占比降到该比例以下停止
     */
    private static final double COMPRESS_TARGET_RATIO = 0.6;

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
                List<RoundTableRole> selectedEnums = zsetRoles.stream()
                        .map(vo -> RoundTableRole.fromKey(vo.getKey()))
                        .filter(Objects::nonNull)
                        .toList();
                log.debug("ZSet 角色推荐命中: bookId={}, roles={}", bookId, zsetRoles.stream().map(RoleVO::getKey).toList());
                return buildRoleListFromSelected(selectedEnums);
            }
        }

        // 2. 冷启动 / 强制刷新 → LLM
        try {
            String bookInfo = buildBookInfoForRoleSelection(book);
            String prompt = String.format(AiPromptConstants.ROUND_TABLE_ROLE_SELECTION_PROMPT, bookInfo);

            String result = chatModelManager.callAi(
                    "圆桌派角色推荐",
                    String.format("bookId=%d, title=%s", bookId, book.getTitle()),
                    prompt);

            if (result != null && !result.isBlank()) {
                result = CommonUtils.stripCodeFence(result);
                List<RoleVO> llmSelectedRoles = parseLlmRoleSelection(result);
                if (llmSelectedRoles != null && llmSelectedRoles.size() >= 3) {
                    // 记录 LLM 选择到 ZSet
                    for (RoleVO vo : llmSelectedRoles) {
                        stringRedisTemplate.opsForZSet().incrementScore(scoreKey, vo.getKey(), 1);
                    }
                    List<RoundTableRole> selectedEnums = llmSelectedRoles.stream()
                            .map(vo -> RoundTableRole.fromKey(vo.getKey()))
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
            RoundTableRole role = RoundTableRole.fromKey(key);
            if (role != null) {
                RoleVO vo = RoleVO.from(role);
                vo.setSelected(true);
                result.add(vo);
            }
            if (result.size() >= 6) break;
        }
        return result;
    }

    // buildRoleListWithSelection 已合并到 buildRoleListFromSelected

    /**
     * 解析 LLM 返回的角色选择 JSON
     */
    private List<RoleVO> parseLlmRoleSelection(String json) {
        try {
            var nodes = objectMapper.readTree(json);
            if (!nodes.isArray()) return null;

            List<RoleVO> roles = new ArrayList<>();
            for (var node : nodes) {
                String key = node.has("key") ? node.get("key").asText() : null;
                int domainRelevance = node.has("domainRelevance") ? node.get("domainRelevance").asInt() : 5;

                if (key == null) continue;
                RoundTableRole role = RoundTableRole.fromKey(key);
                if (role == null || role == RoundTableRole.HOST) continue; // HOST 单独添加

                RoleVO vo = RoleVO.from(role);
                vo.setDomainRelevance(domainRelevance);
                String languageStyle = node.has("languageStyle") ? node.get("languageStyle").asText() : "";
                vo.setLanguageStyle(languageStyle);
                roles.add(vo);

                if (roles.size() >= 6) break; // 最多 6 个非主持人角色
            }
            return roles;
        } catch (Exception e) {
            log.warn("解析 LLM 角色选择结果失败: {}", e.getMessage());
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
            String desc = book.getDescription().length() > 500
                    ? book.getDescription().substring(0, 500) + "..."
                    : book.getDescription();
            sb.append("简介：").append(desc).append("\n");
        }
        String summary = bookService.resolveBookSummary(book);
        if (summary != null && !summary.isBlank()) {
            sb.append("摘要：").append(CommonUtils.truncateText(summary, 2000)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 回退角色推荐 — 先尝试轻量 LLM 调用，失败再用标签硬匹配
     */
    private List<RoleVO> getFallbackRolesByTags(Book book) {
        // 尝试轻量 LLM 调用（比主流程 prompt 更简单，容忍度更高）
        try {
            String bookInfo = buildBookInfoForRoleSelection(book);
            String roleList = Arrays.stream(RoundTableRole.values())
                    .map(r -> r.getKey() + "(" + r.getName() + ")")
                    .collect(Collectors.joining(", "));
            String prompt = """
                    根据以下书籍信息，从角色列表中选出最适合参与讨论的 4-6 个角色（不含 HOST）。
                    只输出角色 key，用逗号分隔，不要输出任何解释。
                    
                    角色列表：%s
                    
                    书籍信息：
                    %s
                    """.stripIndent().formatted(roleList, bookInfo);

            String result = chatModelManager.callAi(
                    "圆桌派角色推荐(回退)",
                    String.format("bookId=%d, title=%s", book.getId(), book.getTitle()),
                    prompt);

            if (result != null && !result.isBlank()) {
                result = CommonUtils.stripCodeFence(result);
                List<String> keys = Arrays.stream(result.split("[,，\\s]+"))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .map(String::toUpperCase)
                        .distinct()
                        .toList();
                List<RoundTableRole> llmRoles = keys.stream()
                        .map(RoundTableRole::fromKey)
                        .filter(Objects::nonNull)
                        .filter(r -> r != RoundTableRole.HOST)
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
        List<RoundTableRole> matchedRoles = selectRolesByTags(tags);
        return buildRoleListFromSelected(matchedRoles);
    }

    /**
     * 从已选角色列表构建 20 人名单（HOST 始终包含且选中）
     */
    private List<RoleVO> buildRoleListFromSelected(List<RoundTableRole> selectedRoles) {
        List<RoleVO> result = new ArrayList<>();
        Set<String> addedKeys = new HashSet<>();

        // HOST 始终选中
        RoleVO hostVo = RoleVO.from(RoundTableRole.HOST);
        hostVo.setSelected(true);
        result.add(hostVo);
        addedKeys.add("HOST");

        for (RoundTableRole role : selectedRoles) {
            if (role == RoundTableRole.HOST || addedKeys.contains(role.getKey())) continue;
            RoleVO vo = RoleVO.from(role);
            vo.setSelected(true);
            result.add(vo);
            addedKeys.add(role.getKey());
        }

        List<RoundTableRole> remaining = Arrays.stream(RoundTableRole.values())
                .filter(r -> !addedKeys.contains(r.getKey()))
                .collect(Collectors.toList());
        Collections.shuffle(remaining);
        for (RoundTableRole role : remaining) {
            if (result.size() >= 20) break;
            RoleVO vo = RoleVO.from(role);
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
            AiConfig.RoundTableRole configRole = aiConfigProvider.getRoundTableRole(key);
            if (configRole != null) {
                RoleVO vo = RoleVO.fromConfig(configRole);
                vo.setSelected(true);
                result.add(vo);
                addedKeys.add(key);
            } else {
                // 配置中找不到则回退到枚举
                RoundTableRole enumRole = RoundTableRole.fromKey(key);
                if (enumRole != null) {
                    RoleVO vo = RoleVO.from(enumRole);
                    vo.setSelected(true);
                    result.add(vo);
                    addedKeys.add(key);
                }
            }
        }

        // 补充剩余角色到上限
        List<RoundTableRole> remainingRoles = Arrays.stream(RoundTableRole.values())
                .filter(r -> !addedKeys.contains(r.getKey()))
                .collect(Collectors.toList());
        Collections.shuffle(remainingRoles);
        for (RoundTableRole role : remainingRoles) {
            if (result.size() >= maxRoles) break;
            // 优先从配置取颜色等数据
            AiConfig.RoundTableRole configRole = aiConfigProvider.getRoundTableRole(role.getKey());
            RoleVO vo = configRole != null ? RoleVO.fromConfig(configRole) : RoleVO.from(role);
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
     * 验证会话归属当前用户，不属于则抛出 BusinessException
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     */
    public void verifySessionOwnership(Long userId, String sessionId) {
        RoundTableSession session = sessionRepository.query()
                .where(RoundTableSession::getSessionId, eq(sessionId))
                .list(1)
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException("会话不存在"));
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
                .where(RoundTableMessage::getUserId, eq(userId))
                .and(RoundTableMessage::getSessionId, eq(sessionId))
                .list();
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
     * 纯 LLM 判断下一轮发言人
     * <p>
     * 核心设计：
     * 1. 给 LLM 完整的角色信息（含性格参数、专业领域、说话风格）
     * 2. 给加权对话历史——越靠后的发言权重越高，让 LLM 能感知对话流向
     * 3. 给公平性约束——禁止连续发言、鼓励沉默者、主持人控场
     * 4. LLM 直接返回一个角色 key，不再经过算法二次调整
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return 选中的角色 key
      */
    @LogAction("纯LLM判断下一发言人")
    @RedisLock(key = "'rt:speaker:' + #sessionId", leaseTime = 30)
    public String getNextSpeakerOnlyLLM(Long userId, String sessionId) {
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
            return "HOST";
        }

        // 4. 统计发言数据
        String[] roleKeys = session.getRoleKeys().split(",");
        Map<String, Long> speakCounts = allMessages.stream()
                .collect(Collectors.groupingBy(RoundTableMessage::getRoleKey, Collectors.counting()));
        String lastSpeaker = allMessages.get(allMessages.size() - 1).getRoleKey();

        // [消息3 - UserMessage] 讨论历史（每次只追加一条新发言，前缀可大量复用 KV-cache）
        String weightedHistory = buildWeightedHistoryForLLM(allMessages);
        messages.add(UserMessage.from(weightedHistory));

        // [消息4 - UserMessage] 公平性约束 + 输出格式（每轮完全变化，放最后不破坏前缀缓存）
        String fairnessConstraints = buildFairnessConstraints(roleKeys, speakCounts, allMessages);
        messages.add(UserMessage.from(fairnessConstraints
                + "\n\n只返回JSON：{\"nextSpeaker\": \"角色KEY\"}"));

        // 5. 调用 LLM
        try {
            String response = chatModelManager.callAi(
                    "圆桌派纯LLM发言人选择",
                    String.format("sessionId=%s", sessionId),
                    messages);
            response = CommonUtils.stripCodeFence(response).trim();

            String selectedKey = parseLlmSpeakerResponse(response, roleKeys);

            if (!allMessages.isEmpty() && selectedKey.equals(lastSpeaker)) {
                throw new BusinessException("连续发言者禁止");
            }

            log.info("纯LLM选择发言人: sessionId={}, selected={}, speakCounts={}",
                    sessionId, selectedKey, speakCounts);

            return selectedKey;
        } catch (Exception e) {
            log.warn("纯LLM发言人选择失败，回退到简单模式: {}", e.getMessage());
            return Arrays.stream(roleKeys)
                    .map(String::trim)
                    .filter(k -> !k.equals(lastSpeaker))
                    .min(Comparator.comparingLong(k -> speakCounts.getOrDefault(k, 0L)))
                    .orElse(roleKeys[0].trim());
        }
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
     * 未在 roleConfigs 中找到的角色使用 RoundTableRole 枚举默认值。
     * <p>
     * 包含角色完整人设描述（从 RoundTableRole 枚举读取），
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

            RoundTableRole role = RoundTableRole.fromKey(key);
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
            // 完整角色人设：身份视角 + 语言指纹 + 社交直觉与张力（天然警惕/天然共鸣/接话直觉） + 输出铁律
            // 注意：社交直觉与张力 是 LLM 判断"谁适合接话"的核心依据
            sb.append(role.getPrompt()).append("\n");
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
     * 构建加权对话历史（用于纯 LLM 发言人选择）
     * <p>
     * 越靠后的发言权重越高，让 LLM 能感知对话流向：
     * - 最近1条：标记为「当前焦点」，权重最高
     * - 最近2-3条：标记为「近期讨论」，权重次高
     * - 更早的：标记为「背景讨论」，权重较低
     * <p>
     * 直接使用 compressedContent（消息保存时已同步写入），不做二次截断。
     */
    private String buildWeightedHistoryForLLM(List<RoundTableMessage> allMessages) {
        if (allMessages.isEmpty()) {
            return "（暂无发言记录，这是讨论的开始）";
        }

        StringBuilder sb = new StringBuilder();
        int total = allMessages.size();

        for (int i = 0; i < total; i++) {
            RoundTableMessage msg = allMessages.get(i);
            String content = msg.getCompressedContent();
            if (content == null || content.isBlank()) {
                content = msg.getContent();
            }

            // 根据位置标记权重
            String weightLabel;
            if (i == total - 1) {
                weightLabel = "【当前焦点】";
            } else if (i >= total - 3) {
                weightLabel = "【近期讨论】";
            } else {
                weightLabel = "【背景讨论】";
            }

            sb.append(weightLabel).append(msg.getRoleName()).append("：").append(content).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 构建公平性约束说明（用于纯 LLM 发言人选择）
     */
    private String buildFairnessConstraints(String[] roleKeys, Map<String, Long> speakCounts,
                                            List<RoundTableMessage> allMessages) {
        StringBuilder sb = new StringBuilder();

        // 1. 禁止连续发言
        if (!allMessages.isEmpty()) {
            String lastSpeaker = allMessages.get(allMessages.size() - 1).getRoleKey();
            sb.append("- 绝对禁止：").append(RoundTableRole.fromKey(lastSpeaker).getName())
                    .append("刚发过言，不能连续发言\n");
        }

        // 2. 从未发言的角色（强制优先）
        Set<String> neverSpoken = Arrays.stream(roleKeys)
                .map(String::trim)
                .filter(key -> !speakCounts.containsKey(key))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!neverSpoken.isEmpty()) {
            sb.append("- 【强制优先】以下角色至今一次都没发言，必须在下一轮选其中一位：");
            for (String key : neverSpoken) {
                RoundTableRole role = RoundTableRole.fromKey(key);
                if (role != null) sb.append(role.getName()).append("(").append(key).append(") ");
            }
            sb.append("\n");
        }

        // 3. 发言次数统计（按次数排序）
        sb.append("- 当前发言次数统计（从少到多）：");
        Arrays.stream(roleKeys)
                .map(String::trim)
                .sorted(Comparator.comparingLong(k -> speakCounts.getOrDefault(k, 0L)))
                .forEach(key -> {
                    RoundTableRole role = RoundTableRole.fromKey(key);
                    long count = speakCounts.getOrDefault(key, 0L);
                    sb.append(role != null ? role.getName() : key).append("=").append(count).append(" ");
                });
        sb.append("\n");

        // 4. 主持人定期控场（建议，非强制）
        int nonHostCount = (int) Arrays.stream(roleKeys).filter(k -> !"HOST".equals(k.trim())).count();
        long hostCount = speakCounts.getOrDefault("HOST", 0L);
        int totalRounds = allMessages.size();
        int hostInterval = Math.max(3, nonHostCount);
        int hostMinExpected = Math.max(1, totalRounds / hostInterval);
        if (totalRounds >= nonHostCount && hostCount < hostMinExpected) {
            sb.append("- 【建议主持人控场】主持人发言偏少（仅发言").append(hostCount)
                    .append("次），可考虑选主持人(HOST)来引导新方向\n");
        }

        // 5. 严重发言不足的角色（发言次数差距>2倍）
        if (allMessages.isEmpty() || neverSpoken.isEmpty()) {
            long maxCount = speakCounts.values().stream().mapToLong(v -> v).max().orElse(1);
            List<String> starved = Arrays.stream(roleKeys)
                    .map(String::trim)
                    .filter(key -> {
                        long c = speakCounts.getOrDefault(key, 0L);
                        return c > 0 && c * 3 <= maxCount && maxCount >= 3;
                    })
                    .toList();
            if (!starved.isEmpty()) {
                sb.append("- 【强制优先】以下角色发言严重不足（与最活跃者差距3倍以上）：");
                for (String key : starved) {
                    RoundTableRole role = RoundTableRole.fromKey(key);
                    if (role != null) sb.append(role.getName()).append("(").append(key).append(") ");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 解析 LLM 返回的发言人选择结果。
     * 如果无法解析出有效的在场角色 key，抛出异常让外层回退到简单模式。
     */
    private String parseLlmSpeakerResponse(String response, String[] validRoleKeys) {
        Set<String> validKeys = Arrays.stream(validRoleKeys).map(String::trim).collect(Collectors.toSet());

        // 尝试解析 JSON {"nextSpeaker": "KEY"}
        try {
            var jsonNode = objectMapper.readTree(response);
            if (jsonNode.has("nextSpeaker")) {
                String key = jsonNode.get("nextSpeaker").asText().trim();
                if (validKeys.contains(key)) return key;
                // JSON 解析成功但 key 不合法 → 直接抛，不进入子串匹配
                throw new BusinessException("LLM返回了不在场角色: " + key);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception ignored) {
            // JSON 解析失败，进入子串匹配兜底
        }

        // 尝试直接匹配角色 key
        String upperResponse = response.toUpperCase();
        for (String key : validKeys) {
            if (upperResponse.contains(key)) {
                return key;
            }
        }

        // LLM 返回了数据但不在在场角色中 → 抛异常，让外层回退简单模式
        throw new BusinessException("LLM返回的角色不在在场角色列表中: " + response);
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
        log.info("========== 圆桌派单角色发言 ==========");
        log.info("userId={}, bookId={}, roleKey={}, sessionId={}", userId, bookId, request.getRoleKey(), request.getSessionId());

        SseEmitter emitter = new SseEmitter(3600_000L);

        // 解析角色
        RoundTableRole role = RoundTableRole.fromKey(request.getRoleKey());
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
                    if (role != RoundTableRole.HOST) {
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
                        request.getSessionId(), userId, systemPrompt, bookInfo, ragContent, roleSetting, extraInstructions, role.getName());

                // 构建流式模型（不使用 thinking 模式）
                StreamingChatModel streamingChatModel = chatModelManager.getStreamingChatModel();
                if (streamingChatModel == null) {
                    SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                long startTime = System.currentTimeMillis();
                StringBuilder fullResponse = new StringBuilder();
                // 连接已关闭标志 — SSE 发送失败时立即停止 AI 输出
                final boolean[] connectionClosed = {false};

                streamingChatModel.chat(
                        messages,
                        new StreamingChatResponseHandler() {
                            StreamingHandle streamingHandle;

                            @Override
                            public void onPartialThinking(dev.langchain4j.model.chat.response.PartialThinking partialThinking) {
                                // 不使用 thinking 模式，忽略
                            }

                            @Override
                            public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                                if (streamingHandle == null) {
                                    streamingHandle = context.streamingHandle();
                                }
                                if (connectionClosed[0] || (streamingHandle != null && streamingHandle.isCancelled()))
                                    return;
                                String text = partialResponse.text();
                                if (text == null || text.isEmpty()) return;

                                fullResponse.append(text);

                                try {
                                    String json = objectMapper.writeValueAsString(
                                            Map.of("roleKey", role.getKey(), "text", text));
                                    if (!SseHelper.safeSendEvent(emitter, "message", json)) {
                                        connectionClosed[0] = true;
                                        if (streamingHandle != null) streamingHandle.cancel();
                                        log.warn("SSE 连接已关闭，停止 AI 输出: roleKey={}", role.getKey());
                                    }
                                } catch (Exception e) {
                                    connectionClosed[0] = true;
                                    if (streamingHandle != null) streamingHandle.cancel();
                                    log.debug("发送 message 事件失败，停止 AI 输出: {}", e.getMessage());
                                }
                            }

                            @Override
                            public void onCompleteResponse(ChatResponse completeResponse) {
                                if (connectionClosed[0]) {
                                    log.info("SSE 连接已关闭，跳过完成处理: roleKey={}", role.getKey());
                                    // 仍然保存已输出的内容
                                    String content = fullResponse.toString().trim();
                                    if (!content.isBlank()) {
                                        try {
                                            saveMessage(userId, request.getSessionId(), bookId, role.getKey(), role.getName(), content);
                                        } catch (Exception e) {
                                            log.warn("保存部分消息失败: {}", e.getMessage());
                                        }
                                    }
                                    return;
                                }
                                if (Thread.currentThread().isInterrupted()) return;
                                long elapsed = System.currentTimeMillis() - startTime;

                                log.info("========== 圆桌派单角色发言完成 ==========");
                                log.info("roleKey={}, 耗时: {}ms", role.getKey(), elapsed);
                                log.info("==========================================");

                                // ====== 关键修复：先保存消息到数据库，再发送 SSE done 事件 ======
                                // 这样前端收到 done 后调用 getNextSpeaker 时，消息已在数据库中
                                String content = fullResponse.toString().trim();
                                if (!content.isBlank()) {
                                    saveMessage(userId, request.getSessionId(), bookId, role.getKey(), role.getName(), content);
                                    updateSessionTimestamp(request.getSessionId());

                                    // 同步压缩历史（确保下次查询时已有压缩内容）
                                    try {
                                        compressHistoryIfNeeded(userId, request.getSessionId(), 0);
                                    } catch (Exception e) {
                                        log.warn("压缩圆桌派历史失败: sessionId={} - {}", request.getSessionId(), e.getMessage());
                                    }
                                }

                                // 先发送 done 事件，让前端立即可以请求下一轮
                                try {
                                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                    emitter.complete();
                                } catch (Exception e) {
                                    log.warn("发送 SSE done 事件失败: {}", e.getMessage());
                                }

                                // 异步更新覆盖度（非 HOST 发言后也更新，确保 HOST 下次发言时数据最新）
                                // 放在 done 之后，避免阻塞 SSE 完成
                                if (role != RoundTableRole.HOST && !content.isBlank()) {
                                    try {
                                        coverageService.updateCoverage(request.getSessionId(), false);
                                    } catch (Exception e) {
                                        log.warn("覆盖度更新失败: sessionId={} - {}", request.getSessionId(), e.getMessage());
                                    }
                                }

                                int apiInputTokens = completeResponse.tokenUsage() != null && completeResponse.tokenUsage().inputTokenCount() != null
                                        ? completeResponse.tokenUsage().inputTokenCount() : 0;
                                int apiOutputTokens = completeResponse.tokenUsage() != null && completeResponse.tokenUsage().outputTokenCount() != null
                                        ? completeResponse.tokenUsage().outputTokenCount() : 0;

                                CommonUtils.logAiCall("圆桌派发言", elapsed, apiInputTokens, apiOutputTokens,
                                        String.format("bookId=%d, roleKey=%s", bookId, role.getKey()));
                            }

                            @Override
                            public void onError(Throwable error) {
                                if (streamingHandle != null && streamingHandle.isCancelled()) return;
                                log.error("圆桌派单角色发言异常: bookId={}, roleKey={} - {}", bookId, role.getKey(), error.getMessage(), error);
                                SseHelper.sendErrorAndComplete(emitter, "AI 响应异常: " + SseHelper.extractFriendlyError(error));
                            }
                        }
                );

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
     * 构建单角色系统提示词（含语言风格、性格维度和 domainRelevance）
     */
    private String buildCharacterSystemPrompt(RoundTableRole role, int domainRelevance, String topic, String languageStyle, boolean isOpening) {
        if (role == RoundTableRole.HOST) {
            return AiPromptConstants.ROUND_TABLE_HOST_PROMPT;
        } else {
            return AiPromptConstants.ROUND_TABLE_CHARACTER_PROMPT;
        }
    }

    /**
     * 构建角色设定 UserMessage（包含人设核心、说话方式、风格、性格参数）
     * 此部分每个角色不同，KV 缓存在此处分叉
     */
    private String buildRoleSettingPrompt(RoundTableRole role, int domainRelevance, String languageStyle) {
        // 优先从外部配置获取角色数据（提示词、金句）
        AiConfig.RoundTableRole configRole = aiConfigProvider.getRoundTableRole(role.getKey());

        if (role == RoundTableRole.HOST) {
            String hostStyle = (languageStyle != null && !languageStyle.isBlank()) ? languageStyle : "沉稳大方，善于引导和总结";
            return String.format(AiPromptConstants.ROUND_TABLE_ROLE_SETTING_HOST,
                    hostStyle,
                    role.getChallenge(), describeChallenge(role.getChallenge()),
                    role.getEmpathy(), describeEmpathy(role.getEmpathy()),
                    role.getOpinionated(), describeOpinionated(role.getOpinionated()),
                    role.getVerbosity(), describeVerbosity(role.getVerbosity()));
        } else {
            String charStyle = (languageStyle != null && !languageStyle.isBlank()) ? languageStyle : "自然流畅，符合你的专业身份";

            // 优先从配置获取提示词和金句，配置不存在则回退到枚举
            String rolePrompt = (configRole != null) ? configRole.getPrompt() : role.getPrompt();
            String catchphrase = (configRole != null) ? configRole.getCatchphrase() :
                    (role.getCatchphrase() != null ? role.getCatchphrase() : "用你自己的方式表达，保持自然");

            return String.format(AiPromptConstants.ROUND_TABLE_ROLE_SETTING_GUEST,
                    rolePrompt,
                    catchphrase,
                    charStyle,
                    role.getChallenge(), describeChallenge(role.getChallenge()),
                    role.getEmpathy(), describeEmpathy(role.getEmpathy()),
                    role.getOpinionated(), describeOpinionated(role.getOpinionated()),
                    role.getVerbosity(), describeVerbosity(role.getVerbosity()),
                    role.getHumor(), describeHumor(role.getHumor()),
                    domainRelevance, describeDomainRelevance(domainRelevance));
        }
    }

    /**
     * 构建额外指令（话题方向 / 开场引导 / 覆盖度），作为发言指令的前缀
     */
    private String buildExtraInstructions(RoundTableRole role, String topic, boolean isOpening) {
        if (role == RoundTableRole.HOST) {
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
    private int resolveDomainRelevance(RoundTableRole role, String roleConfigs) {
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
    private String resolveLanguageStyle(RoundTableRole role, String roleConfigs) {
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
                                                String roleSetting, String extraInstructions, String roleName) {
        List<ChatMessage> messages = new ArrayList<>();

        // 1. 系统提示词（仅共享规则）
        messages.add(SystemMessage.from(systemPrompt));

        // 2. 静态书籍基础信息（跨会话共享 KV Cache 前缀）
        if (bookInfo != null && !bookInfo.isBlank()) {
            messages.add(UserMessage.from("【书籍信息】\n" + bookInfo));
        }

        // 发言指令
        String speakInstruction;
        if ("HOST".equals(roleName) || "主持人".equals(roleName)) {
            speakInstruction = "请以主持人的身份发言。直接说你的观点或抛出问题，绝对不要以「刚才大家...」「前面几位...」「听了各位...」开头。";
        } else {
            speakInstruction = "请以" + roleName + "的身份发言。直接接话，绝对不要以「刚才大家...」「前面几位...」「听了各位...」开头，直接说你的观点。";
        }

        // 加载历史消息并同步压缩，确保 LLM 上下文不超限
        int currentOverhead = systemPrompt.length()
                + (bookInfo != null ? bookInfo.length() : 0)
                + (ragContent != null ? ragContent.length() : 0)
                + (roleSetting != null ? roleSetting.length() : 0)
                + (extraInstructions != null ? extraInstructions.length() : 0)
                + speakInstruction.length()
                + 2000;
        try {
            List<RoundTableMessage> history = loadAndCompressHistory(userId, sessionId, currentOverhead);
            if (!history.isEmpty()) {
                // 找到当前角色最近一条发言的索引（保留一条锚点，避免完全失去自我上下文）
                int lastOwnIndex = -1;
                for (int i = history.size() - 1; i >= 0; i--) {
                    if (roleName.equals(history.get(i).getRoleName())) {
                        lastOwnIndex = i;
                        break;
                    }
                }

                StringBuilder historyBuilder = new StringBuilder("【之前的讨论内容】\n");
                int skippedOwn = 0;
                for (int i = 0; i < history.size(); i++) {
                    RoundTableMessage msg = history.get(i);
                    if (roleName.equals(msg.getRoleName()) && i != lastOwnIndex) {
                        skippedOwn++;
                        continue;
                    }
                    String content = msg.getCompressedContent() != null && !msg.getCompressedContent().isBlank()
                            ? msg.getCompressedContent()
                            : msg.getContent();
                    if (content != null && !content.isBlank()) {
                        historyBuilder.append(msg.getRoleName()).append("：").append(content).append("\n\n");
                    }
                }
                if (skippedOwn > 0) {
                    log.debug("圆桌派历史过滤：跳过当前角色 {} 的 {} 条旧发言", roleName, skippedOwn);
                }

                // 3. 历史对话
                messages.add(UserMessage.from(historyBuilder.toString()));

                // 4. 角色设定（每个角色不同 — 角色内稳定，放在 RAG 前以复用缓存）
                if (roleSetting != null && !roleSetting.isBlank()) {
                    messages.add(UserMessage.from(roleSetting));
                }

                // 5. RAG 内容或覆盖度引导（每次变化，放在最后以最小化缓存失效）
                if (ragContent != null && !ragContent.isBlank()) {
                    messages.add(UserMessage.from("【书籍参考内容】\n" + ragContent));
                }

                // 6. 发言指令（含额外指令）
                StringBuilder finalInstruction = new StringBuilder();
                if (extraInstructions != null && !extraInstructions.isBlank()) {
                    finalInstruction.append(extraInstructions).append("\n\n");
                }
                finalInstruction.append(speakInstruction);
                messages.add(UserMessage.from(finalInstruction.toString()));

                log.debug("加载圆桌派历史: sessionId={}, totalRecords={}", sessionId, history.size());
            } else {
                // 无历史：书籍信息 → 角色设定 → RAG → 发言指令
                if (roleSetting != null && !roleSetting.isBlank()) {
                    messages.add(UserMessage.from(roleSetting));
                }
                if (ragContent != null && !ragContent.isBlank()) {
                    messages.add(UserMessage.from("【书籍参考内容】\n" + ragContent));
                }
                StringBuilder finalInstruction = new StringBuilder();
                if (extraInstructions != null && !extraInstructions.isBlank()) {
                    finalInstruction.append(extraInstructions).append("\n\n");
                }
                finalInstruction.append(speakInstruction);
                messages.add(UserMessage.from(finalInstruction.toString()));
            }
        } catch (Exception e) {
            log.warn("加载圆桌派历史失败，继续无历史对话: {}", e.getMessage());
            if (roleSetting != null && !roleSetting.isBlank()) {
                messages.add(UserMessage.from(roleSetting));
            }
            if (ragContent != null && !ragContent.isBlank()) {
                messages.add(UserMessage.from("【书籍参考内容】\n" + ragContent));
            }
            StringBuilder finalInstruction = new StringBuilder();
            if (extraInstructions != null && !extraInstructions.isBlank()) {
                finalInstruction.append(extraInstructions).append("\n\n");
            }
            finalInstruction.append(speakInstruction);
            messages.add(UserMessage.from(finalInstruction.toString()));
        }

        return messages;
    }

    // ==================== 消息持久化 ====================

    /**
     * 保存角色发言消息
     */
    private void saveMessage(Long userId, String sessionId, Long bookId,
                             String roleKey, String roleName, String content) {
        try {
            // 计算当前轮次
            List<RoundTableMessage> existing = messageRepository.query()
                    .where(RoundTableMessage::getSessionId, eq(sessionId))
                    .orderBy(RoundTableMessage::getId)
                    .list();
            int round = 1;
            if (!existing.isEmpty()) {
                Integer lastRound = existing.get(existing.size() - 1).getRound();
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

        // 3. 计算阈值
        Integer maxTokens = aiProviderConfigService.getActiveMaxTokens();
        int tokenLimit = maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS;
        int charLimit = (int) (tokenLimit * TOKEN_TO_CHAR_RATIO);
        long compressTarget = (long) (charLimit * COMPRESS_TARGET_RATIO) - currentOverheadChars;

        // 4. 未达触发阈值，直接返回
        if (totalChars < charLimit * COMPRESS_TRIGGER_RATIO) {
            return messages;
        }

        // 5. 从内存中找最老的未压缩消息并压缩（避免逐条 DB 查询）
        int compressed = 0;
        while (totalChars >= Math.max(compressTarget, 0)) {
            RoundTableMessage target = findFirstUncompressedInMemory(messages);
            if (target == null) {
                log.info("无可压缩的圆桌派消息: sessionId={}, compressed={}", sessionId, compressed);
                break;
            }

            String original = target.getContent();
            String summary = chatModelManager.compressContent(original);
            if (summary == null) {
                log.warn("压缩失败(跳过): sessionId={}, msgId={}", sessionId, target.getId());
                break;
            }

            target.setCompressedContent(summary);
            messageRepository.save(target);
            totalChars = totalChars - original.length() + summary.length();
            compressed++;
            log.info("压缩圆桌派消息: sessionId={}, msgId={}, {}→{} chars, totalChars={}",
                    sessionId, target.getId(), original.length(), summary.length(), totalChars);
        }

        if (compressed > 0) {
            log.info("圆桌派压缩完成: sessionId={}, compressed={}条", sessionId, compressed);
        }

        return messages;
    }

    /**
     * 从内存列表中查找第一条未压缩的消息。
     * 未压缩判定：compressedContent 为 null，或与 content 完全相同（创建时初始化相等）。
     */
    private RoundTableMessage findFirstUncompressedInMemory(List<RoundTableMessage> messages) {
        for (RoundTableMessage msg : messages) {
            String compressed = msg.getCompressedContent();
            String original = msg.getContent();
            if (compressed == null || compressed.equals(original)) {
                return msg;
            }
        }
        return null;
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
    private String retrieveRagContextForRole(Book book, RoundTableRole role, List<RoundTableMessage> history) {
        if (!embeddingService.isAvailable()) {
            log.debug("Embedding 不可用，跳过角色视角 RAG 检索");
            return "";
        }

        try {
            if (!waitForContentEmbedding(book.getId())) {
                log.debug("内容向量不可用，跳过角色视角 RAG 检索: bookId={}", book.getId());
                return "";
            }

            String query = buildRoleSpecificQuery(book, role, history);
            log.debug("角色视角 RAG 查询: role={}, query={}", role.getKey(), query);

            List<EmbeddingMatch<TextSegment>> matches =
                    embeddingService.searchContent(query, 10, book);

            if (matches.isEmpty()) {
                return "";
            }

            matches = matches.stream()
                    .filter(m -> m.score() >= 0.1)
                    .toList();

            if (matches.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            int maxResults = 8;
            for (int i = 0; i < Math.min(matches.size(), maxResults); i++) {
                EmbeddingMatch<TextSegment> match = matches.get(i);
                sb.append(match.embedded().text()).append("\n\n");
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("角色视角 RAG 检索异常: bookId={}, role={} - {}", book.getId(), role.getKey(), e.getMessage());
            return "";
        }
    }

    /**
     * 构建角色专属的检索查询 — 使用 LLM 生成
     * <p>
     * 输入：角色信息 + 角色专业关键词 + 前两轮发言 + 图书基础信息
     * 输出：一段精准的检索查询文本，用于向量检索
     */
    private String buildRoleSpecificQuery(Book book, RoundTableRole role, List<RoundTableMessage> history) {
        try {
            // HOST 专用：反向查询——从未讨论的概念中选题，引导新话题
            if (role == RoundTableRole.HOST) {
                return buildHostSearchQuery(book, history);
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

            // 角色专业关键词
            String roleKeywords = getRoleSearchKeywords(role);

            String prompt = """
                    你是一个检索查询生成器。请根据以下信息，生成一段用于在书中检索相关段落的查询文本。
                    
                    要求：
                    1. 查询应该从该角色的专业视角出发，结合角色关注的关键词
                    2. 查询要紧扣当前讨论话题，让检索结果能帮助该角色发表有深度的观点
                    3. 只输出查询文本本身，不要输出任何解释或前缀
                    4. 查询长度控制在30-80字
                    
                    【图书】%s
                    【角色】%s（%s）
                    【角色关注领域】%s
                    【最近讨论】
                    %s
                    """.stripIndent().formatted(
                    book.getTitle(),
                    role.getName(), role.getTitle(),
                    roleKeywords,
                    recentDiscussion.isBlank() ? "（讨论尚未开始）" : recentDiscussion
            );

            String result = chatModelManager.callAi(
                    "圆桌派角色检索查询",
                    String.format("role=%s", role.getKey()),
                    prompt);
            if (result != null && !result.isBlank()) {
                // 清理可能的前缀（如"查询："、"检索："等）
                result = result.trim()
                        .replaceAll("^(查询|检索|搜索|关键词)[：:]", "")
                        .trim();
                log.debug("LLM 生成角色检索查询: role={}, query={}", role.getKey(), result);
                return result;
            }
        } catch (Exception e) {
            log.warn("LLM 生成角色检索查询失败，回退到关键词模式: role={} - {}", role.getKey(), e.getMessage());
        }

        // 回退：使用书名 + 角色关键词
        return book.getTitle() + " " + getRoleSearchKeywords(role);
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
    private String getRoleSearchKeywords(RoundTableRole role) {
        return switch (role) {
            case HOST -> "核心观点 主题思想 主要内容";
            case PHILOSOPHER -> "意义 本质 伦理 价值 存在 道德 哲学 思辨";
            case PSYCHOLOGIST -> "心理 情感 动机 行为 人格 认知 潜意识 情绪";
            case SOCIOLOGIST -> "社会 权力 阶级 制度 文化 结构 不平等 群体";
            case SCIENTIST -> "证据 数据 实验 逻辑 推理 假设 验证 方法";
            case CRITIC -> "叙事 修辞 隐喻 象征 风格 美学 结构 技巧";
            case HISTORIAN -> "历史 时代 背景 演变 传统 变革 文化 传承";
            case STUDENT -> "学习 疑问 理解 启发 知识 成长 困惑 思考";
            case ACTOR -> "角色 情感 体验 冲突 表演 人物 性格 内心";
            case COMEDIAN -> "幽默 讽刺 矛盾 荒诞 反差 趣味 比喻 夸张";
            case DIRECTOR -> "画面 场景 节奏 悬念 转折 高潮 结构 视觉";
            case JOURNALIST -> "事实 真相 细节 证据 调查 报道 内幕 背景";
            case LAWYER -> "权利 义务 公平 正义 法律 规则 合同 责任";
            case DOCTOR -> "生命 健康 疾病 心理 精神 生死 临床 治疗 症状 创伤";
            case ARTIST -> "美 创造 灵感 色彩 情感 想象 表达 形式";
            case WRITER -> "写作 叙事 文字 语言 表达 故事 人物 情节";
            case EDUCATOR -> "教育 学习 教学 启发 知识 成长 培养 方法";
            case ENTREPRENEUR -> "创新 机会 创业 市场 突破 变革 风险 价值";
            case INVESTOR -> "价值 投资 回报 风险 趋势 判断 长期 增长";
            case MUSICIAN -> "节奏 旋律 和声 韵律 情感 乐章 变奏 共鸣";
            case DIPLOMAT -> "外交 平衡 共识 妥协 对话 冲突 谈判 和平";
            case ECONOMIST -> "经济 成本 收益 供需 激励 市场 资源 分配";
            case FARMER -> "土地 自然 季节 生长 收获 农事 朴实 耐心";
            case FIREFIGHTER -> "危机 风险 应急 优先 安全 冷静 救援 防范";
            case LIBRARIAN -> "知识 书籍 分类 体系 传统 影响 阅读 推荐";
            case MEDITATION_TEACHER -> "冥想 正念 觉察 内心 平静 精神 成长 禅";
            case NURSE -> "关怀 照顾 痛苦 弱势 细节 温暖 护理 同理心";
            case POET -> "诗意 意象 隐喻 情感 语言 韵律 灵感 表达";
            case SOCIAL_WORKER -> "弱势 公平 权益 社区 援助 倡导 边缘 同理";
            case SPORTS_COACH -> "毅力 团队 训练 极限 竞技 逆境 斗志 潜力";
            case TRAVELER -> "旅行 文化 差异 见闻 世界 视角 跨文化 体验";
            case TECH_EXPERT -> "逻辑 系统 架构 底层 产品 需求 模式 规则";
            case ENGINEER -> "系统 效率 故障 冗余 容错 工程 设计 可靠性";
            case EDITOR -> "出版 编辑 文本 打磨 市场 读者 定位 筛选";
            case BOOK_REVIEWER -> "书评 推荐 阅读体验 品鉴 评分 畅销 口碑";
            case PARENT -> "育儿 家庭 代际 教育 亲子 焦虑 孩子 成长";
            case STRATEGIST -> "战略 博弈 决策 威慑 情报 战术 长期 布局";
            case ANTHROPOLOGIST -> "文化 仪式 符号 部落 他者 田野 民族志 习俗";
            case FEMINIST -> "性别 权力 女性 身体政治 隐形劳动 平权 父权 凝视";
            case ECOLOGIST -> "生态 自然 环境 可持续 末日 生物多样性 气候 伦理";
            case TRANSLATOR -> "翻译 译文 不可译 文化损耗 原文 语境 语言转换";
        };
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
    private List<RoundTableRole> selectRolesByTags(List<String> tags) {
        List<RoundTableRole> result = new ArrayList<>();
        result.add(RoundTableRole.HOST);

        Set<RoundTableRole> candidates = new LinkedHashSet<>();
        String allTags = tags.stream().map(String::toLowerCase).collect(Collectors.joining(" "));

        if (containsAny(allTags, "心理学", "心理", "精神", "情感", "成长", "亲子", "育儿", "教育")) {
            candidates.add(RoundTableRole.PSYCHOLOGIST);
        }
        if (containsAny(allTags, "哲学", "伦理", "存在", "逻辑", "国学", "儒学", "道家", "佛学")) {
            candidates.add(RoundTableRole.PHILOSOPHER);
        }
        if (containsAny(allTags, "社会", "政治", "权力", "阶级", "文化", "人类学", "历史", "中国历史", "世界历史")) {
            candidates.add(RoundTableRole.SOCIOLOGIST);
        }
        if (containsAny(allTags, "科学", "科普", "物理", "数学", "化学", "生物", "医学", "计算机", "编程", "人工智能")) {
            candidates.add(RoundTableRole.SCIENTIST);
            candidates.add(RoundTableRole.TECH_EXPERT);
        }
        if (containsAny(allTags, "商业", "创业", "管理", "经济", "金融", "投资", "职场", "营销")) {
            candidates.add(RoundTableRole.ENTREPRENEUR);
        }
        if (containsAny(allTags, "小说", "文学", "散文", "诗歌", "叙事", "长篇", "短篇", "推理", "悬疑", "科幻", "奇幻")) {
            candidates.add(RoundTableRole.CRITIC);
            candidates.add(RoundTableRole.ACTOR);
            candidates.add(RoundTableRole.WRITER);
        }
        if (containsAny(allTags, "历史", "古代", "近代", "战争", "革命", "传记", "回忆录")) {
            candidates.add(RoundTableRole.HISTORIAN);
        }
        if (containsAny(allTags, "学术", "教育", "学习", "大学", "研究")) {
            candidates.add(RoundTableRole.EDUCATOR);
            candidates.add(RoundTableRole.STUDENT);
        }
        if (containsAny(allTags, "法律", "刑侦", "犯罪", "正义", "权利")) {
            candidates.add(RoundTableRole.LAWYER);
        }
        if (containsAny(allTags, "医学", "健康", "中医", "营养", "生命", "精神", "临床", "心理")) {
            candidates.add(RoundTableRole.DOCTOR);
        }
        if (containsAny(allTags, "艺术", "音乐", "电影", "摄影", "设计")) {
            candidates.add(RoundTableRole.ARTIST);
            candidates.add(RoundTableRole.DIRECTOR);
        }
        if (containsAny(allTags, "新闻", "传播", "媒体", "记者", "调查", "深度")) {
            candidates.add(RoundTableRole.JOURNALIST);
        }
        if (containsAny(allTags, "外交", "国际", "地缘", "谈判", "和平", "冲突")) {
            candidates.add(RoundTableRole.DIPLOMAT);
        }
        if (containsAny(allTags, "经济", "金融", "市场", "货币", "贸易", "供需")) {
            candidates.add(RoundTableRole.ECONOMIST);
        }
        if (containsAny(allTags, "农业", "自然", "生态", "环保", "乡村", "田园")) {
            candidates.add(RoundTableRole.FARMER);
        }
        if (containsAny(allTags, "危机", "应急", "安全", "风险", "消防", "救援")) {
            candidates.add(RoundTableRole.FIREFIGHTER);
        }
        if (containsAny(allTags, "图书馆", "阅读", "书籍", "知识", "文献", "分类")) {
            candidates.add(RoundTableRole.LIBRARIAN);
        }
        if (containsAny(allTags, "冥想", "正念", "禅", "灵修", "瑜伽", "修行")) {
            candidates.add(RoundTableRole.MEDITATION_TEACHER);
        }
        if (containsAny(allTags, "护理", "关怀", "照顾", "康复", "养老", "助人")) {
            candidates.add(RoundTableRole.NURSE);
        }
        if (containsAny(allTags, "政治", "治理", "政策", "公共", "行政", "政府")) {
            candidates.add(RoundTableRole.STRATEGIST);
            candidates.add(RoundTableRole.DIPLOMAT);
        }
        if (containsAny(allTags, "诗歌", "诗意", "意象", "抒情", "文学")) {
            candidates.add(RoundTableRole.POET);
        }
        if (containsAny(allTags, "公益", "社工", "弱势", "公平", "社区", "慈善", "权益")) {
            candidates.add(RoundTableRole.SOCIAL_WORKER);
        }
        if (containsAny(allTags, "体育", "运动", "竞技", "训练", "团队", "比赛", "健身")) {
            candidates.add(RoundTableRole.SPORTS_COACH);
        }
        if (containsAny(allTags, "旅行", "游记", "文化", "地理", "世界", "探险", "跨文化")) {
            candidates.add(RoundTableRole.TRAVELER);
        }
        if (containsAny(allTags, "基金", "风投", "创投", "VC", "PE", "二级市场", "宏观", "周期", "行业", "趋势", "基本面", "估值", "非共识", "尽调")) {
            candidates.add(RoundTableRole.INVESTOR);
        }
        if (containsAny(allTags, "科技", "互联网", "技术", "产品", "架构", "系统设计", "底层", "代码", "扩展性")) {
            candidates.add(RoundTableRole.TECH_EXPERT);
        }
        if (containsAny(allTags, "人类学", "民族志", "田野", "仪式", "部落", "他者", "文化比较")) {
            candidates.add(RoundTableRole.ANTHROPOLOGIST);
        }
        if (containsAny(allTags, "女性", "性别", "女权", "平权", "父权", "身体政治", "隐形劳动", "凝视")) {
            candidates.add(RoundTableRole.FEMINIST);
        }
        if (containsAny(allTags, "生态", "环保", "自然", "可持续", "气候", "生物多样性", "末日")) {
            candidates.add(RoundTableRole.ECOLOGIST);
        }
        if (containsAny(allTags, "翻译", "译本", "外语", "译文", "不可译", "文化损耗", "原文", "语境")) {
            candidates.add(RoundTableRole.TRANSLATOR);
        }
        if (containsAny(allTags, "出版", "编辑", "文本打磨", "市场定位", "读者")) {
            candidates.add(RoundTableRole.EDITOR);
        }
        if (containsAny(allTags, "书评", "推荐", "阅读体验", "品鉴", "评分", "畅销", "口碑")) {
            candidates.add(RoundTableRole.BOOK_REVIEWER);
        }
        if (containsAny(allTags, "育儿", "亲子", "家庭", "代际", "孩子", "教育焦虑")) {
            candidates.add(RoundTableRole.PARENT);
        }
        if (containsAny(allTags, "军事", "战略", "博弈", "威慑", "战术", "情报", "决策")) {
            candidates.add(RoundTableRole.STRATEGIST);
        }
        if (containsAny(allTags, "工程", "系统", "效率", "容错", "冗余", "可靠性", "设计")) {
            candidates.add(RoundTableRole.ENGINEER);
        }

        if (candidates.size() < 3) {
            List<RoundTableRole> defaults = List.of(
                    RoundTableRole.PHILOSOPHER,
                    RoundTableRole.PSYCHOLOGIST,
                    RoundTableRole.SOCIOLOGIST,
                    RoundTableRole.EDUCATOR,
                    RoundTableRole.COMEDIAN
            );
            for (RoundTableRole def : defaults) {
                if (candidates.size() >= 3) break;
                candidates.add(def);
            }
        }

        candidates.stream().limit(5).forEach(result::add);
        return result;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
