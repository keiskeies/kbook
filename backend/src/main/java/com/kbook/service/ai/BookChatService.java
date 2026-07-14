package com.kbook.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.service.ai.streaming.StreamingSseHandler;
import com.kbook.service.user.UserService;
import com.kbook.service.book.BookParserService;
import com.kbook.service.book.BookService;

import com.kbook.service.embedding.RagHitStatisticsService;

import com.kbook.service.embedding.EmbeddingService;

import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.config.ChatModelFactory;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.config.properties.QdrantProperties;
import com.kbook.config.ai.AiConfigProvider;
import com.kbook.constants.AiPromptConstants;
import com.kbook.dto.book.BookProjection;
import com.kbook.entity.AiConversation;
import com.kbook.entity.Book;
import com.kbook.entity.AiSession;
import com.kbook.entity.BookSuggestedQuestion;
import com.kbook.entity.User;
import com.kbook.repository.AiConversationRepository;
import com.kbook.repository.AiSessionRepository;
import com.kbook.repository.BookSuggestedQuestionRepository;

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
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * 图书问答服务
 * <p>
 * 基于书籍内容的 RAG（检索增强生成）问答服务。
 * 用户针对某本书提问时，先通过向量检索从书籍内容中获取相关片段，
 * 再将片段作为上下文交给 AI 生成回答。支持 SSE 流式输出、
 * 缓存回答复用、按需生成内容向量、深入追问等功能。
 */
@Slf4j
@Service
@LogModule("图书问答")
public class BookChatService {

    /**
     * 会话类型标识：图书问答
     */
    private static final String TYPE = "book_chat";

    private final EmbeddingService embeddingService;
    private final BookService bookService;
    private final BookParserService bookParserService;
    private final AiProviderConfigService aiProviderConfigService;
    private final ChatModelFactory chatModelFactory;
    private final ChatModelManager chatModelManager;
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
    private final RagAnswerCache ragAnswerCache;

    public BookChatService(
            EmbeddingService embeddingService,
            BookService bookService,
            BookParserService bookParserService,
            AiProviderConfigService aiProviderConfigService,
            ChatModelFactory chatModelFactory,
            ChatModelManager chatModelManager,
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
            RagAnswerCache ragAnswerCache,
            @Qualifier("sseExecutor") ExecutorService sseExecutor) {
        this.embeddingService = embeddingService;
        this.bookService = bookService;
        this.bookParserService = bookParserService;
        this.aiProviderConfigService = aiProviderConfigService;
        this.chatModelFactory = chatModelFactory;
        this.chatModelManager = chatModelManager;
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
        this.ragAnswerCache = ragAnswerCache;
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
    public SseEmitter streamBookChat(Long userId, Long bookId, String question, String sessionId) {
        log.info("========== 图书问答请求 ==========");
        log.info("userId={}, bookId={}, question={}", userId, bookId, question);

        final String effectiveSessionId = (sessionId == null || sessionId.isBlank())
                ? "book-" + bookId + "-" + UUID.randomUUID().toString().substring(0, 8)
                : sessionId;

        SseEmitter emitter = new SseEmitter(180_000L);

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

        final StreamingChatModel model = chatModelFactory.buildStreamingChatModel();
        if (model == null) {
            SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
            return emitter;
        }



        Future<?> aiFuture = sseExecutor.submit(() -> {
            try {
                // 0. RAG 答案缓存检查（仅首问缓存，无对话上下文依赖）
                String lastAiAnswer = getLastAiAnswer(userId, effectiveSessionId);
                final boolean cacheable = (lastAiAnswer == null || lastAiAnswer.isBlank());
                final String modelName = chatModelFactory.getModelName();

                if (cacheable) {
                    String cached = ragAnswerCache.get(bookId, question, modelName);
                    if (cached != null) {
                        // 缓存命中 — 流式回放 + 保存对话记录
                        final String answer = cached;
                        ragAnswerCache.replay(emitter, cached, () -> {
                            ensureSession(userId, effectiveSessionId, question, bookId);
                            saveMessage(userId, effectiveSessionId, "user", question, bookId, null);
                            saveMessage(userId, effectiveSessionId, "assistant", answer, bookId, null);
                            updateSessionTimestamp(effectiveSessionId);
                        });
                        return;
                    }
                }

                // 1. 按需生成内容向量（首次问答时触发）
                if (!ensureContentEmbedded(book, bookId, emitter)) return;

                // 2. 懒生成 compressedSummary（首次问答时若为空，同步生成并持久化）
                ensureCompressedSummary(book, emitter);

                int ragTopK = Optional.ofNullable(aiProviderConfigService.getActiveRagTopK())
                        .orElse(qdrantProperties.getRagTopK());
                int ragMaxChars = getRagMaxChars();
                String ragContext = null;
                if (embeddingService.isAvailable() && waitForContentEmbedding(book.getId())) {
                    try {
                        ragContext = doRagRetrieval(book, question, lastAiAnswer, ragTopK, ragMaxChars, emitter);
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
                // RAG 参考内容 + 用户问题分别构造为独立消息（结构清晰，避免问题被 RAG 淹没）
                String ragMessage = buildRagMessage(ragContext);
                String questionMessage = buildQuestionMessage(question);
                long startTime = System.currentTimeMillis();

                log.debug("图书问答: bookId={}, question={}, bookInfoLen={}, ragContextLen={}, questionMsgLen={}",
                        bookId, question, bookInfoPrompt.length(),
                        ragContext != null ? ragContext.length() : 0, questionMessage.length());

                // SystemMessage → UserMessage(bookInfo) → HistoryMessages → UserMessage(RAG) → UserMessage(question)
                List<ChatMessage> messages = buildChatMessages(effectiveSessionId, userId, bookInfoPrompt, ragMessage, questionMessage);

                StringBuilder fullThinking = new StringBuilder();

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
                    public void onComplete(String answer, ChatResponse completeResponse) {
                        long elapsed = System.currentTimeMillis() - startTime;

                        int apiInputTokens = completeResponse.tokenUsage() != null && completeResponse.tokenUsage().inputTokenCount() != null
                                ? completeResponse.tokenUsage().inputTokenCount() : 0;
                        int apiOutputTokens = completeResponse.tokenUsage() != null && completeResponse.tokenUsage().outputTokenCount() != null
                                ? completeResponse.tokenUsage().outputTokenCount() : 0;

                        log.info("========== 图书问答 AI 流式响应完成 ==========");
                        log.info("耗时: {}ms", elapsed);
                        log.info("API实际token: 输入={}, 输出={}, 总={}", apiInputTokens, apiOutputTokens, apiInputTokens + apiOutputTokens);
                        log.info("Answer: {}", CommonUtils.truncateText(answer, 100).replace("\n", " "));
                        log.info("==========================================");

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
                        saveMessage(userId, effectiveSessionId, "assistant", safeAnswer, bookId, thinkingText);
                        updateSessionTimestamp(effectiveSessionId);

                        CommonUtils.logAiCall("图书问答", elapsed, apiInputTokens, apiOutputTokens,
                                String.format("bookId=%d, question=%s", bookId, question.substring(0, Math.min(30, question.length()))));

                        // 写入 RAG 答案缓存（仅首问，追问不缓存）
                        if (cacheable) {
                            ragAnswerCache.put(bookId, question, modelName, safeAnswer);
                        }
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
                    }

                    @Override
                    public void onError(Throwable error) {
                        aiProviderConfigService.clearAssistantCache();
                    }
                }, 2);

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
        return chatModelManager.generateFollowUpQuestions(title, question, answer, user, book);
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
     *
     * @param question 用户原始问题
     * @return 问题消息字符串
     */
    private String buildQuestionMessage(String question) {
        return "【读者的问题】\n" + question + "\n\n请根据上述内容，用中文回答。【回答铁律】已在上方告知，请严格遵守。";
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
                    .build();
            conversationRepository.save(record);
        } catch (Exception e) {
            log.warn("保存图书问答记录失败: {}", e.getMessage());
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

        // 3. 计算触发阈值
        Integer maxTokens = aiProviderConfigService.getActiveMaxTokens();
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
        List<String> summaries = chatModelManager.compressContentBatch(originals);
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
        Integer maxTokens = aiProviderConfigService.getActiveMaxTokens();
        int tokens = maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS;
        return (int) (tokens * TOKEN_TO_CHAR_RATIO * 0.6);
    }

    /**
     * 列表型问题 RAG 优化入口。
     * <p>
     * 流程：
     * 1. LLM 检测问题是否为列表型（含列表主题提取）
     * 2. 若是列表型 → 选档策略 → 执行对应检索流程
     * 3. 拼接 RAG 上下文返回
     * <p>
     * 返回 null 表示：
     * - 不是列表型问题 → 走常规 RAG
     * - 是列表型但所有档位都失败 → 走常规 RAG（兜底）
     *
     * @return RAG 上下文字符串；非列表型或失败返回 null
     */
    private String tryListQueryRagRetrieval(Book book, String question, String lastAiAnswer,
                                            int ragMaxChars, SseEmitter emitter) {
        // 1. 检测列表型问题
        ListQueryDetector.DetectionResult detection = listQueryDetector.detect(question, lastAiAnswer);
        if (!detection.isListQuery()) {
            return null; // 非列表型，走常规 RAG
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
            return null;
        }

        // 4. 检查结果
        if (result.matches().isEmpty()) {
            log.warn("[ListQuery] 策略 {} 无结果，退化为常规 RAG: {}", strategy, result.strategyLog());
            return null;
        }

        // 5. 拼接 RAG 上下文
        StringBuilder sb = new StringBuilder();
        int totalLen = 0;
        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            String chunkText = match.embedded() != null ? match.embedded().text() : "";
            if (chunkText.isBlank()) continue;
            if (totalLen + chunkText.length() > ragMaxChars) break;
            sb.append(chunkText).append("\n\n");
            totalLen += chunkText.length();
        }

        String ragContext = sb.toString();
        if (ragContext.isBlank()) {
            log.warn("[ListQuery] 策略 {} 拼接后上下文为空", strategy);
            return null;
        }

        log.info("[ListQuery] 检索成功: bookId={}, strategy={}, matches={}, chars={}, log={}",
                book.getId(), strategy, result.matches().size(), ragContext.length(), result.strategyLog());

        try {
            emitter.send(SseEmitter.event().name("thinking_content")
                    .data("找到 " + result.matches().size() + " 条相关内容（列表型优化），我整理一下思路…\n"));
        } catch (Exception ignored) {
        }

        return ragContext;
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
     * @return RAG 上下文字符串，不可用时返回 null
     */
    private String doRagRetrieval(Book book, String question, String lastAiAnswer, int ragTopK, int ragMaxChars,
                                  SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("thinking_content")
                    .data("我好好分析一下这个问题…\n"));
        } catch (Exception ignored) {
        }

        // ===== 列表型问题 RAG 优化分支 =====
        String listRagContext = tryListQueryRagRetrieval(book, question, lastAiAnswer, ragMaxChars, emitter);
        if (listRagContext != null) {
            return listRagContext;
        }
        // 列表型分支未触发或失败 → 走常规 RAG 流程（兜底）

        List<String> subQueries = chatModelManager.expandQuery(question, lastAiAnswer, book);
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
        return ragContext;
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
        String compressed = chatModelManager.generateCompressedSummary(book);
        if (compressed != null && !compressed.isBlank()) {
            book.setCompressedSummary(compressed);
            bookService.updateBook(book.getId(), book);
            log.info("compressedSummary 懒生成成功: bookId={}, len={}", book.getId(), compressed.length());
        }
    }

}
