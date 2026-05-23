package com.kbook.service;

import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class BookChatService {

    private static final String TYPE = "book_chat";

    private final EmbeddingService embeddingService;
    private final BookService bookService;
    private final BookParserService bookParserService;
    private final AiProviderConfigService aiProviderConfigService;
    private final AiConversationRepository conversationRepository;
    private final AiSessionRepository sessionRepository;
    private final BookSuggestedQuestionRepository suggestedQuestionRepository;
    private final BookQuestionGenService questionGenService;
    private final QdrantProperties qdrantProperties;
    private final RagHitStatisticsService ragHitStatisticsService;

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    private static final String CAT_FICTION = "FICTION_LITERATURE";
    private static final String CAT_EMOTION = "EMOTION_PSYCHOLOGY";
    private static final String CAT_BUSINESS = "BUSINESS_CAREER";
    private static final String CAT_HISTORY = "HISTORY_HUMANITIES";
    private static final String CAT_GROWTH = "GROWTH_EDUCATION";
    private static final String CAT_HEALTH = "HEALTH_WELLNESS";
    private static final String CAT_FAMILY = "FAMILY_PARENTING";
    private static final String CAT_DEFAULT = "DEFAULT";

    private static final Map<String, List<String>> SUGGESTED_QUESTIONS = Map.of(
            CAT_FICTION, List.of(
                    "这本书的核心隐喻或象征是什么？作者想通过故事表达什么？",
                    "主角的性格弧光是如何随着情节演变的？",
                    "作者如何通过细节描写来烘托故事的氛围？",
                    "书中的冲突反映了怎样的社会矛盾或人性困境？",
                    "如果让你给这本书写一个不同的结局，你会怎么设计？",
                    "这本书的叙事结构（如倒叙、多视角）有什么独特之处？"
            ),
            CAT_EMOTION, List.of(
                    "书中描述的心理机制如何解释我日常的情绪波动？",
                    "作者提供了哪些改善亲密关系或家庭沟通的实操建议？",
                    "如何通过书中的方法建立更强大的自我认知和内在安全感？",
                    "书中提到的心理防御机制在现实生活中有哪些具体表现？",
                    "面对书中的情感困境，作者认为最关键的破局点是什么？",
                    "这本书对于'爱自己'和'接纳不完美'有哪些深刻见解？"
            ),
            CAT_BUSINESS, List.of(
                    "书中提到的商业模型在当今市场环境下是否依然有效？",
                    "作者对于领导力提升或团队管理的核心观点是什么？",
                    "如何把书中的策略或工具应用到我的具体工作场景中？",
                    "书中揭示了哪些关于财富积累、投资或避坑的底层逻辑？",
                    "面对行业变革或职场危机，书中建议我们如何保持竞争力？",
                    "这本书对于创业者的决策思维或风险控制有什么启发？"
            ),
            CAT_HISTORY, List.of(
                    "这段历史对理解当下的社会问题或国际局势有什么启示？",
                    "作者是如何客观评价书中关键历史人物的功过是非的？",
                    "书中探讨的文化根源如何影响了现代人的思维方式和价值观？",
                    "从历史规律来看，书中提到的社会变革有哪些必然性和偶然性？",
                    "作者是如何在宏大叙事中展现个体命运的挣扎与抉择的？",
                    "这本书对于理解不同文明或政治制度的演变有什么帮助？"
            ),
            CAT_GROWTH, List.of(
                    "书中提到的学习方法或思维模型如何落地到我的日常习惯中？",
                    "作者在个人成长道路上遇到了哪些关键转折点，是如何跨越的？",
                    "如何通过书中的理念克服拖延、焦虑或自我怀疑？",
                    "这本书对年轻人的职业规划、目标设定或人生选择有什么建议？",
                    "书中提到的'成长型思维'具体体现在哪些行动上？",
                    "面对失败或挫折，书中提供了哪些重建信心的心理建设方法？"
            ),
            CAT_HEALTH, List.of(
                    "书中推荐的养生理念或疗法是否有科学依据或临床支持？",
                    "如何将书中的饮食建议或运动方案融入我的日常生活节奏？",
                    "作者对于常见慢性病或亚健康状态的预防调理有什么独到见解？",
                    "书中提到的身心平衡方法（如冥想、呼吸）具体该如何练习？",
                    "这本书对于现代人常见的'压力病'或'生活方式病'有哪些预警？",
                    "作者在中医或自然疗法方面有哪些值得尝试的实用技巧？"
            ),
            CAT_FAMILY, List.of(
                    "书中的教育观念对现代家庭的育儿焦虑有什么缓解作用？",
                    "作者是如何处理书中复杂的代际冲突或婚姻危机的？",
                    "如何避免书中提到的育儿误区或过度保护带来的负面影响？",
                    "书中对于建立高质量亲子关系或伴侣沟通有哪些具体建议？",
                    "面对孩子的叛逆期或学习压力，书中提供了哪些应对策略？",
                    "这本书对于平衡家庭责任与个人发展有什么启发？"
            ),
            CAT_DEFAULT, List.of(
                    "这本书主要讲了什么内容？适合哪些读者阅读？",
                    "作者的核心观点或创作意图是什么？",
                    "这本书有哪些值得反复阅读的经典段落？",
                    "与其他同类书籍相比，这本书的独特优势是什么？",
                    "读完这本书，我最大的收获或改变应该是什么？"
            )
    );

    private static final Map<String, String> TAG_CATEGORY_MAP = new HashMap<>();
    static {
        List.of("爱情", "悬疑", "奇幻", "冒险", "科幻", "武侠", "推理", "犯罪", "复仇",
                        "穿越", "宫廷", "权谋", "搞笑", "幽默", "治愈", "孤独", "背叛", "误会",
                        "命运", "救赎", "伦理", "现实", "文学", "当代文学", "人物", "回忆", "轻松",
                        "官场", "都市", "战争", "革命")
                .forEach(t -> TAG_CATEGORY_MAP.put(t, CAT_FICTION));

        List.of("情感", "女性", "心理", "心理学", "自我认知", "心态", "自信", "孤独",
                        "情绪", "认知", "幸福", "恋爱", "亲情", "友情", "人际", "沟通", "孤独",
                        "背叛", "误会", "命运", "治愈", "孤独", "情绪", "认知", "个人成长", "人生",
                        "生活", "幸福", "梦想", "奋斗", "责任", "治愈", "沟通", "人际", "友谊")
                .forEach(t -> TAG_CATEGORY_MAP.put(t, CAT_EMOTION));

        List.of("职场", "管理", "创业", "商业", "经济", "金融", "理财", "投资", "市场",
                        "营销", "销售", "品牌", "战略", "领导力", "权力", "成功", "财富", "危机",
                        "创新")
                .forEach(t -> TAG_CATEGORY_MAP.put(t, CAT_BUSINESS));

        List.of("历史", "政治", "文化", "社会", "社会学", "哲学", "宗教", "伦理", "中国",
                        "美国", "战争", "革命", "人性", "权力", "政治", "人物传记")
                .forEach(t -> TAG_CATEGORY_MAP.put(t, CAT_HISTORY));

        List.of("成长", "校园", "教育", "学习", "青春", "自我认知", "心态", "自信", "孤独",
                        "情绪", "认知", "个人成长", "人生", "生活", "幸福", "梦想", "奋斗", "责任",
                        "治愈", "沟通", "人际", "友谊")
                .forEach(t -> TAG_CATEGORY_MAP.put(t, CAT_GROWTH));

        List.of("健康", "养生", "中医", "饮食", "营养", "疾病", "运动", "时尚")
                .forEach(t -> TAG_CATEGORY_MAP.put(t, CAT_HEALTH));

        List.of("家庭", "婚姻", "亲子", "家族")
                .forEach(t -> TAG_CATEGORY_MAP.put(t, CAT_FAMILY));
    }

    private List<String> getSuggestedQuestionsForBook(Book book) {
        if (book.getFormatTags() == null || book.getFormatTags().isBlank()) {
            return SUGGESTED_QUESTIONS.get(CAT_DEFAULT);
        }

        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var tags = mapper.readValue(book.getFormatTags(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});

            for (String tag : tags) {
                String category = TAG_CATEGORY_MAP.get(tag);
                if (category != null && SUGGESTED_QUESTIONS.containsKey(category)) {
                    return SUGGESTED_QUESTIONS.get(category);
                }
            }
        } catch (Exception e) {
            log.debug("解析图书标签失败: bookId={}", book.getId());
        }

        return SUGGESTED_QUESTIONS.get(CAT_DEFAULT);
    }

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
            return SUGGESTED_QUESTIONS.get(CAT_DEFAULT);
        }
        return getSuggestedQuestionsForBook(book);
    }

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

    private List<String> getRandomQuestions(List<String> questions) {
        if (questions.size() <= 6) {
            return new ArrayList<>(questions);
        }
        List<String> shuffled = new ArrayList<>(questions);
        java.util.Collections.shuffle(shuffled);
        return shuffled.subList(0, 6);
    }

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

                // 检查是否有相同问题的缓存回答（跨用户）
                try {
                    Optional<AiConversation> cachedAnswer = conversationRepository.findCachedAnswer(bookId, question);
                    if (cachedAnswer.isPresent()) {
                        AiConversation answer = cachedAnswer.get();
                        log.info("命中缓存回答: bookId={}, question={}", bookId, question);

                        // 逐块输出思考内容
                        if (answer.getThinkingContent() != null && !answer.getThinkingContent().isEmpty()) {
                            String thinking = answer.getThinkingContent();
                            for (int i = 0; i < thinking.length(); ) {
                                int end = Math.min(i + 5, thinking.length());
                                emitter.send(SseEmitter.event().name("thinking_content").data(thinking.substring(i, end)));
                                i = end;
                                try { Thread.sleep(15); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                            }
                        }

                        // 逐块输出回答
                        String content = answer.getContent();
                        for (int i = 0; i < content.length(); ) {
                            int end = Math.min(i + 3, content.length());
                            emitter.send(SseEmitter.event().name("message").data(content.substring(i, end)));
                            i = end;
                            try { Thread.sleep(25); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                        }

                        if (answer.getFollowUpQuestions() != null && !answer.getFollowUpQuestions().isEmpty()) {
                            emitter.send(SseEmitter.event().name("follow_up_questions").data(answer.getFollowUpQuestions()));
                        }
                        emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                        emitter.complete();

                        ensureSession(userId, finalSessionId, question, bookId);
                        saveMessage(userId, finalSessionId, "user", question, bookId, null, null);
                        saveMessage(userId, finalSessionId, "assistant", answer.getContent(), bookId,
                                answer.getThinkingContent(), answer.getFollowUpQuestions());
                        updateSessionTimestamp(finalSessionId);

                        log.info("缓存回答发送完成: bookId={}", bookId);
                        return;
                    }
                } catch (Exception e) {
                    log.warn("查询缓存回答失败，继续调用AI: {}", e.getMessage());
                }

                if (!Boolean.TRUE.equals(book.getContentEmbedded())) {
                    log.info("图书未生成内容向量，尝试按需生成: bookId={}", bookId);
                    try {
                        emitter.send(SseEmitter.event().name("thinking").data("正在检索书籍内容，请稍候..."));
                    } catch (Exception ignored) {
                    }

                    int chunkCount = bookParserService.generateContentEmbeddingWithCount(bookId);
                    if (chunkCount > 0) {
                        book.setContentEmbedded(true);
                        bookService.updateBook(bookId, book);
                        log.info("按需生成内容向量成功: bookId={}, chunks={}", bookId, chunkCount);
                    } else {
                        SseHelper.sendErrorAndComplete(emitter, "该书无法提取文本内容，无法进行 AI 问答");
                        return;
                    }
                }

                int ragTopK = Optional.ofNullable(aiProviderConfigService.getActiveRagTopK())
                        .orElse(qdrantProperties.getRagTopK());
                String ragContext = retrieveRagContext(book, question, ragTopK);
                log.debug("RAG 检索结果长度: {}", ragContext.length());

                try {
                    emitter.send(SseEmitter.event().name("thinking").data("正在生成回答..."));
                } catch (Exception ignored) {
                }

                String fullPrompt = buildPrompt(book, question, ragContext);

                StreamingChatModel streamingChatModel = aiProviderConfigService.buildChatStreamingModel();
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
                                String thinking = partialThinking.text();
                                if (thinking != null && !thinking.isEmpty()) {
                                    fullThinking.append(thinking);
                                    try {
                                        emitter.send(SseEmitter.event().name("thinking_content").data(thinking));
                                    } catch (Exception e) {
                                        log.warn("SSE发送thinking失败: {}", e.getMessage());
                                    }
                                }
                            }

                            @Override
                            public void onPartialResponse(String partialResponse) {
                                fullResponse.append(partialResponse);
                                if (!partialResponse.isEmpty()) {
                                    try {
                                        emitter.send(SseEmitter.event().name("message").data(partialResponse));
                                    } catch (Exception e) {
                                        log.warn("SSE发送token失败: {}", e.getMessage());
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

                                try {
                                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                    emitter.complete();
                                } catch (Exception ignored) {
                                }

                                ensureSession(userId, finalSessionId, question, bookId);
                                saveMessage(userId, finalSessionId, "user", question, bookId, null, null);
                                String thinkingText = fullThinking.length() > 0 ? fullThinking.toString() : null;
                                saveMessage(userId, finalSessionId, "assistant", answer, bookId, thinkingText, null);
                                updateSessionTimestamp(finalSessionId);

                                CommonUtils.logAiCall("图书问答", elapsed, apiInputTokens, apiOutputTokens,
                                        String.format("bookId=%d, question=%s", bookId, question.substring(0, Math.min(30, question.length()))));
                            }

                            @Override
                            public void onError(Throwable error) {
                                log.error("图书问答流式异常: bookId={} - {}", bookId, error.getMessage(), error);
                                aiProviderConfigService.clearAssistantCache();
                                SseHelper.sendErrorAndComplete(emitter, "AI 响应异常: " + SseHelper.extractFriendlyError(error));
                            }
                        }
                );

            } catch (Exception e) {
                log.error("图书问答异常: bookId={} - {}", bookId, e.getMessage(), e);
                aiProviderConfigService.clearAssistantCache();
                SseHelper.sendErrorAndComplete(emitter, "AI 响应异常: " + SseHelper.extractFriendlyError(e));
            }
        });

        emitter.onTimeout(() -> log.warn("图书问答SSE超时: bookId={}", bookId));
        emitter.onError(e -> log.error("图书问答SSE错误: bookId={}", bookId, e));

        return emitter;
    }

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

    public List<AiSession> getBookChatSessions(Long userId, Long bookId) {
        return sessionRepository.findByUserIdAndTypeAndBookIdOrderByUpdatedAtDesc(userId, TYPE, bookId);
    }

    public List<String> generateFollowUpQuestions(Long bookId, String question, String answer) {
        if (answer == null || answer.isBlank() || question == null || question.isBlank()) {
            return Collections.emptyList();
        }

        try {
            String title = "未知书籍";
            Book book = bookService.getBookById(bookId);
            if (book != null) {
                title = book.getTitle();
            }

            String prompt = String.format(
                    """
                            你是一位善于引导读者深入思考的阅读助手。
                            当前在讨论《%s》这本书。

                            读者问了：
                            %s

                            AI 已经回答：
                            %s

                            请根据 AI 的回答内容，生成 3 个深入追问的问题。
                            要求：
                            1. 问题必须紧扣回答内容，引导读者进一步思考。
                            2. 问题语言自然简短，适合移动端阅读场景（不超过 25 字）。
                            3. 不要带序号，每行一个问题。
                            4. 不要泛泛而谈，要针对回答中的具体观点或细节提问。""",
                    title,
                    question,
                    answer.length() > 800 ? answer.substring(0, 800) : answer
            );

            long startTime = System.currentTimeMillis();

            dev.langchain4j.model.chat.ChatModel chatModel = aiProviderConfigService.buildChatModelWithoutThinking();
            dev.langchain4j.model.chat.response.ChatResponse response =
                    chatModel.chat(List.of(dev.langchain4j.data.message.UserMessage.from(prompt)));

            long elapsed = System.currentTimeMillis() - startTime;
            int inputTokens = response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null
                    ? response.tokenUsage().inputTokenCount() : 0;
            int outputTokens = response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null
                    ? response.tokenUsage().outputTokenCount() : 0;

            String aiText = response.aiMessage().text();
            if (aiText != null && !aiText.isBlank()) {
                List<String> followUps = parseQuestions(aiText).stream().limit(3).collect(Collectors.toList());

                CommonUtils.logAiCall("生成深入追问问题", elapsed, inputTokens, outputTokens,
                        String.format("bookId=%d, questions=%s", bookId, followUps));

                return followUps;
            }
        } catch (Exception e) {
            log.debug("生成深入追问问题失败: {}", e.getMessage());
        }

        return Collections.emptyList();
    }

    private static final int MAX_HISTORY_TURNS = AiPromptConstants.MAX_HISTORY_TURNS;

    private List<ChatMessage> buildChatMessages(String sessionId, Long userId, String currentPrompt) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(AiPromptConstants.BOOK_CHAT_SYSTEM_PROMPT));

        try {
            List<AiConversation> history = conversationRepository
                    .findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
            if (!history.isEmpty()) {
                int startIndex = Math.max(0, history.size() - MAX_HISTORY_TURNS * 2);
                for (int i = startIndex; i < history.size(); i++) {
                    AiConversation conv = history.get(i);
                    String content = conv.getContent();
                    if (content == null || content.isBlank()) continue;
                    if (content.length() > AiPromptConstants.MAX_HISTORY_MESSAGE_LENGTH) {
                        content = content.substring(0, AiPromptConstants.MAX_HISTORY_MESSAGE_LENGTH) + "...";
                    }
                    if ("user".equals(conv.getRole())) {
                        messages.add(UserMessage.from(content));
                    } else if ("assistant".equals(conv.getRole())) {
                        messages.add(AiMessage.from(content));
                    }
                }
                log.debug("加载图书问答历史: sessionId={}, totalRecords={}, loadedFrom={}", sessionId, history.size(), startIndex);
            }
        } catch (Exception e) {
            log.warn("加载图书问答历史失败，继续无历史对话: {}", e.getMessage());
        }

        messages.add(UserMessage.from(currentPrompt));
        return messages;
    }

    private String retrieveRagContext(Book book, String question, int topK) {
        if (!embeddingService.isAvailable()) {
            log.debug("Embedding 不可用，跳过 RAG 检索");
            return "";
        }

        try {
            List<EmbeddingMatch<TextSegment>> matches =
                    embeddingService.searchContent(question, topK, book);

            if (matches.isEmpty()) {
                log.debug("RAG 检索无结果: bookId={}, question={}", book.getId(), question.substring(0, Math.min(30, question.length())));
                ragHitStatisticsService.recordMiss(book.getId());
                return "";
            }

            ragHitStatisticsService.recordHit(book.getId());

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < matches.size(); i++) {
                EmbeddingMatch<TextSegment> match = matches.get(i);
                String chunkText = match.embedded() != null ? match.embedded().text() : "";
                if (!chunkText.isBlank()) {
                    sb.append("【参考片段").append(i + 1).append("】\n");
                    sb.append(chunkText).append("\n\n");
                }
            }

            String ragContext = sb.toString();
            log.info("RAG 检索命中: bookId={}, hits={}, contextLen={}", book.getId(), matches.size(), ragContext.length());

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

        sb.append("\n\n【重要提醒】请用中文直接回答上述问题，不要翻译、分类或解释参考片段。");

        String prompt = sb.toString();

        long qmCount = prompt.chars().filter(c -> c == '?').count();
        if (qmCount > prompt.length() * 0.05) {
            log.warn("[编码诊断] 提示词疑似乱码! bookId={}, 问号占比={}/{}, JVM默认编码={}",
                    book.getId(), qmCount, prompt.length(), java.nio.charset.Charset.defaultCharset());
        }

        return prompt;
    }

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

    private void updateSessionTimestamp(String sessionId) {
        sessionRepository.findBySessionId(sessionId).ifPresent(session -> {
            session.setUpdatedAt(java.time.LocalDateTime.now());
            sessionRepository.save(session);
        });
    }

    private void saveMessage(Long userId, String sessionId, String role, String content, Long bookId,
                             String thinkingContent, String followUpQuestions) {
        try {
            AiConversation record = AiConversation.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .type(TYPE)
                    .bookId(bookId)
                    .role(role)
                    .content(content)
                    .thinkingContent(thinkingContent)
                    .followUpQuestions(followUpQuestions)
                    .build();
            conversationRepository.save(record);
        } catch (Exception e) {
            log.warn("保存图书问答记录失败: {}", e.getMessage());
        }
    }

    @org.springframework.transaction.annotation.Transactional
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
