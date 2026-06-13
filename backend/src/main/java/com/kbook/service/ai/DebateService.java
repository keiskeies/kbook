package com.kbook.service.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.exception.BusinessException;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.constants.AiPromptConstants;
import com.kbook.dto.book.BookProjection;
import com.kbook.dto.debate.DebateMessageVO;
import com.kbook.dto.debate.DebateRoleVO;
import com.kbook.dto.debate.DebateSessionVO;
import com.kbook.dto.debate.DebateSpeakRequest;
import com.kbook.dto.debate.DebateTopicVO;
import com.kbook.entity.Book;
import com.kbook.entity.debate.DebateMessage;
import com.kbook.entity.debate.DebateSession;
import com.kbook.enums.DebateRole;
import com.kbook.repository.debate.DebateMessageRepository;
import com.kbook.repository.debate.DebateSessionRepository;
import com.kbook.service.book.BookService;
import com.kbook.service.progress.ReadingProgressService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

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
    private final ExecutorService sseExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String DEBATE_SESSION_KEY_PREFIX = "kbook:debate:session:";
    private static final String DEBATE_TOPICS_KEY_PREFIX = "kbook:debate:topics:";
    private static final long DEBATE_TOPICS_TTL_SECONDS = 86400; // 24h
    private static final long SSE_TIMEOUT = 3600_000L;
    private static final int SUMMARY_MAX_LENGTH = 3000;
    private static final int MAX_ROUND = 5;

    public DebateService(
            BookService bookService,
            ChatModelManager chatModelManager,
            DebateSessionRepository sessionRepository,
            DebateMessageRepository messageRepository,
            DebateScoringService scoringService,
            StringRedisTemplate stringRedisTemplate,
            ReadingProgressService readingProgressService,
            @Qualifier("sseExecutor") ExecutorService sseExecutor) {
        this.bookService = bookService;
        this.chatModelManager = chatModelManager;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.scoringService = scoringService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.readingProgressService = readingProgressService;
        this.sseExecutor = sseExecutor;
    }

    // ==================== 辩题生成 ====================

    /**
     * 从书籍内容生成争议辩题（LLM 驱动），结果缓存 24h
     */
    public List<DebateTopicVO> generateTopics(Long bookId) {
        String cacheKey = DEBATE_TOPICS_KEY_PREFIX + bookId;
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

        Book book = bookService.getBookById(bookId);
        if (book == null) {
            return getFallbackTopics();
        }

        List<DebateTopicVO> topics;
        try {
            String bookInfo = buildBookInfoForTopic(book);
            String prompt = String.format(AiPromptConstants.DEBATE_TOPIC_GENERATION_PROMPT, bookInfo);

            String result = chatModelManager.callAi(
                    "辩论辩题生成",
                    String.format("bookId=%d, title=%s", bookId, book.getTitle()),
                    prompt);

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
        String prompt = String.format(AiPromptConstants.DEBATE_OPTIMIZE_TOPIC_PROMPT,
                bookInfo, topic != null ? topic : "",
                proArg != null ? proArg : "", conArg != null ? conArg : "");

        try {
            String result = chatModelManager.callAi(
                    "辩论辩题优化",
                    String.format("bookId=%d, topic=%s", bookId, topic != null ? topic : ""),
                    prompt);

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
                .and(DebateMessage::getUserId, eq(userId))
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
        return List.of(DebateRole.values()).stream()
                .map(DebateRoleVO::from)
                .collect(Collectors.toList());
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

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingChatModel();
                if (streamingChatModel == null) {
                    SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                streamResponse(emitter, streamingChatModel, messages, userId, bookId, session,
                        personality, positionKey, request, null);
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

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingChatModel();
                if (streamingChatModel == null) {
                    SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                streamResponse(emitter, streamingChatModel, messages, userId, bookId, session,
                        personality, positionKey, request, opponentSpeech);
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

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingChatModel();
                if (streamingChatModel == null) {
                    SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                streamResponse(emitter, streamingChatModel, messages, userId, bookId, session,
                        personality, positionKey, request, extraContent);
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

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingChatModel();
                if (streamingChatModel == null) {
                    SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                streamResponse(emitter, streamingChatModel, messages, userId, bookId, session,
                        personality, positionKey, request, extraContent);
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

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingChatModel();
                if (streamingChatModel == null) {
                    SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                streamResponse(emitter, streamingChatModel, messages, userId, bookId, session,
                        personality, positionKey, request, lastSpeech);
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

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingChatModel();
                if (streamingChatModel == null) {
                    SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                streamResponse(emitter, streamingChatModel, messages, userId, bookId, session,
                        personality, positionKey, request, debateSummary);
            } catch (Exception e) {
                log.error("总结陈词发言失败: {}", e.getMessage(), e);
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

        // 2. 书籍上下文 + 辩题（共享）
        StringBuilder topicInfo = new StringBuilder();
        if (session.getBookContext() != null && !session.getBookContext().isBlank()) {
            topicInfo.append("【书籍上下文】\n").append(session.getBookContext()).append("\n\n");
        }
        topicInfo.append("【当前辩题】\n").append(session.getTopic());
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
        String roleSetting = String.format(
                AiPromptConstants.DEBATE_ROLE_SETTING,
                isHost ? "" : sideName,
                isHost ? "主持人" : positionLabel,
                personality.getTitle(),
                sideFull,
                personality.getPrompt());
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
            case "OPENING" -> AiPromptConstants.DEBATE_OPENING_OUTPUT;
            case "CROSS_EXAM" -> AiPromptConstants.DEBATE_CROSS_EXAM_QUESTIONER_OUTPUT; // fallback
            case "REBUTTAL" -> AiPromptConstants.DEBATE_REBUTTAL_OUTPUT;
            case "FREE" -> AiPromptConstants.DEBATE_FREE_OUTPUT;
            case "CLOSING" -> AiPromptConstants.DEBATE_CLOSING_OUTPUT;
            case "ATTACK" -> AiPromptConstants.DEBATE_ATTACK_OUTPUT; // deprecated
            default -> AiPromptConstants.DEBATE_OPENING_OUTPUT;
        };
    }

    // ==================== 流式响应处理 ====================

    private void streamResponse(
            SseEmitter emitter, StreamingChatModel streamingChatModel,
            List<ChatMessage> messages, Long userId, Long bookId,
            DebateSession session, DebateRole personality, String positionKey,
            DebateSpeakRequest request, String extraContent) {

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
                            personality.getName(), positionKey, side, content,
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

        String prompt = String.format(
                AiPromptConstants.DEBATE_NEXT_SPEAKER_FREE_PROMPT,
                session.getTopic(),
                rolesInfo.toString(),
                lastSpeaker, lastSide,
                countInfo.toString());

        try {
            String result = chatModelManager.callAi(
                    "辩论自由辩论发言人选择",
                    String.format("sessionId=%s", sessionId),
                    prompt);

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
        if (oppositeSide != null) {
            return getLeastSpokenSpeakerBySide(speakCounts, oppositeSide);
        }
        // 无上一位（自由辩论第一轮首次）→ 正方必须先发言
        return getLeastSpokenSpeakerBySide(speakCounts, "PRO");
    }

    /**
     * 回退：获取发言次数最少的非主持人辩手
     */
    private String getLeastSpokenSpeaker(Map<String, Long> speakCounts) {
        String leastSpeaker = null;
        long minCount = Long.MAX_VALUE;

        for (String positionKey : getAllPositionKeys()) {
            if ("HOST".equals(positionKey)) continue;
            long count = speakCounts.getOrDefault(positionKey, 0L);
            if (count < minCount) {
                minCount = count;
                leastSpeaker = positionKey;
            }
        }
        return leastSpeaker;
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
    public DebateSessionVO advanceRound(String sessionId) {
        DebateSession session = getSessionBySessionId(sessionId);
        if (session == null) {
            throw new BusinessException("辩论会话不存在");
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

}
