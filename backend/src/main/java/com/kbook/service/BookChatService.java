package com.kbook.service;

import com.kbook.common.util.CommonUtils;
import com.kbook.entity.AiConversation;
import com.kbook.entity.Book;
import com.kbook.repository.AiConversationRepository;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    /** 图书问答系统提示词模板 */
    private static final String BOOK_CHAT_SYSTEM_PROMPT = """
            你是 KBook 智能阅读平台的「图书伴读」AI 助手。你正在与读者讨论一本具体的书。
            
            你必须严格遵守以下规则：
            1. 只根据提供的「书籍参考内容」回答问题，不要编造书中没有的内容
            2. 如果参考内容不足以回答问题，诚实告知用户，并建议用户阅读原著获取更多信息
            3. 回答时可以引用书中的具体段落或细节来支撑你的分析
            4. 优先使用中文回答，语言风格友好、有深度
            5. 当用户问及"主旨、主题、核心思想"时，从多个角度综合分析
            6. 当用户问及"人物关系"时，尽量梳理人物之间的关联脉络
            7. 当用户问及"叙事手法、写作技巧"时，结合具体内容分析
            8. 当用户问及"时间线"时，按照时间顺序整理关键事件
            9. 当用户根据书中思想提出实际应用问题时，给出基于书中理念的可行建议
            """;

    /** 每本书的推荐问题 — 按类型分类 */
    private static final Map<String, List<String>> DEFAULT_QUESTIONS = Map.of(
            "general", List.of(
                    "这本书的主旨和核心思想是什么？",
                    "这本书的叙事结构和写作手法有什么特点？",
                    "这本书适合什么样的读者？",
                    "读完这本书能获得什么启发？"
            ),
            "fiction", List.of(
                    "这本书的主要人物关系是怎样的？",
                    "故事的时间线是如何展开的？",
                    "书中有哪些关键的情节转折？",
                    "主角经历了怎样的成长或变化？"
            ),
            "nonfiction", List.of(
                    "这本书的核心观点和论据是什么？",
                    "作者提出了哪些解决方案或建议？",
                    "书中的观点有哪些可以应用到实际生活中？",
                    "这本书与同类书相比有什么独特之处？"
            )
    );

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

        sseExecutor.execute(() -> {
            try {
                // 1. 获取图书信息
                Book book = bookService.getBookById(bookId);
                if (book == null) {
                    sendErrorAndComplete(emitter, "图书不存在");
                    return;
                }

                // 1.5 检查是否有内容向量数据，没有则无法进行基于原著的问答
                if (!Boolean.TRUE.equals(book.getContentEmbedded())) {
                    sendErrorAndComplete(emitter, "该书暂未生成内容向量数据，无法进行 AI 问答");
                    return;
                }

                // 2. RAG 检索相关内容片段
                String ragContext = retrieveRagContext(bookId, question);
                log.debug("RAG 检索结果长度: {}", ragContext.length());

                // 3. 构建完整提示词
                String fullPrompt = buildPrompt(book, question, ragContext);

                // 4. 调用 LLM
                ChatModel chatModel = aiProviderConfigService.buildTagChatModel();
                if (chatModel == null) {
                    sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                String thinkingSuffix = aiProviderConfigService.getThinkingPromptSuffix();
                long startTime = System.currentTimeMillis();
                ChatResponse response = chatModel.chat(List.of(
                        UserMessage.from(BOOK_CHAT_SYSTEM_PROMPT + "\n\n" + fullPrompt + thinkingSuffix)
                ));
                long elapsed = System.currentTimeMillis() - startTime;
                String answer = response.aiMessage().text();

                // 5. 分段发送 SSE
                int chunkSize = 3;
                for (int i = 0; i < answer.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, answer.length());
                    String chunk = answer.substring(i, end);
                    emitter.send(SseEmitter.event().name("message").data(chunk));
                    Thread.sleep(30);
                }

                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();

                // 6. 保存对话记录
                saveMessage(userId, finalSessionId, "user", question, bookId);
                saveMessage(userId, finalSessionId, "assistant", answer, bookId);

                // 7. 记录日志
                int inputTokens = CommonUtils.estimateTokens(fullPrompt);
                int outputTokens = CommonUtils.estimateTokens(answer);
                CommonUtils.logAiCall("图书问答", elapsed, inputTokens, outputTokens,
                        String.format("bookId=%d, question=%s", bookId, question.substring(0, Math.min(30, question.length()))));

            } catch (Exception e) {
                log.error("图书问答异常: bookId={} - {}", bookId, e.getMessage(), e);
                aiProviderConfigService.clearAssistantCache();
                sendErrorAndComplete(emitter, "AI 响应异常: " + extractFriendlyError(e));
            }
        });

        emitter.onTimeout(() -> log.warn("图书问答SSE超时: bookId={}", bookId));
        emitter.onError(e -> log.error("图书问答SSE错误: bookId={}", bookId, e));

        return emitter;
    }

    /**
     * 获取图书推荐问题
     */
    public List<String> getSuggestedQuestions(Long bookId) {
        Book book = bookService.getBookById(bookId);
        if (book == null) return DEFAULT_QUESTIONS.get("general");

        List<String> questions = new ArrayList<>();

        // 根据标签判断是小说还是非虚构
        boolean isFiction = isLikelyFiction(book);

        if (isFiction) {
            questions.addAll(DEFAULT_QUESTIONS.get("fiction"));
        } else {
            questions.addAll(DEFAULT_QUESTIONS.get("nonfiction"));
        }

        // 添加通用问题
        questions.addAll(DEFAULT_QUESTIONS.get("general"));

        // 根据书籍标题生成个性化问题
        if (book.getTitle() != null) {
            questions.add(0, "《" + book.getTitle() + "》最打动你的是什么？");
        }

        return questions.stream().distinct().limit(6).collect(Collectors.toList());
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
     * RAG 检索：根据问题在书籍内容向量中检索相关片段
     */
    private String retrieveRagContext(Long bookId, String question) {
        if (!embeddingService.isAvailable()) {
            log.debug("Embedding 不可用，跳过 RAG 检索");
            return "";
        }

        try {
            List<EmbeddingMatch<TextSegment>> matches =
                    embeddingService.searchContent(question, 8, bookId);

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

            log.info("RAG 检索命中: bookId={}, hits={}", bookId, matches.size());
            return sb.toString();
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

        return sb.toString();
    }

    /**
     * 判断书籍是否可能是小说/虚构类
     */
    private boolean isLikelyFiction(Book book) {
        String fictionKeywords = "小说,奇幻,科幻,武侠,仙侠,言情,悬疑,推理,历史小说,文学,故事,冒险,冒险,fiction,novel";
        if (book.getFormatTags() != null) {
            String tags = book.getFormatTags().toLowerCase();
            for (String keyword : fictionKeywords.split(",")) {
                if (tags.contains(keyword.trim())) return true;
            }
        }
        // 标题含常见小说关键词
        if (book.getTitle() != null) {
            String title = book.getTitle();
            return title.contains("传") || title.contains("记") || title.contains("录") ||
                    title.contains("奇谭") || title.contains("物语") || title.contains("演义");
        }
        return false;
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

    private void sendErrorAndComplete(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(message));
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
        } catch (Exception ignored) {}
    }

    private String extractFriendlyError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return "AI 响应异常，请稍后重试。";
        if (msg.contains("timed out") || msg.contains("Timeout")) return "AI 响应超时，书籍内容较多，请稍后重试。";
        if (msg.contains("Connection refused")) return "无法连接 AI 服务，请检查模型服务状态。";
        if (msg.contains("429")) return "AI 服务请求过于频繁，请稍后重试。";
        return "AI 响应异常，请稍后重试。";
    }
}
