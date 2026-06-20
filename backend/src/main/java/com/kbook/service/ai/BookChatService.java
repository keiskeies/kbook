package com.kbook.service.ai;

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
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
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

        try {
            emitter.send(SseEmitter.event().name("thinking_content").data("让我先翻翻这本书…\n"));
        } catch (Exception ignored) {
        }

        Future<?> aiFuture = sseExecutor.submit(() -> {
            try {
                // 1. 按需生成内容向量（首次问答时触发）
                if (!ensureContentEmbedded(book, bookId, emitter)) return;

                // 2. 懒生成 compressedSummary（首次问答时若为空，同步生成并持久化）
                ensureCompressedSummary(book, emitter);

                int ragTopK = Optional.ofNullable(aiProviderConfigService.getActiveRagTopK())
                        .orElse(qdrantProperties.getRagTopK());
                int ragMaxChars = getRagMaxChars();
                String lastAiAnswer = getLastAiAnswer(userId, effectiveSessionId);
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


                // 构建图书基本信息（静态，跨会话共享 KV Cache）和 RAG+问题（每次变化）
                String bookInfoPrompt = buildBookInfoPrompt(book);
                String prompt = buildPrompt(question, ragContext != null ? ragContext : "");
                long startTime = System.currentTimeMillis();

                log.debug("图书问答: bookId={}, question={}, bookInfoLen={}, ragContextLen={}, promptLen={}",
                        bookId, question, bookInfoPrompt.length(),
                        ragContext != null ? ragContext.length() : 0, prompt.length());

                // SystemMessage → UserMessage(bookInfo) → HistoryMessages → UserMessage(RAG + question)
                List<ChatMessage> messages = buildChatMessages(effectiveSessionId, userId, bookInfoPrompt, prompt);

                StringBuilder fullResponse = new StringBuilder();
                StringBuilder fullThinking = new StringBuilder();
                final boolean[] connectionClosed = {false};

                model.chat(
                        messages,
                        new StreamingChatResponseHandler() {
                            StreamingHandle streamingHandle;

                            @Override
                            public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
                                if (streamingHandle == null) {
                                    streamingHandle = context.streamingHandle();
                                }
                                if (connectionClosed[0] || (streamingHandle != null && streamingHandle.isCancelled()))
                                    return;
                                String thinking = partialThinking.text();
                                if (thinking != null && !thinking.isEmpty()) {
                                    fullThinking.append(thinking);
                                    if (!SseHelper.safeSendEvent(emitter, "thinking_content", thinking)) {
                                        connectionClosed[0] = true;
                                        if (streamingHandle != null) streamingHandle.cancel();
                                        log.warn("SSE 连接已关闭，停止 AI 输出: bookId={}", bookId);
                                    }
                                }
                            }

                            @Override
                            public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                                if (streamingHandle == null) {
                                    streamingHandle = context.streamingHandle();
                                }
                                if (connectionClosed[0] || (streamingHandle != null && streamingHandle.isCancelled()))
                                    return;
                                String text = partialResponse.text();
                                fullResponse.append(text);
                                if (!text.isEmpty()) {
                                    if (!SseHelper.safeSendEvent(emitter, "message", text)) {
                                        connectionClosed[0] = true;
                                        if (streamingHandle != null) streamingHandle.cancel();
                                        log.warn("SSE 连接已关闭，停止 AI 输出: bookId={}", bookId);
                                    }
                                }
                            }

                            @Override
                            public void onCompleteResponse(ChatResponse completeResponse) {
                                long elapsed = System.currentTimeMillis() - startTime;
                                String answer = fullResponse.toString().trim();

                                int apiInputTokens = completeResponse.tokenUsage() != null && completeResponse.tokenUsage().inputTokenCount() != null
                                        ? completeResponse.tokenUsage().inputTokenCount() : 0;
                                int apiOutputTokens = completeResponse.tokenUsage() != null && completeResponse.tokenUsage().outputTokenCount() != null
                                        ? completeResponse.tokenUsage().outputTokenCount() : 0;

                                log.info("========== 图书问答 AI 流式响应完成 ==========");
                                log.info("耗时: {}ms", elapsed);
                                log.info("API实际token: 输入={}, 输出={}, 总={}", apiInputTokens, apiOutputTokens, apiInputTokens + apiOutputTokens);
                                log.info("Answer: {}", answer.length() > 500 ? answer.substring(0, 500) + "..." : answer);
                                log.info("==========================================");

                                if (connectionClosed[0]) {
                                    log.warn("SSE 连接已断开，跳过发送done事件，仅保存已输出内容: bookId={}", bookId);
                                } else {
                                    try {
                                        emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                        emitter.complete();
                                    } catch (Exception ignored) {
                                    }
                                }

                                ensureSession(userId, effectiveSessionId, question, bookId);
                                saveMessage(userId, effectiveSessionId, "user", question, bookId, null);
                                String thinkingText = !fullThinking.isEmpty() ? fullThinking.toString() : null;
                                saveMessage(userId, effectiveSessionId, "assistant", answer, bookId, thinkingText);
                                updateSessionTimestamp(effectiveSessionId);

                                CommonUtils.logAiCall("图书问答", elapsed, apiInputTokens, apiOutputTokens,
                                        String.format("bookId=%d, question=%s", bookId, question.substring(0, Math.min(30, question.length()))));
                            }

                            @Override
                            public void onError(Throwable error) {
                                if (connectionClosed[0] || (streamingHandle != null && streamingHandle.isCancelled())) {
                                    log.warn("SSE 连接已断开，跳过错误处理: bookId={}", bookId);
                                    return;
                                }
                                log.error("图书问答流式异常: bookId={} - {}", bookId, error.getMessage(), error);
                                aiProviderConfigService.clearAssistantCache();
                                SseHelper.sendErrorAndComplete(emitter, "AI 响应异常: " + SseHelper.extractFriendlyError(error));
                            }
                        }
                );

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
     * @param currentPrompt  当前用户提示词（RAG + 问题）
     * @return 完整的 ChatMessage 列表
     */
    private List<ChatMessage> buildChatMessages(String sessionId, Long userId, String bookInfoPrompt, String currentPrompt) {
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
                    + currentPrompt.length()
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

        messages.add(UserMessage.from(currentPrompt));
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


    /**
     * 合并相邻的文本片段，减少重复上下文
     * 按 chunkIndex 排序后，将索引差 ≤ 2 且合并后不超过 2000 字符的片段拼接
     *
     * @param matches 原始匹配结果列表
     * @return 合并后的匹配结果列表
     */
    private List<EmbeddingMatch<TextSegment>> mergeAdjacentChunks(List<EmbeddingMatch<TextSegment>> matches) {
        if (matches.size() <= 1) return matches;

        List<EmbeddingMatch<TextSegment>> sortedByIndex = new ArrayList<>(matches);
        sortedByIndex.sort((a, b) -> {
            int indexA = getChunkIndex(a);
            int indexB = getChunkIndex(b);
            return Integer.compare(indexA, indexB);
        });

        List<EmbeddingMatch<TextSegment>> merged = new ArrayList<>();
        EmbeddingMatch<TextSegment> current = sortedByIndex.get(0);
        StringBuilder mergedText = new StringBuilder(current.embedded() != null ? current.embedded().text() : "");
        double bestScore = current.score();

        for (int i = 1; i < sortedByIndex.size(); i++) {
            EmbeddingMatch<TextSegment> next = sortedByIndex.get(i);
            int currentIndex = getChunkIndex(current);
            int nextIndex = getChunkIndex(next);

            if (nextIndex <= currentIndex + 1 && nextIndex > currentIndex) {
                String nextText = next.embedded() != null ? next.embedded().text() : "";
                mergedText.append("\n\n").append(nextText);
                if (next.score() > bestScore) bestScore = next.score();
                current = next;
                continue;
            }

            merged.add(createMergedMatch(mergedText.toString(), current, bestScore));
            mergedText = new StringBuilder(next.embedded() != null ? next.embedded().text() : "");
            bestScore = next.score();
            current = next;
        }
        merged.add(createMergedMatch(mergedText.toString(), current, bestScore));

        return merged;
    }

    /**
     * 从片段元数据中提取 chunkIndex，用于判断片段在书籍中的位置顺序
     *
     * @param match 向量检索匹配结果
     * @return 片段索引，元数据缺失时返回 0
     */
    private int getChunkIndex(EmbeddingMatch<TextSegment> match) {
        if (match.embedded() != null && match.embedded().metadata() != null) {
            Long idx = match.embedded().metadata().getLong("chunkIndex");
            return idx != null ? idx.intValue() : 0;
        }
        return 0;
    }

    /**
     * 根据模板创建合并后的匹配结果，复用模板的 embeddingId 和元数据
     *
     * @param text     合并后的文本内容
     * @param template 原始匹配结果（用于复用元数据）
     * @param score    合并后的得分
     * @return 新的匹配结果对象
     */
    private EmbeddingMatch<TextSegment> createMergedMatch(String text, EmbeddingMatch<TextSegment> template, double score) {
        TextSegment segment = TextSegment.from(text,
                template.embedded() != null ? template.embedded().metadata() : new dev.langchain4j.data.document.Metadata());
        return new EmbeddingMatch<>(score, template.embeddingId(), template.embedding(), segment);
    }

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
     * 构建 RAG 检索上下文 + 用户问题的提示词
     * 不包含图书基本信息（已由 buildBookInfoPrompt 单独发送以优化 KV Cache）
     *
     * @param question   用户问题
     * @param ragContext RAG 检索到的参考内容
     * @return RAG + 问题文本
     */
    private String buildPrompt(String question, String ragContext) {
        StringBuilder sb = new StringBuilder();

        if (!ragContext.isBlank()) {
            sb.append("【书籍参考内容】（以下是从原著中检索到的与问题相关的片段）\n");
            sb.append(ragContext);
        } else {
            sb.append("【注意】未从原著中检索到直接相关的内容片段，请根据书籍基本信息谨慎回答。\n");
        }

        sb.append("\n【读者的问题】\n").append(question);

        sb.append("\n\n【重要提醒】请用中文直接回答上述问题，不要翻译、分类或解释参考片段。绝对不要直接引用或复述书中的原文内容，而是用自己的语言概括和转述书中的观点、情节和信息。");

        String prompt = sb.toString();

        long qmCount = prompt.chars().filter(c -> c == '?').count();
        if (qmCount > prompt.length() * 0.05) {
            log.warn("[编码诊断] RAG提示词疑似乱码! 问号占比={}/{}", qmCount, prompt.length());
        }

        return prompt;
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
                    String title = userMessage.length() > 30 ? userMessage.substring(0, 30) + "..." : userMessage;
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
     * 压缩目标：历史占比降到该比例以下停止
     */
    private static final double COMPRESS_TARGET_RATIO = 0.6;

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

        // 3. 计算阈值
        Integer maxTokens = aiProviderConfigService.getActiveMaxTokens();
        int tokenLimit = maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS;
        int charLimit = (int) (tokenLimit * TOKEN_TO_CHAR_RATIO);
        long compressTarget = (long) (charLimit * COMPRESS_TARGET_RATIO) - currentOverheadChars;

        long totalWithCurrent = totalChars + currentOverheadChars;
        log.debug("压缩检查: sessionId={}, history={}, current={}, total={}/{} ({}%)",
                sessionId, totalChars, currentOverheadChars,
                totalWithCurrent, charLimit,
                charLimit > 0 ? totalWithCurrent * 100 / charLimit : 0);

        // 4. 未达触发阈值，直接返回
        if (totalWithCurrent < charLimit * COMPRESS_TRIGGER_RATIO) {
            return conversations;
        }

        // 5. 从内存中找最老的未压缩 assistant 消息并压缩（避免逐条 DB 查询）
        int compressed = 0;
        while (totalChars >= Math.max(compressTarget, 0)) {
            AiConversation target = findFirstUncompressedAssistantInMemory(conversations);
            if (target == null) {
                log.info("无可压缩的 AI 回复: sessionId={}, compressed={}", sessionId, compressed);
                break;
            }

            String original = target.getContent();
            String summary = chatModelManager.compressContent(original);
            if (summary == null) {
                log.warn("压缩失败(跳过): sessionId={}, convId={}", sessionId, target.getId());
                break;
            }

            target.setCompressedContent(summary);
            conversationRepository.save(target);
            totalChars = totalChars - original.length() + summary.length();
            compressed++;
            log.info("压缩历史消息: sessionId={}, convId={}, {}→{} chars, totalChars={}",
                    sessionId, target.getId(), original.length(), summary.length(), totalChars);
        }

        if (compressed > 0) {
            log.info("压缩完成: sessionId={}, compressed={}条", sessionId, compressed);
        }

        return conversations;
    }

    /**
     * 从内存列表中查找第一条未压缩的 assistant 消息。
     * 未压缩判定：compressedContent 为 null，或与 content 完全相同（创建时初始化相等）。
     */
    private AiConversation findFirstUncompressedAssistantInMemory(List<AiConversation> conversations) {
        for (AiConversation c : conversations) {
            if (!"assistant".equals(c.getRole())) continue;
            String compressed = c.getCompressedContent();
            String original = c.getContent();
            if (compressed == null || compressed.equals(original)) {
                return c;
            }
        }
        return null;
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
                    String json = new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(questions);
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
     * RAG 检索核心逻辑：查询扩展 → 多查询检索 → 去重 → 合并相邻片段 → 组装上下文。
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
        List<String> subQueries = chatModelManager.expandQuery(question, lastAiAnswer, book);
        try {
            emitter.send(SseEmitter.event().name("thinking_content")
                    .data("找找 [" + String.join("，", subQueries) + "] 相关内容…\n"));
        } catch (Exception ignored) {
        }
        Map<String, EmbeddingMatch<TextSegment>> dedupedMatches = new LinkedHashMap<>();
        int rawCount = 0, rawChars = 0;
        int maxResult = subQueries.isEmpty() ? ragTopK :
                Math.min(ragTopK, Math.max(ragTopK / 2, ragTopK * 2 / subQueries.size()));

        for (String subQuery : subQueries) {
            try {
                List<EmbeddingMatch<TextSegment>> matches =
                        embeddingService.searchContent(subQuery, maxResult, book);
                for (EmbeddingMatch<TextSegment> match : matches) {
                    String chunkText = match.embedded() != null ? match.embedded().text() : "";
                    if (chunkText.isBlank()) continue;
                    rawCount++;
                    rawChars += chunkText.length();
                    String dedupeKey = chunkText.length() > 80
                            ? chunkText.substring(0, 80) : chunkText;
                    dedupedMatches.merge(dedupeKey, match,
                            (existing, incoming) -> incoming.score() > existing.score() ? incoming : existing);
                }
            } catch (Exception e) {
                log.debug("子查询检索失败: subQuery={} - {}", subQuery, e.getMessage());
            }
        }

        List<EmbeddingMatch<TextSegment>> allMatches = new ArrayList<>(dedupedMatches.values());
        int dedupChars = allMatches.stream()
                .mapToInt(m -> m.embedded() != null ? m.embedded().text().length() : 0).sum();

        if (allMatches.isEmpty()) {
            log.debug("RAG 检索无结果: bookId={}, question={}",
                    book.getId(), question.substring(0, Math.min(30, question.length())));
            Boolean reEmbedResult = bookParserService.forceReEmbedIfMissing(book.getId());
            if (reEmbedResult != null && reEmbedResult) {
                log.info("内容向量重建成功，重新执行 RAG 检索: bookId={}", book.getId());
                for (String subQuery : subQueries) {
                    try {
                        List<EmbeddingMatch<TextSegment>> matches =
                                embeddingService.searchContent(subQuery, maxResult, book);
                        for (EmbeddingMatch<TextSegment> match : matches) {
                            String chunkText = match.embedded() != null ? match.embedded().text() : "";
                            if (chunkText.isBlank()) continue;
                            String dedupeKey = chunkText.length() > 80
                                    ? chunkText.substring(0, 80) : chunkText;
                            dedupedMatches.merge(dedupeKey, match,
                                    (existing, incoming) -> incoming.score() > existing.score() ? incoming : existing);
                        }
                    } catch (Exception e) {
                        log.debug("子查询检索失败: subQuery={} - {}", subQuery, e.getMessage());
                    }
                }
                allMatches = new ArrayList<>(dedupedMatches.values());
            }
        }

        List<EmbeddingMatch<TextSegment>> merged = mergeAdjacentChunks(allMatches);
        int mergeChars = merged.stream()
                .mapToInt(m -> m.embedded() != null ? m.embedded().text().length() : 0).sum();

        StringBuilder sb = new StringBuilder();
        int totalLen = 0;
        for (int i = 0; i < merged.size(); i++) {
            EmbeddingMatch<TextSegment> match = merged.get(i);
            String chunkText = match.embedded() != null ? match.embedded().text() : "";
            if (chunkText.isBlank()) continue;
            if (totalLen + chunkText.length() > ragMaxChars) break;
            sb.append("【参考片段").append(i + 1).append("】\n");
            sb.append(chunkText).append("\n\n");
            totalLen += chunkText.length();
        }

        String ragContext = sb.toString();
        log.info("RAG检索 bookId={} | 原始{}条{}字 → 去重后得{}条{}字 → 合并后得{}条{}字 → 最终{}字",
                book.getId(), rawCount, rawChars,
                dedupedMatches.size(), dedupChars,
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
