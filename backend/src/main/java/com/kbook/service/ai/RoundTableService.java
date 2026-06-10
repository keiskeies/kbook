package com.kbook.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.exception.BusinessException;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.config.CancellableHttpClientBuilder;
import com.kbook.config.ChatModelFactory;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.config.properties.QdrantProperties;
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
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

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
@RequiredArgsConstructor
public class RoundTableService {

    private final EmbeddingService embeddingService;
    private final BookService bookService;
    private final BookParserService bookParserService;
    private final ChatModelFactory chatModelFactory;
    private final ChatModelManager chatModelManager;
    private final AiProviderConfigService aiProviderConfigService;
    private final QdrantProperties qdrantProperties;
    private final RoundTableSessionRepository sessionRepository;
    private final RoundTableMessageRepository messageRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    /** SSE 异步执行线程池 */
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    /** JSON 序列化 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 默认上下文长度（32K tokens） */
    private static final int DEFAULT_MAX_TOKENS = 32768;
    /** token → 中文字符换算比例 */
    private static final double TOKEN_TO_CHAR_RATIO = 1.5;
    /** 压缩触发阈值：历史占比超过此比例开始压缩 */
    private static final double COMPRESS_TRIGGER_RATIO = 0.8;
    /** 压缩目标：历史占比降到该比例以下停止 */
    private static final double COMPRESS_TARGET_RATIO = 0.6;

    /** 估算字符数 */
    private static final Function<String, Integer> CHAR_LENGTH_ESTIMATE = s -> s != null ? s.length() : 0;

    /** 角色推荐缓存键前缀 */
    private static final String ROLES_CACHE_KEY_PREFIX = "kbook:round-table:roles:";
    /** 角色推荐缓存 TTL（72 小时） */
    private static final long ROLES_CACHE_TTL_HOURS = 72;

    // ==================== 角色推荐（LLM 驱动） ====================

    /**
     * 根据书籍信息通过 LLM 推荐角色列表
     * <p>
     * 始终包含主持人，LLM 推荐 4-6 个其他角色并赋值 domainRelevance。
     * 总共返回 12 个角色（HOST + 11 个非 HOST），其中 LLM 推荐的标记为 selected。
     * LLM 失败时回退到标签匹配。
     *
     * @param bookId 书籍ID
     * @return 推荐角色列表（含 LLM 赋值的 domainRelevance 和 selected 标记）
     */
    @LogAction("LLM推荐角色")
    @SuppressWarnings("unchecked")
    public List<RoleVO> getRecommendedRoles(Long bookId) {
        // 读缓存
        String cacheKey = ROLES_CACHE_KEY_PREFIX + bookId;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof List<?> list) {
            if (!list.isEmpty() && list.get(0) instanceof RoleVO) {
                log.debug("角色推荐缓存命中: bookId={}", bookId);
                return (List<RoleVO>) list;
            }
        }

        Book book = bookService.getBookById(bookId);
        if (book == null) {
            return getDefaultRoles();
        }

        // 尝试 LLM 推荐角色
        try {
            String bookInfo = buildBookInfoForRoleSelection(book);
            String prompt = String.format(AiPromptConstants.ROUND_TABLE_ROLE_SELECTION_PROMPT, bookInfo);

            String result = chatModelManager.callAi(
                    "圆桌派角色推荐",
                    String.format("bookId=%d, title=%s", bookId, book.getTitle()),
                    chatModelFactory::buildChatModelWithoutThinkingFromYml,
                    prompt);

            if (result != null && !result.isBlank()) {
                result = CommonUtils.stripCodeFence(result);
                List<RoleVO> llmSelectedRoles = parseLlmRoleSelection(result);
                if (llmSelectedRoles != null && llmSelectedRoles.size() >= 3) {
                    List<RoundTableRole> selectedEnums = llmSelectedRoles.stream()
                            .map(vo -> RoundTableRole.fromKey(vo.getKey()))
                            .filter(Objects::nonNull)
                            .toList();
                    List<RoleVO> roles = buildRoleListFromSelected(selectedEnums);
                    redisTemplate.opsForValue().set(cacheKey, roles, ROLES_CACHE_TTL_HOURS, TimeUnit.HOURS);
                    return roles;
                }
            }
        } catch (Exception e) {
            log.warn("LLM 角色推荐失败，回退到标签匹配: bookId={} - {}", bookId, e.getMessage());
        }

        // 回退到标签匹配
        List<RoleVO> fallbackRoles = getFallbackRolesByTags(book);
        redisTemplate.opsForValue().set(cacheKey, fallbackRoles, ROLES_CACHE_TTL_HOURS, TimeUnit.HOURS);
        return fallbackRoles;
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
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            String desc = book.getDescription().length() > 500
                    ? book.getDescription().substring(0, 500) + "..."
                    : book.getDescription();
            sb.append("简介：").append(desc).append("\n");
        }
        if (book.getChapterSummary() != null && !book.getChapterSummary().isBlank()) {
            String summary = CommonUtils.truncateText(book.getChapterSummary(), 2000);
            sb.append("章节摘要：").append(summary).append("\n");
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
                    chatModelFactory::buildChatModelWithoutThinkingFromYml,
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
     * 从已选角色列表构建 18 人名单（HOST 始终包含且选中）
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
            if (result.size() >= 18) break;
            RoleVO vo = RoleVO.from(role);
            vo.setSelected(false);
            result.add(vo);
            addedKeys.add(role.getKey());
        }

        return result;
    }


    /**
     * 获取默认角色列表 — 返回12个角色，前5个标记为selected
     */
    private List<RoleVO> getDefaultRoles() {
        List<RoundTableRole> allRoles = Arrays.asList(RoundTableRole.values());
        List<RoleVO> result = new ArrayList<>();
        Set<String> addedKeys = new HashSet<>();

        // 默认选中的角色
        List<RoundTableRole> defaultSelected = List.of(
                RoundTableRole.HOST,
                RoundTableRole.PHILOSOPHER,
                RoundTableRole.PSYCHOLOGIST,
                RoundTableRole.SOCIOLOGIST,
                RoundTableRole.EDUCATOR
        );
        for (RoundTableRole role : defaultSelected) {
            RoleVO vo = RoleVO.from(role);
            vo.setSelected(true);
            result.add(vo);
            addedKeys.add(role.getKey());
        }

        // 补充到12人
        List<RoundTableRole> remainingRoles = allRoles.stream()
                .filter(r -> !addedKeys.contains(r.getKey()))
                .collect(Collectors.toList());
        Collections.shuffle(remainingRoles);
        for (RoundTableRole role : remainingRoles) {
            if (result.size() >= 12) break;
            RoleVO vo = RoleVO.from(role);
            vo.setSelected(false);
            result.add(vo);
            addedKeys.add(role.getKey());
        }

        return result;
    }

    // ==================== 会话管理 ====================

    /**
     * 创建圆桌派会话
     *
     * @param userId     用户ID
     * @param bookId     书籍ID
     * @param roleKeys   参与角色键名列表
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

        return sessionRepository.save(session);
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
        return sessionRepository.findByUserIdAndBookIdOrderByUpdatedAtDesc(userId, bookId);
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
        return messageRepository.findByUserIdAndSessionIdOrderByIdAsc(userId, sessionId);
    }

    /**
     * LLM + 算法混合判断下一轮发言人
     * <p>
     * 两阶段决策：
     * 1. LLM 阶段：根据内容相关性，对所有角色按"发言意愿"排名（提供发言原因）
     * 2. 算法阶段：在 LLM 排名基础上应用公平性约束，选出最终发言人
     * <p>
     * 公平性规则（硬约束优先级从高到低）：
     * - 从未发言的角色优先（必须给沉默者机会）
     * - 刚发过言的角色降级（最近1-2轮内发言过的排后面）
     * - 发言次数偏离平均值的角色调整权重（说太多的降级，说太少的提升）
     * - 主持人发言次数应最少
     */
    @LogAction("LLM+算法判断下一发言人")
    public String getNextSpeaker(Long userId, String sessionId) {
        // 1. 加载会话
        RoundTableSession session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException("会话不存在"));

        // 2. 加载所有历史消息
        List<RoundTableMessage> allMessages = messageRepository
                .findBySessionIdOrderByIdAsc(sessionId);

        // 3. 统计发言数据
        String[] roleKeys = session.getRoleKeys().split(",");
        Map<String, Long> speakCounts = allMessages.stream()
                .collect(Collectors.groupingBy(RoundTableMessage::getRoleKey, Collectors.counting()));

        // 最近2轮发言的角色
        Set<String> recentSpeakers = new HashSet<>();
        int recentCount = Math.min(allMessages.size(), 2);
        for (int i = allMessages.size() - 1; i >= allMessages.size() - recentCount; i--) {
            recentSpeakers.add(allMessages.get(i).getRoleKey());
        }

        // 从未发言的角色
        Set<String> neverSpoken = Arrays.stream(roleKeys)
                .map(String::trim)
                .filter(key -> !speakCounts.containsKey(key))
                .collect(Collectors.toSet());

        // 4. 调用 LLM 获取排名列表
        List<String> llmRankings = getLlmRankings(session, allMessages);

        // 5. 算法阶段：在 LLM 排名基础上应用公平性约束
        String selected = applyFairnessRules(llmRankings, roleKeys, speakCounts, recentSpeakers, neverSpoken, allMessages);

        log.info("圆桌派下一发言人: sessionId={}, LLM排名={}, 公平性调整后={}, 发言统计={}",
                sessionId, llmRankings, selected, speakCounts);

        return selected;
    }

    /**
     * 调用 LLM 获取所有角色的发言意愿排名
     * <p>
     * LLM 只负责"内容相关性"判断，返回按发言意愿从高到低排列的角色列表
     */
    private List<String> getLlmRankings(RoundTableSession session, List<RoundTableMessage> allMessages) {
        try {
            // 构建角色信息（不含公平性标记，LLM 只看性格和内容）
            String rolesInfo = buildRolesInfoForContentRelevance(session);

            // 加载最近消息（最多5条）
            int start = Math.max(0, allMessages.size() - 5);
            List<RoundTableMessage> recentMessages = allMessages.subList(start, allMessages.size());
            String recentHistory = buildRecentHistoryForSpeakerSelection(recentMessages);

            // 构建提示词
            String prompt = String.format(
                    AiPromptConstants.ROUND_TABLE_NEXT_SPEAKER_PROMPT,
                    rolesInfo, recentHistory);

            // 调用 LLM
            ChatModel chatModel = chatModelFactory.buildChatModelWithoutThinkingFromYml();
            String response = chatModel.chat(prompt);

            // 解析排名列表
            var jsonNode = objectMapper.readTree(CommonUtils.stripCodeFence(response));
            if (jsonNode.has("rankings") && jsonNode.get("rankings").isArray()) {
                List<String> rankings = new ArrayList<>();
                for (var item : jsonNode.get("rankings")) {
                    rankings.add(item.asText());
                }
                if (!rankings.isEmpty()) {
                    return rankings;
                }
            }

            // 兼容旧格式 {"nextSpeaker": "KEY"}
            if (jsonNode.has("nextSpeaker")) {
                return List.of(jsonNode.get("nextSpeaker").asText());
            }

            log.warn("LLM 返回格式异常，使用兜底逻辑: {}", response);
        } catch (Exception e) {
            log.warn("LLM 排名失败，使用兜底逻辑: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * 在 LLM 排名基础上应用公平性约束，选出最终发言人
     * <p>
     * 算法逻辑：
     * 1. 为每个角色计算"综合得分" = LLM排名分 × 公平性权重
     * 2. LLM排名分：排名第1得N分，第2得N-1分...（N=角色总数）
     *    未被LLM列入的角色按发言次数从少到多赋予递增分数，避免永远垫底
     * 3. 公平性权重（所有角色一视同仁，包括主持人）：
     *    - 从未发言：2.0（强力优先）
     *    - 刚发过言（最近1-2轮）：0.2（强力降级）
     *    - 发言次数 > 平均值×1.5：0.5（明显降级）
     *    - 发言次数 < 平均值×0.5：1.5（明显提升）
     *    - 其他：1.0
     * 4. 主持人强制介入规则：
     *    - 每满 N 轮（N=在场非主持人数），主持人必须至少发言 1 次
     *    - 如果主持人发言次数 < 轮次/非主持人数，主持人获得额外权重加成
     *    - 当检测到连续 3 轮以上同一角色发言或讨论循环重复时，强制选主持人
     */
    private String applyFairnessRules(List<String> llmRankings, String[] roleKeys,
                                       Map<String, Long> speakCounts,
                                       Set<String> recentSpeakers,
                                       Set<String> neverSpoken,
                                       List<RoundTableMessage> allMessages) {
        Set<String> validKeys = Arrays.stream(roleKeys).map(String::trim).collect(Collectors.toSet());
        int totalRoles = validKeys.size();
        int nonHostCount = (int) validKeys.stream().filter(k -> !"HOST".equals(k)).count();
        if (nonHostCount <= 0) nonHostCount = 1;

        long hostCount = speakCounts.getOrDefault("HOST", 0L);
        int totalRounds = allMessages.size();

        // 检测循环重复
        boolean isLooping = detectLoopingPattern(allMessages);

        // 计算平均发言次数
        double avgCount = validKeys.stream()
                .mapToLong(key -> speakCounts.getOrDefault(key, 0L))
                .average()
                .orElse(0.0);

        // ====== 硬性规则：优先级最高，直接决定发言人 ======

        // 硬规则1：如果讨论已进行3轮以上，仍有角色从未发言，强制选从未发言者
        if (totalRounds >= 3 && !neverSpoken.isEmpty()) {
            // 从未发言的角色中，选 LLM 排名最高的
            String forcedSpeaker = neverSpoken.stream()
                    .min(Comparator.comparingInt(key -> {
                        int idx = llmRankings.indexOf(key);
                        return idx >= 0 ? idx : Integer.MAX_VALUE;
                    }))
                    .orElse(null);
            if (validKeys.contains(forcedSpeaker)) {
                log.info("硬规则触发：从未发言角色强制发言: {}, totalRounds={}", forcedSpeaker, totalRounds);
                return forcedSpeaker;
            }
        }

        // 硬规则2：主持人严重缺席 — 讨论超过 (非主持人数*2) 轮后，主持人发言次数应 >= 总轮次/(非主持人数+1)
        // 如果主持人0次发言且讨论已进行 nonHostCount 轮以上，强制主持人发言
        if (validKeys.contains("HOST") && !recentSpeakers.contains("HOST")) {
            int hostMinExpected = Math.max(1, totalRounds / (nonHostCount + 1));
            if (totalRounds >= nonHostCount && hostCount < hostMinExpected) {
                log.info("硬规则触发：主持人发言不足，强制发言: hostCount={}, totalRounds={}, minExpected={}",
                        hostCount, totalRounds, hostMinExpected);
                return "HOST";
            }
        }

        // ====== 软性规则：基于得分计算 ======

        // 为每个角色计算综合得分
        Map<String, Double> scores = new LinkedHashMap<>();

        List<String> unrankedKeys = validKeys.stream()
                .filter(key -> !llmRankings.contains(key))
                .sorted(Comparator.comparingLong(key -> speakCounts.getOrDefault(key, 0L)))
                .toList();

        for (String key : validKeys) {
            int rankIndex = llmRankings.indexOf(key);
            if (rankIndex >= 0) {
                scores.put(key, (double) (totalRoles - rankIndex));
            } else {
                int unrankedIndex = unrankedKeys.indexOf(key);
                scores.put(key, (double) (2 + unrankedIndex));
            }
        }

        // 应用公平性权重
        for (String key : validKeys) {
            double fairnessWeight = 1.0;
            long count = speakCounts.getOrDefault(key, 0L);

            // 从未发言 → 强力优先
            if (neverSpoken.contains(key)) {
                fairnessWeight = 3.0;
            }
            // 刚发过言 → 强力降级
            else if (recentSpeakers.contains(key)) {
                fairnessWeight = 0.2;
            }
            // 发言次数偏离平均值
            else if (avgCount > 0) {
                if (count > avgCount * 1.5) {
                    fairnessWeight = 0.5;
                } else if (count < avgCount * 0.5) {
                    fairnessWeight = 2.0;
                }
            }

            scores.put(key, scores.getOrDefault(key, 1.0) * fairnessWeight);
        }

        // 主持人额外加成（循环重复时强制介入）
        if (validKeys.contains("HOST") && isLooping && !recentSpeakers.contains("HOST")) {
            scores.put("HOST", scores.getOrDefault("HOST", 1.0) + 100.0);
            log.info("检测到讨论循环重复，强制主持人介入控场");
        }

        // 选出得分最高的
        String selected = scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("HOST");

        // 安全检查
        if (!validKeys.contains(selected)) {
            return "HOST";
        }

        return selected;
    }

    /**
     * 检测讨论是否出现循环重复模式
     * 检查最近6条消息是否有角色重复或内容相似的模式
     */
    private boolean detectLoopingPattern(List<RoundTableMessage> allMessages) {
        if (allMessages.size() < 6) return false;

        // 快速结构检测：同一角色高频出现（不需要 LLM）
        List<String> recentRoles = allMessages.subList(allMessages.size() - 6, allMessages.size())
                .stream()
                .map(RoundTableMessage::getRoleKey)
                .toList();
        Map<String, Long> recentRoleCounts = recentRoles.stream()
                .collect(Collectors.groupingBy(r -> r, Collectors.counting()));
        for (long count : recentRoleCounts.values()) {
            if (count >= 4) return true;
        }

        // 语义循环检测：用 LLM 判断讨论是否在绕圈子
        try {
            int start = Math.max(0, allMessages.size() - 8);
            List<RoundTableMessage> recent = allMessages.subList(start, allMessages.size());
            StringBuilder sb = new StringBuilder();
            for (RoundTableMessage msg : recent) {
                String name = msg.getRoleName() != null ? msg.getRoleName() : msg.getRoleKey();
                String content = msg.getCompressedContent();
                if (content != null && !content.isBlank()) {
                    sb.append(name).append("：").append(CommonUtils.truncateText(content, 150)).append("\n");
                }
            }
            String recentText = sb.toString();
            if (recentText.isBlank()) return false;

            String prompt = """
                    判断以下讨论是否在"绕圈子"（反复说同一个观点、内容同义重复、讨论停滞不前）。
                    只回答 YES 或 NO。

                    最近发言：
                    %s
                    """.stripIndent().formatted(recentText);

            String result = chatModelManager.callAi(
                    "圆桌派循环检测",
                    "检测讨论是否循环重复",
                    chatModelFactory::buildChatModelWithoutThinkingFromYml,
                    prompt);

            if (result != null) {
                String upper = result.trim().toUpperCase();
                return upper.startsWith("YES") || upper.contains("YES");
            }
        } catch (Exception e) {
            log.debug("LLM 循环检测失败，回退到结构检测: {}", e.getMessage());
        }

        return false;
    }

    /**
     * 构建角色信息文本（用于 LLM 内容相关性判断）
     * 只包含角色性格参数，不包含发言次数等公平性信息（公平性由算法处理）
     */
    private String buildRolesInfoForContentRelevance(RoundTableSession session) {
        StringBuilder sb = new StringBuilder();
        try {
            if (session.getRoleConfigs() != null) {
                var configs = objectMapper.readTree(session.getRoleConfigs());
                if (configs.isArray()) {
                    for (var config : configs) {
                        String key = config.has("key") ? config.get("key").asText() : "";
                        String name = config.has("name") ? config.get("name").asText() : "";
                        int grabWeight = config.has("grabWeight") ? config.get("grabWeight").asInt() : 5;
                        int challenge = config.has("challenge") ? config.get("challenge").asInt() : 3;
                        int empathy = config.has("empathy") ? config.get("empathy").asInt() : 3;
                        int opinionated = config.has("opinionated") ? config.get("opinionated").asInt() : 3;
                        int verbosity = config.has("verbosity") ? config.get("verbosity").asInt() : 3;
                        int humor = config.has("humor") ? config.get("humor").asInt() : 3;
                        int domainRelevance = config.has("domainRelevance") ? config.get("domainRelevance").asInt() : 0;

                        sb.append("- ").append(key).append("(").append(name).append(")");
                        sb.append("：抢麦=").append(grabWeight);
                        sb.append(" 挑战=").append(challenge);
                        sb.append(" 共情=").append(empathy);
                        sb.append(" 主见=").append(opinionated);
                        sb.append(" 话量=").append(verbosity);
                        sb.append(" 幽默=").append(humor);
                        sb.append(" 专业相关度=").append(domainRelevance);
                        sb.append("\n");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析角色配置失败: {}", e.getMessage());
        }
        return sb.toString();
    }

    /**
     * 构建最近发言记录文本（用于发言人选择提示词）
     */
    private String buildRecentHistoryForSpeakerSelection(List<RoundTableMessage> messages) {
        if (messages.isEmpty()) {
            return "（暂无发言记录，这是讨论的开始）";
        }
        StringBuilder sb = new StringBuilder();
        for (RoundTableMessage msg : messages) {
            String content = msg.getContent();
            // 截取前100字，避免太长
            if (content != null && content.length() > 100) {
                content = content.substring(0, 100) + "...";
            }
            sb.append(msg.getRoleName()).append("：").append(content).append("\n");
        }
        return sb.toString();
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
        messageRepository.deleteByUserIdAndSessionId(userId, sessionId);
        sessionRepository.findBySessionId(sessionId).ifPresent(session -> {
            if (session.getUserId().equals(userId)) {
                sessionRepository.delete(session);
            }
        });
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
    public String getNextSpeakerOnlyLLM(Long userId, String sessionId) {
        // 1. 加载会话
        RoundTableSession session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException("会话不存在"));

        // 2. 构建角色信息（不依赖消息列表，可先构建）
        String rolesInfo = buildRolesInfoForLLMSpeakerSelection(session);

        // 3. 加载历史消息并同步压缩（一次 DB 查询）
        int currentOverhead = AiPromptConstants.ROUND_TABLE_NEXT_SPEAKER_LLM_ONLY_PROMPT.length()
                + rolesInfo.length()
                + 1000; // fairnessConstraints 预估 + LLM 回复预留
        List<RoundTableMessage> allMessages;
        try {
            allMessages = loadAndCompressHistory(userId, sessionId, currentOverhead);
        } catch (Exception e) {
            log.warn("加载/压缩圆桌派历史失败，回退到直接加载: {}", e.getMessage());
            allMessages = messageRepository.findBySessionIdOrderByIdAsc(sessionId);
        }

        // 4. 统计发言数据
        String[] roleKeys = session.getRoleKeys().split(",");
        Map<String, Long> speakCounts = allMessages.stream()
                .collect(Collectors.groupingBy(RoundTableMessage::getRoleKey, Collectors.counting()));

        // 5. 构建公平性约束说明
        String fairnessConstraints = buildFairnessConstraints(roleKeys, speakCounts, allMessages);

        // 6. 构建加权对话历史（压缩后，使用 compressedContent）
        String weightedHistory = buildWeightedHistoryForLLM(allMessages);

        // 7. 构建提示词并调用 LLM
        String prompt = String.format(
                AiPromptConstants.ROUND_TABLE_NEXT_SPEAKER_LLM_ONLY_PROMPT,
                rolesInfo, weightedHistory, fairnessConstraints);

        try {
            ChatModel chatModel = chatModelFactory.buildChatModelWithoutThinkingFromYml();
            String response = chatModel.chat(prompt);
            response = CommonUtils.stripCodeFence(response).trim();

            // 解析返回的角色 key
            String selectedKey = parseLlmSpeakerResponse(response, roleKeys);

            log.info("纯LLM选择发言人: sessionId={}, selected={}, speakCounts={}",
                    sessionId, selectedKey, speakCounts);

            return selectedKey;
        } catch (Exception e) {
            log.warn("纯LLM发言人选择失败，回退到混合模式: {}", e.getMessage());
            return getNextSpeaker(userId, sessionId);
        }
    }

    /**
     * 构建角色信息文本（用于纯 LLM 发言人选择）
     * 包含角色性格参数、专业领域、说话风格，让 LLM 理解每个角色的特点
     */
    private String buildRolesInfoForLLMSpeakerSelection(RoundTableSession session) {
        StringBuilder sb = new StringBuilder();
        try {
            if (session.getRoleConfigs() != null) {
                var configs = objectMapper.readTree(session.getRoleConfigs());
                if (configs.isArray()) {
                    for (var config : configs) {
                        String key = config.has("key") ? config.get("key").asText() : "";
                        String name = config.has("name") ? config.get("name").asText() : "";
                        String title = config.has("title") ? config.get("title").asText() : "";
                        int challenge = config.has("challenge") ? config.get("challenge").asInt() : 3;
                        int empathy = config.has("empathy") ? config.get("empathy").asInt() : 3;
                        int opinionated = config.has("opinionated") ? config.get("opinionated").asInt() : 3;
                        int verbosity = config.has("verbosity") ? config.get("verbosity").asInt() : 3;
                        int humor = config.has("humor") ? config.get("humor").asInt() : 3;
                        int domainRelevance = config.has("domainRelevance") ? config.get("domainRelevance").asInt() : 0;
                        String languageStyle = config.has("languageStyle") ? config.get("languageStyle").asText() : "";

                        sb.append("- ").append(key).append("(").append(name).append(", ").append(title).append(")");
                        sb.append("：挑战=").append(challenge);
                        sb.append(" 共情=").append(empathy);
                        sb.append(" 主见=").append(opinionated);
                        sb.append(" 话量=").append(verbosity);
                        sb.append(" 幽默=").append(humor);
                        sb.append(" 专业相关度=").append(domainRelevance);
                        if (!languageStyle.isBlank()) {
                            sb.append(" 语言风格=").append(languageStyle);
                        }
                        sb.append("\n");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析角色配置失败: {}", e.getMessage());
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
     */
    private String buildWeightedHistoryForLLM(List<RoundTableMessage> allMessages) {
        if (allMessages.isEmpty()) {
            return "（暂无发言记录，这是讨论的开始）";
        }

        StringBuilder sb = new StringBuilder();
        int total = allMessages.size();

        for (int i = 0; i < total; i++) {
            RoundTableMessage msg = allMessages.get(i);
            // 优先使用压缩后的摘要内容，避免原始长文本导致上下文溢出
            String content = msg.getCompressedContent() != null && !msg.getCompressedContent().isBlank()
                    ? msg.getCompressedContent()
                    : msg.getContent();
            if (content != null && content.length() > 150) {
                content = content.substring(0, 150) + "...";
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

        // 2. 从未发言的角色
        Set<String> neverSpoken = Arrays.stream(roleKeys)
                .map(String::trim)
                .filter(key -> !speakCounts.containsKey(key))
                .collect(Collectors.toSet());
        if (!neverSpoken.isEmpty()) {
            sb.append("- 优先鼓励：以下角色至今未发言，应优先考虑让他们参与讨论：");
            for (String key : neverSpoken) {
                RoundTableRole role = RoundTableRole.fromKey(key);
                if (role != null) sb.append(role.getName()).append(" ");
            }
            sb.append("\n");
        }

        // 3. 发言次数统计
        sb.append("- 当前发言次数统计：");
        for (String key : roleKeys) {
            String trimmed = key.trim();
            RoundTableRole role = RoundTableRole.fromKey(trimmed);
            if (role != null) {
                long count = speakCounts.getOrDefault(trimmed, 0L);
                sb.append(role.getName()).append("=").append(count).append(" ");
            }
        }
        sb.append("\n");

        // 4. 主持人控场规则
        int nonHostCount = (int) Arrays.stream(roleKeys).filter(k -> !"HOST".equals(k.trim())).count();
        long hostCount = speakCounts.getOrDefault("HOST", 0L);
        int totalRounds = allMessages.size();
        int hostMinExpected = Math.max(1, totalRounds / (nonHostCount + 1));
        if (totalRounds >= nonHostCount && hostCount < hostMinExpected) {
            sb.append("- 主持人控场：主持人发言次数不足（").append(hostCount).append("次，应至少")
              .append(hostMinExpected).append("次），需要让主持人介入引导\n");
        }

        return sb.toString();
    }

    /**
     * 解析 LLM 返回的发言人选择结果
     */
    private String parseLlmSpeakerResponse(String response, String[] validRoleKeys) {
        Set<String> validKeys = Arrays.stream(validRoleKeys).map(String::trim).collect(Collectors.toSet());

        // 尝试解析 JSON {"nextSpeaker": "KEY"}
        try {
            var jsonNode = objectMapper.readTree(response);
            if (jsonNode.has("nextSpeaker")) {
                String key = jsonNode.get("nextSpeaker").asText().trim();
                if (validKeys.contains(key)) return key;
            }
        } catch (Exception ignored) {
        }

        // 尝试直接匹配角色 key
        String upperResponse = response.toUpperCase();
        for (String key : validKeys) {
            if (upperResponse.contains(key)) {
                return key;
            }
        }

        // 兜底：返回第一个非 HOST 角色
        for (String key : validKeys) {
            if (!"HOST".equals(key)) return key;
        }
        return "HOST";
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
        RoundTableSession session = sessionRepository.findBySessionId(request.getSessionId()).orElse(null);
        if (session == null) {
            SseHelper.sendErrorAndComplete(emitter, "会话不存在: " + request.getSessionId());
            return emitter;
        }

        // 获取角色的 domainRelevance（从 roleConfigs JSON 中读取，或使用枚举默认值）
        int domainRelevance = resolveDomainRelevance(role, session.getRoleConfigs());

        // 获取角色的 languageStyle（从 roleConfigs JSON 中读取）
        String languageStyle = resolveLanguageStyle(role, session.getRoleConfigs());

        final long[] executorThreadId = new long[1];
        Future<?> aiFuture = sseExecutor.submit(() -> {
            executorThreadId[0] = Thread.currentThread().getId();
            try {
                Book book = bookService.getBookById(bookId);

                // 构建系统提示词（含语言风格和性格维度）
                String systemPrompt = buildCharacterSystemPrompt(role, domainRelevance, request.getTopic(), languageStyle);

                // 构建书籍上下文
                String bookContext = "";
                if (book != null) {
                    if (!Boolean.TRUE.equals(book.getContentEmbedded())) {
                        boolean embedded = waitForContentEmbedding(bookId);
                        if (embedded) {
                            book.setContentEmbedded(true);
                        }
                    }
                    // 角色视角 RAG 检索 — 每个角色根据自己的专业角度搜索书中相关内容
                    List<RoundTableMessage> historyMessages = messageRepository
                            .findBySessionIdOrderByIdAsc(request.getSessionId());
                    String ragContext = retrieveRagContextForRole(book, role, historyMessages);
                    bookContext = buildBookContext(book, ragContext);
                }

                // 构建消息列表：系统提示 + 书籍上下文 + DB 历史 + 发言指令
                List<ChatMessage> messages = buildChatMessages(
                        request.getSessionId(), userId, systemPrompt, bookContext, role.getName());

                // 构建流式模型（不使用 thinking 模式）
                StreamingChatModel streamingChatModel = chatModelFactory.buildStreamingChatModelWithoutThinking();
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
                            @Override
                            public void onPartialThinking(dev.langchain4j.model.chat.response.PartialThinking partialThinking) {
                                // 不使用 thinking 模式，忽略
                            }

                            @Override
                            public void onPartialResponse(String partialResponse) {
                                if (connectionClosed[0] || Thread.currentThread().isInterrupted()) return;
                                if (partialResponse == null || partialResponse.isEmpty()) return;

                                fullResponse.append(partialResponse);

                                try {
                                    String json = objectMapper.writeValueAsString(
                                            Map.of("roleKey", role.getKey(), "text", partialResponse));
                                    if (!SseHelper.safeSendEvent(emitter, "message", json)) {
                                        connectionClosed[0] = true;
                                        Thread.currentThread().interrupt();
                                        log.warn("SSE 连接已关闭，停止 AI 输出: roleKey={}", role.getKey());
                                    }
                                } catch (Exception e) {
                                    connectionClosed[0] = true;
                                    Thread.currentThread().interrupt();
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

                                // 消息保存完成后，再发送 done 事件
                                try {
                                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                    emitter.complete();
                                } catch (Exception e) {
                                    log.warn("发送 SSE done 事件失败: {}", e.getMessage());
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
                                if (Thread.currentThread().isInterrupted()) return;
                                log.error("圆桌派单角色发言异常: bookId={}, roleKey={} - {}", bookId, role.getKey(), error.getMessage(), error);
                                SseHelper.sendErrorAndComplete(emitter, "AI 响应异常: " + SseHelper.extractFriendlyError(error));
                            }
                        }
                );

            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) return;
                log.error("圆桌派单角色发言异常: bookId={}, roleKey={} - {}", bookId, role.getKey(), e.getMessage(), e);
                SseHelper.sendErrorAndComplete(emitter, "AI 响应异常: " + SseHelper.extractFriendlyError(e));
            } finally {
                CancellableHttpClientBuilder.clearStream(executorThreadId[0]);
            }
        });

        emitter.onCompletion(() -> {
            CancellableHttpClientBuilder.cancelStream(executorThreadId[0]);
            aiFuture.cancel(true);
        });
        emitter.onTimeout(() -> {
            CancellableHttpClientBuilder.cancelStream(executorThreadId[0]);
            aiFuture.cancel(true);
            log.warn("圆桌派SSE超时: bookId={}, roleKey={}", bookId, role.getKey());
        });
        emitter.onError(e -> {
            CancellableHttpClientBuilder.cancelStream(executorThreadId[0]);
            aiFuture.cancel(true);
            log.error("圆桌派SSE错误: bookId={}, roleKey={}", bookId, role.getKey(), e);
        });

        return emitter;
    }

    // ==================== 提示词构建 ====================

    /**
     * 构建单角色系统提示词（含语言风格、性格维度和 domainRelevance）
     */
    private String buildCharacterSystemPrompt(RoundTableRole role, int domainRelevance, String topic, String languageStyle) {
        if (role == RoundTableRole.HOST) {
            String hostStyle = (languageStyle != null && !languageStyle.isBlank()) ? languageStyle : "沉稳大方，善于引导和总结";
            String extraInstructions;
            if (topic != null && !topic.isBlank()) {
                extraInstructions = "【话题方向】\n请围绕以下方向引导讨论：" + topic;
            } else {
                extraInstructions = "请回顾之前的对话。如果讨论陷入僵局、重复或钻牛角尖，请果断抛出一个新的话题或角度来激发讨论。如果讨论还在正常进行，可以简短回应或向某位嘉宾提问。如果是开场，请介绍书籍并抛出第一个讨论问题。";
            }
            return String.format(AiPromptConstants.ROUND_TABLE_HOST_PROMPT,
                    hostStyle,
                    role.getChallenge(), describeChallenge(role.getChallenge()),
                    role.getEmpathy(), describeEmpathy(role.getEmpathy()),
                    role.getOpinionated(), describeOpinionated(role.getOpinionated()),
                    role.getVerbosity(), describeVerbosity(role.getVerbosity()),
                    extraInstructions);
        } else {
            String charStyle = (languageStyle != null && !languageStyle.isBlank()) ? languageStyle : "自然流畅，符合你的专业身份";
            String extraInstructions = "";
            String catchphrase = role.getCatchphrase() != null ? role.getCatchphrase() : "用你自己的方式表达，保持自然";
            return String.format(AiPromptConstants.ROUND_TABLE_CHARACTER_PROMPT,
                    role.getPrompt(),
                    catchphrase,
                    charStyle,
                    role.getChallenge(), describeChallenge(role.getChallenge()),
                    role.getEmpathy(), describeEmpathy(role.getEmpathy()),
                    role.getOpinionated(), describeOpinionated(role.getOpinionated()),
                    role.getVerbosity(), describeVerbosity(role.getVerbosity()),
                    role.getHumor(), describeHumor(role.getHumor()),
                    domainRelevance, describeDomainRelevance(domainRelevance),
                    extraInstructions);
        }
    }

    /** 挑战倾向描述 */
    private String describeChallenge(int v) {
        if (v >= 4) return "喜欢质疑和反驳";
        if (v >= 3) return "适度挑战";
        return "较少质疑";
    }

    /** 共情力描述 */
    private String describeEmpathy(int v) {
        if (v >= 4) return "善于理解和共鸣";
        if (v >= 3) return "适度共情";
        return "理性优先";
    }

    /** 主见程度描述 */
    private String describeOpinionated(int v) {
        if (v >= 4) return "立场坚定";
        if (v >= 3) return "有一定主见";
        return "立场灵活";
    }

    /** 话量描述 */
    private String describeVerbosity(int v) {
        if (v >= 4) return "话多";
        if (v >= 3) return "话量适中";
        return "话少精炼";
    }

    /** 幽默感描述 */
    private String describeHumor(int v) {
        if (v >= 4) return "善于调侃和活跃气氛";
        if (v >= 3) return "适度幽默";
        return "严肃认真";
    }

    /** 专业相关度描述 */
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
     * 构建消息列表：系统提示 + 书籍上下文 + DB 历史 + 发言指令
     */
    private List<ChatMessage> buildChatMessages(String sessionId, Long userId,
                                                 String systemPrompt, String bookContext, String roleName) {
        List<ChatMessage> messages = new ArrayList<>();

        // 系统提示词
        messages.add(SystemMessage.from(systemPrompt));

        // 书籍上下文
        if (bookContext != null && !bookContext.isBlank()) {
            messages.add(UserMessage.from("【书籍信息与参考内容】\n" + bookContext));
            messages.add(dev.langchain4j.data.message.AiMessage.from("好的，我已了解书籍信息，准备参与讨论。"));
        }

        // 发言指令 — 强化禁止总结式开头（放在前面，让LLM优先理解要求）
        String speakInstruction;
        if ("HOST".equals(roleName) || "主持人".equals(roleName)) {
            speakInstruction = "请以主持人的身份发言。直接说你的观点或抛出问题，绝对不要以「刚才大家...」「前面几位...」「听了各位...」开头。";
        } else {
            speakInstruction = "请以" + roleName + "的身份发言。直接接话，绝对不要以「刚才大家...」「前面几位...」「听了各位...」开头，直接说你的观点。";
        }

        // 加载历史消息并同步压缩，确保 LLM 上下文不超限
        int currentOverhead = systemPrompt.length()
                + (bookContext != null ? bookContext.length() : 0)
                + speakInstruction.length()
                + 2000; // AI 回复预留
        try {
            List<RoundTableMessage> history = loadAndCompressHistory(userId, sessionId, currentOverhead);
            if (!history.isEmpty()) {
                StringBuilder historyBuilder = new StringBuilder("【之前的讨论内容】\n");
                for (RoundTableMessage msg : history) {
                    // 优先使用压缩后的摘要内容，避免原始长文本导致重复
                    String content = msg.getCompressedContent() != null && !msg.getCompressedContent().isBlank()
                            ? msg.getCompressedContent()
                            : msg.getContent();
                    if (content != null && !content.isBlank()) {
                        historyBuilder.append(msg.getRoleName()).append("：").append(content).append("\n\n");
                    }
                }
                messages.add(UserMessage.from(historyBuilder.toString()));
                messages.add(dev.langchain4j.data.message.AiMessage.from("好的，我已了解之前的讨论内容。"));
                messages.add(UserMessage.from(speakInstruction));
                log.debug("加载圆桌派历史: sessionId={}, totalRecords={}", sessionId, history.size());
            } else {
                messages.add(UserMessage.from(speakInstruction));
            }
        } catch (Exception e) {
            log.warn("加载圆桌派历史失败，继续无历史对话: {}", e.getMessage());
            messages.add(UserMessage.from(speakInstruction));
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
            List<RoundTableMessage> existing = messageRepository.findBySessionIdOrderByIdAsc(sessionId);
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
        sessionRepository.findBySessionId(sessionId).ifPresent(session -> {
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
        List<RoundTableMessage> messages = messageRepository.findByUserIdAndSessionIdOrderByIdAsc(userId, sessionId);
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
     * 从内存列表中查找第一条未压缩的非 HOST 消息。
     * 未压缩判定：compressedContent 为 null，或与 content 完全相同（创建时初始化相等）。
     */
    private RoundTableMessage findFirstUncompressedInMemory(List<RoundTableMessage> messages) {
        for (RoundTableMessage msg : messages) {
            if ("HOST".equals(msg.getRoleKey())) continue;
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
     * 获取书籍讨论上下文（书籍信息 + RAG 检索结果）
     *
     * @param bookId 书籍ID
     * @return 书籍上下文字符串
     */
    @LogAction("获取书籍上下文")
    public String getBookContext(Long bookId) {
        Book book = bookService.getBookById(bookId);
        if (book == null) {
            return "";
        }

        if (!Boolean.TRUE.equals(book.getContentEmbedded())) {
            boolean embedded = waitForContentEmbedding(bookId);
            if (embedded) {
                book.setContentEmbedded(true);
            }
        }

        String ragContext = retrieveRagContext(book);
        return buildBookContext(book, ragContext);
    }

    /**
     * 构建书籍上下文字符串（书籍信息 + RAG 检索结果）
     */
    private String buildBookContext(Book book, String ragContext) {
        StringBuilder sb = new StringBuilder();

        sb.append("书名：《").append(book.getTitle()).append("》\n");
        if (book.getAuthor() != null && !book.getAuthor().isBlank()) {
            sb.append("作者：").append(book.getAuthor()).append("\n");
        }
        if (book.getFormatTags() != null && !book.getFormatTags().isBlank()) {
            String tags = book.getFormatTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("标签：").append(tags).append("\n");
        }
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            String desc = book.getDescription().length() > 800
                    ? book.getDescription().substring(0, 800) + "..."
                    : book.getDescription();
            sb.append("简介：").append(desc).append("\n");
        }
//        if (book.getToc() != null && !book.getToc().isBlank()) {
//            String toc = book.getToc().length() > 1000
//                    ? book.getToc().substring(0, 1000) + "..."
//                    : book.getToc();
//            sb.append("目录：\n").append(toc).append("\n");
//        }
//        if (book.getChapterSummary() != null && !book.getChapterSummary().isBlank()) {
//            String summary = CommonUtils.truncateText(book.getChapterSummary(), 5000);
//            sb.append("\n【章节摘要】（每章核心内容概述）\n").append(summary).append("\n");
//        }

        if (!ragContext.isBlank()) {
            sb.append("\n【书籍参考内容】（以下是从原著中检索到的相关内容，讨论时可参考）\n");
            sb.append(ragContext);
        } else {
            sb.append("\n【注意】未从原著中检索到直接相关的内容片段，请根据书籍基本信息进行讨论。\n");
        }

        return sb.toString();
    }

    // ==================== RAG 检索 ====================

    /**
     * RAG 语义检索：从书籍内容向量中检索相关片段
     */
    private String retrieveRagContext(Book book) {
        if (!embeddingService.isAvailable()) {
            log.debug("Embedding 不可用，跳过 RAG 检索");
            return "";
        }

        try {
            if (!waitForContentEmbedding(book.getId())) {
                log.debug("内容向量不可用，跳过 RAG 检索: bookId={}", book.getId());
                return "";
            }

            String query = buildGeneralSearchQuery(book);

            List<EmbeddingMatch<TextSegment>> matches =
                    embeddingService.searchContent(query, 15, book);

            if (matches.isEmpty()) {
                log.debug("RAG 检索无结果: bookId={}", book.getId());
                return "";
            }

            matches = matches.stream()
                    .filter(m -> m.score() >= 0.1)
                    .collect(Collectors.toList());

            if (matches.isEmpty()) {
                return "";
            }

            matches.sort((a, b) -> Double.compare(b.score(), a.score()));

            int maxChars = 8000;
            StringBuilder sb = new StringBuilder();
            int totalLen = 0;
            for (int i = 0; i < matches.size(); i++) {
                EmbeddingMatch<TextSegment> match = matches.get(i);
                String chunkText = match.embedded() != null ? match.embedded().text() : "";
                if (chunkText.isBlank()) continue;
                if (totalLen + chunkText.length() > maxChars) break;
                sb.append(chunkText).append("\n\n");
                totalLen += chunkText.length();
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("圆桌派 RAG 检索异常: bookId={} - {}", book.getId(), e.getMessage());
            return "";
        }
    }

    /**
     * 用 LLM 生成通用 RAG 检索查询（用于首轮讨论上下文）
     */
    private String buildGeneralSearchQuery(Book book) {
        try {
            String desc = book.getDescription() != null
                    ? CommonUtils.truncateText(book.getDescription(), 300)
                    : "（无简介）";
            String prompt = """
                    你是一个检索查询生成器。请根据书籍信息，生成一段用于在书中检索核心内容片段的查询文本。
                    要求：覆盖书籍的核心主题、关键论点和重要内容，适合首次讨论使用。
                    只输出查询文本本身，30-80字。

                    【书名】%s
                    【简介】%s
                    """.stripIndent().formatted(book.getTitle(), desc);

            String result = chatModelManager.callAi(
                    "圆桌派通用RAG查询",
                    String.format("bookId=%d, title=%s", book.getId(), book.getTitle()),
                    chatModelFactory::buildChatModelWithoutThinkingFromYml,
                    prompt);

            if (result != null && !result.isBlank()) {
                result = result.trim()
                        .replaceAll("^(查询|检索|搜索|关键词)[：:]", "")
                        .trim();
                if (!result.isBlank()) {
                    log.debug("LLM 生成通用 RAG 查询: bookId={}, query={}", book.getId(), result);
                    return result;
                }
            }
        } catch (Exception e) {
            log.warn("LLM 生成通用 RAG 查询失败，回退到书名+简介: bookId={} - {}", book.getId(), e.getMessage());
        }

        // 回退：书名 + 简介前200字
        String fallback = book.getTitle();
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            fallback += " " + book.getDescription().substring(0, Math.min(200, book.getDescription().length()));
        }
        return fallback;
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

            // 构建角色专属的检索查询
            String query = buildRoleSpecificQuery(book, role, history);
            log.debug("角色视角 RAG 查询: role={}, query={}", role.getKey(), query);

            List<EmbeddingMatch<TextSegment>> matches =
                    embeddingService.searchContent(query, 10, book);

            if (matches.isEmpty()) {
                log.debug("角色视角 RAG 检索无结果: bookId={}, role={}", book.getId(), role.getKey());
                return "";
            }

            matches = matches.stream()
                    .filter(m -> m.score() >= 0.1)
                    .toList();

            if (matches.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(matches.size(), 8); i++) {
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

            ChatModel chatModel = chatModelFactory.buildChatModelWithoutThinkingFromYml();
            String result = chatModel.chat(prompt);
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
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
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
