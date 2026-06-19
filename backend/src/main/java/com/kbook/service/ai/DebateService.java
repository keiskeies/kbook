package com.kbook.service.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.exception.BusinessException;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.config.ai.AiConfig;
import com.kbook.config.ai.AiConfigProvider;
import com.kbook.constants.AiPromptConstants;
import com.kbook.dto.debate.*;
import com.kbook.entity.Book;
import com.kbook.entity.debate.DebateMessage;
import com.kbook.entity.debate.DebateScore;
import com.kbook.entity.debate.DebateSession;
import com.kbook.enums.DebateRole;
import com.kbook.repository.BookRepository;
import com.kbook.repository.debate.DebateMessageRepository;
import com.kbook.repository.debate.DebateScoreRepository;
import com.kbook.repository.debate.DebateSessionRepository;
import com.kbook.service.book.BookService;
import com.kbook.service.progress.ReadingProgressService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.*;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.time.LocalDateTime;

import static com.kbook.common.util.QueryBuilder.eq;
import static com.kbook.common.util.QueryBuilder.ne;

/**
 * 奇葩说辩论服务 — AI 辩论功能核心
 * <p>
 * 职责：
 * - 辩题生成（LLM 从书籍提取 + 用户手动输入）
 * - 辩论会话管理（创建、查询、删除）
 * - 5轮辩论发言流程（开篇立论、交叉质询、驳论、自由辩论、总结陈词）
 * - 自由辩论发言人选择（LLM 驱动）
 * - 辩论轮次推进
 * <p>
 * 角色体系：roleKey 为位置键（PRO_1/CON_2/HOST），性格从 session 的 proRoleKeys/conRoleKeys 查询。
 * 每个辩手的性格在创建 session 时选定，同一性格可被多个位置选用。
 * <p>
 * 赛制参考：新国辩 4v4 赛制，分工为"一辩搭台、二辩拆台、四辩盖棺定论"。
 */
@Slf4j
@Service
public class DebateService {

    private final BookService bookService;
    private final ChatModelManager chatModelManager;
    private final DebateSessionRepository sessionRepository;
    private final DebateMessageRepository messageRepository;
    private final DebateScoringService scoringService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ReadingProgressService readingProgressService;
    private final AiConfigProvider aiConfigProvider;
    private final ExecutorService sseExecutor;
    private final BookRepository bookRepository;
    private final DebateScoreRepository debateScoreRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String DEBATE_SESSION_KEY_PREFIX = "kbook:debate:session:";
    private static final String DEBATE_TOPICS_KEY_PREFIX = "kbook:debate:topics:";
    private static final long DEBATE_TOPICS_TTL_SECONDS = 86400; // 24h
    private static final long SSE_TIMEOUT = 3600_000L;
    private static final int SUMMARY_MAX_LENGTH = 3000;

    public DebateService(
            BookService bookService,
            ChatModelManager chatModelManager,
            DebateSessionRepository sessionRepository,
            DebateMessageRepository messageRepository,
            DebateScoringService scoringService,
            StringRedisTemplate stringRedisTemplate,
            ReadingProgressService readingProgressService,
            AiConfigProvider aiConfigProvider,
            @Qualifier("sseExecutor") ExecutorService sseExecutor,
            @Lazy BookRepository bookRepository,
            @Lazy DebateScoreRepository debateScoreRepository) {
        this.bookService = bookService;
        this.chatModelManager = chatModelManager;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.scoringService = scoringService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.readingProgressService = readingProgressService;
        this.aiConfigProvider = aiConfigProvider;
        this.sseExecutor = sseExecutor;
        this.bookRepository = bookRepository;
        this.debateScoreRepository = debateScoreRepository;
    }

    // ==================== 辩题生成 ====================

    /**
     * 从书籍内容生成争议辩题（LLM 驱动），结果缓存 24h
     */
    public List<DebateTopicVO> generateTopics(Long bookId, boolean forceRefresh) {
        String cacheKey = DEBATE_TOPICS_KEY_PREFIX + bookId;

        if (!forceRefresh) {
            try {
                String cached = stringRedisTemplate.opsForValue().get(cacheKey);
                if (cached != null && !cached.isBlank()) {
                    List<DebateTopicVO> topics = objectMapper.readValue(cached,
                            new TypeReference<>() {
                            });
                    if (topics != null && !topics.isEmpty()) {
                        log.debug("辩题缓存命中: bookId={}", bookId);
                        return topics;
                    }
                }
            } catch (Exception e) {
                log.debug("辩题缓存读取失败: {}", e.getMessage());
            }
        } else {
            log.info("强制刷新辩题，跳过缓存: bookId={}", bookId);
        }

        Book book = bookService.getBookById(bookId);
        if (book == null) {
            return getFallbackTopics();
        }

        List<DebateTopicVO> topics;
        try {
            String bookInfo = buildBookInfoForTopic(book);
            String result = chatModelManager.callAiForDebateTopics(bookInfo);

            if (result != null && !result.isBlank()) {
                result = CommonUtils.stripCodeFence(result);
                topics = objectMapper.readValue(result,
                        new TypeReference<List<DebateTopicVO>>() {});
                if (topics == null || topics.isEmpty()) {
                    topics = getFallbackTopics();
                }
            } else {
                topics = getFallbackTopics();
            }
        } catch (Exception e) {
            log.warn("LLM 辩题生成失败，使用兜底话题: bookId={} - {}", bookId, e.getMessage());
            topics = getFallbackTopics();
        }

        try {
            stringRedisTemplate.opsForValue().set(cacheKey,
                    objectMapper.writeValueAsString(topics),
                    java.time.Duration.ofSeconds(DEBATE_TOPICS_TTL_SECONDS));
        } catch (Exception e) {
            log.debug("辩题缓存写入失败: {}", e.getMessage());
        }

        return topics;
    }

    private String buildBookInfoForTopic(Book book) {
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
            String tags = book.getConceptTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("核心概念：").append(tags).append("\n");
        }
        if (book.getReaderNeedTags() != null && !book.getReaderNeedTags().isBlank()) {
            String tags = book.getReaderNeedTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("读者需求：").append(tags).append("\n");
        }
        if (book.getTargetReaderTags() != null && !book.getTargetReaderTags().isBlank()) {
            String tags = book.getTargetReaderTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("目标读者：").append(tags).append("\n");
        }
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            sb.append("简介：").append(book.getDescription()).append("\n");
        }
        if (book.getToc() != null && !book.getToc().isBlank()) {
            sb.append("目录：\n").append(book.getToc()).append("\n");
        }

        // 摘要：优先 compressedSummary（LLM精炼） → chapterSummary（原始提取），均完整不截断
        if (book.getCompressedSummary() != null && !book.getCompressedSummary().isBlank()) {
            sb.append("\n【图书精炼摘要】\n").append(book.getCompressedSummary()).append("\n");
        } else if (book.getChapterSummary() != null && !book.getChapterSummary().isBlank()) {
            sb.append("\n【章节摘要】（每章核心内容概述）\n").append(book.getChapterSummary()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 使用LLM优化用户自定义辩题
     */
    public DebateTopicVO optimizeTopic(Long bookId, String topic, String proArg, String conArg) {
        Book book = bookService.getBookById(bookId);
        if (book == null) {
            return DebateTopicVO.builder()
                    .topic(topic).source("USER")
                    .proArgument(proArg).conArgument(conArg)
                    .build();
        }

        String bookInfo = buildBookInfoForTopic(book);

        try {
            String result = chatModelManager.callAiForDebateTopicOptimization(
                    bookId, topic, bookInfo,
                    proArg != null ? proArg : "",
                    conArg != null ? conArg : "");

            if (result != null && !result.isBlank()) {
                result = CommonUtils.stripCodeFence(result);
                var node = objectMapper.readTree(result);
                String optimizedTopic = node.has("topic") ? node.get("topic").asText() : topic;
                String optimizedPro = node.has("proArgument") ? node.get("proArgument").asText() : proArg;
                String optimizedCon = node.has("conArgument") ? node.get("conArgument").asText() : conArg;
                return DebateTopicVO.builder()
                        .topic(optimizedTopic).source("LLM")
                        .proArgument(optimizedPro).conArgument(optimizedCon)
                        .build();
            }
        } catch (Exception e) {
            log.warn("LLM 辩题优化失败，返回原始输入: {}", e.getMessage());
        }

        return DebateTopicVO.builder()
                .topic(topic).source("USER")
                .proArgument(proArg).conArgument(conArg)
                .build();
    }

    private List<DebateTopicVO> getFallbackTopics() {
        return List.of(
                DebateTopicVO.builder().topic("技术进步是否必然带来幸福？").source("SYSTEM")
                        .proArgument("技术进步提高了生活效率、医疗水平和信息获取能力，是人类幸福的基石")
                        .conArgument("技术进步导致隐私丧失、社会焦虑加剧、人际关系疏离").build(),
                DebateTopicVO.builder().topic("成功更多依赖天赋还是努力？").source("SYSTEM")
                        .proArgument("天赋决定上限，没有天赋再努力也难以达到顶尖水平")
                        .conArgument("努力可以弥补天赋的不足，持续的努力才是成功的关键").build(),
                DebateTopicVO.builder().topic("人工智能的发展对人类利大于弊吗？").source("SYSTEM")
                        .proArgument("AI 提升了生产效率、医疗诊断准确率，解决了很多人类难以解决的问题")
                        .conArgument("AI 会导致大规模失业、伦理困境，甚至可能威胁人类生存").build()
        );
    }

    // ==================== 会话管理 ====================

    @Transactional(rollbackFor = Exception.class)
    public DebateSessionVO createSession(Long userId, Long bookId, String topic,
                                          String topicSource, String bookContext,
                                          String proRoleKeys, String conRoleKeys) {
        String sessionId = "db-" + UUID.randomUUID().toString().replace("-", "");

        if (proRoleKeys == null || proRoleKeys.isBlank()) {
            proRoleKeys = "LOGICAL,SHARP,HUMOROUS,EMPATHETIC";
        }
        if (conRoleKeys == null || conRoleKeys.isBlank()) {
            conRoleKeys = "SENSITIVE,DOMINEERING,ERUDITE,PRACTICAL";
        }

        DebateSession session = DebateSession.builder()
                .userId(userId)
                .bookId(bookId)
                .sessionId(sessionId)
                .topic(topic)
                .topicSource(topicSource != null ? topicSource : "LLM")
                .bookContext(bookContext)
                .proRoleKeys(proRoleKeys)
                .conRoleKeys(conRoleKeys)
                .currentRound(1)
                .currentPhase("OPENING")
                .status("ACTIVE")
                .visibility("PUBLIC")
                .build();

        sessionRepository.save(session);

        stringRedisTemplate.opsForValue().set(
                DEBATE_SESSION_KEY_PREFIX + sessionId, topic);

        // 将该图书加入阅读历史（标记为讨论行为）
        readingProgressService.reportProgress(userId, bookId, 0.0, "chat");

        log.info("辩论会话已创建: sessionId={}, bookId={}, topic={}", sessionId, bookId, topic);
        return DebateSessionVO.from(session);
    }

    public List<DebateSessionVO> getSessions(Long userId, Long bookId) {
        List<DebateSession> sessions = sessionRepository.query()
                .where(DebateSession::getUserId, eq(userId))
                .and(DebateSession::getBookId, eq(bookId))
                .and(DebateSession::getStatus, ne("ABANDONED"))
                .orderByDesc(DebateSession::getCreatedAt)
                .list();
        return sessions.stream()
                .map(DebateSessionVO::from)
                .collect(Collectors.toList());
    }

    public DebateSessionVO getSession(String sessionId) {
        DebateSession session = getSessionBySessionId(sessionId);
        if (session == null) {
            throw new BusinessException("会话不存在");
        }
        return DebateSessionVO.from(session);
    }

    public List<DebateMessageVO> getHistory(Long userId, String sessionId) {
        List<DebateMessage> messages = messageRepository.query()
                .where(DebateMessage::getSessionId, eq(sessionId))
                .orderBy(DebateMessage::getId)
                .list();
        return messages.stream()
                .map(DebateMessageVO::from)
                .collect(Collectors.toList());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(Long userId, String sessionId) {
        DebateSession session = sessionRepository.query()
                .where(DebateSession::getSessionId, eq(sessionId))
                .and(DebateSession::getUserId, eq(userId))
                .list(1).stream().findFirst().orElse(null);
        if (session == null) {
            throw new BusinessException("辩论会话不存在");
        }
        session.setStatus("ABANDONED");
        sessionRepository.save(session);
        stringRedisTemplate.delete(DEBATE_SESSION_KEY_PREFIX + sessionId);
    }

    public List<DebateRoleVO> getRoles() {
        return Stream.of(DebateRole.values())
                .map(DebateRoleVO::from)
                .collect(Collectors.toList());
    }

    /**
     * 获取全局辩论会话列表（发现页）
     * 只返回公开会话或当前用户创建的会话
     */
    public Page<DebateSessionFeedVO> getGlobalSessions(int page, int size, String sort, boolean mine) {
        var pageable = PageRequest.of(page, size,
                Sort.by("hot".equals(sort)
                        ? Sort.Order.desc("updatedAt")
                        : Sort.Order.desc("createdAt")));

        // 获取当前用户ID（未登录则只能看到公开会话）
        Long currentUserId = getCurrentUserId();

        // 查询公开会话或当前用户的会话；如果 mine=true 则只查当前用户
        var sessions = mine
                ? sessionRepository.findByUserId(currentUserId, pageable)
                : sessionRepository.findPublicOrOwnSessions(pageable);

        // Get book info
        var bookIds = sessions.getContent().stream().map(DebateSession::getBookId).distinct().toList();
        var books = bookRepository.findListByIds(bookIds);
        var bookMap = books.stream().collect(Collectors.toMap(Book::getId, b -> b));

        // Get average scores per session
        var sessionIds = sessions.getContent().stream().map(DebateSession::getSessionId).toList();
        var scoreMap = new java.util.HashMap<String, Double>();
        if (!sessionIds.isEmpty()) {
            var scores = debateScoreRepository.findAllBySessionIdIn(sessionIds);
            scores.stream()
                    .collect(Collectors.groupingBy(
                            DebateScore::getSessionId,
                            Collectors.averagingDouble(s -> s.getAverageScore() != null ? s.getAverageScore() : 0)))
                    .forEach(scoreMap::put);
        }

        // 计算热度分数
        var now = LocalDateTime.now();

        return sessions.map(session -> {
            var book = bookMap.get(session.getBookId());
            var avgScore = scoreMap.getOrDefault(session.getSessionId(), 0.0);
            
            // 计算热度分数
            double hotScore = calculateHotScore(avgScore, session.getStatus(), session.getCreatedAt(), now);
            
            return DebateSessionFeedVO.builder()
                    .id(session.getId())
                    .sessionId(session.getSessionId())
                    .bookId(session.getBookId())
                    .bookTitle(book != null ? book.getTitle() : "未知书籍")
                    .bookCoverUrl(book != null ? book.getCoverUrl() : null)
                    .topic(session.getTopic())
                    .proRoleKeys(session.getProRoleKeys())
                    .conRoleKeys(session.getConRoleKeys())
                    .currentRound(session.getCurrentRound())
                    .currentPhase(session.getCurrentPhase())
                    .status(session.getStatus())
                    .visibility(session.getVisibility())
                    .isOwner(currentUserId != null && currentUserId.equals(session.getUserId()))
                    .avgScore(avgScore)
                    .hotScore(hotScore)
                    .createdAt(session.getCreatedAt())
                    .updatedAt(session.getUpdatedAt())
                    .build();
        });
    }

    /**
     * 计算热度分数
     * @param score 评分（辩论用avgScore，圆桌用coverageScore）
     * @param status 状态
     * @param createdAt 创建时间
     * @param now 当前时间
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

    // ==================== 位置 ↔ 性格 解析 ====================

    /** 从位置键获取立场 */
    private String getSideFromPositionKey(String positionKey) {
        if (positionKey == null || "HOST".equals(positionKey)) return "NEUTRAL";
        return positionKey.startsWith("PRO") ? "PRO" : "CON";
    }

    /** 从立场获取中文名 */
    private String getSideName(String side) {
        return switch (side) {
            case "PRO" -> "正方";
            case "CON" -> "反方";
            default -> "";
        };
    }

    /** 从位置键获取完整立场显示名 */
    private String getSideFull(String positionKey) {
        if (positionKey == null || "HOST".equals(positionKey)) return "主持人（中立）";
        return positionKey.startsWith("PRO") ? "正方" : "反方";
    }

    /** 从位置键获取位置标签（如 正方一辩、反方三辩） */
    private String getPositionLabel(String positionKey) {
        if (positionKey == null || "HOST".equals(positionKey)) return "主持人";
        String sideName = positionKey.startsWith("PRO") ? "正方" : "反方";
        char numChar = positionKey.charAt(positionKey.length() - 1);
        int index = numChar - '1';
        String[] slotLabels = {"一辩", "二辩", "三辩", "四辩"};
        if (index >= 0 && index < 4) {
            return sideName + slotLabels[index];
        }
        return positionKey;
    }

    /** 从 Session 和位置键获取性格键 */
    private String getPersonalityKey(DebateSession session, String positionKey) {
        if (positionKey == null || "HOST".equals(positionKey)) return "HOST";
        String[] keys;
        int index;
        if (positionKey.startsWith("PRO")) {
            keys = splitKeys(session.getProRoleKeys());
            index = Integer.parseInt(positionKey.substring(positionKey.length() - 1)) - 1;
        } else {
            keys = splitKeys(session.getConRoleKeys());
            index = Integer.parseInt(positionKey.substring(positionKey.length() - 1)) - 1;
        }
        if (index >= 0 && index < keys.length && !keys[index].isBlank()) {
            return keys[index].trim();
        }
        return "LOGICAL"; // fallback
    }

    private String[] splitKeys(String keys) {
        if (keys == null || keys.isBlank()) return new String[0];
        return keys.split(",", -1);
    }

    // ==================== 发言 SSE 核心方法 ====================

    /**
     * 开篇立论 SSE 发言 — 第1轮：HOST → PRO_1 → CON_1（仅一辩立论）
     */
    public SseEmitter streamOpeningSpeech(Long userId, Long bookId, DebateSpeakRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        String positionKey = request.getRoleKey();
        DebateSession session = getSessionBySessionId(request.getSessionId());
        if (session == null) {
            SseHelper.sendErrorAndComplete(emitter, "会话不存在: " + request.getSessionId());
            return emitter;
        }

        // 验证会话所有权：只有创建者可以发言
        if (!session.getUserId().equals(userId)) {
            SseHelper.sendErrorAndComplete(emitter, "无权操作该会话");
            return emitter;
        }

        DebateRole personality = resolvePersonality(session, positionKey);
        String side = getSideFromPositionKey(positionKey);
        String sideName = getSideName(side);
        String sideFull = getSideFull(positionKey);
        String positionLabel = getPositionLabel(positionKey);

        sseExecutor.submit(() -> {
            try {
                String systemPrompt = String.format(
                        AiPromptConstants.DEBATE_OPENING_PROMPT);

                List<ChatMessage> messages = buildSpeechMessages(
                        systemPrompt, session, request, personality,
                        side, sideName, positionLabel, sideFull, null);

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingChatModelWithoutThinking();
                if (streamingChatModel == null) {
                    SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                streamResponse(emitter, streamingChatModel, messages, userId, bookId, session,
                        personality, positionKey, request);
            } catch (Exception e) {
                log.error("开篇立论发言失败: {}", e.getMessage(), e);
                SseHelper.sendErrorAndComplete(emitter, SseHelper.extractFriendlyError(e));
            }
        });

        return emitter;
    }

    /**
     * 奇袭攻辩 SSE 发言 — 已废弃（被 CROSS_EXAM + REBUTTAL 取代）
     * @deprecated 使用 streamCrossExamSpeech / streamRebuttalSpeech
     */
    @Deprecated
    public SseEmitter streamAttackSpeech(Long userId, Long bookId, DebateSpeakRequest request, String opponentSpeech) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        String positionKey = request.getRoleKey();
        DebateSession session = getSessionBySessionId(request.getSessionId());
        if (session == null) {
            SseHelper.sendErrorAndComplete(emitter, "会话不存在: " + request.getSessionId());
            return emitter;
        }

        // 验证会话所有权：只有创建者可以发言
        if (!session.getUserId().equals(userId)) {
            SseHelper.sendErrorAndComplete(emitter, "无权操作该会话");
            return emitter;
        }

        DebateRole personality = resolvePersonality(session, positionKey);
        String side = getSideFromPositionKey(positionKey);
        String sideName = getSideName(side);
        String sideFull = getSideFull(positionKey);
        String positionLabel = getPositionLabel(positionKey);

        sseExecutor.submit(() -> {
            try {
                String systemPrompt = String.format(
                        AiPromptConstants.DEBATE_ATTACK_PROMPT);

                List<ChatMessage> messages = buildSpeechMessages(
                        systemPrompt, session, request, personality,
                        side, sideName, positionLabel, sideFull, opponentSpeech);

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingChatModelWithoutThinking();
                if (streamingChatModel == null) {
                    SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                streamResponse(emitter, streamingChatModel, messages, userId, bookId, session,
                        personality, positionKey, request);
            } catch (Exception e) {
                log.error("奇袭攻辩发言失败: {}", e.getMessage(), e);
                SseHelper.sendErrorAndComplete(emitter, SseHelper.extractFriendlyError(e));
            }
        });

        return emitter;
    }

    // ==================== 新增：交叉质询 + 驳论 ====================

    /**
     * 交叉质询 SSE 发言 — 第2轮 CROSS_EXAM
     * <p>
     * 质询方(QUESTIONER)只能提问，不能陈述观点；
     * 被质询方(ANSWERER)只能回答，不能反问或反击。
     *
     * @param defenderOpening 被质询方的开篇立论全文（质询方用来找漏洞）
     * @param questionContent 质询方的问题内容（被质询方用来回答）
     */
    public SseEmitter streamCrossExamSpeech(Long userId, Long bookId, DebateSpeakRequest request,
                                            String defenderOpening, String questionContent) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        String positionKey = request.getRoleKey();
        String examRole = request.getExamRole();
        DebateSession session = getSessionBySessionId(request.getSessionId());
        if (session == null) {
            SseHelper.sendErrorAndComplete(emitter, "会话不存在: " + request.getSessionId());
            return emitter;
        }

        // 验证会话所有权：只有创建者可以发言
        if (!session.getUserId().equals(userId)) {
            SseHelper.sendErrorAndComplete(emitter, "无权操作该会话");
            return emitter;
        }

        DebateRole personality = resolvePersonality(session, positionKey);
        String side = getSideFromPositionKey(positionKey);
        String sideName = getSideName(side);
        String sideFull = getSideFull(positionKey);
        String positionLabel = getPositionLabel(positionKey);

        sseExecutor.submit(() -> {
            try {
                // 根据质询角色选择不同的系统提示词
                boolean isQuestioner = "QUESTIONER".equals(examRole);
                String systemPrompt = isQuestioner
                        ? AiPromptConstants.DEBATE_CROSS_EXAM_QUESTIONER_PROMPT
                        : AiPromptConstants.DEBATE_CROSS_EXAM_ANSWERER_PROMPT;

                // 构建上下文
                String extraContent;
                if (isQuestioner) {
                    extraContent = "【对方一辩立论全文 — 请针对以下内容提出质询问题】\n" + defenderOpening;
                } else {
                    extraContent = "【你的立论全文 — 请坚守以下立场回答】\n" + defenderOpening
                            + "\n\n【对方的问题】\n" + questionContent;
                }

                List<ChatMessage> messages = buildSpeechMessages(
                        systemPrompt, session, request, personality,
                        side, sideName, positionLabel, sideFull, extraContent);

                // 交叉质询不需要对话记录中的角色设定冲突 — 用专门的 output prompt
                // 替换最后一条消息 (outputPrompt + speakInstruction) 为专门的质询指令
                String examOutput = isQuestioner
                        ? AiPromptConstants.DEBATE_CROSS_EXAM_QUESTIONER_OUTPUT
                        : AiPromptConstants.DEBATE_CROSS_EXAM_ANSWERER_OUTPUT;
                String examInstruction = isQuestioner
                        ? "你是质询方，请向对方一辩提出一个尖锐的问题。只能提问，不能陈述观点。"
                        : "你正在被对方二辩质询。请正面回答对方的问题。只能回答，绝不能反问或反击。坚守你在一辩立论中的立场。";
                // 替换最后一条 UserMessage
                messages.remove(messages.size() - 1);
                messages.add(UserMessage.from(examOutput + "\n\n" + examInstruction));

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingChatModelWithoutThinking();
                if (streamingChatModel == null) {
                    SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                streamResponse(emitter, streamingChatModel, messages, userId, bookId, session,
                        personality, positionKey, request);
            } catch (Exception e) {
                log.error("交叉质询发言失败: {}", e.getMessage(), e);
                SseHelper.sendErrorAndComplete(emitter, SseHelper.extractFriendlyError(e));
            }
        });

        return emitter;
    }

    /**
     * 驳论 SSE 发言 — 第3轮 REBUTTAL
     * <p>
     * 二辩集中反驳对方一辩的立论，回应质询环节暴露的问题。
     *
     * @param opponentOpening 对方一辩的立论全文
     * @param crossExamContext 质询环节中对方回答的摘要
     */
    public SseEmitter streamRebuttalSpeech(Long userId, Long bookId, DebateSpeakRequest request,
                                           String opponentOpening, String crossExamContext) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        String positionKey = request.getRoleKey();
        DebateSession session = getSessionBySessionId(request.getSessionId());
        if (session == null) {
            SseHelper.sendErrorAndComplete(emitter, "会话不存在: " + request.getSessionId());
            return emitter;
        }

        // 验证会话所有权：只有创建者可以发言
        if (!session.getUserId().equals(userId)) {
            SseHelper.sendErrorAndComplete(emitter, "无权操作该会话");
            return emitter;
        }

        DebateRole personality = resolvePersonality(session, positionKey);
        String side = getSideFromPositionKey(positionKey);
        String sideName = getSideName(side);
        String sideFull = getSideFull(positionKey);
        String positionLabel = getPositionLabel(positionKey);

        sseExecutor.submit(() -> {
            try {
                String systemPrompt = AiPromptConstants.DEBATE_REBUTTAL_PROMPT;

                String extraContent = "【对方一辩立论 — 请集中火力反驳以下论证】\n" + opponentOpening
                        + "\n\n【质询环节摘要】\n" + crossExamContext;

                List<ChatMessage> messages = buildSpeechMessages(
                        systemPrompt, session, request, personality,
                        side, sideName, positionLabel, sideFull, extraContent);

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingChatModelWithoutThinking();
                if (streamingChatModel == null) {
                    SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                streamResponse(emitter, streamingChatModel, messages, userId, bookId, session,
                        personality, positionKey, request);
            } catch (Exception e) {
                log.error("驳论发言失败: {}", e.getMessage(), e);
                SseHelper.sendErrorAndComplete(emitter, SseHelper.extractFriendlyError(e));
            }
        });

        return emitter;
    }

    /**
     * 自由辩论 SSE 发言 — 第4轮：抢麦制，PRO 方先发起
     */
    public SseEmitter streamFreeSpeech(Long userId, Long bookId, DebateSpeakRequest request, String lastSpeech) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        String positionKey = request.getRoleKey();
        DebateSession session = getSessionBySessionId(request.getSessionId());
        if (session == null) {
            SseHelper.sendErrorAndComplete(emitter, "会话不存在: " + request.getSessionId());
            return emitter;
        }

        // 验证会话所有权：只有创建者可以发言
        if (!session.getUserId().equals(userId)) {
            SseHelper.sendErrorAndComplete(emitter, "无权操作该会话");
            return emitter;
        }

        DebateRole personality = resolvePersonality(session, positionKey);
        String side = getSideFromPositionKey(positionKey);
        String sideName = getSideName(side);
        String sideFull = getSideFull(positionKey);
        String positionLabel = getPositionLabel(positionKey);

        sseExecutor.submit(() -> {
            try {

                String systemPrompt = String.format(
                        AiPromptConstants.DEBATE_FREE_PROMPT);

                List<ChatMessage> messages = buildSpeechMessages(
                        systemPrompt, session, request, personality,
                        side, sideName, positionLabel, sideFull, lastSpeech);

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingChatModelWithoutThinking();
                if (streamingChatModel == null) {
                    SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                streamResponse(emitter, streamingChatModel, messages, userId, bookId, session,
                        personality, positionKey, request);
            } catch (Exception e) {
                log.error("自由辩论发言失败: {}", e.getMessage(), e);
                SseHelper.sendErrorAndComplete(emitter, SseHelper.extractFriendlyError(e));
            }
        });

        return emitter;
    }

    /**
     * 总结陈词 SSE 发言 — 第5轮：CON_4 → PRO_4（反方先结，正方后结）
     */
    public SseEmitter streamClosingSpeech(Long userId, Long bookId, DebateSpeakRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        String positionKey = request.getRoleKey();
        DebateSession session = getSessionBySessionId(request.getSessionId());
        if (session == null) {
            SseHelper.sendErrorAndComplete(emitter, "会话不存在: " + request.getSessionId());
            return emitter;
        }

        // 验证会话所有权：只有创建者可以发言
        if (!session.getUserId().equals(userId)) {
            SseHelper.sendErrorAndComplete(emitter, "无权操作该会话");
            return emitter;
        }

        DebateRole personality = resolvePersonality(session, positionKey);
        String side = getSideFromPositionKey(positionKey);
        String sideName = getSideName(side);
        String sideFull = getSideFull(positionKey);
        String positionLabel = getPositionLabel(positionKey);

        sseExecutor.submit(() -> {
            try {
                // 构建全场辩论摘要
                String debateSummary = buildDebateSummary(session);

                String systemPrompt = String.format(
                        AiPromptConstants.DEBATE_CLOSING_PROMPT);

                List<ChatMessage> messages = buildSpeechMessages(
                        systemPrompt, session, request, personality,
                        side, sideName, positionLabel, sideFull, debateSummary);

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingChatModelWithoutThinking();
                if (streamingChatModel == null) {
                    SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                streamResponse(emitter, streamingChatModel, messages, userId, bookId, session,
                        personality, positionKey, request);
            } catch (Exception e) {
                log.error("总结陈词发言失败: {}", e.getMessage(), e);
                SseHelper.sendErrorAndComplete(emitter, SseHelper.extractFriendlyError(e));
            }
        });

        return emitter;
    }

    // ==================== 主持人即兴点评 ====================

    /**
     * 主持人即兴点评 SSE — 异步、非阻塞、允许失败
     * <p>
     * 提示词顺序设计（最大化缓存命中）：
     * 1. SystemMessage: 静态点评规则（DEBATE_HOST_COMMENTARY_SYSTEM_PROMPT）
     * 2. SystemMessage: 静态 HOST 人设（DebateRole.HOST.prompt）
     * 3. UserMessage: 动态上下文（点评类型 + 辩论近期发言）
     * <p>
     * 前两条在每次调用中完全相同，LLM 提供商可缓存 KV，大幅降低延迟和成本。
     *
     * @param sessionId  会话 ID
     * @param type       点评类型：TRANSITION / FREE_MID / WRAPUP
     * @param context    动态上下文（环节过渡说明、近期交锋摘要等）
     */
    public SseEmitter streamHostCommentary(Long userId, String sessionId, String type, String context) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        DebateSession session = getSessionBySessionId(sessionId);
        if (session == null) {
            SseHelper.sendErrorAndComplete(emitter, "会话不存在: " + sessionId);
            return emitter;
        }
        if (!session.getUserId().equals(userId)) {
            SseHelper.sendErrorAndComplete(emitter, "无权操作该会话");
            return emitter;
        }

        sseExecutor.submit(() -> {
            try {
                // 按缓存命中率排序：静态规则 → 静态人设 → 动态上下文
                List<ChatMessage> messages = new ArrayList<>();
                messages.add(SystemMessage.from(AiPromptConstants.DEBATE_HOST_COMMENTARY_SYSTEM_PROMPT));
                messages.add(SystemMessage.from(DebateRole.HOST.getPrompt()));

                StringBuilder userCtx = new StringBuilder();
                userCtx.append("【点评类型】").append(type).append("\n");
                userCtx.append("【辩题】").append(session.getTopic()).append("\n");
                if (context != null && !context.isBlank()) {
                    userCtx.append("【点评上下文】\n").append(context);
                }
                messages.add(UserMessage.from(userCtx.toString()));

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingChatModelWithoutThinking();
                if (streamingChatModel == null) {
                    SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                final boolean[] connectionClosed = {false};
                StringBuilder fullResponse = new StringBuilder();

                streamingChatModel.chat(messages, new StreamingChatResponseHandler() {
                    StreamingHandle streamingHandle;

                    @Override
                    public void onPartialThinking(PartialThinking partialThinking) {}

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
                                    Map.of("roleKey", "HOST", "text", text));
                            if (!SseHelper.safeSendEvent(emitter, "message", json)) {
                                connectionClosed[0] = true;
                                if (streamingHandle != null) streamingHandle.cancel();
                            }
                        } catch (Exception e) {
                            connectionClosed[0] = true;
                            if (streamingHandle != null) streamingHandle.cancel();
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        if (connectionClosed[0]) return;
                        if (Thread.currentThread().isInterrupted()) return;

                        String content = fullResponse.toString().trim();
                        if (!content.isBlank()) {
                            try {
                                DebateMessage record = DebateMessage.builder()
                                        .userId(userId)
                                        .sessionId(sessionId)
                                        .bookId(session.getBookId())
                                        .roleKey("HOST")
                                        .roleName("主持人")
                                        .positionKey("HOST")
                                        .side("NEUTRAL")
                                        .content(content)
                                        .roundNumber(session.getCurrentRound())
                                        .roundType("HOST_" + type)
                                        .phaseOrder(0)
                                        .build();
                                messageRepository.save(record);
                            } catch (Exception e) {
                                log.warn("保存主持人点评失败: {}", e.getMessage());
                            }
                        }

                        try {
                            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                            emitter.complete();
                        } catch (Exception e) {
                            log.warn("发送 SSE done 事件失败: {}", e.getMessage());
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.warn("主持人点评 SSE 流错误: type={} - {}", type, error.getMessage());
                        SseHelper.sendErrorAndComplete(emitter, SseHelper.extractFriendlyError(error));
                    }
                });
            } catch (Exception e) {
                log.error("主持人点评失败: type={} - {}", type, e.getMessage(), e);
                SseHelper.sendErrorAndComplete(emitter, SseHelper.extractFriendlyError(e));
            }
        });

        return emitter;
    }

    /** 查询性格（带兜底） */
    private DebateRole resolvePersonality(DebateSession session, String positionKey) {
        String personalityKey = getPersonalityKey(session, positionKey);
        DebateRole personality = DebateRole.fromKey(personalityKey);
        if (personality == null) {
            log.warn("性格键无效，使用兜底: personalityKey={}", personalityKey);
            personality = DebateRole.fromKey("LOGICAL");
        }
        return personality;
    }

    // ==================== 发言消息构建 ====================

    /**
     * 构建发言消息列表
     */
    private List<ChatMessage> buildSpeechMessages(
            String systemPrompt, DebateSession session,
            DebateSpeakRequest request, DebateRole personality,
            String side, String sideName, String positionLabel, String sideFull,
            String extraContent) {

        List<ChatMessage> messages = new ArrayList<>();

        // 1. 系统提示词（仅共享规则）
        messages.add(SystemMessage.from(systemPrompt));

        // 2. 辩题 + 立场 + 外部知识（替代原书籍上下文 RAG）
        StringBuilder topicInfo = new StringBuilder();
        topicInfo.append("【当前辩题】\n").append(session.getTopic()).append("\n");
        topicInfo.append("【你的立场】\n").append(sideFull).append("\n");

        String externalKnowledge = generateExternalKnowledgeForDebate(session, personality, side);
        if (externalKnowledge != null && !externalKnowledge.isBlank()) {
            topicInfo.append("【外部参考知识】\n以下知识可帮助你论证：\n").append(externalKnowledge).append("\n");
        }

        messages.add(UserMessage.from(topicInfo.toString()));

        // 3. 对话记录（共享）
        List<DebateMessage> history = messageRepository.query()
                .where(DebateMessage::getSessionId, eq(request.getSessionId()))
                .orderBy(DebateMessage::getId)
                .list();

        boolean hasHistory = !history.isEmpty();
        boolean hasExtra = extraContent != null && !extraContent.isBlank();
        if (hasHistory || hasExtra) {
            StringBuilder historyBuilder = new StringBuilder("【对话记录】\n");
            for (DebateMessage msg : history) {
                historyBuilder.append(msg.getRoleName()).append("：").append(msg.getContent()).append("\n\n");
            }
            if (hasExtra) {
                historyBuilder.append("当前上下文：").append(extraContent);
            }
            messages.add(UserMessage.from(historyBuilder.toString()));
        }

        // 4. 角色设定（每个角色不同 — KV 缓存在此处分叉）
        boolean isHost = "HOST".equals(request.getRoleKey());

        // 优先从外部配置获取性格标题和提示词，配置不存在则回退到枚举
        AiConfig.DebatePersonality configPersonality = aiConfigProvider.getDebatePersonality(personality.getKey());
        String personalityTitle = configPersonality != null ? configPersonality.getTitle() : personality.getTitle();
        String personalityPrompt = configPersonality != null ? configPersonality.getPrompt() : personality.getPrompt();

        String roleSetting = String.format(
                AiPromptConstants.DEBATE_ROLE_SETTING,
                isHost ? "" : sideName,
                isHost ? "主持人" : positionLabel,
                personalityTitle,
                sideFull,
                personalityPrompt);
        messages.add(UserMessage.from(roleSetting));

        // 5. 输出要求 + 发言指令（每个角色相同，按轮次类型区分）
        String outputPrompt = getOutputPrompt(request.getRoundType());
        String speakInstruction = isHost
                ? "请以主持人的身份发言。引导本环节、点评精彩观点、为下一位辩手铺垫。"
                : "请以" + positionLabel + "的身份发言。"
                + ("PRO".equals(side) ? "坚定维护正方立场。" : "CON".equals(side) ? "坚定维护反方立场。" : "")
                + "直接说出你的观点，不要复述自己的角色设定。";
        messages.add(UserMessage.from(outputPrompt + "\n\n" + speakInstruction));

        return messages;
    }

    private String getOutputPrompt(String roundType) {
        return switch (roundType) {
            case "CROSS_EXAM" -> AiPromptConstants.DEBATE_CROSS_EXAM_QUESTIONER_OUTPUT; // fallback
            case "REBUTTAL" -> AiPromptConstants.DEBATE_REBUTTAL_OUTPUT;
            case "FREE" -> AiPromptConstants.DEBATE_FREE_OUTPUT;
            case "CLOSING" -> AiPromptConstants.DEBATE_CLOSING_OUTPUT;
            // ATTACK 已废弃，被 CROSS_EXAM + REBUTTAL 取代
            default -> AiPromptConstants.DEBATE_OPENING_OUTPUT;
        };
    }

    // ==================== 流式响应处理 ====================

    private void streamResponse(
            SseEmitter emitter, StreamingChatModel streamingChatModel,
            List<ChatMessage> messages, Long userId, Long bookId,
            DebateSession session, DebateRole personality, String positionKey,
            DebateSpeakRequest request) {

        final boolean[] connectionClosed = {false};
        StringBuilder fullResponse = new StringBuilder();

        streamingChatModel.chat(messages, new StreamingChatResponseHandler() {
            StreamingHandle streamingHandle;

            @Override
            public void onPartialThinking(dev.langchain4j.model.chat.response.PartialThinking partialThinking) {
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
                            Map.of("roleKey", positionKey, "text", text));
                    if (!SseHelper.safeSendEvent(emitter, "message", json)) {
                        connectionClosed[0] = true;
                        if (streamingHandle != null) streamingHandle.cancel();
                    }
                } catch (Exception e) {
                    connectionClosed[0] = true;
                    if (streamingHandle != null) streamingHandle.cancel();
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                if (connectionClosed[0]) {
                    String content = fullResponse.toString().trim();
                    if (!content.isBlank()) {
                        saveMessage(userId, bookId, session, personality, positionKey, request, content);
                    }
                    return;
                }
                if (Thread.currentThread().isInterrupted()) return;

                String content = fullResponse.toString().trim();
                if (!content.isBlank()) {
                    int phaseOrder = saveMessage(userId, bookId, session, personality, positionKey, request, content);

                    String side = getSideFromPositionKey(positionKey);
                    scoringService.scoreSpeechAsync(userId, request.getSessionId(),
                            personality.getKey(), positionKey, side, content,
                            request.getRoundNumber(), request.getRoundType());
                }

                try {
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                    emitter.complete();
                } catch (Exception e) {
                    log.warn("发送 SSE done 事件失败: {}", e.getMessage());
                }
            }

            @Override
            public void onError(Throwable error) {
                log.error("辩论发言 SSE 流错误: positionKey={} - {}", positionKey, error.getMessage());
                String content = fullResponse.toString().trim();
                if (!content.isBlank()) {
                    saveMessage(userId, bookId, session, personality, positionKey, request, content);
                }
                SseHelper.sendErrorAndComplete(emitter, SseHelper.extractFriendlyError(error));
            }
        });
    }

    // ==================== 消息持久化 ====================

    private int saveMessage(Long userId, Long bookId, DebateSession session,
                            DebateRole personality, String positionKey,
                            DebateSpeakRequest request, String content) {
        try {
            List<DebateMessage> roundMessages = messageRepository.findBySessionIdAndRoundNumberOrderByPhaseOrder(
                    request.getSessionId(), request.getRoundNumber());
            int phaseOrder = roundMessages.size() + 1;

            String side = getSideFromPositionKey(positionKey);

            DebateMessage record = DebateMessage.builder()
                    .userId(userId)
                    .sessionId(request.getSessionId())
                    .bookId(bookId)
                    .roleKey(personality.getKey())
                    .roleName(personality.getName())
                    .positionKey(positionKey)
                    .side(side)
                    .content(content)
                    .roundNumber(request.getRoundNumber())
                    .roundType(request.getRoundType())
                    .phaseOrder(phaseOrder)
                    .examRole(request.getExamRole())
                    .build();
            messageRepository.save(record);
            return phaseOrder;
        } catch (Exception e) {
            log.warn("保存辩论消息失败: {}", e.getMessage());
            return 0;
        }
    }

    // ==================== 自由辩论发言人选择 ====================

    /**
     * LLM 决定自由辩论下一发言人
     */
    public String getNextSpeakerFree(Long userId, String sessionId) {
        DebateSession session = getSessionBySessionId(sessionId);
        if (session == null) return null;
        // 验证会话所有权
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("无权操作该会话");
        }

        List<DebateMessage> allMessages = messageRepository.findBySessionIdOrderById(sessionId);
        List<DebateMessage> roundMessages = messageRepository.findBySessionIdAndRoundNumberOrderByPhaseOrder(
                sessionId, session.getCurrentRound());

        // 统计发言次数（按位置键统计）
        Map<String, Long> speakCounts = allMessages.stream()
                .collect(Collectors.groupingBy(
                        m -> m.getPositionKey() != null ? m.getPositionKey() : m.getRoleKey(),
                        Collectors.counting()));

        // 角色信息
        StringBuilder rolesInfo = new StringBuilder();
        for (String positionKey : getAllPositionKeys()) {
            if (!"HOST".equals(positionKey)) {
                String side = getSideFromPositionKey(positionKey);
                rolesInfo.append(positionKey).append("(")
                        .append(getSideName(side)).append(") - ")
                        .append("发言").append(
                                speakCounts.getOrDefault(positionKey, 0L)).append("次\n");
            }
        }

        // 上一位发言者
        DebateMessage lastMsg = roundMessages.isEmpty() ? null : roundMessages.get(roundMessages.size() - 1);
        String lastSpeaker = lastMsg != null ? (lastMsg.getPositionKey() != null ? lastMsg.getPositionKey() : lastMsg.getRoleKey()) : "无";
        String lastSide = lastMsg != null ? lastMsg.getSide() : "";

        // 发言次数统计
        StringBuilder countInfo = new StringBuilder();
        for (String positionKey : getAllPositionKeys()) {
            if (!"HOST".equals(positionKey)) {
                countInfo.append(positionKey).append(": ").append(
                        speakCounts.getOrDefault(positionKey, 0L)).append("次\n");
            }
        }

        try {
            String result = chatModelManager.callAiForFreeDebaterSelection(
                    sessionId, session.getTopic(),
                    rolesInfo.toString(), lastSpeaker, lastSide,
                    countInfo.toString());

            if (result != null && !result.isBlank()) {
                result = result.trim();
                // 验证位置键有效性
                if (isValidPositionKey(result)) {
                    // 验证交替：不能与上一位同方
                    if (lastSide.isEmpty() || !lastSide.equals(getSideFromPositionKey(result))) {
                        return result;
                    }
                    // 同方 → 回退到对方发言最少的辩手
                    log.warn("LLM 选择了同方辩手 {}，强制交替到对方", result);
                }
            }
        } catch (Exception e) {
            log.warn("LLM 发言人选择失败: {}", e.getMessage());
        }

        // 回退：选取对方发言次数最少的非 HOST 辩手（保证交替）
        String oppositeSide = "PRO".equals(lastSide) ? "CON" : "CON".equals(lastSide) ? "PRO" : null;
        return getLeastSpokenSpeakerBySide(speakCounts, Objects.requireNonNullElse(oppositeSide, "PRO"));
    }

    /**
     * 按方筛选：获取指定立场发言次数最少的辩手
     */
    private String getLeastSpokenSpeakerBySide(Map<String, Long> speakCounts, String side) {
        String leastSpeaker = null;
        long minCount = Long.MAX_VALUE;

        for (String positionKey : getAllPositionKeys()) {
            if ("HOST".equals(positionKey)) continue;
            if (!side.equals(getSideFromPositionKey(positionKey))) continue;
            long count = speakCounts.getOrDefault(positionKey, 0L);
            if (count < minCount) {
                minCount = count;
                leastSpeaker = positionKey;
            }
        }
        return leastSpeaker;
    }

    /** 获取所有位置键 */
    private String[] getAllPositionKeys() {
        return new String[]{"HOST", "PRO_1", "PRO_2", "PRO_3", "PRO_4", "CON_1", "CON_2", "CON_3", "CON_4"};
    }

    /** 校验位置键是否有效 */
    private boolean isValidPositionKey(String key) {
        if (key == null) return false;
        for (String pk : getAllPositionKeys()) {
            if (pk.equals(key)) return true;
        }
        return false;
    }

    // ==================== 轮次推进 ====================

    @Transactional(rollbackFor = Exception.class)
    public DebateSessionVO advanceRound(Long userId, String sessionId) {
        DebateSession session = getSessionBySessionId(sessionId);
        if (session == null) {
            throw new BusinessException("辩论会话不存在");
        }
        // 验证会话所有权
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("无权操作该会话");
        }

        String currentPhase = session.getCurrentPhase();
        int currentRound = session.getCurrentRound();

        String nextPhase;
        int nextRound;
        boolean completed = false;

        switch (currentPhase) {
            case "OPENING" -> {
                nextPhase = "CROSS_EXAM";
                nextRound = 2;
            }
            case "CROSS_EXAM" -> {
                nextPhase = "REBUTTAL";
                nextRound = 3;
            }
            case "REBUTTAL" -> {
                nextPhase = "FREE";
                nextRound = 4;
            }
            case "FREE" -> {
                nextPhase = "CLOSING";
                nextRound = 5;
            }
            case "CLOSING" -> {
                nextPhase = "CLOSING";
                nextRound = 5;
                completed = true;
            }
            default -> {
                nextPhase = "OPENING";
                nextRound = 1;
            }
        }

        if (completed) {
            session.setStatus("COMPLETED");
        } else {
            session.setCurrentRound(nextRound);
            session.setCurrentPhase(nextPhase);
        }
        sessionRepository.save(session);

        log.info("辩论轮次推进: sessionId={}, round={}→{}, phase={}→{}, status={}",
                sessionId, currentRound, session.getCurrentRound(),
                currentPhase, session.getCurrentPhase(), session.getStatus());
        return DebateSessionVO.from(session);
    }

    /** @deprecated 使用 advanceRound 中的显式 switch */
    @Deprecated
    private String getPhaseForRound(int round) {
        return switch (round) {
            case 1 -> "OPENING";
            case 2 -> "CROSS_EXAM";
            case 3 -> "REBUTTAL";
            case 4 -> "FREE";
            case 5 -> "CLOSING";
            default -> "OPENING";
        };
    }

    // ==================== 辅助方法 ====================

    private DebateSession getSessionBySessionId(String sessionId) {
        return sessionRepository.query()
                .where(DebateSession::getSessionId, eq(sessionId))
                .list(1).stream().findFirst().orElse(null);
    }

    /**
     * 构建辩论摘要（用于总结陈词）
     */
    private String buildDebateSummary(DebateSession session) {
        List<DebateMessage> allMessages = messageRepository.findBySessionIdOrderById(session.getSessionId());
        if (allMessages.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (DebateMessage msg : allMessages) {
            String sideLabel = switch (msg.getSide()) {
                case "PRO" -> "[正方]";
                case "CON" -> "[反方]";
                default -> "[主持]";
            };
            sb.append(sideLabel).append(msg.getRoleName()).append("：").append(msg.getContent()).append("\n\n");
        }
        return sb.length() > SUMMARY_MAX_LENGTH
                ? sb.substring(0, SUMMARY_MAX_LENGTH) + "..."
                : sb.toString();
    }

    /**
     * 为辩论生成外部知识
     */
    private String generateExternalKnowledgeForDebate(DebateSession session, DebateRole personality, String side) {
        try {
            String topic = session.getTopic();
            String stance = "PRO".equals(side) ? "支持该观点" : "反对该观点";
            String personalityContext = personality.getTitle() + "：" + personality.getPrompt().substring(0, Math.min(200, personality.getPrompt().length()));
            return chatModelManager.generateDebateExternalKnowledge(topic, stance, personalityContext);
        } catch (Exception e) {
            log.debug("生成辩论外部知识失败: session={}, error={}", session.getSessionId(), e.getMessage());
            return "";
        }
    }

}
