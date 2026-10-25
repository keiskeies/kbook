package com.kbook.service.debate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.exception.BusinessException;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.config.ai.AiConfig;
import com.kbook.config.ai.AiConfigProvider;
import com.kbook.dto.debate.*;
import com.kbook.entity.AiScene;
import com.kbook.entity.Book;
import com.kbook.entity.debate.DebateMessage;
import com.kbook.entity.debate.DebateScore;
import com.kbook.entity.debate.DebateSession;
import com.kbook.common.enums.DebateRole;
import com.kbook.repository.BookRepository;
import com.kbook.repository.debate.DebateMessageRepository;
import com.kbook.repository.debate.DebateScoreRepository;
import com.kbook.repository.debate.DebateSessionRepository;
import com.kbook.service.ai.core.ExternalKnowledgeGenerator;
import com.kbook.service.ai.streaming.StreamingSseHandler;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.time.LocalDateTime;
import static com.kbook.common.util.QueryBuilder.eq;
import static com.kbook.common.util.QueryBuilder.ne;
import com.kbook.service.ai.core.ChatModelManager;
import com.kbook.service.ai.core.AiUsageContext;
import com.kbook.constants.ai.DebatePrompts;

/**
 * 辩论发言服务 — 七环节 SSE 发言（开篇/奇袭/质询/驳论/自由/总结/主持点评）与消息构建落库。
 */
@Slf4j
@Service
public class DebateSpeechService {

    private static final long SSE_TIMEOUT = 3600_000L;
    private static final int SUMMARY_MAX_LENGTH = 3000;

    private final BookService bookService;
    private final ChatModelManager chatModelManager;
    private final DebateScoringService scoringService;
    private final ExternalKnowledgeGenerator externalKnowledgeGenerator;
    private final AiConfigProvider aiConfigProvider;
    private final ExecutorService sseExecutor;
    private final DebateSessionRepository sessionRepository;
    private final DebateMessageRepository messageRepository;
    private final DebateSessionService sessionService;
    private final ObjectMapper objectMapper;

    public DebateSpeechService(
            BookService bookService,
            ChatModelManager chatModelManager,
            DebateScoringService scoringService,
            ExternalKnowledgeGenerator externalKnowledgeGenerator,
            AiConfigProvider aiConfigProvider,
            @Qualifier("sseExecutor") ExecutorService sseExecutor,
            DebateSessionRepository sessionRepository,
            DebateMessageRepository messageRepository,
            DebateSessionService sessionService,
            ObjectMapper objectMapper) {
        this.bookService = bookService;
        this.chatModelManager = chatModelManager;
        this.scoringService = scoringService;
        this.externalKnowledgeGenerator = externalKnowledgeGenerator;
        this.aiConfigProvider = aiConfigProvider;
        this.sseExecutor = sseExecutor;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    /**
     * 开篇立论 SSE 发言 — 第1轮：HOST → PRO_1 → CON_1（仅一辩立论）
     */
    public SseEmitter streamOpeningSpeech(Long userId, Long bookId, DebateSpeakRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        String positionKey = request.getRoleKey();
        DebateSession session = sessionService.getSessionBySessionId(request.getSessionId());
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
        String side = sessionService.getSideFromPositionKey(positionKey);
        String sideName = sessionService.getSideName(side);
        String sideFull = sessionService.getSideFull(positionKey);
        String positionLabel = sessionService.getPositionLabel(positionKey);

        AiUsageContext.submit(sseExecutor, session.getBookId(), session.getSessionId(), () -> {
            try {
                String systemPrompt = String.format(
                        DebatePrompts.DEBATE_OPENING_PROMPT);

                List<ChatMessage> messages = buildSpeechMessages(
                        systemPrompt, session, request, personality,
                        side, sideName, positionLabel, sideFull, null);

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingModelForScene(AiScene.DEBATE_SPEECH);
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
        DebateSession session = sessionService.getSessionBySessionId(request.getSessionId());
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
        String side = sessionService.getSideFromPositionKey(positionKey);
        String sideName = sessionService.getSideName(side);
        String sideFull = sessionService.getSideFull(positionKey);
        String positionLabel = sessionService.getPositionLabel(positionKey);

        AiUsageContext.submit(sseExecutor, session.getBookId(), session.getSessionId(), () -> {
            try {
                String systemPrompt = String.format(
                        DebatePrompts.DEBATE_ATTACK_PROMPT);

                List<ChatMessage> messages = buildSpeechMessages(
                        systemPrompt, session, request, personality,
                        side, sideName, positionLabel, sideFull, opponentSpeech);

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingModelForScene(AiScene.DEBATE_SPEECH);
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
        DebateSession session = sessionService.getSessionBySessionId(request.getSessionId());
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
        String side = sessionService.getSideFromPositionKey(positionKey);
        String sideName = sessionService.getSideName(side);
        String sideFull = sessionService.getSideFull(positionKey);
        String positionLabel = sessionService.getPositionLabel(positionKey);

        AiUsageContext.submit(sseExecutor, session.getBookId(), session.getSessionId(), () -> {
            try {
                // 根据质询角色选择不同的系统提示词
                boolean isQuestioner = "QUESTIONER".equals(examRole);
                String systemPrompt = isQuestioner
                        ? DebatePrompts.DEBATE_CROSS_EXAM_QUESTIONER_PROMPT
                        : DebatePrompts.DEBATE_CROSS_EXAM_ANSWERER_PROMPT;

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
                        ? DebatePrompts.DEBATE_CROSS_EXAM_QUESTIONER_OUTPUT
                        : DebatePrompts.DEBATE_CROSS_EXAM_ANSWERER_OUTPUT;
                String examInstruction = isQuestioner
                        ? "你是质询方，请向对方一辩提出一个尖锐的问题。只能提问，不能陈述观点。"
                        : "你正在被对方二辩质询。请正面回答对方的问题。只能回答，绝不能反问或反击。坚守你在一辩立论中的立场。";
                // 替换最后一条 UserMessage
                messages.remove(messages.size() - 1);
                messages.add(UserMessage.from(examOutput + "\n\n" + examInstruction));

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingModelForScene(AiScene.DEBATE_SPEECH);
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
        DebateSession session = sessionService.getSessionBySessionId(request.getSessionId());
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
        String side = sessionService.getSideFromPositionKey(positionKey);
        String sideName = sessionService.getSideName(side);
        String sideFull = sessionService.getSideFull(positionKey);
        String positionLabel = sessionService.getPositionLabel(positionKey);

        AiUsageContext.submit(sseExecutor, session.getBookId(), session.getSessionId(), () -> {
            try {
                String systemPrompt = DebatePrompts.DEBATE_REBUTTAL_PROMPT;

                String extraContent = "【对方一辩立论 — 请集中火力反驳以下论证】\n" + opponentOpening
                        + "\n\n【质询环节摘要】\n" + crossExamContext;

                List<ChatMessage> messages = buildSpeechMessages(
                        systemPrompt, session, request, personality,
                        side, sideName, positionLabel, sideFull, extraContent);

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingModelForScene(AiScene.DEBATE_SPEECH);
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
        DebateSession session = sessionService.getSessionBySessionId(request.getSessionId());
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
        String side = sessionService.getSideFromPositionKey(positionKey);
        String sideName = sessionService.getSideName(side);
        String sideFull = sessionService.getSideFull(positionKey);
        String positionLabel = sessionService.getPositionLabel(positionKey);

        AiUsageContext.submit(sseExecutor, session.getBookId(), session.getSessionId(), () -> {
            try {

                String systemPrompt = String.format(
                        DebatePrompts.DEBATE_FREE_PROMPT);

                List<ChatMessage> messages = buildSpeechMessages(
                        systemPrompt, session, request, personality,
                        side, sideName, positionLabel, sideFull, lastSpeech);

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingModelForScene(AiScene.DEBATE_SPEECH);
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
        DebateSession session = sessionService.getSessionBySessionId(request.getSessionId());
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
        String side = sessionService.getSideFromPositionKey(positionKey);
        String sideName = sessionService.getSideName(side);
        String sideFull = sessionService.getSideFull(positionKey);
        String positionLabel = sessionService.getPositionLabel(positionKey);

        AiUsageContext.submit(sseExecutor, session.getBookId(), session.getSessionId(), () -> {
            try {
                // 构建全场辩论摘要
                String debateSummary = buildDebateSummary(session);

                String systemPrompt = String.format(
                        DebatePrompts.DEBATE_CLOSING_PROMPT);

                List<ChatMessage> messages = buildSpeechMessages(
                        systemPrompt, session, request, personality,
                        side, sideName, positionLabel, sideFull, debateSummary);

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingModelForScene(AiScene.DEBATE_SPEECH);
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
        DebateSession session = sessionService.getSessionBySessionId(sessionId);
        if (session == null) {
            SseHelper.sendErrorAndComplete(emitter, "会话不存在: " + sessionId);
            return emitter;
        }
        if (!session.getUserId().equals(userId)) {
            SseHelper.sendErrorAndComplete(emitter, "无权操作该会话");
            return emitter;
        }

        AiUsageContext.submit(sseExecutor, session.getBookId(), session.getSessionId(), () -> {
            try {
                // 按缓存命中率排序：静态规则 → 静态人设 → 动态上下文
                List<ChatMessage> messages = new ArrayList<>();
                messages.add(SystemMessage.from(DebatePrompts.DEBATE_HOST_COMMENTARY_SYSTEM_PROMPT));
                messages.add(SystemMessage.from(DebateRole.HOST.getPrompt()));

                StringBuilder userCtx = new StringBuilder();
                userCtx.append("【点评类型】").append(type).append("\n");
                userCtx.append("【辩题】").append(session.getTopic()).append("\n");
                if (context != null && !context.isBlank()) {
                    userCtx.append("【点评上下文】\n").append(context);
                }
                messages.add(UserMessage.from(userCtx.toString()));

                StreamingChatModel streamingChatModel = chatModelManager.getStreamingModelForScene(AiScene.DEBATE_SPEECH);
                if (streamingChatModel == null) {
                    SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                var logContext = chatModelManager.buildLogContext(AiScene.DEBATE_SPEECH);

                StreamingSseHandler.stream(streamingChatModel, messages, emitter, new StreamingSseHandler.Callback() {
                    @Override
                    public String getOperationName() { return "主持人点评"; }

                    @Override
                    public String formatMessageEvent(String text) {
                        try {
                            return objectMapper.writeValueAsString(
                                    Map.of("roleKey", "HOST", "text", text));
                        } catch (Exception e) {
                            return text;
                        }
                    }

                    @Override
                    public void onComplete(String content, ChatResponse completeResponse) {
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
                    }
                }, 2, logContext);
            } catch (Exception e) {
                log.error("主持人点评失败: type={} - {}", type, e.getMessage(), e);
                SseHelper.sendErrorAndComplete(emitter, SseHelper.extractFriendlyError(e));
            }
        });

        return emitter;
    }

    /** 查询性格（带兜底） */
    private DebateRole resolvePersonality(DebateSession session, String positionKey) {
        String personalityKey = sessionService.getPersonalityKey(session, positionKey);
        DebateRole personality = DebateRole.fromKey(personalityKey);
        if (personality == null) {
            log.warn("性格键无效，使用兜底: personalityKey={}", personalityKey);
            personality = DebateRole.fromKey("LOGICAL");
        }
        return personality;
    }

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
                DebatePrompts.DEBATE_ROLE_SETTING,
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
            case "CROSS_EXAM" -> DebatePrompts.DEBATE_CROSS_EXAM_QUESTIONER_OUTPUT; // fallback
            case "REBUTTAL" -> DebatePrompts.DEBATE_REBUTTAL_OUTPUT;
            case "FREE" -> DebatePrompts.DEBATE_FREE_OUTPUT;
            case "CLOSING" -> DebatePrompts.DEBATE_CLOSING_OUTPUT;
            // ATTACK 已废弃，被 CROSS_EXAM + REBUTTAL 取代
            default -> DebatePrompts.DEBATE_OPENING_OUTPUT;
        };
    }

    private void streamResponse(
            SseEmitter emitter, StreamingChatModel streamingChatModel,
            List<ChatMessage> messages, Long userId, Long bookId,
            DebateSession session, DebateRole personality, String positionKey,
            DebateSpeakRequest request) {

        // 所有调用方均使用 AiScene.DEBATE_SPEECH 构建模型，此处统一构建日志上下文
        var logContext = chatModelManager.buildLogContext(AiScene.DEBATE_SPEECH);

        StreamingSseHandler.stream(streamingChatModel, messages, emitter, new StreamingSseHandler.Callback() {
            @Override
            public String getOperationName() { return "辩论发言"; }

            @Override
            public String formatMessageEvent(String text) {
                try {
                    return objectMapper.writeValueAsString(
                            Map.of("roleKey", positionKey, "text", text));
                } catch (Exception e) {
                    return text;
                }
            }

            @Override
            public void onComplete(String content, ChatResponse completeResponse) {
                if (!content.isBlank()) {
                    saveMessage(userId, bookId, session, personality, positionKey, request, content);
                    String side = sessionService.getSideFromPositionKey(positionKey);
                    scoringService.scoreSpeechAsync(userId, request.getSessionId(),
                            personality.getKey(), positionKey, side, content,
                            request.getRoundNumber(), request.getRoundType());
                }
            }

            @Override
            public void onConnectionClosed(String partialContent) {
                if (!partialContent.isBlank()) {
                    saveMessage(userId, bookId, session, personality, positionKey, request, partialContent);
                }
            }
        }, 2, logContext);
    }

    private int saveMessage(Long userId, Long bookId, DebateSession session,
                            DebateRole personality, String positionKey,
                            DebateSpeakRequest request, String content) {
        try {
            List<DebateMessage> roundMessages = messageRepository.findBySessionIdAndRoundNumberOrderByPhaseOrder(
                    request.getSessionId(), request.getRoundNumber());
            int phaseOrder = roundMessages.size() + 1;

            String side = sessionService.getSideFromPositionKey(positionKey);

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
        return CommonUtils.truncateText(sb.toString(), SUMMARY_MAX_LENGTH);
    }

    /**
     * 为辩论生成外部知识
     */
    private String generateExternalKnowledgeForDebate(DebateSession session, DebateRole personality, String side) {
        try {
            String topic = session.getTopic();
            String stance = "PRO".equals(side) ? "支持该观点" : "反对该观点";
            String personalityContext = personality.getTitle() + "：" + personality.getPrompt().substring(0, Math.min(200, personality.getPrompt().length()));
            return externalKnowledgeGenerator.generateForDebate(topic, stance, personalityContext);
        } catch (Exception e) {
            log.debug("生成辩论外部知识失败: session={}, error={}", session.getSessionId(), e.getMessage());
            return "";
        }
    }
}
