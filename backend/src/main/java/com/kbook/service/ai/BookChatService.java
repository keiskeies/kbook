package com.kbook.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.service.ai.streaming.StreamingSseHandler;
import com.kbook.service.ai.behavior.BehaviorProfileBuilder;
import com.kbook.service.ai.behavior.UserBehaviorSignalEvent;
import com.kbook.service.user.UserService;
import com.kbook.service.book.BookParserService;
import com.kbook.service.book.BookService;
import com.kbook.service.embedding.RagHitStatisticsService;
import com.kbook.service.embedding.EmbeddingService;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.config.ChatModelFactory;
import com.kbook.service.ai.core.ChatHistoryCompressor;
import com.kbook.service.ai.core.UserProfileBuilder;
import com.kbook.service.ai.behavior.BehaviorProfileBuilder;
import com.kbook.service.ai.behavior.BehaviorProfileService;
import com.kbook.service.ai.behavior.UserBehaviorSignalEvent;
import com.kbook.entity.UserBehaviorProfile;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.config.properties.QdrantProperties;
import com.kbook.config.properties.BookQaProperties;
import com.kbook.config.ai.AiConfigProvider;
import com.kbook.dto.book.BookProjection;
import com.kbook.entity.AiConversation;
import com.kbook.entity.AiRetrievalSnapshot;
import com.kbook.entity.AiScene;
import com.kbook.entity.AiProviderConfig;
import com.kbook.entity.Book;
import com.kbook.entity.AiSession;
import com.kbook.entity.BookSuggestedQuestion;
import com.kbook.entity.User;
import com.kbook.entity.UserBehaviorProfile;
import com.kbook.repository.AiConversationRepository;
import com.kbook.repository.AiSessionRepository;
import com.kbook.repository.BookSuggestedQuestionRepository;
import com.kbook.repository.UserBehaviorProfileRepository;
import static com.kbook.common.util.QueryBuilder.*;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import com.kbook.service.ai.rag.ListQueryDetector;
import com.kbook.service.ai.rag.ListQueryRetriever;
import com.kbook.service.ai.rag.ListQueryStrategySelector;
import com.kbook.service.ai.rag.ItemListRetriever;
import com.kbook.service.ai.rag.RagPipelineComponents;
import com.kbook.service.ai.rag.BookAnswerGuard;
import com.kbook.service.ai.rag.AiRetrievalSnapshotService;
import com.kbook.service.ai.rag.CitationValidator;
import com.kbook.service.ai.rag.BookScaleRouter;
import com.kbook.service.ai.rag.ContextMode;
import com.kbook.service.ai.rag.FullTextContextBuilder;
import com.kbook.service.ai.rag.AnswerOverlapGuard;
import com.kbook.service.ai.core.ChatModelManager;
import com.kbook.service.ai.core.AiAssistantProvider;
import com.kbook.service.ai.core.AiSceneConfigService;
import com.kbook.service.ai.core.AiUsageContext;
import com.kbook.constants.ai.BookChatPrompts;
import com.kbook.constants.ai.RagPrompts;

/**
 * 图书问答服务（主流程编排）— SSE 主链路调度 RAG 检索 / 列表流水线 / 会话持久化；
 * 会话、检索、流水线、追问生成分别由 BookChatSessionService / BookChatRagService /
 * BookChatPipelineService / BookChatFollowUpService 承接（M5 拆分，公开 API 原样保留）。
 */
@Slf4j
@Service
@LogModule("图书问答")
public class BookChatService {

    private final EmbeddingService embeddingService;
    private final BookService bookService;
    private final BookParserService bookParserService;
    private final AiAssistantProvider assistantProvider;
    private final AiSceneConfigService aiSceneConfigService;
    private final ChatModelManager chatModelManager;
    private final QdrantProperties qdrantProperties;
    private final ListQueryDetector listQueryDetector;
    private final BehaviorProfileBuilder behaviorProfileBuilder;
    private final BookChatSessionService sessionService;
    private final BookChatRagService ragService;
    private final BookChatPipelineService pipelineService;
    private final BookChatFollowUpService followUpService;
    private final AiRetrievalSnapshotService retrievalSnapshotService;
    private final BookScaleRouter bookScaleRouter;
    private final FullTextContextBuilder fullTextContextBuilder;
    private final BookQaProperties bookQaProperties;
    private final AnswerFeedbackService answerFeedbackService;
    private final @Qualifier("sseExecutor") ExecutorService sseExecutor;

    public BookChatService(
            EmbeddingService embeddingService,
            BookService bookService,
            BookParserService bookParserService,
            AiAssistantProvider assistantProvider,
            AiSceneConfigService aiSceneConfigService,
            ChatModelManager chatModelManager,
            QdrantProperties qdrantProperties,
            ListQueryDetector listQueryDetector,
            BehaviorProfileBuilder behaviorProfileBuilder,
            BookChatSessionService sessionService,
            BookChatRagService ragService,
            BookChatPipelineService pipelineService,
            BookChatFollowUpService followUpService,
            AiRetrievalSnapshotService retrievalSnapshotService,
            BookScaleRouter bookScaleRouter,
            FullTextContextBuilder fullTextContextBuilder,
            BookQaProperties bookQaProperties,
            AnswerFeedbackService answerFeedbackService,
            @Qualifier("sseExecutor") ExecutorService sseExecutor) {
        this.embeddingService = embeddingService;
        this.bookService = bookService;
        this.bookParserService = bookParserService;
        this.assistantProvider = assistantProvider;
        this.aiSceneConfigService = aiSceneConfigService;
        this.chatModelManager = chatModelManager;
        this.qdrantProperties = qdrantProperties;
        this.listQueryDetector = listQueryDetector;
        this.behaviorProfileBuilder = behaviorProfileBuilder;
        this.sessionService = sessionService;
        this.ragService = ragService;
        this.pipelineService = pipelineService;
        this.followUpService = followUpService;
        this.retrievalSnapshotService = retrievalSnapshotService;
        this.bookScaleRouter = bookScaleRouter;
        this.fullTextContextBuilder = fullTextContextBuilder;
        this.bookQaProperties = bookQaProperties;
        this.answerFeedbackService = answerFeedbackService;
        this.sseExecutor = sseExecutor;
    }

// ==================== 主流程：SSE 图书问答 ====================

    /**
     * SSE 流式图书问答：基于 RAG 检索书籍内容并流式生成回答
     *
     * @param userId    用户ID
     * @param bookId    书籍ID
     * @param question  用户问题
     * @param sessionId 会话ID（可为空，自动生成）
     * @return SseEmitter 流式发射器
     */
    @LogAction("SSE图书问答")
    public SseEmitter streamBookChat(Long userId, Long bookId, String question, String sessionId,
                                     boolean manual, boolean regenerate, boolean emitConversationId) {
        log.info("========== 图书问答请求 ==========");
        log.info("userId={}, bookId={}, question={}", userId, bookId, question);

        final String effectiveSessionId = (sessionId == null || sessionId.isBlank())
                ? "book-" + bookId + "-" + UUID.randomUUID().toString().substring(0, 8)
                : sessionId;

        // 超时对齐 application.yml spring.async.request-timeout: 3600000（1h）。
        // 原 180s 会被多段串行生成的耗时腰斩（阶段1 止血项，v2 流水线的硬前提）。
        SseEmitter emitter = new SseEmitter(3_600_000L);

        try {
            emitter.send(SseEmitter.event().name("session_id").data(effectiveSessionId));
        } catch (Exception ignored) {
        }

        // ——— 前置校验（emitter 创建后、异步任务提交前，快速失败） ———
        final Book book = bookService.getBookById(bookId);
        if (book == null) {
            SseHelper.sendErrorAndComplete(emitter, "图书不存在");
            return emitter;
        }

        final StreamingChatModel model = chatModelManager.getStreamingModelForScene(AiScene.BOOK_QA);
        if (model == null) {
            SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
            return emitter;
        }

        // 构建日志上下文（场景/模型/思考配置），供 StreamingSseHandler 打印统一摘要
        final var logContext = chatModelManager.buildLogContext(AiScene.BOOK_QA);



        Future<?> aiFuture = AiUsageContext.submit(sseExecutor, bookId, effectiveSessionId, () -> {
            try {
                // 重新生成：先删掉最后一条旧回答，后续历史/上轮回答自然回到上一轮
                if (regenerate) {
                    AiConversation lastAssistant = sessionService.findLastAssistantRow(userId, effectiveSessionId);
                    if (lastAssistant != null) {
                        retrievalSnapshotService.deleteByConversationId(lastAssistant.getId());
                        // P2-5：旧回答被删，对应反馈也要清掉，否则标注队列会留下指向已删行的孤儿样本
                        answerFeedbackService.deleteByConversationId(lastAssistant.getId());
                        sessionService.deleteMessageById(lastAssistant.getId());
                        log.info("[Regenerate] 删除旧回答: conversationId={}, sessionId={}",
                                lastAssistant.getId(), effectiveSessionId);
                    }
                }

                // 上一轮 AI 回答（用于追问场景的 RAG 查询扩展，不缓存答案——不同用户画像应得到不同回答）
                String lastAiAnswer = sessionService.getLastAiAnswer(userId, effectiveSessionId);

                // 1. 按需生成内容向量（首次问答时触发）
                if (!ensureContentEmbedded(book, bookId, emitter)) return;

                // 2. 懒生成 compressedSummary（首次问答时若为空，同步生成并持久化）
                ensureCompressedSummary(book, emitter);

                // ragTopK 从 BOOK_QA 场景绑定的模型配置读（跟随场景路由，替代旧的 getChatConfigByRole("QA")）
                Integer sceneRagTopK = null;
                try {
                    AiProviderConfig sceneConfig = aiSceneConfigService.resolveConfig(AiScene.BOOK_QA);
                    if (sceneConfig != null) {
                        sceneRagTopK = sceneConfig.getRagTopK();
                    }
                } catch (Exception e) {
                    log.warn("解析 BOOK_QA 场景配置失败，回退到全局默认: {}", e.getMessage());
                }
                int ragTopK = Optional.ofNullable(sceneRagTopK)
                        .orElse(qdrantProperties.getRagTopK());
                log.info("RAG topK 解析: BOOK_QA场景配置.ragTopK={}, fallback={}, 最终 ragTopK={}",
                        sceneRagTopK, qdrantProperties.getRagTopK(), ragTopK);
                int ragMaxChars = ragService.getRagMaxChars();

                // ===== 按书规模分档（P1）=====
                // 先构建静态书籍语料：它是唯一的缓存前缀，档位只决定要不要往里追加原文。
                // 灰度开关关闭时 decide() 恒返回 LARGE，staticCorpus 与改造前逐字节一致。
                String baseStaticCorpus = buildStaticBookCorpus(book);
                String dynamicMeta = buildDynamicBookMeta(book);
                int historyChars = sessionService.estimateHistoryChars(userId, effectiveSessionId);
                BookScaleRouter.ScaleDecision scaleDecision = bookScaleRouter.decide(
                        book, effectiveSessionId, question,
                        baseStaticCorpus.length() + dynamicMeta.length() + question.length(),
                        historyChars);
                final ContextMode contextMode = scaleDecision.mode();
                final String staticCorpus = switch (contextMode) {
                    case SMALL -> fullTextContextBuilder.buildSmallCorpus(baseStaticCorpus, book);
                    case MEDIUM -> scaleDecision.chapter() != null
                            ? fullTextContextBuilder.buildMediumCorpus(baseStaticCorpus, scaleDecision.chapter())
                            : baseStaticCorpus;
                    case LARGE -> baseStaticCorpus;
                };
                log.info("[ScaleRouter] 本轮上下文: bookId={}, mode={}, staticChars={}, historyChars={}, reason={}",
                        bookId, contextMode, staticCorpus.length(), historyChars, scaleDecision.reason());
                // §5.3 观测项：把档位回传前端，便于人工核对 A/B 分组与档位漂移。
                // 路由关闭时 decide() 恒返回 LARGE，此时下发档位没有观测价值，
                // 只会在 SSE 流开头多出一条元数据，因此仅灰度开启时发送。
                if (bookQaProperties.isScaleRoutingEnabled()) {
                    SseHelper.safeSendEvent(emitter, "scale_mode",
                            contextMode.name().toLowerCase(Locale.ROOT));
                }

                String ragContext = null;
                String listTopic = null;
                List<EmbeddingMatch<TextSegment>> ragMatches = null;
                List<EmbeddingMatch<TextSegment>> ragIncludedMatches = null;
                ListQueryDetector.DetectionResult detection = ListQueryDetector.DetectionResult.notList();
                boolean retrievalAttempted = false;
                if (contextMode.usesRetrieval()
                        && embeddingService.isAvailable() && waitForContentEmbedding(book.getId())) {
                    retrievalAttempted = true;
                    // 检测提前到检索之前（§3.4 流水线触发第一步）：一次 LLM 调用，
                    // 列表型分支内部复用传入的 detection 结果，不再重复检测。
                    detection = listQueryDetector.detect(question, lastAiAnswer);
                    try {
                        BookChatRagService.RagRetrievalResult ragResult =
                                ragService.doRagRetrieval(book, question, lastAiAnswer, ragTopK, ragMaxChars,
                                        emitter, detection);
                        ragContext = ragResult.ragContext();
                        listTopic = ragResult.listTopic();
                        ragMatches = ragResult.matches();
                        ragIncludedMatches = ragResult.includedMatches();
                    } catch (Exception e) {
                        log.warn("RAG 检索异常: bookId={} - {}", book.getId(), e.getMessage());
                        try {
                            emitter.send(SseEmitter.event().name("thinking_content").data("检索出了点问题，我确认一下…\n"));
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (ragContext != null) {
                    log.debug("RAG 检索结果长度: {}", ragContext.length());
                }
                // 全文/整章档跳过了检索分支，但仍需一次列表型检测：覆盖度契约与
                // 回答条目化依赖 listTopic；只是不启动列表流水线（原文已在上下文里）。
                if (!contextMode.usesRetrieval()) {
                    detection = listQueryDetector.detect(question, lastAiAnswer);
                    listTopic = detection.listTopic();
                }

                // 构建图书基本信息（合并版，供列表条目流水线使用）
                String bookInfoPrompt = buildBookInfoPrompt(book);

                // 快照：记录"实际进入上下文"的片段与该轮提示词版本
                final List<EmbeddingMatch<TextSegment>> includedMatchesForSnapshot = ragIncludedMatches;
                final String promptVersion = BookChatPrompts.versionOf(
                        sessionService.getSystemPromptForStyle(sessionService.getChatStyleForUser(userId)));

                // ===== 列表展开流水线触发判定（§3.4）=====
                // 条件：检测为列表型 && outline 可得（DB 优先，缺失现场条目化）且 items≥2 && ≤ MAX_PIPELINE_ITEMS
                // 任何一条不满足 → 完全退回现有单次流式路径（行为不比现状差，Q4）
                // 全文/整章档首版禁用列表流水线：它依赖 ragMatches，且原文已在上下文里，
                // 逐条检索反而是重复劳动。可通过配置放开做 A/B 对照。
                boolean listPipelineAllowed = !contextMode.fullContext()
                        || bookQaProperties.isListPipelineEnabledForFullText();
                com.kbook.service.ai.outline.AnswerOutline pipelineOutline = listPipelineAllowed
                        ? pipelineService.resolvePipelineOutline(detection, question, userId, effectiveSessionId,
                                emitter, lastAiAnswer, ragMatches, book.getToc())
                        : null;

                if (pipelineOutline != null) {
                    pipelineService.streamItemListPipeline(book, question, pipelineOutline,
                            bookInfoPrompt, model, userId, effectiveSessionId, emitter, manual,
                            includedMatchesForSnapshot, promptVersion, regenerate, emitConversationId);
                    return;
                }

                // ===== 空检索/低分拒答（P0-B）=====
                // 只在"确实尝试过检索"且无上下文或常规检索低于绝对阈值时触发；
                // 元问题/闲聊仍允许用图书信息回答；列表型不使用余弦阈值（其分值是 rank 序数分）。
                boolean noContext = ragContext == null || ragContext.isBlank();
                boolean lowScore = !noContext && BookAnswerGuard.isLowScore(
                        includedMatchesForSnapshot, qdrantProperties.getAbsoluteMinScore(),
                        detection.isListQuery());
                if (qdrantProperties.getAbsoluteMinScore() > 0) {
                    log.info("[AnswerGuard] topScore={}, threshold={}, list={}, noContext={}",
                            BookAnswerGuard.topScore(includedMatchesForSnapshot),
                            qdrantProperties.getAbsoluteMinScore(), detection.isListQuery(), noContext);
                }
                if (retrievalAttempted
                        && (noContext || lowScore)
                        && BookAnswerGuard.shouldRefuse(question)) {
                    String refusal = BookAnswerGuard.refusalMessage(question);
                    log.info("[AnswerGuard] 检索为空，拒答: bookId={}, question={}", bookId, question);
                    try {
                        sessionService.ensureSession(userId, effectiveSessionId, question, bookId);
                        if (!regenerate || !sessionService.isLastUserMessage(userId, effectiveSessionId, question)) {
                            sessionService.saveMessage(userId, effectiveSessionId, "user", question, bookId, null);
                        }
                        AiConversation refusalRow = sessionService.saveMessage(userId, effectiveSessionId,
                                "assistant", refusal, bookId, null, "NO_CONTEXT");
                        if (emitConversationId) {
                            SseHelper.safeSendEvent(emitter, "conversation_id",
                                    refusalRow == null ? "" : String.valueOf(refusalRow.getId()));
                        }
                        sessionService.updateSessionTimestamp(effectiveSessionId);
                        sessionService.publishUserSignal(userId, effectiveSessionId, bookId, question, manual);
                    } catch (Exception e) {
                        log.warn("[AnswerGuard] 拒答落库失败（不影响回复）: {}", e.getMessage());
                    }
                    SseHelper.safeSendEvent(emitter, "message", refusal);
                    try {
                        emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                        emitter.complete();
                    } catch (Exception e) {
                        log.debug("[AnswerGuard] done/complete 发送失败: {}", e.getMessage());
                    }
                    return;
                }

                // ===== 回退路径：现有单次流式（ListQueryRetriever 三档检索保留为回退，§8）=====
                // RAG 参考内容 + 用户问题分别构造为独立消息（结构清晰，避免问题被 RAG 淹没）
                int includedCount = includedMatchesForSnapshot == null ? 0 : includedMatchesForSnapshot.size();
                String ragMessage = buildRagMessage(ragContext);
                String questionMessage = buildQuestionMessage(question, listTopic, includedCount, contextMode);

                log.debug("图书问答: bookId={}, question={}, mode={}, staticLen={}, ragContextLen={}, questionMsgLen={}",
                        bookId, question, contextMode, staticCorpus.length(),
                        ragContext != null ? ragContext.length() : 0, questionMessage.length());

                List<ChatMessage> messages = buildChatMessages(effectiveSessionId, userId,
                        staticCorpus, dynamicMeta, ragMessage, questionMessage);

                // 合规第 3 道防线：只比对「本轮真正进入上下文」的书籍文本，
                // 不比对整库，避免把模型自己的常识表述误判成复述原文。
                String overlapSource = (ragContext == null ? "" : ragContext)
                        + (contextMode.fullContext() ? staticCorpus : "");
                int sourceCap = Math.max(0, bookQaProperties.getAnswerOverlapSourceMaxChars());
                if (overlapSource.length() > sourceCap) overlapSource = overlapSource.substring(0, sourceCap);
                final String overlapSourceFinal = overlapSource;

                StringBuilder fullThinking = new StringBuilder();
                // 匿名类捕获需 effectively-final
                final ListQueryDetector.DetectionResult detectionFinal = detection;
                final java.util.concurrent.atomic.AtomicReference<String> mainFinishReason =
                        new java.util.concurrent.atomic.AtomicReference<>();

                StreamingSseHandler.stream(model, messages, emitter, new StreamingSseHandler.Callback() {
                    @Override
                    public String getOperationName() { return "图书问答"; }

                    @Override
                    public void onThinkingToken(String thinkingText, SseEmitter emitter) {
                        fullThinking.append(thinkingText);
                        if (!SseHelper.safeSendEvent(emitter, "thinking_content", thinkingText)) {
                            log.warn("SSE 连接已关闭，停止 AI 输出: bookId={}", bookId);
                        }
                    }

                    @Override
                    public void onFinishReason(String finishReason) {
                        mainFinishReason.set(finishReason);
                    }

                    @Override
                    public void onComplete(String answer, ChatResponse completeResponse) {
                        // 统一摘要日志已由 StreamingSseHandler.onCompleteResponse 打印
                        log.debug("图书问答流式完成: bookId={}", bookId);

                        sessionService.ensureSession(userId, effectiveSessionId, question, bookId);
                        if (!regenerate || !sessionService.isLastUserMessage(userId, effectiveSessionId, question)) {
                            sessionService.saveMessage(userId, effectiveSessionId, "user", question, bookId, null);
                        }
                        String thinkingText = !fullThinking.isEmpty() ? fullThinking.toString() : null;
                        // 输出审查 P1 #17：过滤可能的系统提示泄露后再持久化和缓存
                        String safeAnswer = CommonUtils.sanitizeAiOutput(answer);
                        // 合规第 3 道防线：答案里若整段照抄原文，替换成「〔原文从略〕」后再落库。
                        // 灰度开关关闭时 answerForCitations == safeAnswer，逐字节等价。
                        String answerForCitations = safeAnswer;
                        if (bookQaProperties.isAnswerOverlapGuardEnabled()) {
                            AnswerOverlapGuard.Result overlap = AnswerOverlapGuard.check(
                                    safeAnswer, overlapSourceFinal,
                                    Math.max(AnswerOverlapGuard.DEFAULT_MIN_CHARS,
                                            bookQaProperties.getAnswerOverlapMinChars()));
                            if (overlap.hasOverlap()) {
                                answerForCitations = overlap.redactedAnswer();
                                log.warn("[OverlapGuard] 答案检出原文重合: bookId={}, hits={}, maxChars={}, mode={}",
                                        bookId, overlap.hits(), overlap.maxOverlap(), contextMode);
                            }
                        }
                        // 引用校验：只保留真实存在的片段编号，非法编号剔除
                        int citationIncludedCount = includedMatchesForSnapshot == null
                                ? 0 : includedMatchesForSnapshot.size();
                        CitationValidator.Result citationResult =
                                CitationValidator.validate(answerForCitations, citationIncludedCount);
                        String finalAnswer = citationResult.answer();
                        // 检测到泄露或非法引用时发送 replace 事件覆盖前端已显示的流式内容
                        if (!finalAnswer.equals(answer)) {
                            try {
                                emitter.send(SseEmitter.event().name("replace").data(finalAnswer));
                            } catch (Exception ignored) {
                            }
                            // 引用编号 [[N]] → [N] 是正常改写，几乎每次都会触发；
                            // 只有真出现非法编号（或 sanitize 命中）才是需要盯的问题。
                            if (citationResult.invalidCount() > 0) {
                                log.warn("回答含非法引用编号，已剔除并覆盖: bookId={}, sessionId={}, invalid={}, included={}",
                                        bookId, effectiveSessionId, citationResult.invalidCount(), citationIncludedCount);
                            } else {
                                log.debug("引用编号已规范化([[N]]→[N])并覆盖前端内容: bookId={}, sessionId={}, valid={}, included={}",
                                        bookId, effectiveSessionId, citationResult.validCount(), citationIncludedCount);
                            }
                        }
                        AiConversation assistantRow = sessionService.saveMessage(userId, effectiveSessionId, "assistant",
                                finalAnswer, bookId, thinkingText, mainFinishReason.get());
                        // P2-5 / P2-7：把落库行 id 交给前端，才能对「刚流完的这条回答」做反馈与查看依据。
                        // 只有声明了该能力的前端才下发，旧版前端会把它当正文显示成一串数字。
                        if (emitConversationId) {
                            SseHelper.safeSendEvent(emitter, "conversation_id",
                                    assistantRow == null ? "" : String.valueOf(assistantRow.getId()));
                        }
                        if (assistantRow != null) {
                            if (citationResult.citationJson() != null) {
                                sessionService.updateCitationById(assistantRow.getId(), citationResult.citationJson());
                            }
                            if (contextMode.fullContext()) {
                                // 无 chunk 级依据，写占位行让 /evidence 与评测识别上下文模式
                                retrievalSnapshotService.savePlaceholder(assistantRow.getId(), bookId,
                                        contextMode, "static_chars=" + staticCorpus.length(), promptVersion);
                            } else {
                                retrievalSnapshotService.save(assistantRow.getId(), bookId,
                                        includedMatchesForSnapshot, promptVersion);
                            }
                        }
                        sessionService.updateSessionTimestamp(effectiveSessionId);
                        // 发布用户提问信号（异步由 BehaviorProfileService 消费）
                        sessionService.publishUserSignal(userId, effectiveSessionId, bookId, question, manual);
                        // §4.5：回答含枚举结构 → 异步条目化落库（纯服务端状态，失败不影响主流程）
                        pipelineService.scheduleOutline(assistantRow, finalAnswer, detectionFinal.isListQuery());
                    }

                    @Override
                    public void onConnectionClosed(String partialContent) {
                        // 连接断开时仍然保存已输出的部分内容（与原行为一致）
                        sessionService.ensureSession(userId, effectiveSessionId, question, bookId);
                        if (!regenerate || !sessionService.isLastUserMessage(userId, effectiveSessionId, question)) {
                            sessionService.saveMessage(userId, effectiveSessionId, "user", question, bookId, null);
                        }
                        String thinkingText = !fullThinking.isEmpty() ? fullThinking.toString() : null;
                        AiConversation partialRow = sessionService.saveMessage(userId, effectiveSessionId, "assistant",
                                CommonUtils.sanitizeAiOutput(partialContent), bookId, thinkingText, null);
                        if (emitConversationId) {
                            SseHelper.safeSendEvent(emitter, "conversation_id",
                                    partialRow == null ? "" : String.valueOf(partialRow.getId()));
                        }
                        sessionService.updateSessionTimestamp(effectiveSessionId);
                        // 连接断开也算用户提过问
                        sessionService.publishUserSignal(userId, effectiveSessionId, bookId, question, manual);
                    }

                    @Override
                    public void onError(Throwable error) {
                        assistantProvider.clearAssistantCache();
                    }
                }, 2, logContext);

            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) return;
                log.error("图书问答异常: bookId={} - {}", bookId, e.getMessage(), e);
                assistantProvider.clearAssistantCache();
                SseHelper.sendErrorAndComplete(emitter, "AI 响应异常: " + SseHelper.extractFriendlyError(e));
            }
        });

        emitter.onCompletion(() -> aiFuture.cancel(true));
        emitter.onTimeout(() -> {
            aiFuture.cancel(true);
            log.warn("图书问答SSE超时: bookId={}", bookId);
        });
        emitter.onError(e -> {
            aiFuture.cancel(true);
            log.error("图书问答SSE错误: bookId={}", bookId, e);
        });

        return emitter;
    }

// ==================== 消息构建 ====================

    /**
     * 构建完整消息列表。
     * <p>
     * 消息顺序（静态前缀必须逐字节稳定，provider 端前缀缓存才能命中）：
     * <pre>
     * SystemMessage（固定提示词）
     *   → UserMessage（静态书籍语料：书名/作者/标签/简介/目录/摘要，档A再加全文）
     *   → 历史对话
     *   → UserMessage（动态书籍元信息：评分/阅读量）
     *   → UserMessage（RAG 参考内容，可为空）
     *   → UserMessage（读者画像）
     *   → UserMessage（问题 + 回答契约）
     * </pre>
     * 旧实现把评分、阅读量这类随用户行为变化的字段和目录、摘要放在同一条第 2 消息里，
     * 且 compressedSummary 首轮懒生成，导致前缀每轮漂移、缓存必然失效。
     * 历史消息数量由压缩机制动态控制，不硬限轮数。
     *
     * @param staticCorpus   静态书籍语料（缓存前缀，禁止掺入运行时可变数据）
     * @param dynamicMeta    动态书籍元信息（放在历史之后，不参与前缀）
     */
    private List<ChatMessage> buildChatMessages(String sessionId, Long userId,
                                                String staticCorpus, String dynamicMeta,
                                                String ragMessage, String questionMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        String style = sessionService.getChatStyleForUser(userId);
        String systemPrompt = sessionService.getSystemPromptForStyle(style);
        messages.add(SystemMessage.from(systemPrompt));

        // 唯一且稳定的缓存前缀
        if (staticCorpus != null && !staticCorpus.isBlank()) {
            messages.add(UserMessage.from(staticCorpus));
        }

        try {
            // 加载历史消息并同步压缩，确保 LLM 上下文不超限
            int currentOverhead = systemPrompt.length()
                    + (staticCorpus != null ? staticCorpus.length() : 0)
                    + (dynamicMeta != null ? dynamicMeta.length() : 0)
                    + (ragMessage != null ? ragMessage.length() : 0)
                    + (questionMessage != null ? questionMessage.length() : 0)
                    + 2000; // AI 回复预留
            List<AiConversation> history = sessionService.loadAndCompressHistory(userId, sessionId, currentOverhead);
            if (!history.isEmpty()) {
                for (AiConversation conv : history) {
                    String content = conv.getCompressedContent();
                    if (content == null || content.isBlank()) continue;
                    if ("user".equals(conv.getRole())) {
                        messages.add(UserMessage.from(content));
                    } else if ("assistant".equals(conv.getRole())) {
                        messages.add(AiMessage.from(content));
                    }
                }
                log.debug("加载图书问答历史: sessionId={}, totalRecords={}", sessionId, history.size());
            }
        } catch (Exception e) {
            log.warn("加载图书问答历史失败，继续无历史对话: {}", e.getMessage());
        }

        // 动态书籍元信息放在历史之后：变化它不会破坏前面的缓存前缀
        if (dynamicMeta != null && !dynamicMeta.isBlank()) {
            messages.add(UserMessage.from(dynamicMeta));
        }

        // RAG 参考内容作为独立 UserMessage（参考资料性质，与问题分离）
        if (ragMessage != null && !ragMessage.isBlank()) {
            messages.add(UserMessage.from(ragMessage));
        }
        // 行为画像（L2 画像）— 精简版本 ≤ 200 字，放在问题前让 LLM 自然贴合
        UserBehaviorProfile behaviorProfile = sessionService.loadBehaviorProfile(userId);
        if (behaviorProfile != null) {
            String behaviorDesc = behaviorProfileBuilder.buildSummary(behaviorProfile);
            if (!behaviorDesc.isBlank()) {
                messages.add(UserMessage.from("【读者近期画像】\n" + behaviorDesc
                        + "\n回答时可在相关处自然贴合，不要生硬提及读者画像。"));
            }
        }
        // 用户问题 + 回答要求作为最后一个 UserMessage（任务目标，位置突出）
        messages.add(UserMessage.from(questionMessage));
        return messages;
    }

// ==================== 内容就绪等待 ====================

    /**
     * 等待图书内容向量就绪，利用 ensureContentEmbedded 的 @RedisLock 防止并发重写
     *
     * @return true=向量可用，false=不可用
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

    /**
     * 确保内容向量就绪：若未嵌入则阻塞等待按需生成（最长 1 小时）。
     *
     * @return true=就绪，false=失败（错误已通过 SSE 发送）
     */
    private boolean ensureContentEmbedded(Book book, Long bookId, SseEmitter emitter) {
        if (Boolean.TRUE.equals(book.getContentEmbedded())) return true;

        log.info("图书未生成内容向量，尝试按需生成: bookId={}", bookId);
        try {
            emitter.send(SseEmitter.event().name("thinking_content")
                    .data("这本书我第一次读，花点时间消化一下…\n"));
        } catch (Exception ignored) {
        }

        boolean done = false;
        long deadline = System.currentTimeMillis() + 3600_000L;
        while (System.currentTimeMillis() < deadline) {
            Boolean result = bookParserService.ensureContentEmbedded(bookId);
            if (result == null) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } else {
                if (result) {
                    book.setContentEmbedded(true);
                    log.info("按需生成内容向量成功: bookId={}", bookId);
                    return true;
                } else {
                    SseHelper.sendErrorAndComplete(emitter, "该书无法提取文本内容，无法进行 AI 问答");
                    return false;
                }
            }
        }
        if (!done) {
            SseHelper.sendErrorAndComplete(emitter, "内容向量生成等待超时，请稍后重试");
        }
        return false;
    }

    /**
     * 确保 compressedSummary 就绪：若为空且 chapterSummary 存在则懒生成。
     */
    private void ensureCompressedSummary(Book book, SseEmitter emitter) {
        if (book.getCompressedSummary() != null && !book.getCompressedSummary().isBlank()) return;
        if (book.getChapterSummary() == null || book.getChapterSummary().isBlank()) return;

        try {
            emitter.send(SseEmitter.event().name("thinking_content")
                    .data("让我理一理这本书的脉络…\n"));
        } catch (Exception ignored) {
        }
        String compressed = bookService.generateCompressedSummary(book);
        if (compressed != null && !compressed.isBlank()) {
            book.setCompressedSummary(compressed);
            bookService.updateBook(book.getId(), book);
            log.info("compressedSummary 懒生成成功: bookId={}, len={}", book.getId(), compressed.length());
        }
    }

// ==================== 提示词构建 ====================

    /**
     * 静态书籍语料 —— 唯一允许进入 provider 缓存前缀的书籍内容。
     * <p>
     * 只包含导入期确定、不随用户行为变化的字段。评分/阅读量等运行时数据
     * 由 {@link #buildDynamicBookMeta} 单独承接到历史消息之后。
     * 按书规模分档时，SMALL 档在此追加全书原文、MEDIUM 档追加整章原文，
     * 依然保持「同一本书 + 同一档位 → 逐字节相同」。
     */
    private String buildStaticBookCorpus(Book book) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前讨论的书籍】\n");
        sb.append("书名：《").append(book.getTitle()).append("》\n");
        if (book.getAuthor() != null && !book.getAuthor().isBlank()) {
            sb.append("作者：").append(book.getAuthor()).append("\n");
        }
        appendBookTags(sb, book);
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            sb.append("简介：").append(book.getDescription()).append("\n");
        }
        if (book.getToc() != null && !book.getToc().isBlank()) {
            sb.append("目录：\n").append(book.getToc()).append("\n");
        }
        // 摘要优先 compressedSummary（导入期已预生成）→ chapterSummary，均完整不截断
        if (book.getCompressedSummary() != null && !book.getCompressedSummary().isBlank()) {
            sb.append("\n【图书精炼摘要】\n").append(book.getCompressedSummary()).append("\n");
        } else if (book.getChapterSummary() != null && !book.getChapterSummary().isBlank()) {
            sb.append("\n【章节摘要】\n").append(book.getChapterSummary()).append("\n");
        }
        return sb.toString();
    }

    /** 标签清洗，静态语料与合并版共用。 */
    private static void appendBookTags(StringBuilder sb, Book book) {
        if (book.getFormatTags() != null && !book.getFormatTags().isBlank()) {
            sb.append("标签：").append(book.getFormatTags().replaceAll("[\\[\\]\"]", "").replace(",", "、")).append("\n");
        }
        if (book.getConceptTags() != null && !book.getConceptTags().isBlank()) {
            sb.append("核心概念：").append(book.getConceptTags().replaceAll("[\\[\\]\"]", "").replace(",", "、")).append("\n");
        }
        if (book.getReaderNeedTags() != null && !book.getReaderNeedTags().isBlank()) {
            sb.append("读者需求：").append(book.getReaderNeedTags().replaceAll("[\\[\\]\"]", "").replace(",", "、")).append("\n");
        }
        if (book.getTargetReaderTags() != null && !book.getTargetReaderTags().isBlank()) {
            sb.append("目标读者：").append(book.getTargetReaderTags().replaceAll("[\\[\\]\"]", "").replace(",", "、")).append("\n");
        }
    }

    /** 动态书籍元信息：每次评分/阅读都可能变化，禁止进入缓存前缀。 */
    private String buildDynamicBookMeta(Book book) {
        StringBuilder sb = new StringBuilder();
        if (book.getRating() != null) {
            sb.append("评分：").append(String.format("%.1f", book.getRating()));
            if (book.getRatingCount() != null && book.getRatingCount() > 0) {
                sb.append("（").append(book.getRatingCount()).append("人评分）");
            }
            sb.append("\n");
        }
        if (book.getReadCount() != null && book.getReadCount() > 0) {
            sb.append("阅读量：").append(book.getReadCount()).append("次\n");
        }
        return sb.length() == 0 ? "" : "【图书动态信息】\n" + sb;
    }

    /**
     * 构建图书基本信息提示词（静态 + 动态的合并版）。
     * <p>
     * 保留原有字段顺序，专供列表条目流水线使用（该流水线做过大量 A/B 调优，
     * 不宜因缓存改造改变提示词形态）。SSE 主链路已改用
     * {@link #buildStaticBookCorpus} + {@link #buildDynamicBookMeta} 的拆分版。
     *
     * @param book 书籍实体
     * @return 图书基本信息文本
     */
    private String buildBookInfoPrompt(Book book) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前讨论的书籍】\n");
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
        if (book.getRating() != null) {
            sb.append("评分：").append(String.format("%.1f", book.getRating()));
            if (book.getRatingCount() != null && book.getRatingCount() > 0) {
                sb.append("（").append(book.getRatingCount()).append("人评分）");
            }
            sb.append("\n");
        }
        if (book.getReadCount() != null && book.getReadCount() > 0) {
            sb.append("阅读量：").append(book.getReadCount()).append("次\n");
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
     * 构建 RAG 参考内容消息（作为独立 UserMessage，与用户问题分离）
     * <p>
     * 设计：把 RAG 内容单独包装，让 LLM 明确这是"参考资料"而非"任务描述"。
     * RAG 内容可能很长（数千到数万字），与用户问题混在一起会淹没问题本身。
     *
     * @param ragContext RAG 检索结果（可为空）
     * @return RAG 消息字符串；ragContext 为空时返回空字符串（不加入消息列表）
     */
    private String buildRagMessage(String ragContext) {
        if (ragContext == null || ragContext.isBlank()) return "";

        String message = "【书籍参考内容】（以下是从原著中检索到的与问题相关的片段，不是全书完整内容）\n"
                + ragContext;

        // 乱码诊断：问号占比过高提示编码异常
        long qmCount = message.chars().filter(c -> c == '?').count();
        if (qmCount > message.length() * 0.05) {
            log.warn("[编码诊断] RAG内容疑似乱码! 问号占比={}/{}", qmCount, message.length());
        }
        return message;
    }

    /**
     * 构建用户问题 + 回答要求消息（作为最后一个 UserMessage，位置突出）
     * <p>
     * 设计：把用户问题和回答指引打包为独立消息，置于消息列表末尾，
     * 让 LLM 在生成时注意力集中在问题上，而非被 RAG 内容淹没。
     * <p>
     * 覆盖度契约（阶段1 止血）：当本轮被检测为列表展开型问题时，在消息末尾追加
     * 强约束——每一项都必须输出、禁止合并省略。与系统提示词的【列表展开豁免】配合，
     * 对抗"展开这5点只写了前3点"的丢尾行为。
     *
     * @param question  用户原始问题
     * @param listTopic 列表主题（来自列表型检测；非列表型问题为 null）
     * @return 问题消息字符串
     */
    /** 保留原签名供既有单测与调用方使用（默认按检索档处理）。 */
    static String buildQuestionMessage(String question, String listTopic, int citationCount) {
        return buildQuestionMessage(question, listTopic, citationCount, ContextMode.LARGE);
    }

    /**
     * 按上下文模式切换引用契约：全文/整章档没有「【片段 N】」，若继续要求 [[N]]，
     * 模型会编造不存在的片段编号，反而触发 CitationValidator 剔除与 replace 事件。
     */
    static String buildQuestionMessage(String question, String listTopic, int citationCount,
                                       ContextMode mode) {
        StringBuilder sb = new StringBuilder(
                "【读者的问题】\n" + question + "\n\n请根据上述内容，用中文回答。【回答铁律】已在上方告知，请严格遵守。");

        if (mode != null && mode.fullContext()) {
            sb.append("\n\n【引用契约】全书或相关章节原文已完整提供，请直接依据原文作答。")
              .append("可以指出章节名或位置帮助读者定位，但不要编造「【片段 N】」这类片段编号；")
              .append("没有原文依据的判断请明确说明。");
        } else if (citationCount > 0) {
            sb.append("\n\n【引用契约】参考内容以「【片段 N】」分隔。")
              .append("每个来自书里的关键事实后面用 [[N]] 标注来源（如 [[1]] 或 [[1,2]]）；")
              .append("N 只能是参考内容中出现过的片段编号，不要编造；没有依据的判断不要标注。");
        }

        if (listTopic == null || listTopic.isBlank()) {
            return sb.toString();
        }
        sb.append("\n\n【覆盖度契约】本轮问题要求展开列表「").append(listTopic).append("」：")
          .append("参考内容中该列表的每一项都必须单独成节输出，一项不落，")
          .append("禁止合并条目、禁止只展开前几项后省略其余；某一项在参考内容中确实缺失时，")
          .append("也要为该单项一小节并说明这部分材料不足，不许静默跳过。");
        return sb.toString();
    }

    // ==================== 委托（公开 API 保持原签名，Controller 零改动） ====================

    /**
     * 查询某条回答实际使用的检索依据（归属校验：必须属于该用户与该书）。
     * 评测脚本与前端引用区共用；无权限/非 assistant 行返回空列表。
     */
    @LogAction("查询回答依据")
    public List<AiRetrievalSnapshot> getRetrievalEvidence(Long userId, Long bookId, Long conversationId) {
        AiConversation conversation = sessionService.findConversationForUser(userId, bookId, conversationId);
        if (conversation == null || !"assistant".equals(conversation.getRole())) {
            return List.of();
        }
        return retrievalSnapshotService.findByConversationId(conversationId);
    }

    @LogAction("获取推荐问题")
    public List<String> getSuggestedQuestions(Long bookId) {
        return followUpService.getSuggestedQuestions(bookId);
    }

    @LogAction("获取问答历史")
    public List<AiConversation> getBookChatHistory(Long userId, Long bookId, String sessionId) {
        return sessionService.getBookChatHistory(userId, bookId, sessionId);
    }

    @LogAction("获取问答会话列表")
    public List<AiSession> getBookChatSessions(Long userId, Long bookId) {
        return sessionService.getBookChatSessions(userId, bookId);
    }

    @LogAction("生成深入追问")
    public List<String> generateFollowUpQuestions(Long userId, Long bookId, String question, String answer) {
        return followUpService.generateFollowUpQuestions(userId, bookId, question, answer);
    }

    @org.springframework.transaction.annotation.Transactional
    @LogAction("保存追问问题")
    public void saveFollowUpQuestions(Long userId, String sessionId, Long bookId, List<String> questions) {
        sessionService.saveFollowUpQuestions(userId, sessionId, bookId, questions);
    }

    @LogAction("导出图书问答对话")
    public Map<String, String> exportBookChatHistory(Long userId, Long bookId, String sessionId) {
        return sessionService.exportBookChatHistory(userId, bookId, sessionId);
    }
}
