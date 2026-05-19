package com.kbook.service;

import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.config.properties.QdrantProperties;
import com.kbook.constants.AiPromptConstants;
import com.kbook.entity.AiConversation;
import com.kbook.entity.Book;
import com.kbook.entity.BookSuggestedQuestion;
import com.kbook.repository.AiConversationRepository;
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
 * 图书问答服务 — 基于书籍 RAG 向量检索 + LLM 的深度问答
 * <p>
 * 核心流程：
 * 1. 用户提问 → 用 Embedding 模型将问题向量化
 * 2. 在 Qdrant kbook_content 集合中检索该书的相似内容片段
 * 3. 将检索到的内容片段 + 书籍元数据 + 问题一起发给 LLM
 * 4. LLM 基于书籍内容生成精准回答
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookChatService {

    private final EmbeddingService embeddingService;
    private final BookService bookService;
    private final BookParserService bookParserService;
    private final AiProviderConfigService aiProviderConfigService;
    private final AiConversationRepository conversationRepository;
    private final BookSuggestedQuestionRepository suggestedQuestionRepository;
    private final BookQuestionGenService questionGenService;
    private final QdrantProperties qdrantProperties;
    private final RagHitStatisticsService ragHitStatisticsService;

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    // ======================== 预设问题与标签分类 ========================

    // ======================== 预设问题与标签分类 ========================

    /** 预设问题分类键 */
    private static final String CAT_FICTION = "FICTION_LITERATURE";
    private static final String CAT_EMOTION = "EMOTION_PSYCHOLOGY";
    private static final String CAT_BUSINESS = "BUSINESS_CAREER";
    private static final String CAT_HISTORY = "HISTORY_HUMANITIES";
    private static final String CAT_GROWTH = "GROWTH_EDUCATION";
    private static final String CAT_HEALTH = "HEALTH_WELLNESS";
    private static final String CAT_FAMILY = "FAMILY_PARENTING";
    private static final String CAT_DEFAULT = "DEFAULT";

    /** 各分类对应的推荐问题列表 */
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
                    "这本书对于“爱自己”和“接纳不完美”有哪些深刻见解？"
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
                    "书中提到的“成长型思维”具体体现在哪些行动上？",
                    "面对失败或挫折，书中提供了哪些重建信心的心理建设方法？"
            ),
            CAT_HEALTH, List.of(
                    "书中推荐的养生理念或疗法是否有科学依据或临床支持？",
                    "如何将书中的饮食建议或运动方案融入我的日常生活节奏？",
                    "作者对于常见慢性病或亚健康状态的预防调理有什么独到见解？",
                    "书中提到的身心平衡方法（如冥想、呼吸）具体该如何练习？",
                    "这本书对于现代人常见的“压力病”或“生活方式病”有哪些预警？",
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

    /** 标签到分类的映射表（基于 Top 100 标签构建） */
    private static final Map<String, String> TAG_CATEGORY_MAP = new HashMap<>();
    static {
        // 1. 小说文学类 (Fiction & Literature)
        List.of("爱情", "悬疑", "奇幻", "冒险", "科幻", "武侠", "推理", "犯罪", "复仇",
                        "穿越", "宫廷", "权谋", "搞笑", "幽默", "治愈", "孤独", "背叛", "误会",
                        "命运", "救赎", "伦理", "现实", "文学", "当代文学", "人物", "回忆", "轻松",
                        "官场", "都市", "战争", "革命")
                .forEach(t -> TAG_CATEGORY_MAP.put(t, CAT_FICTION));

        // 2. 情感心理类 (Emotion & Psychology)
        List.of("情感", "女性", "心理", "心理学", "自我认知", "心态", "自信", "孤独",
                        "情绪", "认知", "幸福", "恋爱", "亲情", "友情", "人际", "沟通", "孤独",
                        "背叛", "误会", "命运", "治愈", "孤独", "情绪", "认知", "个人成长", "人生",
                        "生活", "幸福", "梦想", "奋斗", "责任", "治愈", "沟通", "人际", "友谊")
                .forEach(t -> TAG_CATEGORY_MAP.put(t, CAT_EMOTION));

        // 3. 商业职场类 (Business & Career)
        List.of("职场", "管理", "创业", "商业", "经济", "金融", "理财", "投资", "市场",
                        "营销", "销售", "品牌", "战略", "领导力", "权力", "成功", "财富", "危机",
                        "创新")
                .forEach(t -> TAG_CATEGORY_MAP.put(t, CAT_BUSINESS));

        // 4. 历史人文类 (History & Humanities)
        List.of("历史", "政治", "文化", "社会", "社会学", "哲学", "宗教", "伦理", "中国",
                        "美国", "战争", "革命", "人性", "权力", "政治", "人物传记")
                .forEach(t -> TAG_CATEGORY_MAP.put(t, CAT_HISTORY));

        // 5. 成长教育类 (Growth & Education)
        List.of("成长", "校园", "教育", "学习", "青春", "自我认知", "心态", "自信", "孤独",
                        "情绪", "认知", "个人成长", "人生", "生活", "幸福", "梦想", "奋斗", "责任",
                        "治愈", "沟通", "人际", "友谊")
                .forEach(t -> TAG_CATEGORY_MAP.put(t, CAT_GROWTH));

        // 6. 健康养生类 (Health & Wellness)
        List.of("健康", "养生", "中医", "饮食", "营养", "疾病", "运动", "时尚")
                .forEach(t -> TAG_CATEGORY_MAP.put(t, CAT_HEALTH));

        // 7. 家庭亲子类 (Family & Parenting)
        List.of("家庭", "婚姻", "亲子", "家族")
                .forEach(t -> TAG_CATEGORY_MAP.put(t, CAT_FAMILY));
    }

    /**
     * 根据图书标签获取对应的推荐问题
     */
    private List<String> getSuggestedQuestionsForBook(Book book) {
        if (book.getFormatTags() == null || book.getFormatTags().isBlank()) {
            return SUGGESTED_QUESTIONS.get(CAT_DEFAULT);
        }

        try {
            // 解析 JSON 标签数组
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var tags = mapper.readValue(book.getFormatTags(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});

            // 匹配第一个有分类的标签
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

    /**
     * 获取图书推荐问题
     * 流程：
     * 1. 查库，有则随机返回 6 个其他问题，并在最前面固定插入"这本书主要讲了什么？"。
     * 2. 无则触发异步生成，并立即返回兜底问题（同样第一个固定为介绍类问题）。
     */
    public List<String> getSuggestedQuestions(Long bookId) {
        List<BookSuggestedQuestion> existing = suggestedQuestionRepository.findByBookId(bookId);
        if (!existing.isEmpty()) {
            String introQuestion = "这本书主要讲了什么？";
            List<String> all = existing.stream()
                    .map(BookSuggestedQuestion::getQuestion)
                    .filter(q -> !introQuestion.equals(q))
                    .collect(Collectors.toList());
            // 随机取 6 个，再在最前面固定插入介绍问题
            List<String> selected = getRandomQuestions(all);
            selected.add(0, introQuestion);
            return selected;
        }

        // 尝试触发异步生成（内部包含分布式锁判断）
        questionGenService.asyncGenerateQuestions(bookId);

        // 返回基于标签的兜底问题
        Book book = bookService.getBookById(bookId);
        if (book == null) {
            return SUGGESTED_QUESTIONS.get(CAT_DEFAULT);
        }
        return getSuggestedQuestionsForBook(book);
    }

    /**
     * 解析 AI 返回的文本，提取问题列表
     */
    private List<String> parseQuestions(String text) {
        return Arrays.stream(text.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(line -> line.replaceAll("^\\d+[.、)\\s]*", "").trim()) // 去除序号
                .filter(line -> line.length() > 2) // 过滤太短的无效行
                .distinct()
                .limit(20)
                .collect(Collectors.toList());
    }

    /**
     * 从列表中随机选取 count 个问题
     */
    private List<String> getRandomQuestions(List<String> questions) {
        if (questions.size() <= 6) {
            return new ArrayList<>(questions);
        }
        List<String> shuffled = new ArrayList<>(questions);
        java.util.Collections.shuffle(shuffled);
        return shuffled.subList(0, 6);
    }

    /**
     * 流式图书问答 — SSE
     *
     * @param bookId    图书ID
     * @param question  用户问题
     * @param sessionId 会话ID（可选，用于保持上下文）
     * @return SseEmitter
     */
    public SseEmitter streamBookChat(Long userId, Long bookId, String question, String sessionId) {
        log.info("========== 图书问答请求 ==========");
        log.info("userId={}, bookId={}, question={}", userId, bookId, question);

        // 预先确定 sessionId，确保是 effectively final
        final String finalSessionId = (sessionId == null || sessionId.isBlank())
                ? "book-" + bookId + "-" + UUID.randomUUID().toString().substring(0, 8)
                : sessionId;

        SseEmitter emitter = new SseEmitter(180_000L); // 3分钟超时（大书可能较慢）

        // 立即发送 thinking 事件，让前端知道请求已被接受，正在检索
        try {
            emitter.send(SseEmitter.event().name("thinking").data("正在检索书籍内容..."));
        } catch (Exception ignored) {
        }

        sseExecutor.execute(() -> {
            try {
                // 1. 获取图书信息
                Book book = bookService.getBookById(bookId);
                if (book == null) {
                    SseHelper.sendErrorAndComplete(emitter, "图书不存在");
                    return;
                }

                // 1.5 检查是否有内容向量数据，没有则立即生成
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

                // 2. RAG 检索相关内容片段
                // 根据当前 AI 配置动态决定 TopK，若未配置则使用全局默认值
                int ragTopK = Optional.ofNullable(aiProviderConfigService.getActiveRagTopK())
                        .orElse(qdrantProperties.getRagTopK());
                String ragContext = retrieveRagContext(bookId, question, ragTopK);
                log.debug("RAG 检索结果长度: {}", ragContext.length());

                // 检索完成，发送 thinking 更新
                try {
                    emitter.send(SseEmitter.event().name("thinking").data("正在生成回答..."));
                } catch (Exception ignored) {
                }

                // 3. 构建完整提示词
                String fullPrompt = buildPrompt(book, question, ragContext);

                // 4. 调用 StreamingChatModel 实现真正的 token 级流式输出
                StreamingChatModel streamingChatModel = aiProviderConfigService.buildChatStreamingModel();
                if (streamingChatModel == null) {
                    SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                long startTime = System.currentTimeMillis();

                log.debug("图书问答: bookId={}, question={}, ragContextLen={}, fullPromptLen={}",
                        bookId, question, ragContext.length(), fullPrompt.length());

                // 4.5 构建带历史对话的消息列表（最近 N 轮，避免上下文过长）
                List<ChatMessage> messages = buildChatMessages(finalSessionId, userId, fullPrompt);

                StringBuilder fullResponse = new StringBuilder();

                streamingChatModel.chat(
                        messages,
                        new StreamingChatResponseHandler() {
                            @Override
                            public void onPartialThinking(dev.langchain4j.model.chat.response.PartialThinking partialThinking) {
                                String thinking = partialThinking.text();
                                if (thinking != null && !thinking.isEmpty()) {
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

                                // 解析 token 用量
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

                                // 保存对话记录
                                saveMessage(userId, finalSessionId, "user", question, bookId);
                                saveMessage(userId, finalSessionId, "assistant", answer, bookId);

                                // 记录日志
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

    /**
     * 获取图书问答历史
     */
    public List<AiConversation> getBookChatHistory(Long userId, Long bookId, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return conversationRepository.findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
        }
        // 按 bookId 前缀查找最近的会话
        String prefix = "book-" + bookId + "-";
        List<String> sessionIds = conversationRepository.findSessionIdsByUserId(userId);
        List<AiConversation> result = new ArrayList<>();
        for (String sid : sessionIds) {
            if (sid.startsWith(prefix)) {
                result = conversationRepository.findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sid);
                if (!result.isEmpty()) break;
            }
        }
        return result;
    }

    /**
     * 根据 AI 回答生成深入追问问题
     */
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

    // ==================== 内部方法 ====================

    /**
     * 对话历史保留的最大轮数 — 使用 AiPromptConstants 统一管理
     */
    private static final int MAX_HISTORY_TURNS = AiPromptConstants.MAX_HISTORY_TURNS;

    /**
     * 构建包含系统提示词、历史对话和当前问题的完整消息列表
     * <p>
     * 消息顺序：SystemMessage → 历史对话(user/assistant交替) → 当前 UserMessage
     * 历史对话最多保留最近 MAX_HISTORY_TURNS 轮，避免上下文过长
     */
    private List<ChatMessage> buildChatMessages(String sessionId, Long userId, String currentPrompt) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(AiPromptConstants.BOOK_CHAT_SYSTEM_PROMPT));

        // 加载历史对话
        try {
            List<AiConversation> history = conversationRepository
                    .findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
            if (!history.isEmpty()) {
                // 只取最近 MAX_HISTORY_TURNS 轮（2条/轮）
                int startIndex = Math.max(0, history.size() - MAX_HISTORY_TURNS * 2);
                for (int i = startIndex; i < history.size(); i++) {
                    AiConversation conv = history.get(i);
                    String content = conv.getContent();
                    if (content == null || content.isBlank()) continue;
                    // 截断过长的历史消息（避免单条消息消耗过多 token）
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

        // 当前用户消息
        messages.add(UserMessage.from(currentPrompt));
        return messages;
    }

    /**
     * RAG 检索：根据问题在书籍内容向量中检索相关片段
     */
    private String retrieveRagContext(Long bookId, String question, int topK) {
        if (!embeddingService.isAvailable()) {
            log.debug("Embedding 不可用，跳过 RAG 检索");
            return "";
        }

        try {
            List<EmbeddingMatch<TextSegment>> matches =
                    embeddingService.searchContent(question, topK, bookId);

            if (matches.isEmpty()) {
                log.debug("RAG 检索无结果: bookId={}, question={}", bookId, question.substring(0, Math.min(30, question.length())));
                ragHitStatisticsService.recordMiss(bookId);
                return "";
            }

            ragHitStatisticsService.recordHit(bookId);

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
            log.info("RAG 检索命中: bookId={}, hits={}, contextLen={}", bookId, matches.size(), ragContext.length());

            // 编码诊断：检查 RAG 上下文中是否有乱码（中文字符被替换为 ?）
            long questionMarkCount = ragContext.chars().filter(c -> c == '?').count();
            if (questionMarkCount > ragContext.length() * 0.1) {
                log.warn("[编码诊断] RAG 上下文疑似乱码! bookId={}, 问号占比={}/{}, 丢弃乱码上下文",
                        bookId, questionMarkCount, ragContext.length());
                ragHitStatisticsService.recordMiss(bookId);
                return "";
            } else {
//                log.debug("RAG 上下文: {}", ragContext);
            }

            return ragContext;
        } catch (Exception e) {
            log.warn("RAG 检索异常: bookId={} - {}", bookId, e.getMessage());
            ragHitStatisticsService.recordMiss(bookId);
            return "";
        }
    }

    /**
     * 构建完整的用户提示词
     */
    private String buildPrompt(Book book, String question, String ragContext) {
        StringBuilder sb = new StringBuilder();

        // 书籍基本信息
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

        // RAG 检索到的参考内容
        if (!ragContext.isBlank()) {
            sb.append("\n【书籍参考内容】（以下是从原著中检索到的与问题相关的片段）\n");
            sb.append(ragContext);
        } else {
            sb.append("\n【注意】未从原著中检索到直接相关的内容片段，请根据书籍基本信息谨慎回答。\n");
        }

        // 用户问题
        sb.append("\n【读者的问题】\n").append(question);

        // 末尾强化指令：防止模型跑偏（分析/分类参考内容或使用英文回答）
        sb.append("\n\n【重要提醒】请用中文直接回答上述问题，不要翻译、分类或解释参考片段。");

        String prompt = sb.toString();

        // 编码诊断：检查完整提示词中是否有乱码
        long qmCount = prompt.chars().filter(c -> c == '?').count();
        if (qmCount > prompt.length() * 0.05) {
            log.warn("[编码诊断] 提示词疑似乱码! bookId={}, 问号占比={}/{}, JVM默认编码={}",
                    book.getId(), qmCount, prompt.length(), java.nio.charset.Charset.defaultCharset());
        }

        return prompt;
    }

    private void saveMessage(Long userId, String sessionId, String role, String content, Long bookId) {
        try {
            AiConversation record = AiConversation.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .role(role)
                    .content(content)
                    .build();
            conversationRepository.save(record);
        } catch (Exception e) {
            log.warn("保存图书问答记录失败: {}", e.getMessage());
        }
    }

}
