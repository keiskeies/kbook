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
    private final AiProviderConfigService aiProviderConfigService;
    private final AiConversationRepository conversationRepository;
    private final BookSuggestedQuestionRepository suggestedQuestionRepository;
    private final BookQuestionGenService questionGenService;
    private final QdrantProperties qdrantProperties;

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    /**
     * 每本书的推荐问题 — 按书籍类型分类，匹配实际标签体系
     */
    private static final Map<String, List<String>> SUGGESTED_QUESTIONS = Map.ofEntries(
            Map.entry("fiction", List.of(
                    "这本书的主要人物之间是什么关系？",
                    "故事的情节有哪些关键转折点？",
                    "主角经历了怎样的成长或变化？",
                    "书中描写的爱情/友情给你留下了什么印象？"
            )),
            Map.entry("history", List.of(
                    "这本书对这段历史有什么独特的解读？",
                    "书中哪些史实或细节最让你印象深刻？",
                    "作者的历史观是否客观，有没有明显的立场？",
                    "这段历史对今天有什么现实启示？"
            )),
            Map.entry("philosophy", List.of(
                    "这本书讨论的核心哲学问题是什么？",
                    "作者的观点和传统看法有什么不同？",
                    "书中的思想如何应用到日常生活中？",
                    "这本书对你的人生观有什么启发？"
            )),
            Map.entry("psychology", List.of(
                    "这本书讲了哪些心理学原理或方法？",
                    "书中哪些观点能帮助解决实际困惑？",
                    "作者的建议有科学依据吗，还是更多经验之谈？",
                    "这本书和同类心理学读物相比有什么不同？"
            )),
            Map.entry("education", List.of(
                    "这本书的核心教育理念是什么？",
                    "书中哪些方法可以直接实践？",
                    "作者的观点和传统教育观念有什么不同？",
                    "这本书适合哪些家长或教育者阅读？"
            )),
            Map.entry("health", List.of(
                    "这本书的核心健康理念是什么？",
                    "书中提供了哪些具体的实操方法？",
                    "这些建议有哪些注意事项或适用条件？",
                    "这本书的观点和主流认知有什么不同？"
            )),
            Map.entry("business", List.of(
                    "这本书的核心方法论是什么？",
                    "书中的案例和经验如何应用到自己的工作中？",
                    "作者的观点有哪些局限性？",
                    "这本书适合什么阶段的职场人阅读？"
            )),
            Map.entry("general", List.of(
                    "这本书的核心内容是什么？",
                    "这本书适合什么样的读者？",
                    "读完这本书最大的收获是什么？",
                    "书中哪些观点最打动你？"
            ))
    );

    /**
     * 标签到问题类别的映射关键词
     */
    private static final Map<String, String> TAG_CATEGORY_MAP = Map.<String, String>ofEntries(
            Map.entry("小说", "fiction"), Map.entry("悬疑", "fiction"), Map.entry("推理", "fiction"),
            Map.entry("都市", "fiction"), Map.entry("奇幻", "fiction"), Map.entry("科幻", "fiction"),
            Map.entry("武侠", "fiction"), Map.entry("仙侠", "fiction"), Map.entry("言情", "fiction"),
            Map.entry("校园", "fiction"), Map.entry("冒险", "fiction"), Map.entry("传奇", "fiction"),
            Map.entry("复仇", "fiction"), Map.entry("游戏", "fiction"), Map.entry("爱情", "fiction"),
            Map.entry("友情", "fiction"),
            Map.entry("误会", "fiction"), Map.entry("搞笑", "fiction"), Map.entry("当代文学", "fiction"),
            Map.entry("文学", "fiction"),
            Map.entry("历史", "history"), Map.entry("战争", "history"), Map.entry("政治", "history"),
            Map.entry("社会", "history"), Map.entry("社会学", "history"), Map.entry("人物", "history"),
            Map.entry("权谋", "history"), Map.entry("回忆录", "history"), Map.entry("文化", "history"),
            Map.entry("美国", "history"),
            Map.entry("哲学", "philosophy"), Map.entry("人生", "philosophy"), Map.entry("命运", "philosophy"),
            Map.entry("传统文化", "philosophy"), Map.entry("易学", "philosophy"), Map.entry("自我认知", "philosophy"),
            Map.entry("宗教", "philosophy"), Map.entry("思想", "philosophy"),
            Map.entry("心理", "psychology"), Map.entry("心理学", "psychology"), Map.entry("成长", "psychology"),
            Map.entry("励志", "psychology"), Map.entry("认知", "psychology"), Map.entry("积极心态", "psychology"),
            Map.entry("自我成长", "psychology"), Map.entry("个人成长", "psychology"), Map.entry("孤独", "psychology"),
            Map.entry("情感", "psychology"), Map.entry("回忆", "psychology"), Map.entry("女性", "psychology"),
            Map.entry("教育", "education"), Map.entry("亲子", "education"), Map.entry("家庭", "education"),
            Map.entry("亲情", "education"), Map.entry("青春", "education"), Map.entry("婚姻", "education"),
            Map.entry("养生", "health"), Map.entry("饮食", "health"), Map.entry("瑜伽", "health"),
            Map.entry("中医", "health"), Map.entry("生活", "health"), Map.entry("自然", "health"),
            Map.entry("营养", "health"), Map.entry("健康", "health"),
            Map.entry("营销", "business"), Map.entry("职场", "business"), Map.entry("金融", "business"),
            Map.entry("效率", "business"), Map.entry("时间管理", "business"), Map.entry("沟通", "business"),
            Map.entry("学习", "business"), Map.entry("技巧", "business"), Map.entry("记忆", "business"),
            Map.entry("创新", "business"), Map.entry("互联网", "business"), Map.entry("创业", "business"),
            Map.entry("成功", "business"), Map.entry("理财", "business"), Map.entry("时尚", "business")
    );

    /**
     * 获取图书推荐问题
     * 流程：
     * 1. 查库，有则随机返回 7 个。
     * 2. 无则触发异步生成，并立即返回空列表。
     */
    public List<String> getSuggestedQuestions(Long bookId) {
        List<BookSuggestedQuestion> existing = suggestedQuestionRepository.findByBookId(bookId);
        if (!existing.isEmpty()) {
            List<String> all = existing.stream().map(BookSuggestedQuestion::getQuestion).collect(Collectors.toList());
            return getRandomQuestions(all, 7);
        }

        // 尝试触发异步生成（内部包含分布式锁判断）
        questionGenService.asyncGenerateQuestions(bookId);

        // 返回基于标签的兜底问题
        return getFallbackQuestions(bookId);
    }

    /**
     * 获取兜底预设问题（基于标签匹配）
     */
    private List<String> getFallbackQuestions(Long bookId) {
        Book book = bookService.getBookById(bookId);
        if (book == null) {
            return SUGGESTED_QUESTIONS.get("general");
        }

        String category = detectBookCategory(book);
        List<String> questions = new ArrayList<>(SUGGESTED_QUESTIONS.getOrDefault(category, SUGGESTED_QUESTIONS.get("general")));

        if (book.getTitle() != null) {
            questions.add(0, "《" + book.getTitle() + "》最打动你的是什么？");
        }

        return questions.stream().distinct().limit(6).collect(Collectors.toList());
    }

    /**
     * 根据书籍标签检测最匹配的问题类别
     */
    private String detectBookCategory(Book book) {
        if (book.getFormatTags() != null && !book.getFormatTags().isBlank()) {
            String tags = book.getFormatTags().replaceAll("[\\[\\]\"]", "");
            Map<String, Integer> categoryHits = new HashMap<>();
            for (String tag : tags.split("[,，]")) {
                String t = tag.trim();
                if (t.isBlank()) continue;
                String cat = TAG_CATEGORY_MAP.get(t);
                if (cat != null) {
                    categoryHits.merge(cat, 1, Integer::sum);
                }
            }
            if (!categoryHits.isEmpty()) {
                return categoryHits.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse("general");
            }
        }

        if (book.getTitle() != null) {
            String title = book.getTitle();
            if (title.contains("传") || title.contains("记") || title.contains("录") ||
                    title.contains("奇谭") || title.contains("物语") || title.contains("演义")) {
                return "fiction";
            }
        }

        return "general";
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
    private List<String> getRandomQuestions(List<String> questions, int count) {
        if (questions.size() <= count) {
            return new ArrayList<>(questions);
        }
        List<String> shuffled = new ArrayList<>(questions);
        java.util.Collections.shuffle(shuffled);
        return shuffled.subList(0, count);
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

                // 1.5 检查是否有内容向量数据，没有则无法进行基于原著的问答
                if (!Boolean.TRUE.equals(book.getContentEmbedded())) {
                    SseHelper.sendErrorAndComplete(emitter, "该书暂未生成内容向量数据，无法进行 AI 问答");
                    return;
                }

                // 2. RAG 检索相关内容片段
                String ragContext = retrieveRagContext(bookId, question);
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
    private String retrieveRagContext(Long bookId, String question) {
        if (!embeddingService.isAvailable()) {
            log.debug("Embedding 不可用，跳过 RAG 检索");
            return "";
        }

        try {
            List<EmbeddingMatch<TextSegment>> matches =
                    embeddingService.searchContent(question, qdrantProperties.getRagTopK(), bookId);

            if (matches.isEmpty()) {
                log.debug("RAG 检索无结果: bookId={}, question={}", bookId, question.substring(0, Math.min(30, question.length())));
                return "";
            }

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
                // 乱码上下文会误导模型语言判断，丢弃后基于书籍元数据回答
                return "";
            } else {
//                log.debug("RAG 上下文: {}", ragContext);
            }

            return ragContext;
        } catch (Exception e) {
            log.warn("RAG 检索异常: bookId={} - {}", bookId, e.getMessage());
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
