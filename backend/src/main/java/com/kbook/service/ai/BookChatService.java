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
import com.kbook.config.ai.AiConfigProvider;
import com.kbook.constants.AiPromptConstants;
import com.kbook.dto.book.BookProjection;
import com.kbook.entity.AiConversation;
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

/**
 * 图书问答服务
 * <p>
 * 基于书籍内容的 RAG（检索增强生成）问答服务。
 * 用户针对某本书提问时，先通过向量检索从书籍内容中获取相关片段，
 * 再将片段作为上下文交给 AI 生成回答。支持 SSE 流式输出、
 * 列表展开流水线（多检索多段生成聚合为单条回答）、按需生成内容向量、深入追问等功能。
 */
@Slf4j
@Service
@LogModule("图书问答")
public class BookChatService {

    /**
     * 会话类型标识：图书问答
     */
    private static final String TYPE = "book_chat";
    private static final int SPEED_READ_CONTENT_LIMIT = 15000;

    private final EmbeddingService embeddingService;
    private final BookService bookService;
    private final BookParserService bookParserService;
    private final AiProviderConfigService aiProviderConfigService;
    private final AiSceneConfigService aiSceneConfigService;
    private final ChatModelFactory chatModelFactory;
    private final ChatModelManager chatModelManager;
    private final ChatHistoryCompressor chatHistoryCompressor;
    private final AiConversationRepository conversationRepository;
    private final AiSessionRepository sessionRepository;
    private final BookSuggestedQuestionRepository suggestedQuestionRepository;
    private final QdrantProperties qdrantProperties;
    private final RagHitStatisticsService ragHitStatisticsService;
    private final BookQuestionGenService questionGenService;
    private final UserService userService;
    private final AiConfigProvider aiConfigProvider;
    private final ExecutorService sseExecutor;
    private final ObjectMapper objectMapper;
    // 列表型问题 RAG 优化
    private final ListQueryDetector listQueryDetector;
    private final ListQueryStrategySelector listQueryStrategySelector;
    private final ListQueryRetriever listQueryRetriever;
    // 列表展开流水线（阶段2）：逐条定向检索 + outline 条目化
    private final ItemListRetriever itemListRetriever;
    private final com.kbook.service.ai.outline.AnswerOutlineService answerOutlineService;
    private final java.util.concurrent.ExecutorService qaOutlineExecutor;
    private final UserProfileBuilder userProfileBuilder;
    private final BehaviorProfileBuilder behaviorProfileBuilder;
    private final UserBehaviorProfileRepository behaviorProfileRepository;
    private final ApplicationEventPublisher eventPublisher;

    public BookChatService(
            EmbeddingService embeddingService,
            BookService bookService,
            BookParserService bookParserService,
            AiProviderConfigService aiProviderConfigService,
            AiSceneConfigService aiSceneConfigService,
            ChatModelFactory chatModelFactory,
            ChatModelManager chatModelManager,
            ChatHistoryCompressor chatHistoryCompressor,
            AiConversationRepository conversationRepository,
            AiSessionRepository sessionRepository,
            BookSuggestedQuestionRepository suggestedQuestionRepository,
            QdrantProperties qdrantProperties,
            RagHitStatisticsService ragHitStatisticsService,
            BookQuestionGenService questionGenService,
            UserService userService,
            AiConfigProvider aiConfigProvider,
            ObjectMapper objectMapper,
            ListQueryDetector listQueryDetector,
            ListQueryStrategySelector listQueryStrategySelector,
            ListQueryRetriever listQueryRetriever,
            ItemListRetriever itemListRetriever,
            com.kbook.service.ai.outline.AnswerOutlineService answerOutlineService,
            @Qualifier("qaOutlineExecutor") java.util.concurrent.ExecutorService qaOutlineExecutor,
            UserProfileBuilder userProfileBuilder,
            BehaviorProfileBuilder behaviorProfileBuilder,
            UserBehaviorProfileRepository behaviorProfileRepository,
            ApplicationEventPublisher eventPublisher,
            @Qualifier("sseExecutor") ExecutorService sseExecutor) {
        this.embeddingService = embeddingService;
        this.bookService = bookService;
        this.bookParserService = bookParserService;
        this.aiProviderConfigService = aiProviderConfigService;
        this.aiSceneConfigService = aiSceneConfigService;
        this.chatModelFactory = chatModelFactory;
        this.chatModelManager = chatModelManager;
        this.chatHistoryCompressor = chatHistoryCompressor;
        this.conversationRepository = conversationRepository;
        this.sessionRepository = sessionRepository;
        this.suggestedQuestionRepository = suggestedQuestionRepository;
        this.qdrantProperties = qdrantProperties;
        this.ragHitStatisticsService = ragHitStatisticsService;
        this.questionGenService = questionGenService;
        this.userService = userService;
        this.aiConfigProvider = aiConfigProvider;
        this.objectMapper = objectMapper;
        this.listQueryDetector = listQueryDetector;
        this.listQueryStrategySelector = listQueryStrategySelector;
        this.listQueryRetriever = listQueryRetriever;
        this.itemListRetriever = itemListRetriever;
        this.answerOutlineService = answerOutlineService;
        this.qaOutlineExecutor = qaOutlineExecutor;
        this.userProfileBuilder = userProfileBuilder;
        this.behaviorProfileBuilder = behaviorProfileBuilder;
        this.behaviorProfileRepository = behaviorProfileRepository;
        this.eventPublisher = eventPublisher;
        this.sseExecutor = sseExecutor;
    }

    /**
     * 根据书籍标签获取预设推荐问题
     *
     * @param book 书籍实体
     * @return 推荐问题列表
     */
    private List<String> getSuggestedQuestionsForBook(BookProjection book) {
        if (book.getFormatTags() == null || book.getFormatTags().isBlank()) {
            return BookTagQuestions.getQuestions(null);
        }

        try {
            var mapper = objectMapper;
            var tags = mapper.readValue(book.getFormatTags(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
            return BookTagQuestions.getQuestions(tags);
        } catch (Exception e) {
            log.debug("解析图书标签失败: bookId={}", book.getId());
        }

        return BookTagQuestions.getQuestions(null);
    }

    /**
     * 获取图书推荐问题列表
     * 优先使用已生成的推荐问题，否则基于标签返回预设问题并异步触发生成
     *
     * @param bookId 书籍ID
     * @return 推荐问题列表
     */
    @LogAction("获取推荐问题")
    public List<String> getSuggestedQuestions(Long bookId) {
        List<BookSuggestedQuestion> existing = suggestedQuestionRepository.query()
                .where(BookSuggestedQuestion::getBookId, eq(bookId))
                .list();
        if (!existing.isEmpty()) {
            String introQuestion = "这本书主要讲了什么？";
            List<String> all = existing.stream()
                    .map(BookSuggestedQuestion::getQuestion)
                    .filter(q -> !introQuestion.equals(q))
                    .collect(Collectors.toList());
            List<String> selected = getRandomQuestions(all);
            selected.add(0, introQuestion);
            return selected;
        }

        questionGenService.asyncGenerateQuestions(bookId);

        try {
            BookProjection book = bookService.getBookProjectionById(bookId);
            return getSuggestedQuestionsForBook(book);
        } catch (Exception e) {
            return BookTagQuestions.getQuestions(null);
        }
    }

    /**
     * 从问题池中随机选取最多6个问题
     */
    private List<String> getRandomQuestions(List<String> questions) {
        if (questions.size() <= 6) {
            return new ArrayList<>(questions);
        }
        List<String> shuffled = new ArrayList<>(questions);
        java.util.Collections.shuffle(shuffled);
        return shuffled.subList(0, 10);
    }

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
    public SseEmitter streamBookChat(Long userId, Long bookId, String question, String sessionId, boolean manual) {
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
                // 上一轮 AI 回答（用于追问场景的 RAG 查询扩展，不缓存答案——不同用户画像应得到不同回答）
                String lastAiAnswer = getLastAiAnswer(userId, effectiveSessionId);

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
                int ragMaxChars = getRagMaxChars();
                String ragContext = null;
                String listTopic = null;
                List<EmbeddingMatch<TextSegment>> ragMatches = null;
                ListQueryDetector.DetectionResult detection = ListQueryDetector.DetectionResult.notList();
                if (embeddingService.isAvailable() && waitForContentEmbedding(book.getId())) {
                    // 检测提前到检索之前（§3.4 流水线触发第一步）：一次 LLM 调用，
                    // 列表型分支内部复用传入的 detection 结果，不再重复检测。
                    detection = listQueryDetector.detect(question, lastAiAnswer);
                    try {
                        RagRetrievalResult ragResult =
                                doRagRetrieval(book, question, lastAiAnswer, ragTopK, ragMaxChars,
                                        emitter, detection);
                        ragContext = ragResult.ragContext();
                        listTopic = ragResult.listTopic();
                        ragMatches = ragResult.matches();
                    } catch (Exception e) {
                        log.warn("RAG 检索异常: bookId={} - {}", book.getId(), e.getMessage());
                        try {
                            emitter.send(SseEmitter.event().name("thinking_content").data("没找到直接相关的内容，凭印象回答你…\n"));
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (ragContext != null) {
                    log.debug("RAG 检索结果长度: {}", ragContext.length());
                }

                // 构建图书基本信息（静态，跨会话共享 KV Cache）
                String bookInfoPrompt = buildBookInfoPrompt(book);

                // ===== 列表展开流水线触发判定（§3.4）=====
                // 条件：检测为列表型 && outline 可得（DB 优先，缺失现场条目化）且 items≥2 && ≤ MAX_PIPELINE_ITEMS
                // 任何一条不满足 → 完全退回现有单次流式路径（行为不比现状差，Q4）
                com.kbook.service.ai.outline.AnswerOutline pipelineOutline =
                        resolvePipelineOutline(detection, userId, effectiveSessionId, emitter,
                                lastAiAnswer, ragMatches);

                if (pipelineOutline != null) {
                    streamItemListPipeline(book, question, pipelineOutline,
                            bookInfoPrompt, model, userId, effectiveSessionId, emitter, manual);
                    return;
                }

                // ===== 回退路径：现有单次流式（ListQueryRetriever 三档检索保留为回退，§8）=====
                // RAG 参考内容 + 用户问题分别构造为独立消息（结构清晰，避免问题被 RAG 淹没）
                String ragMessage = buildRagMessage(ragContext);
                String questionMessage = buildQuestionMessage(question, listTopic);

                log.debug("图书问答: bookId={}, question={}, bookInfoLen={}, ragContextLen={}, questionMsgLen={}",
                        bookId, question, bookInfoPrompt.length(),
                        ragContext != null ? ragContext.length() : 0, questionMessage.length());

                // SystemMessage → UserMessage(bookInfo) → HistoryMessages → UserMessage(RAG) → UserMessage(question)
                List<ChatMessage> messages = buildChatMessages(effectiveSessionId, userId, bookInfoPrompt, ragMessage, questionMessage);

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

                        ensureSession(userId, effectiveSessionId, question, bookId);
                        saveMessage(userId, effectiveSessionId, "user", question, bookId, null);
                        String thinkingText = !fullThinking.isEmpty() ? fullThinking.toString() : null;
                        // 输出审查 P1 #17：过滤可能的系统提示泄露后再持久化和缓存
                        String safeAnswer = CommonUtils.sanitizeAiOutput(answer);
                        // 检测到泄露时发送 replace 事件覆盖前端已显示的流式内容
                        if (!safeAnswer.equals(answer)) {
                            try {
                                emitter.send(SseEmitter.event().name("replace").data(safeAnswer));
                                log.warn("已发送 replace 事件覆盖泄露内容: bookId={}, sessionId={}", bookId, effectiveSessionId);
                            } catch (Exception ignored) {
                            }
                        }
                        AiConversation assistantRow = saveMessage(userId, effectiveSessionId, "assistant",
                                safeAnswer, bookId, thinkingText, mainFinishReason.get());
                        updateSessionTimestamp(effectiveSessionId);
                        // 发布用户提问信号（异步由 BehaviorProfileService 消费）
                        publishUserSignal(userId, effectiveSessionId, bookId, question, manual);
                        // §4.5：回答含枚举结构 → 异步条目化落库（纯服务端状态，失败不影响主流程）
                        scheduleOutline(assistantRow, safeAnswer, detectionFinal.isListQuery());
                    }

                    @Override
                    public void onConnectionClosed(String partialContent) {
                        // 连接断开时仍然保存已输出的部分内容（与原行为一致）
                        ensureSession(userId, effectiveSessionId, question, bookId);
                        saveMessage(userId, effectiveSessionId, "user", question, bookId, null);
                        String thinkingText = !fullThinking.isEmpty() ? fullThinking.toString() : null;
                        saveMessage(userId, effectiveSessionId, "assistant",
                                CommonUtils.sanitizeAiOutput(partialContent), bookId, thinkingText);
                        updateSessionTimestamp(effectiveSessionId);
                        // 连接断开也算用户提过问
                        publishUserSignal(userId, effectiveSessionId, bookId, question, manual);
                    }

                    @Override
                    public void onError(Throwable error) {
                        aiProviderConfigService.clearAssistantCache();
                    }
                }, 2, logContext);

            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) return;
                log.error("图书问答异常: bookId={} - {}", bookId, e.getMessage(), e);
                aiProviderConfigService.clearAssistantCache();
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

    /**
     * 获取图书问答的历史消息
     *
     * @param userId    用户ID
     * @param bookId    书籍ID
     * @param sessionId 会话ID（指定则返回该会话，否则返回最新会话）
     * @return 对话记录列表
     */
    @LogAction("获取问答历史")
    public List<AiConversation> getBookChatHistory(Long userId, Long bookId, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return conversationRepository.findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
        }
        List<AiSession> sessions = sessionRepository.query()
                .where(AiSession::getUserId, eq(userId))
                .and(AiSession::getType, eq(TYPE))
                .and(AiSession::getBookId, eq(bookId))
                .orderByDesc(AiSession::getUpdatedAt)
                .list();
        if (sessions.isEmpty()) {
            return Collections.emptyList();
        }
        return conversationRepository.findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessions.get(0).getSessionId());
    }

    @LogAction("获取问答会话列表")
    public List<AiSession> getBookChatSessions(Long userId, Long bookId) {
        return sessionRepository.query()
                .where(AiSession::getUserId, eq(userId))
                .and(AiSession::getType, eq(TYPE))
                .and(AiSession::getBookId, eq(bookId))
                .orderByDesc(AiSession::getUpdatedAt)
                .list();
    }

    /**
     * 根据已有问答生成深入追问问题（包含用户画像和图书信息）
     *
     * @param userId   用户ID
     * @param bookId   书籍ID
     * @param question 原始问题
     * @param answer   AI 回答
     * @return 深入追问问题列表（最多3个）
     */
    @LogAction("生成深入追问")
    public List<String> generateFollowUpQuestions(Long userId, Long bookId, String question, String answer) {
        if (answer == null || answer.isBlank() || question == null || question.isBlank()) {
            return Collections.emptyList();
        }

        String title = "未知书籍";
        Book book = bookService.getBookById(bookId);
        if (book != null) {
            title = book.getTitle();
        }

        User user = userService.getUserById(userId);
        return generateFollowUpQuestions(title, question, answer, user, book);
    }

    /**
     * 构建包含系统提示词、图书基本信息、历史对话和当前问题的完整消息列表。
     * <p>
     * 消息顺序（优化 KV Cache 命中）：
     * SystemMessage（固定提示词）→ UserMessage（图书基本信息，静态）→
     * 历史对话 → UserMessage（RAG + 当前问题）
     * <p>
     * 历史消息数量由压缩机制动态控制，不硬限轮数
     *
     * @param sessionId      会话ID
     * @param userId         用户ID
     * @param bookInfoPrompt 图书基本信息提示词（静态，用于 KV Cache 前缀复用）
     * @param ragMessage     RAG 参考内容消息（可为空）
     * @param questionMessage 用户问题 + 回答要求消息
     * @return 完整的 ChatMessage 列表
     */
    private List<ChatMessage> buildChatMessages(String sessionId, Long userId, String bookInfoPrompt,
                                                String ragMessage, String questionMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        String style = getChatStyleForUser(userId);
        String systemPrompt = getSystemPromptForStyle(style);
        messages.add(SystemMessage.from(systemPrompt));

        // 图书基本信息作为独立 UserMessage（静态前缀，跨会话共享 KV Cache）
        if (bookInfoPrompt != null && !bookInfoPrompt.isBlank()) {
            messages.add(UserMessage.from(bookInfoPrompt));
        }

        try {
            // 加载历史消息并同步压缩，确保 LLM 上下文不超限
            int currentOverhead = AiPromptConstants.BOOK_CHAT_SYSTEM_PROMPT.length()
                    + (bookInfoPrompt != null ? bookInfoPrompt.length() : 0)
                    + (ragMessage != null ? ragMessage.length() : 0)
                    + (questionMessage != null ? questionMessage.length() : 0)
                    + 2000; // AI 回复预留
            List<AiConversation> history = loadAndCompressHistory(userId, sessionId, currentOverhead);
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

        // RAG 参考内容作为独立 UserMessage（参考资料性质，与问题分离）
        if (ragMessage != null && !ragMessage.isBlank()) {
            messages.add(UserMessage.from(ragMessage));
        }
        // 行为画像（L2 画像）— 精简版本 ≤ 200 字，放在问题前让 LLM 自然贴合
        UserBehaviorProfile behaviorProfile = loadBehaviorProfile(userId);
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

    /**
     * 获取指定会话中最近一次 AI 回复内容
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return 最近一条 assistant 消息的内容，无则返回 null
     */
    private String getLastAiAnswer(Long userId, String sessionId) {
        try {
            List<AiConversation> history = conversationRepository
                    .findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
            for (int i = history.size() - 1; i >= 0; i--) {
                if ("assistant".equals(history.get(i).getRole())) {
                    return history.get(i).getCompressedContent();
                }
            }
        } catch (Exception e) {
            log.debug("获取上次AI回答失败: {}", e.getMessage());
        }
        return null;
    }

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


    /** 邻域扩展候选数量：前 1 后 2 */
    private static final int NEIGHBOR_PREV = 1;
    private static final int NEIGHBOR_NEXT = 2;

    /**
     * 构建图书基本信息提示词（纯静态信息，用于 KV Cache 前缀复用）
     * 包括书名、作者、标签、简介、目录、摘要。不会变化的数据放在这里。
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
    private String buildQuestionMessage(String question, String listTopic) {
        String base = "【读者的问题】\n" + question + "\n\n请根据上述内容，用中文回答。【回答铁律】已在上方告知，请严格遵守。";
        if (listTopic == null || listTopic.isBlank()) {
            return base;
        }
        return base + "\n\n【覆盖度契约】本轮问题要求展开列表「" + listTopic + "」："
                + "参考内容中该列表的每一项都必须单独成节输出，一项不落，"
                + "禁止合并条目、禁止只展开前几项后省略其余；某一项在参考内容中确实缺失时，"
                + "也要为该单项一小节并说明这部分材料不足，不许静默跳过。";
    }

    /**
     * 阶段0诊断：提取一组 chunk 的索引分布概况（数量、最小/最大索引、跨度、是否覆盖全书中段）。
     * <p>
     * 用途：判断列表型问题"展开丢尾项"是否因检索材料只聚集在书中某一区域——
     * 若 span 很窄（例如全部落在前 1/5 区间），第 4、5 项根本没有原文可依据，
     * 这与"数据量够不够"无关，而是覆盖面的问题。
     *
     * @return 形如 {@code idx[n=6,min=12,max=38,span=26]} 的诊断串；无有效索引时返回 {@code idx[none]}
     */
    private static String describeChunkIndexSpan(List<EmbeddingMatch<TextSegment>> chunks) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int valid = 0;
        for (EmbeddingMatch<TextSegment> m : chunks) {
            int idx = RagPipelineComponents.getChunkIndex(m);
            if (idx < 0) continue;
            valid++;
            min = Math.min(min, idx);
            max = Math.max(max, idx);
        }
        if (valid == 0) return "idx[none]";
        return "idx[n=" + valid + ",min=" + min + ",max=" + max + ",span=" + (max - min + 1) + "]";
    }

    /**
     * 确保会话记录存在，不存在则自动创建（含 bookId）
     */
    private void ensureSession(Long userId, String sessionId, String userMessage, Long bookId) {
        sessionRepository.query()
                .where(AiSession::getSessionId, eq(sessionId))
                .list(1)
                .stream().findFirst()
                .orElseGet(() -> {
                    String title = CommonUtils.truncateText(userMessage, 30);
                    AiSession session = AiSession.builder()
                            .userId(userId)
                            .type(TYPE)
                            .bookId(bookId)
                            .sessionId(sessionId)
                            .title(title)
                            .build();
                    return sessionRepository.save(session);
                });
    }

    private void updateSessionTimestamp(String sessionId) {
        sessionRepository.query()
                .where(AiSession::getSessionId, eq(sessionId))
                .list(1)
                .stream().findFirst()
                .ifPresent(session -> {
                    session.setUpdatedAt(java.time.LocalDateTime.now());
                    sessionRepository.save(session);
                });
    }

    /**
     * 保存图书问答消息记录
     */
    private void saveMessage(Long userId, String sessionId, String role, String content, Long bookId,
                             String thinkingContent) {
        saveMessage(userId, sessionId, role, content, bookId, thinkingContent, null);
    }

    /**
     * 保存图书问答消息记录（可带 finishReason；assistant 行供 §4.5 outline 关联与截断诊断）。
     *
     * @return 落库成功的记录（含 ID）；失败返回 null
     */
    private AiConversation saveMessage(Long userId, String sessionId, String role, String content, Long bookId,
                                       String thinkingContent, String finishReason) {
        try {
            AiConversation record = AiConversation.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .type(TYPE)
                    .bookId(bookId)
                    .role(role)
                    .content(content)
                    .compressedContent(content) // 初始等于原始内容
                    .thinkingContent(thinkingContent)
                    .followUpQuestions(null)
                    .finishReason(finishReason)
                    .build();
            return conversationRepository.save(record);
        } catch (Exception e) {
            log.warn("保存图书问答记录失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 发布用户提问信号到行为画像服务（异步消费，不阻塞主回答）。
     */
    private void publishUserSignal(Long userId, String sessionId, Long bookId, String question, boolean manual) {
        try {
            eventPublisher.publishEvent(new UserBehaviorSignalEvent(
                    userId, sessionId, TYPE, bookId, question, manual));
        } catch (Exception e) {
            log.debug("发布行为信号失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * 加载用户行为画像（L2 画像），用于主回答 prompt 注入。
     * 失败返回 null，不影响主流程。
     */
    private UserBehaviorProfile loadBehaviorProfile(Long userId) {
        try {
            return behaviorProfileRepository.findByUserId(userId).orElse(null);
        } catch (Exception e) {
            log.debug("加载行为画像失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 根据用户设置返回对应的系统提示词
     */
    private String getChatStyleForUser(Long userId) {
        try {
            var user = userService.getUserById(userId);
            if (user != null && user.getBookChatStyle() != null) {
                return user.getBookChatStyle();
            }
        } catch (Exception e) {
            log.debug("获取用户对话风格失败，使用默认风格: {}", e.getMessage());
        }
        return "DEEP";
    }

    /**
     * 根据对话风格标识返回对应的系统提示词
     * 优先从 ai-config.json 外部配置加载，找不到时回退到代码常量
     * 支持 CASUAL（轻松）、CONCISE（简洁）、WITTY（幽默）、DEEP（深入）四种风格
     *
     * @param style 对话风格标识
     * @return 对应的系统提示词文本
     */
    private String getSystemPromptForStyle(String style) {
        // 优先从外部配置加载
        String configPrompt = aiConfigProvider.getChatStylePrompt(style);
        if (!configPrompt.isEmpty()) {
            return configPrompt;
        }
        // 回退到代码常量
        if (style != null) {
            return switch (style.toUpperCase()) {
                case "CASUAL" -> AiPromptConstants.BOOK_CHAT_STYLE_CASUAL;
                case "CONCISE" -> AiPromptConstants.BOOK_CHAT_STYLE_CONCISE;
                case "WITTY" -> AiPromptConstants.BOOK_CHAT_STYLE_WITTY;
                default -> AiPromptConstants.BOOK_CHAT_STYLE_DEEP;
            };
        }
        return AiPromptConstants.BOOK_CHAT_STYLE_CASUAL;
    }

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
     * 加载历史消息并同步压缩，一次 DB 查询完成加载+压缩判断+压缩执行。
     *
     * @param userId               用户ID
     * @param sessionId            会话ID
     * @param currentOverheadChars 当前请求的系统开销字符数（系统提示词、RAG 上下文等固定部分）
     * @return 压缩后的对话记录列表，可直接用于构建 ChatMessage
     */
    private List<AiConversation> loadAndCompressHistory(Long userId, String sessionId, int currentOverheadChars) {
        // 1. 一次查询加载所有消息
        List<AiConversation> conversations = conversationRepository
                .findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
        if (conversations.isEmpty()) return conversations;

        // 2. 内存计算总字符数（优先 compressedContent）
        long totalChars = conversations.stream()
                .mapToLong(c -> {
                    String compressed = c.getCompressedContent();
                    return compressed != null ? compressed.length()
                            : (c.getContent() != null ? c.getContent().length() : 0);
                })
                .sum();

        // 3. 计算触发阈值（从 BOOK_QA 场景绑定配置读，跟随场景路由）
        Integer maxTokens = null;
        try {
            AiProviderConfig sceneConfig = aiSceneConfigService.resolveConfig(AiScene.BOOK_QA);
            if (sceneConfig != null) {
                maxTokens = sceneConfig.getMaxTokens();
            }
        } catch (Exception e) {
            log.warn("解析 BOOK_QA 场景配置失败，回退到默认 maxTokens: {}", e.getMessage());
        }
        int tokenLimit = maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS;
        int charLimit = (int) (tokenLimit * TOKEN_TO_CHAR_RATIO);

        long totalWithCurrent = totalChars + currentOverheadChars;
        log.debug("压缩检查: sessionId={}, history={}, current={}, total={}/{} ({}%)",
                sessionId, totalChars, currentOverheadChars,
                totalWithCurrent, charLimit,
                charLimit > 0 ? totalWithCurrent * 100 / charLimit : 0);

        // 4. 未达触发阈值，直接返回（不再做预算估算——batch 只有一次 LLM 调用，直接告诉比例即可）
        if (totalWithCurrent < charLimit * COMPRESS_TRIGGER_RATIO) {
            return conversations;
        }

        // 5. 收集所有未压缩的 assistant 消息，一次性批量压缩
        List<AiConversation> toCompress = conversations.stream()
                .filter(c -> "assistant".equals(c.getRole()))
                .filter(c -> {
                    String compressed = c.getCompressedContent();
                    String original = c.getContent();
                    return compressed == null || original == null || compressed.equals(original);
                })
                .collect(Collectors.toList());

        if (toCompress.isEmpty()) {
            return conversations;
        }

        // 6. 一次性批量压缩（单次 LLM 调用），替换原有逐条串行压缩
        List<String> originals = toCompress.stream()
                .map(AiConversation::getContent)
                .toList();
        List<String> summaries = chatHistoryCompressor.compressContentBatch(originals);
        if (summaries == null || summaries.size() != toCompress.size()) {
            log.warn("批量压缩返回异常(跳过): sessionId={}, expected={}, actual={}",
                    sessionId, toCompress.size(),
                    summaries != null ? summaries.size() : "null");
            return conversations;
        }

        int compressed = 0;
        for (int i = 0; i < toCompress.size(); i++) {
            String summary = summaries.get(i);
            if (summary == null || summary.isBlank()) {
                log.warn("单条压缩结果为空(跳过): sessionId={}, convId={}",
                        sessionId, toCompress.get(i).getId());
                continue;
            }
            AiConversation target = toCompress.get(i);
            String original = target.getContent();
            target.setCompressedContent(summary);
            conversationRepository.save(target);
            totalChars = totalChars - (original != null ? original.length() : 0) + summary.length();
            compressed++;
            log.info("压缩历史消息: sessionId={}, convId={}, {}→{} chars, totalChars={}",
                    sessionId, target.getId(), original != null ? original.length() : 0, summary.length(), totalChars);
        }

        if (compressed > 0) {
            log.info("压缩完成: sessionId={}, 批量压缩 {} 条（单次 LLM 调用）", sessionId, compressed);
        }

        return conversations;
    }

    /**
     * RAG 上下文最大字符数：maxTokens × 1.5 × 0.6（留 40% 给系统和对话）
     */
    private int getRagMaxChars() {
        // 从 BOOK_QA 场景绑定配置读 maxTokens（跟随场景路由）
        Integer maxTokens = null;
        try {
            AiProviderConfig sceneConfig = aiSceneConfigService.resolveConfig(AiScene.BOOK_QA);
            if (sceneConfig != null) {
                maxTokens = sceneConfig.getMaxTokens();
            }
        } catch (Exception e) {
            log.warn("解析 BOOK_QA 场景配置失败，回退到默认 maxTokens: {}", e.getMessage());
        }
        int tokens = maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS;
        return (int) (tokens * TOKEN_TO_CHAR_RATIO * 0.6);
    }

    /**
     * RAG 检索结果：上下文字符串 + 列表型主题。
     * <p>
     * listTopic 非空 = 本轮问题被判定为列表展开型（无论列表专项检索成功还是退化为常规 RAG）。
     * 主链路据此在问题消息末尾附加"覆盖度契约"，要求逐条展开、禁止省略后几项
     * （阶段1 止血项：主干路径下"5 点只展开 3 点"的直接对策之一）。
     */
    private record RagRetrievalResult(String ragContext, String listTopic,
                                      List<EmbeddingMatch<TextSegment>> matches) {
        static final RagRetrievalResult EMPTY = new RagRetrievalResult(null, null, null);
    }

    /**
     * 列表型问题 RAG 优化入口。
     * <p>
     * 流程：
     * 1. LLM 检测问题是否为列表型（含列表主题提取）
     * 2. 若是列表型 → 选档策略 → 执行对应检索流程
     * 3. 拼接 RAG 上下文返回
     * <p>
     * ragContext 为 null 表示：
     * - 不是列表型问题 → 走常规 RAG
     * - 是列表型但所有档位都失败 → 走常规 RAG（兜底）
     * 但 listTopic 在"是列表型"时始终保留，供回退路径的覆盖度契约使用。
     * <p>
     * matches：策略检索命中的向量匹配片段（含 chunkIndex 元数据），
     * 供流水线 §3.1③ 把条目 chunkAnchors 对齐到这些片段；检索失败/兜底时为 null。
     *
     * @return 检索结果（context + listTopic + matches）；非列表型或失败时 ragContext 为 null
     */
    private RagRetrievalResult tryListQueryRagRetrieval(Book book, String question, String lastAiAnswer,
                                            int ragMaxChars, SseEmitter emitter,
                                            ListQueryDetector.DetectionResult detection) {
        // 1. 检测列表型问题（检测已提前到检索入口，本处复用结果，不再重复调 LLM）
        if (!detection.isListQuery()) {
            return RagRetrievalResult.EMPTY; // 非列表型，走常规 RAG
        }

        String listTopic = detection.listTopic();
        log.info("[ListQuery] 检测到列表型问题: bookId={}, topic={}, question={}",
                book.getId(), listTopic, question);

        try {
            emitter.send(SseEmitter.event().name("thinking_content")
                    .data("这是个列表型问题，我用专门策略找找\"" + listTopic + "\"的具体内容…\n"));
        } catch (Exception ignored) {
        }

        // 2. 选档策略
        ListQueryStrategySelector.Strategy strategy = listQueryStrategySelector.selectStrategy(book);

        // 3. 执行对应检索
        ListQueryRetriever.RetrievalResult result;
        try {
            result = switch (strategy) {
                case FULL_SCAN -> listQueryRetriever.fullScanAndRefine(book, listTopic, ragMaxChars);
                case TOC_RANGE -> listQueryRetriever.tocRangeRetrieve(book, listTopic, ragMaxChars);
                case CLUSTER_EXPAND -> listQueryRetriever.clusterExpandRetrieve(book, listTopic, question, ragMaxChars);
            };
        } catch (Exception e) {
            log.warn("[ListQuery] 策略 {} 执行异常，退化为常规 RAG: {}", strategy, e.getMessage());
            return new RagRetrievalResult(null, listTopic, null);
        }

        // 4. 检查结果
        if (result.matches().isEmpty()) {
            log.warn("[ListQuery] 策略 {} 无结果，退化为常规 RAG: {}", strategy, result.strategyLog());
            return new RagRetrievalResult(null, listTopic, null);
        }

        // 5. 拼接 RAG 上下文
        StringBuilder sb = new StringBuilder();
        int totalLen = 0;
        int charsIn = 0;
        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            String chunkText = match.embedded() != null ? match.embedded().text() : "";
            if (chunkText.isBlank()) continue;
            charsIn += chunkText.length();
            if (totalLen + chunkText.length() > ragMaxChars) break;
            sb.append(chunkText).append("\n\n");
            totalLen += chunkText.length();
        }

        String ragContext = sb.toString();
        if (ragContext.isBlank()) {
            log.warn("[ListQuery] 策略 {} 拼接后上下文为空", strategy);
            return new RagRetrievalResult(null, listTopic, null);
        }

        log.info("[ListQuery] 检索成功: bookId={}, strategy={}, matches={}, chars={}, log={}",
                book.getId(), strategy, result.matches().size(), ragContext.length(), result.strategyLog());
        // 阶段0诊断：chunkIndex 跨度 + 填充率。charsAvail≫maxChars 且 fillRate 低 = 截断在咬（H5）；
        // span 窄 = 检索材料集中在某一区域、后几项无原文可依（H4）
        log.info("[RAG诊断/列表型] bookId={} | strategy={} matches={} charsAvail={} filled={} maxChars={} fillRate={} {}",
                book.getId(), strategy, result.matches().size(), charsIn, totalLen, ragMaxChars,
                ragMaxChars > 0 ? String.format("%.1f%%", ragContext.length() * 100.0 / ragMaxChars) : "n/a",
                describeChunkIndexSpan(result.matches()));

        try {
            emitter.send(SseEmitter.event().name("thinking_content")
                    .data("找到 " + result.matches().size() + " 条相关内容（列表型优化），我整理一下思路…\n"));
        } catch (Exception ignored) {
        }

        return new RagRetrievalResult(ragContext, listTopic, result.matches());
    }

    /**
     * 保存深入追问问题到最近一条 assistant 消息记录
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param bookId    书籍ID
     * @param questions 追问问题列表
     */
    @org.springframework.transaction.annotation.Transactional
    @LogAction("保存追问问题")
    public void saveFollowUpQuestions(Long userId, String sessionId, Long bookId, List<String> questions) {
        try {
            List<AiConversation> records = conversationRepository
                    .findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
            for (int i = records.size() - 1; i >= 0; i--) {
                AiConversation record = records.get(i);
                if ("assistant".equals(record.getRole()) && record.getFollowUpQuestions() == null) {
                    String json = objectMapper.writeValueAsString(questions);
                    record.setFollowUpQuestions(json);
                    conversationRepository.save(record);
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("保存深入追问失败: {}", e.getMessage());
        }
    }

    /**
     * 导出图书问答对话记录
     * <p>
     * 验证用户权限和对话归属，返回格式化的对话文本
     *
     * @param userId    用户ID
     * @param bookId    书籍ID
     * @param sessionId 会话ID
     * @return 包含标题和内容的Map，失败时返回错误信息
     */
    @LogAction("导出图书问答对话")
    public Map<String, String> exportBookChatHistory(Long userId, Long bookId, String sessionId) {
        Map<String, String> result = new HashMap<>();

        // 1. 验证会话存在且属于当前用户
        List<AiSession> sessions = sessionRepository.query()
                .where(AiSession::getUserId, eq(userId))
                .and(AiSession::getSessionId, eq(sessionId))
                .and(AiSession::getBookId, eq(bookId))
                .list();

        if (sessions.isEmpty()) {
            result.put("error", "对话不存在或无权访问");
            return result;
        }

        AiSession session = sessions.get(0);

        // 2. 获取书籍信息
        Book book = bookService.getBookById(bookId);
        if (book == null) {
            result.put("error", "书籍不存在");
            return result;
        }

        // 3. 获取对话历史
        List<AiConversation> conversations = conversationRepository
                .findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);

        if (conversations.isEmpty()) {
            result.put("error", "对话记录为空");
            return result;
        }

        // 4. 构建导出内容
        StringBuilder content = new StringBuilder();

        // 标题
        String title = book.getTitle() + " 书籍讲解";
        content.append(title).append("\n\n");

        // 书籍简介
        content.append("书籍简介\n");
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            content.append(book.getDescription()).append("\n");
        } else {
            content.append("暂无简介\n");
        }
        content.append("\n");

        // 对话内容
        for (AiConversation conv : conversations) {
            String role = "user".equals(conv.getRole()) ? "问" : "答";
            content.append(role).append(": ").append(conv.getContent()).append("\n\n\n\n");
        }

        result.put("title", title);
        result.put("content", content.toString());

        return result;
    }

    /**
     * RAG 检索核心逻辑：查询扩展 → 多查询检索 → 去重 → 合并相邻片段 → 组装上下文。
     * <p>
     * 列表型问题优化：先检测是否为列表型问题，若是则走三档优化策略
     * （全量 scroll / toc 范围 / 簇扩展 + LLM 精筛），失败时退化为常规流程。
     *
     * @return 检索结果（context + listTopic）；不可用时 ragContext 为 null
     */
    private RagRetrievalResult doRagRetrieval(Book book, String question, String lastAiAnswer, int ragTopK, int ragMaxChars,
                                   SseEmitter emitter, ListQueryDetector.DetectionResult detection) {
        try {
            emitter.send(SseEmitter.event().name("thinking_content")
                    .data("我好好分析一下这个问题…\n"));
        } catch (Exception ignored) {
        }

        // ===== 列表型问题 RAG 优化分支 =====
        RagRetrievalResult listResult = tryListQueryRagRetrieval(book, question, lastAiAnswer, ragMaxChars, emitter, detection);
        if (listResult.ragContext() != null) {
            return listResult;
        }
        // 列表型分支未触发或失败 → 走常规 RAG 流程（兜底；listTopic 若存在则保留给覆盖度契约）

        List<String> subQueries = expandQuery(question, lastAiAnswer, book);
        try {
            emitter.send(SseEmitter.event().name("thinking_content")
                    .data("找找 [" + String.join("，", subQueries) + "] 相关内容…\n"));
        } catch (Exception ignored) {
        }

        // ===== 新 RAG 检索管线 =====
        // 1. 向量查询：每个子查询取 ragTopK × 2（宽松取，后续由全局 topN 截断）
        // 2. 按 chunkIndex 去重（替代前 80 字符 key）
        // 3. 全局 score 排序 + topN 截断
        // 4. 软阈值过滤（砍明显噪声）
        // 5. 自适应邻域扩展（前 1 后 2 候选，按 score 决定保留）
        // 6. 合并相邻 + 按 bestScore 重排
        // 7. ragMaxChars 截断填充
        int rawCount = 0, rawChars = 0;
        Map<Integer, EmbeddingMatch<TextSegment>> dedupedByIndex = new LinkedHashMap<>();
        // 每个子查询统一取 ragTopK × 2，不再用固定公式
        int perQueryLimit = Math.max(ragTopK, ragTopK * 2);

        // 1. 首轮向量查询
        for (String subQuery : subQueries) {
            try {
                List<EmbeddingMatch<TextSegment>> matches =
                        embeddingService.searchContent(subQuery, perQueryLimit, book);
                for (EmbeddingMatch<TextSegment> match : matches) {
                    String chunkText = match.embedded() != null ? match.embedded().text() : "";
                    if (chunkText.isBlank()) continue;
                    rawCount++;
                    rawChars += chunkText.length();
                    int idx = RagPipelineComponents.getChunkIndex(match);
                    // 按 chunkIndex 去重，保留 score 更高的
                    dedupedByIndex.merge(idx, match,
                            (existing, incoming) -> incoming.score() > existing.score() ? incoming : existing);
                }
            } catch (Exception e) {
                log.debug("子查询检索失败: subQuery={} - {}", subQuery, e.getMessage());
            }
        }

        // 2. 无结果时触发向量重建（保留原兜底逻辑）
        if (dedupedByIndex.isEmpty()) {
            log.debug("RAG 检索无结果: bookId={}, question={}",
                    book.getId(), question.substring(0, Math.min(30, question.length())));
            Boolean reEmbedResult = bookParserService.forceReEmbedIfMissing(book.getId());
            if (reEmbedResult != null && reEmbedResult) {
                log.info("内容向量重建成功，重新执行 RAG 检索: bookId={}", book.getId());
                // 重建后重置计数器，确保日志反映最终检索数据
                rawCount = 0;
                rawChars = 0;
                for (String subQuery : subQueries) {
                    try {
                        List<EmbeddingMatch<TextSegment>> matches =
                                embeddingService.searchContent(subQuery, perQueryLimit, book);
                        for (EmbeddingMatch<TextSegment> match : matches) {
                            String chunkText = match.embedded() != null ? match.embedded().text() : "";
                            if (chunkText.isBlank()) continue;
                            rawCount++;
                            rawChars += chunkText.length();
                            int idx = RagPipelineComponents.getChunkIndex(match);
                            dedupedByIndex.merge(idx, match,
                                    (existing, incoming) -> incoming.score() > existing.score() ? incoming : existing);
                        }
                    } catch (Exception e) {
                        log.debug("子查询检索失败: subQuery={} - {}", subQuery, e.getMessage());
                    }
                }
            }
        }

        // 3. 全局按 score 降序排序 + topN 截断
        List<EmbeddingMatch<TextSegment>> sortedByScore = new ArrayList<>(dedupedByIndex.values());
        sortedByScore.sort((a, b) -> Double.compare(b.score(), a.score()));
        int dedupCount = sortedByScore.size();
        int dedupChars = sortedByScore.stream()
                .mapToInt(m -> m.embedded() != null ? m.embedded().text().length() : 0).sum();

        List<EmbeddingMatch<TextSegment>> topN = sortedByScore.stream()
                .limit(ragTopK)
                .collect(Collectors.toCollection(ArrayList::new));

        // 4. 软阈值过滤：只砍明显噪声（score < max_score × 0.3）
        if (!topN.isEmpty()) {
            double maxScore = topN.get(0).score();
            double noiseThreshold = maxScore * 0.3;
            int beforeFilter = topN.size();
            topN = topN.stream()
                    .filter(m -> m.score() >= noiseThreshold)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (topN.size() < beforeFilter) {
                log.debug("软阈值过滤: maxScore={}, threshold={}, 砍掉 {} 条噪声",
                        String.format("%.4f", maxScore), String.format("%.4f", noiseThreshold),
                        beforeFilter - topN.size());
            }
        }

        // 5. 自适应邻域扩展
        List<EmbeddingMatch<TextSegment>> expanded = RagPipelineComponents.adaptiveNeighborExpand(
                topN, book.getId(), NEIGHBOR_PREV, NEIGHBOR_NEXT,
                (bookId, idx) -> embeddingService.searchContentByChunkIndex(bookId, idx));

        // 6. 合并相邻 + 按 bestScore 重排
        List<EmbeddingMatch<TextSegment>> merged = RagPipelineComponents.mergeAdjacentChunks(expanded);
        // 关键改造：合并后按 bestScore 降序（高相关片段优先填满 ragMaxChars）
        merged.sort((a, b) -> Double.compare(b.score(), a.score()));
        int mergeChars = merged.stream()
                .mapToInt(m -> m.embedded() != null ? m.embedded().text().length() : 0).sum();

        // 7. ragMaxChars 截断填充
        String ragContext = RagPipelineComponents.truncateToChars(merged, ragMaxChars);
        log.info("RAG检索 bookId={} | 原始{}条{}字 → 去重{}条{}字 → topN={} → 邻域扩展={} → 合并{}条{}字 → 最终{}字",
                book.getId(), rawCount, rawChars,
                dedupCount, dedupChars,
                Math.min(ragTopK, dedupCount), expanded.size(),
                merged.size(), mergeChars, ragContext.length());
        // 阶段0诊断：填充率 + chunkIndex 跨度。span 远小于全书 chunk 数 = 材料集中在某一区域（H4 证据）；
        // fillRate 低 = merged 总量本就不足或首条即超配额（H5 咬人信号）。
        log.info("[RAG诊断/常规] bookId={} | chunksIn={} charsIn={} maxChars={} fillRate={} {} | question={}",
                book.getId(), merged.size(), mergeChars, ragMaxChars,
                ragMaxChars > 0 ? String.format("%.1f%%", ragContext.length() * 100.0 / ragMaxChars) : "n/a",
                describeChunkIndexSpan(merged),
                question.length() > 40 ? question.substring(0, 40) : question);

        long questionMarkCount = ragContext.chars().filter(c -> c == '?').count();
        if (questionMarkCount > ragContext.length() * 0.1) {
            log.warn("[编码诊断] RAG 上下文疑似乱码! bookId={}, 问号占比={}/{}, 丢弃乱码上下文",
                    book.getId(), questionMarkCount, ragContext.length());
            ragHitStatisticsService.recordMiss(book.getId());
        }

        String thinkingText = (!merged.isEmpty())
                ? "找到 " + merged.size() + " 条相关内容, 我整理一下思路…\n"
                : "没找到直接相关的内容，凭印象回答你…\n";
        try {
            emitter.send(SseEmitter.event().name("thinking_content").data(thinkingText));
        } catch (Exception ignored) {
        }
        return new RagRetrievalResult(ragContext, listResult.listTopic(), merged);
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

    // ================================================================
    // 问答域 AI 调用 & 工具方法
    // ================================================================

    /**
     * 根据已有问答生成深入追问问题，引导读者进行深度思考。
     *
     * @param title    书籍标题
     * @param question 用户问题
     * @param answer   AI 回答
     * @param user     用户实体（可为 null，影响问题个性化程度）
     * @param book     书籍实体，提供图书信息作为上下文
     * @return 生成的追问问题列表（最多 3 个），失败时返回空列表
     */
    public List<String> generateFollowUpQuestions(String title, String question, String answer, User user, Book book) {
        // 如果回答或问题为空，无法生成追问
        if (answer == null || answer.isBlank() || question == null || question.isBlank()) {
            return List.of();
        }

        // 构建用户画像和书籍信息作为上下文
        String userProfileDesc = userProfileBuilder.build(user);
        // 行为画像（L2 画像）— 与 L1 静态画像合并喂入追问生成
        UserBehaviorProfile behaviorProfile = loadBehaviorProfile(user != null ? user.getId() : null);
        String behaviorDesc = behaviorProfile != null ? behaviorProfileBuilder.buildFull(behaviorProfile) : "";
        String bookInfo = buildSpeedReadContent(book);

        try {
            // 消息顺序优化 KV Cache：SystemMessage(固定指令) → UserMessage(图书信息) → UserMessage(用户画像) → UserMessage(上轮问答)
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(AiPromptConstants.FOLLOW_UP_QUESTION_SYSTEM_PROMPT));
            messages.add(UserMessage.from("【图书信息】\n" + bookInfo));
            if (!userProfileDesc.isBlank()) {
                messages.add(UserMessage.from("【读者画像】\n" + userProfileDesc));
            }
            if (!behaviorDesc.isBlank()) {
                messages.add(UserMessage.from("【读者近期行为画像】\n" + behaviorDesc));
            }
            messages.add(UserMessage.from("读者问：" + question + "\n你回答：" + answer));

            String aiText = chatModelManager.callAiForScene(AiScene.FOLLOW_UP_QUESTION,
                    "生成深入追问问题",
                    String.format("title=%s", title),
                    messages);
            if (aiText != null) {
                return parseQuestions(aiText).stream().limit(5).collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.debug("生成深入追问问题失败: {}", e.getMessage());
        }

        return List.of();
    }

    /**
     * RAG 查询扩展，根据问题类型生成多组检索查询以提高召回率。
     *
     * @param question     用户原始问题
     * @param lastAiAnswer 上一轮 AI 回答摘要（可为 null，用于追问场景）
     * @param book         书籍
     * @return 扩展后的查询列表（最多 9 个），包含原始查询
     */
    public List<String> expandQuery(String question, String lastAiAnswer, Book book) {
        // 初始化查询列表
        List<String> queries = new ArrayList<>();

        // 原始查询：如果超过15字，提取关键词作为首个查询
        String primaryQuery = question.length() > 15 ? extractKeywords(question) : question;
        queries.add(primaryQuery);

        try {
            // 构建静态书籍信息（书名、作者、目录）— 同书复用 KV Cache
            String bookContext = buildSpeedReadContent(book);

            // 固定指令作为 SystemMessage（与动态内容分离，复用 KV Cache 前缀）
            String systemPrompt = AiPromptConstants.EXPAND_QUERY_SYSTEM_PROMPT;

            // 消息顺序：SystemMessage(固定指令) → UserMessage(图书信息) → UserMessage(上轮回答,可空) → UserMessage(用户问题)
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(systemPrompt));
            messages.add(UserMessage.from("【书籍信息】\n" + bookContext.trim()));
            if (lastAiAnswer != null && !lastAiAnswer.isBlank()) {
                messages.add(UserMessage.from("【上轮AI回答】\n" + lastAiAnswer.trim()));
            }
            messages.add(UserMessage.from("【用户问题】\n" + question));

            String aiText = chatModelManager.callAiForScene(AiScene.QUERY_EXPAND,
                    "RAG查询扩展",
                    String.format("原始: %s", question),
                    messages);
            if (aiText != null) {
                for (String line : aiText.split("\n")) {
                    line = line.trim();
                    // 过滤：非空、不过长、不重复
                    if (!line.isBlank() && line.length() <= 20 && !queries.contains(line)) {
                        queries.add(line);
                    }
                }
            }

            log.debug("[RAG查询扩展] 原始: {} → 扩展后: {}", question, queries);
        } catch (Exception e) {
            log.warn("[RAG查询扩展] 失败，使用原始查询: {}", e.getMessage());
        }

        return queries;
    }

    /**
     * 构建速读摘要的书籍内容部分，格式化为结构化文本。
     *
     * <p>包含书名、作者、标签、简介、章节摘要或目录。内容会被截断以避免超出 AI 上下文长度限制。
     * 优先使用章节摘要，如果没有则使用目录。</p>
     *
     * @param book 书籍实体
     * @return 格式化后的书籍内容文本
     */
    public static String buildSpeedReadContent(Book book) {
        StringBuilder contentBuilder = new StringBuilder();
        contentBuilder.append("书名：《").append(book.getTitle()).append("》\n");
        if (book.getAuthor() != null && !book.getAuthor().isBlank()) {
            contentBuilder.append("作者：").append(book.getAuthor()).append("\n");
        }
        if (book.getFormatTags() != null && !book.getFormatTags().isBlank()) {
            String tags = book.getFormatTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            contentBuilder.append("标签：").append(tags).append("\n");
        }
        if (book.getConceptTags() != null && !book.getConceptTags().isBlank()) {
            String concepts = book.getConceptTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            contentBuilder.append("核心概念：").append(concepts).append("\n");
        }
        if (book.getReaderNeedTags() != null && !book.getReaderNeedTags().isBlank()) {
            String needs = book.getReaderNeedTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            contentBuilder.append("读者关注：").append(needs).append("\n");
        }
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            contentBuilder.append("简介：").append(CommonUtils.truncateText(book.getDescription(), 2000)).append("\n");
        }

        if (book.getChapterSummary() != null && !book.getChapterSummary().isBlank()) {
            contentBuilder.append("章节摘要：\n").append(CommonUtils.truncateText(book.getChapterSummary(), SPEED_READ_CONTENT_LIMIT)).append("\n");
        } else if (book.getToc() != null && !book.getToc().isBlank()) {
            contentBuilder.append("目录：\n").append(CommonUtils.truncateText(book.getToc(), 1500)).append("\n");
        }
        return contentBuilder.toString();
    }

    /**
     * 从长问题中提取关键词，用于向量检索。
     *
     * @param question 用户原始问题
     * @return 提取的关键词短语（≤15字）
     */
    private static String extractKeywords(String question) {
        // 去除口语化前缀和标点
        String cleaned = question
                .replaceAll("^(既然|那么|如果|请问|我想问|请告诉我|你能告诉我)", "")
                .replaceAll("[？?!！。，,、\"“”‘’（）()\\[\\]【】]", " ")
                .trim();

        // 按空格/标点分割，取核心词
        String[] words = cleaned.split("\\s+");
        StringBuilder keywords = new StringBuilder();
        for (String word : words) {
            if (word.length() >= 2 && keywords.length() + word.length() <= 15) {
                if (!keywords.isEmpty()) keywords.append(" ");
                keywords.append(word);
            }
        }

        String result = keywords.toString().trim();
        return result.isEmpty() ? question.substring(0, Math.min(15, question.length())) : result;
    }

    /**
     * 解析 AI 生成的问题列表文本。
     *
     * @param text AI 生成的多行问题文本
     * @return 解析后的问题列表
     */
    private List<String> parseQuestions(String text) {
        return Arrays.stream(text.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(line -> line.replaceAll("^\\d+[.、)\\s]*", "").trim())
                .filter(line -> line.length() > 2)
                .distinct()
                .limit(20)
                .collect(Collectors.toList());
    }

    // ====================================================================
    // 列表展开流水线（阶段2，§3.1–3.4 / §4.4–4.7）
    //
    // 把"必须回答哪些条目"从模型的临场记忆变成 Java 循环的已知常量：
    //   outline 对象化 → 逐条配额定向检索（治 H4 覆盖集中化）
    //   → 逐段 streamSegment 串行生成、按序写同一条 SSE 流的 message 事件
    //   →（节数=N 由循环结构保证）→ 聚合单行落库。前端协议零改动。
    // ====================================================================

    /** 流水线条目数上限（§3.4）：超过退回单次流式，不贪 */
    private static final int MAX_PIPELINE_ITEMS = 8;
    /** 单节生成超时（§4.7）：120s/节（Q7 定） */
    private static final long SEGMENT_TIMEOUT_MS = 120_000L;
    /** 逐条预取整体超时：并行执行，15s 覆盖 P99 */
    private static final long PREFETCH_TIMEOUT_MS = 15_000L;
    /** 现场条目化超时：1.2s 硬上限（§4.7：超即退回单次流式，用户无感） */
    private static final long ONSITE_OUTLINE_TIMEOUT_MS = 1_200L;
    /** 流水线内历史注入上限（§4.5：outline 已携带清单，历史可截） */
    private static final int PIPELINE_HISTORY_LIMIT = 6;
    /** §3.1 第 1 轮③ 对齐产物：chunkAnchors 已写入的 outline（alignChunkAnchors 设置、
     *  streamItemListPipeline 消费）。ThreadLocal 避免并发串轮。 */
    private static final ThreadLocal<com.kbook.service.ai.outline.AnswerOutline> ALIGNED_OUTLINE =
            new ThreadLocal<>();

    /**
     * 流水线触发判定（§3.4）：检测为列表型 && outline 可得 && items≥2 && items≤8。
     * <p>outline 两级获取对抗异步落库竞态：① DB（上一轮 assistant 的 answer_outline 列）；
     * ② 缺失即现场条目化（输入=上轮回答原文，1.2s 硬超时）并回填 DB。</p>
     * <p>命中且本轮 RAG 有 matches 时，把上轮 RAG 材料里的 chunkIndex 对齐写入 outline 的
     * chunkAnchors（§3.1 第 1 轮③，字面命中率、不用 LLM），供流水线逐条锚点检索；
     * 对齐失败留空，检索阶段走关键词向量兜底（§4.6 第 2 级）。</p>
     *
     * @param lastAnswer 调用方已加载的上轮回答（避免重复查库）
     * @param ragMatches  本轮 RAG 匹配片段（含 chunkIndex），可为 null（无对齐，走关键词兜底）
     * @return 可用的 outline（已尽量对齐锚点）；不触发时 null（调用方走单次流式回退路径）
     */
    private com.kbook.service.ai.outline.AnswerOutline resolvePipelineOutline(
            ListQueryDetector.DetectionResult detection, Long userId, String sessionId, SseEmitter emitter,
            String lastAnswer, List<EmbeddingMatch<TextSegment>> ragMatches) {
        if (!detection.isListQuery()) {
            return null;
        }
        if (lastAnswer == null || lastAnswer.isBlank()) {
            return null;
        }

        // ① DB 优先
        AiConversation lastRow = findLastAssistantRow(userId, sessionId);
        com.kbook.service.ai.outline.AnswerOutline outline = lastRow != null
                ? answerOutlineService.parseFromDb(lastRow.getAnswerOutline()) : null;
        String source = "db";

        // ② 现场条目化（回退），1.2s 硬超时
        if (outline == null) {
            try {
                emitter.send(SseEmitter.event().name("thinking_content")
                        .data("我把上一条回答整理成清单，逐条展开讲给你…\n"));
            } catch (Exception ignored) {
            }
            // 输入优先原文 content（压缩版可能丢列表结构——H3 教训），缺失才回退压缩文本
            final String answerForOutline = (lastRow != null && lastRow.getContent() != null
                    && !lastRow.getContent().isBlank()) ? lastRow.getContent() : lastAnswer;
            java.util.concurrent.Future<com.kbook.service.ai.outline.AnswerOutline> buildFuture =
                    qaOutlineExecutor.submit(() -> answerOutlineService.build(answerForOutline));
            try {
                outline = buildFuture.get(ONSITE_OUTLINE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                source = "onsite";
            } catch (TimeoutException te) {
                // 超时仅让等待方降级。用 ThreadPoolExecutor.submit 拿 Future（非 CompletableFuture），
                // cancel(true) 会真正中断 worker 线程——langchain4j 阻塞调用响应中断时提前返回，
                // 避免 qaOutlineExecutor（2 线程）被跑满 30s 的僵尸任务堵死导致后续流水线全灭。
                buildFuture.cancel(true);
                log.info("[Pipeline] 现场条目化超时（{}ms），已中断后台任务，退回单次流式路径", ONSITE_OUTLINE_TIMEOUT_MS);
                return null;
            } catch (Exception e) {
                log.info("[Pipeline] 现场条目化失败，退回单次流式路径: {}", e.getMessage());
                return null;
            }
            // 回填 DB（异步，不阻塞当前回答）：现场条目化的输入已是上轮回答，
            // 回填后下次追问直接命中 DB 路径
            if (outline != null && lastRow != null) {
                final String json = answerOutlineService.toJson(outline);
                if (json != null) {
                    updateOutlineById(lastRow.getId(), json);
                }
            }
        }

        if (outline == null || outline.size() < 2) {
            return null;
        }
        if (outline.size() > MAX_PIPELINE_ITEMS) {
            log.info("[Pipeline] 条目数 {} 超上限 {}，退回单次流式路径（覆盖度契约仍生效）",
                    outline.size(), MAX_PIPELINE_ITEMS);
            return null;
        }

        // ③ 对齐 chunkAnchors（§3.1 第 1 轮③）：把上轮 RAG 材料片段中的 chunkIndex
        // 关联到各条目（条目关键词字面命中率粗排，不用 LLM），对齐后写 ALIGNED_OUTLINE
        // 供流水线逐条锚点检索；对齐失败/无可对齐时留空，检索阶段走关键词向量兜底
        alignChunkAnchors(outline, ragMatches);
        com.kbook.service.ai.outline.AnswerOutline aligned = ALIGNED_OUTLINE.get();
        ALIGNED_OUTLINE.remove();
        if (aligned != null) {
            log.info("[Pipeline] 触发: source={}, items={}, topic={}, alignedAnchors=true",
                    source, aligned.size(), aligned.topic());
            return aligned;
        }
        log.info("[Pipeline] 触发: source={}, items={}, topic={}", source, outline.size(), outline.topic());
        return outline;
    }

    /**
     * 列表展开流水线主流程（§3.1）。
     * <p>严格 for i=1..N 串行生成（循环计数器，不依赖闭包 state——A2 教训）：
     * 节数 = 条目数恒成立，漏条在结构上不可能发生。每节失败重试一次，仍失败落占位节继续，
     * 整轮绝不因单节失败而报废（Q5）。N 节全部完成后聚合为单行 assistant 落库。</p>
     */
    private void streamItemListPipeline(
            Book book, String question,
            com.kbook.service.ai.outline.AnswerOutline outline,
            String bookInfoPrompt, StreamingChatModel fallbackModel,
            Long userId, String sessionId, SseEmitter emitter, boolean manual) {

        List<com.kbook.service.ai.outline.AnswerOutline.Item> items = outline.items();
        final int n = items.size();

        // 单节模型：BOOK_QA_SEGMENT（QA_WITHOUT_THINKING 未绑定回退 QA 配置 + thinking 关，Q6）
        StreamingChatModel segmentModel = fallbackModel;
        var segLogContext = chatModelManager.buildLogContext(AiScene.BOOK_QA);
        try {
            StreamingChatModel m = chatModelManager.getStreamingModelForScene(AiScene.BOOK_QA_SEGMENT);
            if (m != null) {
                segmentModel = m;
                segLogContext = chatModelManager.buildLogContext(AiScene.BOOK_QA_SEGMENT);
            }
        } catch (Exception e) {
            log.warn("[Pipeline] 分节模型解析失败，沿用 BOOK_QA 模型: {}", e.getMessage());
        }

        // 1. 逐条定向预取（§4.6：并行，纯向量检索无 LLM；每条独立配额 → "每条都有材料"是代码不变量）
        // 外层 supplyAsync 只做"整体超时"控制；池内任务由 prefetchAll 自己并行。
        // RejectedExecutionException 异步化到 future，统一按超时/失败降级为空材料，绝不抛穿整轮。
        // 注意：传入的 outline 已含 §3.1③ 对齐后的 chunkAnchors（retrieveByAnchors 消费）
        Map<Integer, String> fetched;
        try {
            fetched = java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> itemListRetriever.prefetchAll(book, items), qaOutlineExecutor)
                    .get(PREFETCH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("[Pipeline] 逐条预取整体超时/失败（缺材料节走降级话术）: {}", e.getMessage());
            fetched = Map.of();
        }
        Map<Integer, String> perItemMaterial = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int id = items.get(i).id() != null ? items.get(i).id() : i + 1;
            perItemMaterial.put(id, fetched.getOrDefault(id, ""));
        }
        long materialHit = perItemMaterial.values().stream().filter(s -> !s.isBlank()).count();
        int anchorHit = (int) items.stream().filter(it -> it.chunkAnchors() != null && !it.chunkAnchors().isEmpty()).count();
        log.info("[Pipeline] 预取完成: bookId={}, items={}, 有材料={}/{}, 有锚点={}/{}",
                book.getId(), n, materialHit, n, anchorHit, n);

        // 2. 消息组装：静态前缀 + 历史 + 本轮专属材料 + 单节任务（§3.3）
        List<ChatMessage> baseMessages = buildBaseMessages(userId, sessionId, bookInfoPrompt);
        String style = getChatStyleForUser(userId);

        StringBuilder answer = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        final SseEmitter finalEmitter = emitter;
        StreamingSseHandler.Callback noopCallback = new StreamingSseHandler.Callback() {
            @Override
            public String getOperationName() {
                return "图书问答-列表分节";
            }

            @Override
            public void onThinkingToken(String thinkingText, SseEmitter e) {
                // 分节模型默认 thinking 关；解析失败回退 BOOK_QA（thinking 开）时转发给前端
                thinking.append(thinkingText);
                SseHelper.safeSendEvent(finalEmitter, "thinking_content", thinkingText);
            }

            @Override
            public void onComplete(String fullResponse, ChatResponse completeResponse) {
                // 流水线自己聚合落库，段级不使用 onComplete
            }
        };

        List<String> sectionFinishReasons = new ArrayList<>(n);
        boolean interrupted = false;

        for (int i = 0; i < n; i++) {
            com.kbook.service.ai.outline.AnswerOutline.Item item = items.get(i);
            int id = item.id() != null ? item.id() : i + 1;
            String prevTitle = i > 0 ? items.get(i - 1).title() : null;
            String prevTail = i > 0 ? tailSentences(answer.toString(), 2) : null;
            String instruction = buildSegmentInstruction(i + 1, n, outline.topic(), item.title(),
                    item.gist(), prevTitle, prevTail, perItemMaterial.get(id), style);

            List<ChatMessage> segMessages = new ArrayList<>(baseMessages.size() + 2);
            segMessages.addAll(baseMessages);
            segMessages.add(UserMessage.from(instruction));

            // 节间分隔：先于本节流式内容发送（streamSegment 直接推 message），
            // 前端拼接出的全文与聚合落库文本一致；空节/占位节时只是多一个空行，Markdown 渲染无害
            if (answer.length() > 0) {
                answer.append("\n\n");
                if (!SseHelper.safeSendEvent(emitter, "message", "\n\n")) {
                    interrupted = true;
                    sectionFinishReasons.add("DISCONNECTED");
                    break;
                }
            }

            StreamingSseHandler.SegmentResult seg = StreamingSseHandler.streamSegment(
                    segmentModel, segMessages, emitter, noopCallback, segLogContext, SEGMENT_TIMEOUT_MS);

            if (seg.connectionClosed()) {
                // 客户端断开/任务取消：本节已流出部分并入聚合（与前端显示一致），部分落库，不发 done（§7 验收）
                appendSection(answer, seg.text());
                interrupted = true;
                sectionFinishReasons.add("DISCONNECTED");
                break;
            }
            // 失败（ERROR/TIMEOUT）且未流出任何内容时重试一次（Q5）；
            // 已有部分输出则直接接受（前端已实时流出，重试会造成重复内容）
            boolean sectionFailed = seg.failed() || "TIMEOUT".equals(seg.finishReason());
            if (sectionFailed && seg.text().isBlank()) {
                log.warn("[Pipeline] 第 {}/{} 节生成失败且无输出，重试一次: {}", i + 1, n, seg.error());
                seg = StreamingSseHandler.streamSegment(
                        segmentModel, segMessages, emitter, noopCallback, segLogContext, SEGMENT_TIMEOUT_MS);
                if (seg.connectionClosed()) {
                    appendSection(answer, seg.text());
                    interrupted = true;
                    sectionFinishReasons.add("DISCONNECTED");
                    break;
                }
                sectionFailed = seg.failed() || "TIMEOUT".equals(seg.finishReason());
            }

            String sectionText = seg.text() != null ? seg.text().trim() : "";
            if (sectionFailed && sectionText.isBlank()) {
                // 占位节 + 继续——"输出节数 = N 恒成立"不允许被单节失败破坏（Q5）
                sectionText = "这一节暂时没法展开，材料不足，先记着，回头我单独补给你。";
                log.warn("[Pipeline] 第 {}/{} 节无有效输出，已落占位节: {}", i + 1, n, seg.error());
                // 占位节是本地生成的文本（未走 streamSegment，前端从未收到）→ 按 message 事件补发，
                // 保证前端显示与落库文本一致；模型部分输出已实时流出，不补发
                String placeholderText = "这一节暂时没法展开，材料不足，先记着，回头我单独补给你。";
                SseHelper.safeSendEvent(emitter, "message", placeholderText);
            }
            sectionFinishReasons.add(seg.finishReason() != null ? seg.finishReason() : "UNKNOWN");
            appendSection(answer, sectionText);
        }

        if (interrupted && answer.isEmpty()) {
            log.info("[Pipeline] 断开于首节之前，无内容可落库");
            return;
        }

        // 3. 聚合落库（单行 assistant，§3.2）
        String aggregated = answer.toString().trim();
        String joinedFr = sectionFinishReasons.stream().distinct()
                .collect(java.util.stream.Collectors.joining(","));
        // finish_reason 列 length=20；多节拼接（如 "STOP,LENGTH,TIMEOUT"）会超 20 → 截断，
        // 否则 MySQL 数据截断导致整轮落库失败（§3.2 单行助手语义被破坏）
        if (joinedFr.length() > 20) joinedFr = joinedFr.substring(0, 20);
        String outlineJson = answerOutlineService.toJson(outline);
        AiConversation pipelineRow = persistPipelineResult(userId, sessionId, book.getId(), question, aggregated,
                thinking.isEmpty() ? null : thinking.toString(), joinedFr, outlineJson, manual);

        if (interrupted) {
            log.warn("[Pipeline] 中途断开，按部分输出落库: chars={}, sectionsDone={}/{}",
                    aggregated.length(), sectionFinishReasons.size() - 1, n);
            return;
        }

        // §3.1④ 二级条目化：流水线的展开结果本身也是"分节列表"，再异步条目化一次，
        // 支持"第 3 条再详细点"三级钻取（下一轮 resolvePipelineOutline 能直读这份 outline）
        scheduleOutline(pipelineRow, aggregated, true);

        // 4. 结束：replace（仅 sanitize 改动时）→ done → complete（复用现有事件，前端零改动）
        String safeAggregated = CommonUtils.sanitizeAiOutput(aggregated);
        if (!safeAggregated.equals(aggregated)) {
            try {
                emitter.send(SseEmitter.event().name("replace").data(safeAggregated));
                log.warn("[Pipeline] sanitize 命中，已发 replace 覆盖: sessionId={}", sessionId);
            } catch (Exception ignored) {
            }
        }
        try {
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
        } catch (Exception e) {
            log.debug("[Pipeline] done/complete 发送失败（连接已断）: {}", e.getMessage());
        }
        log.info("[Pipeline] 流水线完成: bookId={}, sections={}, totalChars={}, finishReasons={}",
                book.getId(), n, aggregated.length(), joinedFr);
    }

    /**
     * 流水线单节指令（§3.3 组装规则 + §4.2 预算）。
     */
    private String buildSegmentInstruction(int i, int n, String topic, String title, String gist,
                                           String prevTitle, String prevTail, String material, String style) {
        StringBuilder sb = new StringBuilder();
        sb.append("【本轮任务 = 第 ").append(i).append('/').append(n).append(" 节】\n");
        sb.append("总任务：把「").append(topic != null && !topic.isBlank() ? topic : "上一条回答").append("」逐条展开，一条不落。\n");
        sb.append("本节只写第 ").append(i).append(" 项「").append(title).append("」");
        if (gist != null && !gist.isBlank()) {
            sb.append("（要点：").append(gist).append("）");
        }
        sb.append("，不要提及其他条目。\n");

        sb.append("【衔接】");
        if (i == 1) {
            sb.append("你是首节：用一句话点明主题后立刻进入本节内容；禁止开场客套、禁止复述全列表、禁止加总结。\n");
        } else {
            sb.append("上一节为「").append(prevTitle).append("」，其结尾是「")
              .append(prevTail != null && !prevTail.isBlank() ? prevTail : "……").append("」。");
            sb.append("不要重复上一节内容，换一条自然的衔接句进入本节。\n");
        }

        sb.append("【本节预算】约 ").append(segmentBudgetText(style)).append("；写满即收，禁止拖成两节的内容。\n");

        if (i == n) {
            sb.append("【本节是末节】写完后允许一句收束，除此之外不要加总结。\n");
        }

        if (material != null && !material.isBlank()) {
            sb.append("【本节专属参考材料（只含第 ").append(i).append(" 项）】\n").append(material).append("\n");
        } else {
            sb.append("【材料说明】原书中该项的直接材料没检索到。基于你对其主题的理解谨慎展开，")
              .append("明确说明这部分书中着墨不多，篇幅可短，但必须成节、不许跳过。\n");
        }

        sb.append("【续写契约】直接输出本节正文。严禁输出「（未完）」「（待续）」等提示性文字；")
          .append("严禁向用户提问或询问是否继续；严禁重复编号列表结构以外的整段内容。");
        return sb.toString();
    }

    /**
     * 按风格取单节预算字符数（§3.3/§4.2：DEEP 400–500 / CASUAL 250–350 / WITTY 300–400 / CONCISE 100–200）。
     */
    private long segmentBudgetForStyle(String style) {
        if (style == null) return 450;
        return switch (style.toUpperCase()) {
            case "CONCISE" -> 250;
            case "CASUAL" -> 350;
            case "WITTY" -> 400;
            default -> 500; // DEEP
        };
    }

    /** 预算文案（进指令用，给模型区间而非定值） */
    private String segmentBudgetText(String style) {
        if (style == null) return "400~500 字";
        return switch (style.toUpperCase()) {
            case "CONCISE" -> "100~200 字";
            case "CASUAL" -> "250~350 字";
            case "WITTY" -> "300~400 字";
            default -> "400~500 字"; // DEEP
        };
    }

    /**
     * 流水线静态消息前缀（§3.3）：SystemMessage → UserMessage(bookInfo) → 截断历史。
     * 不含 RAG 与问题消息——本轮专属材料逐节注入。
     * 历史最多 {@link PIPELINE_HISTORY_LIMIT} 条且预留空间不足时进一步砍（outline 已携带清单）。
     */
    private List<ChatMessage> buildBaseMessages(Long userId, String sessionId, String bookInfoPrompt) {
        List<ChatMessage> messages = new ArrayList<>();
        String style = getChatStyleForUser(userId);
        String systemPrompt = getSystemPromptForStyle(style);
        messages.add(SystemMessage.from(systemPrompt));

        if (bookInfoPrompt != null && !bookInfoPrompt.isBlank()) {
            messages.add(UserMessage.from(bookInfoPrompt));
        }

        try {
            // 保守预留：N 节 × 每节预算 ×（1.2 指令/材料放大）
            int overhead = systemPrompt.length()
                    + (bookInfoPrompt != null ? bookInfoPrompt.length() : 0)
                    + (int) (MAX_PIPELINE_ITEMS * segmentBudgetForStyle(style) * 1.2) + 2000;
            List<AiConversation> history = loadAndCompressHistory(userId, sessionId, overhead);
            if (!history.isEmpty()) {
                // 从最新往回取最多 PIPELINE_HISTORY_LIMIT 条，触顶即停（保证"最近的历史"优先留下）
                int estimated = messagesChars(messages);
                int contextLimit = contextCharLimit();
                LinkedList<ChatMessage> picked = new LinkedList<>();
                int taken = 0;
                for (int i = history.size() - 1; i >= 0 && taken < PIPELINE_HISTORY_LIMIT; i--) {
                    AiConversation conv = history.get(i);
                    String content = conv.getCompressedContent();
                    if (content == null || content.isBlank()) continue;
                    if (estimated + content.length() > contextLimit) {
                        log.info("[Pipeline] 历史注入触顶，停止追加");
                        break;
                    }
                    if ("user".equals(conv.getRole())) {
                        picked.addFirst(UserMessage.from(content));
                    } else if ("assistant".equals(conv.getRole())) {
                        picked.addFirst(AiMessage.from(content));
                    } else {
                        continue;
                    }
                    estimated += content.length();
                    taken++;
                }
                messages.addAll(picked);
            }
        } catch (Exception e) {
            log.warn("[Pipeline] 加载历史失败，流水线继续无历史: {}", e.getMessage());
        }

        // 行为画像（与主路径一致，静态前缀的一部分）
        UserBehaviorProfile behaviorProfile = loadBehaviorProfile(userId);
        if (behaviorProfile != null) {
            String behaviorDesc = behaviorProfileBuilder.buildSummary(behaviorProfile);
            if (!behaviorDesc.isBlank()) {
                messages.add(UserMessage.from("【读者近期画像】\n" + behaviorDesc
                        + "\n回答时可在相关处自然贴合，不要生硬提及读者画像。"));
            }
        }
        return messages;
    }

    /** 流水线聚合结果落库：单行 assistant（含 finishReason / outline），与追问历史兼容（§3.2）。 */
    private AiConversation persistPipelineResult(Long userId, String sessionId, Long bookId, String question,
                                       String aggregated, String thinkingText,
                                       String finishReason, String outlineJson, boolean manual) {
        try {
            ensureSession(userId, sessionId, question, bookId);
            saveMessage(userId, sessionId, "user", question, bookId, null);

            String safe = CommonUtils.sanitizeAiOutput(aggregated);
            AiConversation row = saveMessage(userId, sessionId, "assistant", safe,
                    bookId, thinkingText, finishReason);
            if (row != null && outlineJson != null) {
                updateOutlineById(row.getId(), outlineJson);
            }
            updateSessionTimestamp(sessionId);
            publishUserSignal(userId, sessionId, bookId, question, manual);
            return row;
        } catch (Exception e) {
            log.warn("[Pipeline] 聚合落库失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * §4.5：异步条目化回写。fire-and-forget，Rejected/异常全部吞掉，不影响主流程。
     *
     * @param row         刚落库的 assistant 行（null 则跳过）
     * @param answer      回答全文
     * @param isListQuery 本轮检测是否列表型（true 时无论结果都写，false 仅含枚举结构时写）
     */
    private void scheduleOutline(AiConversation row, String answer, boolean isListQuery) {
        if (row == null || row.getId() == null || answer == null || answer.isBlank()) {
            return;
        }
        Long rowId = row.getId();
        // 无枚举结构直接跳过（省一次 TOOL 调用）；列表型问题即使文本无标号也跑条目化（可能用散文列举）
        if (!isListQuery && !hasEnumerativeStructure(answer)) {
            return;
        }
        try {
            qaOutlineExecutor.execute(() -> {
                try {
                    var outline = answerOutlineService.build(answer);
                    if (outline == null) return;
                    String json = answerOutlineService.toJson(outline);
                    if (json != null) {
                        updateOutlineById(rowId, json);
                    }
                } catch (Exception e) {
                    log.warn("[Outline] 异步条目化失败: rowId={} - {}", rowId, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[Outline] 异步任务提交被拒（不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * §3.1 第 1 轮③：把上一轮 RAG 检索结果里的 chunkIndex 关联到 outline 各条目（chunkAnchors）。
     * <p>对齐依据：条目（标题+要点+关键词）在材料片段中的<b>字面命中率</b>——不用 LLM，
     * 流水线触发前同步执行、开销可忽略。每条目取命中率 ≥ 0.3 的片段中最多 3 个 chunkIndex；
     * 无可靠对齐则留空——检索阶段由关键词向量检索兜底（§4.6 第 2 级）。
     * <p>对齐后的 outline 写入 {@link #ALIGNED_OUTLINE}，由 {@code streamItemListPipeline} 消费；
     * 落库保持 keywords 版（锚点是本轮检索产物，不污染 DB 直读路径）。
     */
    private void alignChunkAnchors(com.kbook.service.ai.outline.AnswerOutline outline,
                                   List<EmbeddingMatch<TextSegment>> ragMatches) {
        if (outline == null || outline.size() < 2 || ragMatches == null || ragMatches.isEmpty()) {
            return;
        }
        try {
            List<List<Integer>> anchorLists = new ArrayList<>(outline.size());
            int aligned = 0;
            for (com.kbook.service.ai.outline.AnswerOutline.Item item : outline.items()) {
                List<Integer> anchors = new ArrayList<>();
                for (int i = 0; i < ragMatches.size() && anchors.size() < 3; i++) {
                    if (literalHit(item, ragMatches.get(i)) < 0.3f) continue;
                    int idx = RagPipelineComponents.getChunkIndex(ragMatches.get(i));
                    if (idx >= 0 && !anchors.contains(idx)) {
                        anchors.add(idx);
                    }
                }
                if (!anchors.isEmpty()) aligned++;
                anchorLists.add(anchors);
            }
            List<com.kbook.service.ai.outline.AnswerOutline.Item> newItems = new ArrayList<>(outline.size());
            for (int i = 0; i < outline.size(); i++) {
                com.kbook.service.ai.outline.AnswerOutline.Item it = outline.items().get(i);
                newItems.add(new com.kbook.service.ai.outline.AnswerOutline.Item(
                        it.id(), it.title(), it.gist(), it.keywords(),
                        anchorLists.get(i).isEmpty() ? null : anchorLists.get(i)));
            }
            log.info("[Pipeline] chunkAnchors 对齐: items={}, 有锚点={}/{}",
                    outline.size(), aligned, outline.size());
            ALIGNED_OUTLINE.set(new com.kbook.service.ai.outline.AnswerOutline(outline.topic(), newItems));
        } catch (Exception e) {
            log.debug("[Pipeline] chunkAnchors 对齐失败（留空走关键词兜底）: {}", e.getMessage());
        }
    }

    /** 条目关键词（标题+要点+keywords 字段）在材料片段中的字面命中率（0~1）。 */
    private static float literalHit(com.kbook.service.ai.outline.AnswerOutline.Item item,
                                    EmbeddingMatch<TextSegment> match) {
        String text = match.embedded() != null ? match.embedded().text() : "";
        if (text.isBlank()) return 0;
        Set<String> terms = new LinkedHashSet<>();
        if (item.title() != null && !item.title().isBlank()) terms.add(item.title().trim());
        if (item.gist() != null && !item.gist().isBlank()) terms.add(item.gist().trim());
        if (item.keywords() != null) item.keywords().stream()
                .filter(k -> k != null && !k.isBlank()).forEach(k -> terms.add(k.trim()));
        if (terms.isEmpty()) return 0;
        int hit = 0;
        for (String t : terms) {
            if (t.length() >= 2 && text.contains(t)) hit++;
        }
        return (float) hit / terms.size();
    }

    /** 粗略枚举结构信号：≥2 行行首编号/项目符号。命中才值得花一次 TOOL 调用条目化。 */
    private static boolean hasEnumerativeStructure(String answer) {
        int hits = 0;
        for (String line : answer.split("\n")) {
            String t = line.trim();
            if (t.matches("^\\s*(\\d+[.、)）]|[-*•·]|[一二三四五六七八九十]+[、.）])\\s*.*")) {
                hits++;
            }
        }
        return hits >= 2;
    }

    /**
     * 按行 ID 定向更新 answer_outline（不整行 save，避免与压缩等字段写入互相覆盖）。
     */
    private void updateOutlineById(Long rowId, String outlineJson) {
        try {
            conversationRepository.findById(rowId).ifPresent(row -> {
                row.setAnswerOutline(outlineJson);
                conversationRepository.save(row);
            });
        } catch (Exception e) {
            log.warn("[Outline] 回写失败: rowId={} - {}", rowId, e.getMessage());
        }
    }

    /** 会话内最近一条 assistant 行（取 outline 用）；无则 null。 */
    private AiConversation findLastAssistantRow(Long userId, String sessionId) {
        try {
            List<AiConversation> history = conversationRepository
                    .findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
            for (int i = history.size() - 1; i >= 0; i--) {
                if ("assistant".equals(history.get(i).getRole())) {
                    return history.get(i);
                }
            }
        } catch (Exception e) {
            log.debug("获取最近 assistant 行失败: {}", e.getMessage());
        }
        return null;
    }

    /** 节文本并入聚合（trim 后非空才追加分隔+内容，保证与前端 message 流一致）。 */
    private static void appendSection(StringBuilder answer, String sectionText) {
        if (sectionText == null) return;
        String s = sectionText.trim();
        if (s.isEmpty()) return;
        if (answer.length() > 0 && answer.charAt(answer.length() - 1) != '\n') {
            answer.append("\n\n");
        }
        answer.append(s);
    }

    /** 取文本末尾 k 句（流水线衔接用，§3.3：仅上一节标题 + 结尾 1~2 句）。 */
    private static String tailSentences(String text, int k) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();
        int count = 0;
        int pos = t.length();
        for (int i = t.length() - 1; i >= 0; i--) {
            char c = t.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '…') {
                count++;
                if (count >= k) {
                    pos = i + 1;
                    break;
                }
                // 跳过句末连续标点
                int j = i - 1;
                while (j >= 0 && (t.charAt(j) == '”' || t.charAt(j) == '』' || t.charAt(j) == '）')) {
                    i = j;
                    j--;
                }
            }
        }
        String tail = t.substring(Math.min(pos, t.length())).trim();
        return tail.length() > 160 ? tail.substring(tail.length() - 160) : tail;
    }

    /** 上下文预算（字符）：场景配置 maxTokens × 1.5；解析失败按 32K tokens 估。 */
    private int contextCharLimit() {
        try {
            AiProviderConfig cfg = aiSceneConfigService.resolveConfig(AiScene.BOOK_QA);
            if (cfg != null && cfg.getMaxTokens() != null) {
                return (int) (cfg.getMaxTokens() * TOKEN_TO_CHAR_RATIO);
            }
        } catch (Exception ignored) {
        }
        return (int) (DEFAULT_MAX_TOKENS * TOKEN_TO_CHAR_RATIO);
    }

    private static int messagesChars(List<ChatMessage> messages) {
        int sum = 0;
        for (ChatMessage m : messages) {
            if (m instanceof SystemMessage s) sum += s.text().length();
            else if (m instanceof UserMessage u) sum += u.singleText().length();
            else if (m instanceof AiMessage a) sum += a.text().length();
        }
        return sum;
    }

}
