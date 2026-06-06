package com.kbook.service;

import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.config.ChatModelFactory;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.config.properties.QdrantProperties;
import com.kbook.constants.AiPromptConstants;
import com.kbook.entity.AiConversation;
import com.kbook.entity.AiSession;
import com.kbook.entity.Book;
import com.kbook.entity.BookSuggestedQuestion;
import com.kbook.repository.AiConversationRepository;
import com.kbook.repository.AiSessionRepository;
import com.kbook.repository.BookSuggestedQuestionRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
@RequiredArgsConstructor
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

    /**
     * SSE 异步执行线程池
     */
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    /**
     * 根据书籍标签获取预设推荐问题
     *
     * @param book 书籍实体
     * @return 推荐问题列表
     */
    private List<String> getSuggestedQuestionsForBook(Book book) {
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
        List<BookSuggestedQuestion> existing = suggestedQuestionRepository.findByBookId(bookId);
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

        Book book = bookService.getBookById(bookId);
        if (book == null) {
            return BookTagQuestions.getQuestions(null);
        }
        return getSuggestedQuestionsForBook(book);
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

        final String finalSessionId = (sessionId == null || sessionId.isBlank())
                ? "book-" + bookId + "-" + UUID.randomUUID().toString().substring(0, 8)
                : sessionId;

        SseEmitter emitter = new SseEmitter(180_000L);

        try {
            emitter.send(SseEmitter.event().name("session_id").data(finalSessionId));
        } catch (Exception ignored) {
        }

        try {
            emitter.send(SseEmitter.event().name("thinking").data("正在检索书籍内容..."));
        } catch (Exception ignored) {
        }

        sseExecutor.execute(() -> {
            try {
                Book book = bookService.getBookById(bookId);
                if (book == null) {
                    SseHelper.sendErrorAndComplete(emitter, "图书不存在");
                    return;
                }

                if (!Boolean.TRUE.equals(book.getContentEmbedded())) {
                    log.info("图书未生成内容向量，尝试按需生成: bookId={}", bookId);
                    try {
                        emitter.send(SseEmitter.event().name("thinking").data("正在检索书籍内容，请稍候..."));
                    } catch (Exception ignored) {
                    }

                    boolean done = false;
                    long deadline = System.currentTimeMillis() + 3600_000L;
                    while (!done && System.currentTimeMillis() < deadline) {
                        Boolean result = bookParserService.ensureContentEmbedded(bookId);
                        if (result == null) {
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        } else {
                            done = true;
                            if (result) {
                                book.setContentEmbedded(true);
                                log.info("按需生成内容向量成功: bookId={}", bookId);
                            } else {
                                SseHelper.sendErrorAndComplete(emitter, "该书无法提取文本内容，无法进行 AI 问答");
                                return;
                            }
                        }
                    }
                    if (!done) {
                        SseHelper.sendErrorAndComplete(emitter, "内容向量生成等待超时，请稍后重试");
                        return;
                    }
                }

                int ragTopK = Optional.ofNullable(aiProviderConfigService.getActiveRagTopK())
                        .orElse(qdrantProperties.getRagTopK());
                int ragMaxChars = getRagMaxChars();
                String lastAiAnswer = getLastAiAnswer(userId, finalSessionId);
                String ragContext = retrieveRagContext(book, question, ragTopK, ragMaxChars, lastAiAnswer);
                log.debug("RAG 检索结果长度: {}", ragContext.length());

                try {
                    emitter.send(SseEmitter.event().name("thinking").data("根据图书内容思考中..."));
                } catch (Exception ignored) {
                }

                String fullPrompt = buildPrompt(book, question, ragContext);

                StreamingChatModel streamingChatModel = chatModelFactory.buildStreamingChatModel();
                if (streamingChatModel == null) {
                    SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                long startTime = System.currentTimeMillis();

                log.debug("图书问答: bookId={}, question={}, ragContextLen={}, fullPromptLen={}",
                        bookId, question, ragContext.length(), fullPrompt.length());

                List<ChatMessage> messages = buildChatMessages(finalSessionId, userId, fullPrompt);

                StringBuilder fullResponse = new StringBuilder();
                StringBuilder fullThinking = new StringBuilder();

                streamingChatModel.chat(
                        messages,
                        new StreamingChatResponseHandler() {
                            @Override
                            public void onPartialThinking(dev.langchain4j.model.chat.response.PartialThinking partialThinking) {
                                if (Thread.currentThread().isInterrupted()) return;
                                String thinking = partialThinking.text();
                                if (thinking != null && !thinking.isEmpty()) {
                                    fullThinking.append(thinking);
                                    if (!SseHelper.safeSendEvent(emitter, "thinking_content", thinking)) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                            }

                            @Override
                            public void onPartialResponse(String partialResponse) {
                                if (Thread.currentThread().isInterrupted()) return;
                                fullResponse.append(partialResponse);
                                if (!partialResponse.isEmpty()) {
                                    if (!SseHelper.safeSendEvent(emitter, "message", partialResponse)) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                            }

                            @Override
                            public void onCompleteResponse(ChatResponse completeResponse) {
                                if (Thread.currentThread().isInterrupted()) return;
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

                                try {
                                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                    emitter.complete();
                                } catch (Exception ignored) {
                                }

                                ensureSession(userId, finalSessionId, question, bookId);
                                saveMessage(userId, finalSessionId, "user", question, bookId, null);
                                String thinkingText = !fullThinking.isEmpty() ? fullThinking.toString() : null;
                                saveMessage(userId, finalSessionId, "assistant", answer, bookId, thinkingText);
                                updateSessionTimestamp(finalSessionId);

                                CommonUtils.logAiCall("图书问答", elapsed, apiInputTokens, apiOutputTokens,
                                        String.format("bookId=%d, question=%s", bookId, question.substring(0, Math.min(30, question.length()))));
                            }

                            @Override
                            public void onError(Throwable error) {
                                if (Thread.currentThread().isInterrupted()) return;
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

        emitter.onTimeout(() -> log.warn("图书问答SSE超时: bookId={}", bookId));
        emitter.onError(e -> log.error("图书问答SSE错误: bookId={}", bookId, e));

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
        List<AiSession> sessions = sessionRepository.findByUserIdAndTypeAndBookIdOrderByUpdatedAtDesc(userId, TYPE, bookId);
        if (sessions.isEmpty()) {
            return Collections.emptyList();
        }
        return conversationRepository.findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessions.get(0).getSessionId());
    }

    /**
     * 获取用户对指定书籍的所有问答会话
     *
     * @param userId 用户ID
     * @param bookId 书籍ID
     * @return 会话列表
     */
    @LogAction("获取问答会话列表")
    public List<AiSession> getBookChatSessions(Long userId, Long bookId) {
        return sessionRepository.findByUserIdAndTypeAndBookIdOrderByUpdatedAtDesc(userId, TYPE, bookId);
    }

    /**
     * 根据已有问答生成深入追问问题
     *
     * @param bookId   书籍ID
     * @param question 原始问题
     * @param answer   AI 回答
     * @return 深入追问问题列表（最多3个）
     */
    @LogAction("生成深入追问")
    public List<String> generateFollowUpQuestions(Long bookId, String question, String answer) {
        if (answer == null || answer.isBlank() || question == null || question.isBlank()) {
            return Collections.emptyList();
        }

        String title = "未知书籍";
        Book book = bookService.getBookById(bookId);
        if (book != null) {
            title = book.getTitle();
        }

        return chatModelManager.generateFollowUpQuestions(title, question, answer);
    }

    /**
     * 构建包含系统提示词、历史对话和当前问题的完整消息列表
     * 历史消息数量由压缩机制动态控制，不硬限轮数
     *
     * @param sessionId     会话ID
     * @param userId        用户ID
     * @param currentPrompt 当前用户提示词
     * @return 完整的 ChatMessage 列表
     */
    private List<ChatMessage> buildChatMessages(String sessionId, Long userId, String currentPrompt) {
        List<ChatMessage> messages = new ArrayList<>();
        String style = getChatStyleForUser(userId);
        String systemPrompt = getSystemPromptForStyle(style);
        messages.add(SystemMessage.from(systemPrompt));

        try {
            // 先压缩历史（如有需要），确保 LLM 收到的上下文不超限
            int currentOverhead = AiPromptConstants.BOOK_CHAT_SYSTEM_PROMPT.length()
                    + currentPrompt.length()
                    + 2000; // AI 回复预留
            compressHistoryIfNeeded(userId, sessionId, currentOverhead);

            List<AiConversation> history = conversationRepository
                    .findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
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
     * RAG 语义检索：从书籍内容向量中检索与问题相关的片段
     * RAG 长度上限由模型上下文动态决定：maxTokens × 1.5 × 0.6（留 40% 给系统和对话）
     * 优化策略：多查询检索 + 相邻片段合并 + 关键词重排序 + 自适应 topK
     *
     * @param book     书籍实体
     * @param question 用户问题
     * @param topK     返回的最大结果数
     * @return 拼接后的 RAG 上下文文本
     */
    private String retrieveRagContext(Book book, String question, int topK, int maxChars, String lastAiAnswer) {
        if (!embeddingService.isAvailable()) {
            log.debug("Embedding 不可用，跳过 RAG 检索");
            return "";
        }

        try {

            List<String> subQueries = chatModelManager.expandQuery(question, book.getTitle(), book.getAuthor(), lastAiAnswer);
            Map<String, EmbeddingMatch<TextSegment>> dedupedMatches = new LinkedHashMap<>();
            int rawCount = 0, rawChars = 0;
            // 优化策略：多查询检索 + 相邻片段合并 + 关键词重排序 + 自适应 topK
            int maxResult = Math.min(topK, !subQueries.isEmpty() ? topK / subQueries.size() * 2 : topK);

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
                                ? chunkText.substring(0, 80)
                                : chunkText;
                        dedupedMatches.merge(dedupeKey, match, (existing, incoming) ->
                                incoming.score() > existing.score() ? incoming : existing);
                    }
                } catch (Exception e) {
                    log.debug("子查询检索失败: subQuery={} - {}", subQuery, e.getMessage());
                }
            }

            List<EmbeddingMatch<TextSegment>> allMatches = new ArrayList<>(dedupedMatches.values());
            int dedupChars = allMatches.stream()
                    .mapToInt(m -> m.embedded() != null ? m.embedded().text().length() : 0).sum();

            if (allMatches.isEmpty()) {
                log.debug("RAG 检索无结果: bookId={}, question={}", book.getId(), question.substring(0, Math.min(30, question.length())));
                ragHitStatisticsService.recordMiss(book.getId());
                return "";
            }

            double topScore = allMatches.stream()
                    .mapToDouble(EmbeddingMatch::score)
                    .max()
                    .orElse(0.0);
            if (topScore < 0.1) {
                log.warn("[RAG质量门控] 最高score={} < 0.1, 结果为噪声, 丢弃: bookId={}, question={}",
                        String.format("%.4f", topScore), book.getId(), question.substring(0, Math.min(30, question.length())));
                ragHitStatisticsService.recordMiss(book.getId());
                return "";
            }

            ragHitStatisticsService.recordHit(book.getId());

            allMatches.sort((a, b) -> {
                double scoreA = a.score() + keywordRelevanceBonus(a, question);
                double scoreB = b.score() + keywordRelevanceBonus(b, question);
                return Double.compare(scoreB, scoreA);
            });

            List<EmbeddingMatch<TextSegment>> merged = mergeAdjacentChunks(allMatches);
            int mergeChars = merged.stream()
                    .mapToInt(m -> m.embedded() != null ? m.embedded().text().length() : 0).sum();

            StringBuilder sb = new StringBuilder();
            int totalLen = 0;
            for (int i = 0; i < merged.size(); i++) {
                EmbeddingMatch<TextSegment> match = merged.get(i);
                String chunkText = match.embedded() != null ? match.embedded().text() : "";
                if (chunkText.isBlank()) continue;
                if (totalLen + chunkText.length() > maxChars) break;
                sb.append("【参考片段").append(i + 1).append("】\n");
                sb.append(chunkText).append("\n\n");
                totalLen += chunkText.length();
            }

            String ragContext = sb.toString();
            log.info("RAG检索 bookId={} | 原始{}条{}字 → 去重{}条{}字 → 合并{}条{}字 → 最终{}字",
                    book.getId(),
                    rawCount, rawChars,
                    dedupedMatches.size(), dedupChars,
                    merged.size(), mergeChars,
                    ragContext.length());

            long questionMarkCount = ragContext.chars().filter(c -> c == '?').count();
            if (questionMarkCount > ragContext.length() * 0.1) {
                log.warn("[编码诊断] RAG 上下文疑似乱码! bookId={}, 问号占比={}/{}, 丢弃乱码上下文",
                        book.getId(), questionMarkCount, ragContext.length());
                ragHitStatisticsService.recordMiss(book.getId());
                return "";
            }

            return ragContext;
        } catch (Exception e) {
            log.warn("RAG 检索异常: bookId={} - {}", book.getId(), e.getMessage());
            ragHitStatisticsService.recordMiss(book.getId());
            return "";
        }
    }

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


    private double keywordRelevanceBonus(EmbeddingMatch<TextSegment> match, String question) {
        if (match.embedded() == null || match.embedded().text() == null) return 0;
        String chunkText = match.embedded().text();
        String[] questionWords = question.replaceAll("[的了是在有和与及或吗呢吧啊]", "").split("");
        long hitCount = 0;
        for (String word : questionWords) {
            if (!word.isBlank() && chunkText.contains(word)) {
                hitCount++;
            }
        }
        return hitCount * 0.01;
    }

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

            if (nextIndex <= currentIndex + 2 && nextIndex > currentIndex) {
                String nextText = next.embedded() != null ? next.embedded().text() : "";
                if (mergedText.length() + nextText.length() <= 2000) {
                    mergedText.append("\n\n").append(nextText);
                    if (next.score() > bestScore) bestScore = next.score();
                    current = next;
                    continue;
                }
            }

            merged.add(createMergedMatch(mergedText.toString(), current, bestScore));
            mergedText = new StringBuilder(next.embedded() != null ? next.embedded().text() : "");
            bestScore = next.score();
            current = next;
        }
        merged.add(createMergedMatch(mergedText.toString(), current, bestScore));

        return merged;
    }

    private int getChunkIndex(EmbeddingMatch<TextSegment> match) {
        if (match.embedded() != null && match.embedded().metadata() != null) {
            Long idx = match.embedded().metadata().getLong("chunkIndex");
            return idx != null ? idx.intValue() : 0;
        }
        return 0;
    }

    private EmbeddingMatch<TextSegment> createMergedMatch(String text, EmbeddingMatch<TextSegment> template, double score) {
        TextSegment segment = TextSegment.from(text,
                template.embedded() != null ? template.embedded().metadata() : new dev.langchain4j.data.document.Metadata());
        return new EmbeddingMatch<>(score, template.embeddingId(), template.embedding(), segment);
    }

    /**
     * 构建完整的图书问答提示词，包含书籍信息、RAG 上下文和用户问题
     *
     * @param book       书籍实体
     * @param question   用户问题
     * @param ragContext RAG 检索到的参考内容
     * @return 完整的提示词文本
     */
    private String buildPrompt(Book book, String question, String ragContext) {
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
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            String desc = book.getDescription().length() > 800
                    ? book.getDescription().substring(0, 800) + "..."
                    : book.getDescription();
            sb.append("简介：").append(desc).append("\n");
        }
        if (book.getToc() != null && !book.getToc().isBlank()) {
            String toc = book.getToc().length() > 1000
                    ? book.getToc().substring(0, 1000) + "..."
                    : book.getToc();
            sb.append("目录：\n").append(toc).append("\n");
        }

        if (!ragContext.isBlank()) {
            sb.append("\n【书籍参考内容】（以下是从原著中检索到的与问题相关的片段）\n");
            sb.append(ragContext);
        } else {
            sb.append("\n【注意】未从原著中检索到直接相关的内容片段，请根据书籍基本信息谨慎回答。\n");
        }

        sb.append("\n【读者的问题】\n").append(question);

        sb.append("\n\n【重要提醒】请用中文直接回答上述问题，不要翻译、分类或解释参考片段。引用原文时不超过100字，长段落请概括后引用关键句，用 > 引用格式标注原文。");

        String prompt = sb.toString();

        long qmCount = prompt.chars().filter(c -> c == '?').count();
        if (qmCount > prompt.length() * 0.05) {
            log.warn("[编码诊断] 提示词疑似乱码! bookId={}, 问号占比={}/{}, JVM默认编码={}",
                    book.getId(), qmCount, prompt.length(), java.nio.charset.Charset.defaultCharset());
        }

        return prompt;
    }

    /**
     * 确保会话记录存在，不存在则自动创建（含 bookId）
     */
    private void ensureSession(Long userId, String sessionId, String userMessage, Long bookId) {
        sessionRepository.findBySessionId(sessionId).orElseGet(() -> {
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

    /**
     * 更新会话的最后活跃时间
     */
    private void updateSessionTimestamp(String sessionId) {
        sessionRepository.findBySessionId(sessionId).ifPresent(session -> {
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

    private String getSystemPromptForStyle(String style) {
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
     * 按需压缩会话历史消息，防止超出模型 token 限制
     * <p>
     * 当会话历史总字符数（含当前开销）超过触发阈值时，从最老的未压缩 AI 回复开始逐条压缩，
     * 直到总字符数降至目标值以下。压缩通过 AI 模型将原始内容总结为更简短的版本，
     * 并保存到 compressed_content 字段中。
     *
     * @param userId               用户ID，用于定位会话记录
     * @param sessionId            会话ID，标识需要压缩的具体会话
     * @param currentOverheadChars 当前请求的系统开销字符数（包括系统提示词、RAG 上下文等固定部分）
     */
    private void compressHistoryIfNeeded(Long userId, String sessionId, int currentOverheadChars) {
        // 计算字符数限制和压缩目标值
        Integer maxTokens = aiProviderConfigService.getActiveMaxTokens();
        int tokenLimit = maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS;
        int charLimit = (int) (tokenLimit * TOKEN_TO_CHAR_RATIO);
        long compressTarget = (long) (charLimit * COMPRESS_TARGET_RATIO) - currentOverheadChars;
        try {
            // 检查是否需要压缩：总字符数未达到触发阈值则直接返回
            long totalChars = conversationRepository.sumCompressedContentLength(userId, sessionId);
            long totalWithCurrent = totalChars + currentOverheadChars;
            log.debug("压缩检查: sessionId={}, history={}, current={}, total={}/{} ({}%)",
                    sessionId, totalChars, currentOverheadChars,
                    totalWithCurrent, charLimit,
                    charLimit > 0 ? totalWithCurrent * 100 / charLimit : 0);
            if (totalWithCurrent < charLimit * COMPRESS_TRIGGER_RATIO) return;

            // 循环压缩最老的未压缩 AI 回复，直到总量降至目标线以下
            int compressed = 0;
            while (totalChars >= Math.max(compressTarget, 0)) {
                // 查找最老的未压缩 AI 回复，若无则退出
                AiConversation target = conversationRepository
                        .findFirstUncompressedAssistant(userId, sessionId)
                        .orElse(null);
                if (target == null) {
                    log.info("无可压缩的 AI 回复: sessionId={}, compressed={}", sessionId, compressed);
                    return;
                }

                // 调用 AI 模型压缩内容，失败则跳过
                String original = target.getContent();
                String summary = chatModelManager.compressContent(original);
                if (summary == null) {
                    log.warn("压缩失败(跳过): sessionId={}, convId={}", sessionId, target.getId());
                    break;
                }

                // 保存压缩结果并更新累计字符数统计
                target.setCompressedContent(summary);
                conversationRepository.save(target);
                totalChars = totalChars - CHAR_LENGTH_ESTIMATE.apply(original) + CHAR_LENGTH_ESTIMATE.apply(summary);
                compressed++;
                log.info("压缩历史消息: sessionId={}, convId={}, {}→{} chars, totalChars={}",
                        sessionId, target.getId(), original.length(), summary.length(), totalChars);
            }
            // 输出压缩完成统计信息
            if (compressed > 0) {
                log.info("压缩完成: sessionId={}, compressed={}条", sessionId, compressed);
            }
        } catch (Exception e) {
            log.warn("压缩历史消息失败: {}", e.getMessage());
        }
    }

    /**
     * 估算字符数（优先 CHAR_LENGTH，兜底用 Java length）
     */
    private static final java.util.function.Function<String, Integer> CHAR_LENGTH_ESTIMATE =
            s -> s != null ? s.length() : 0;

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

}
