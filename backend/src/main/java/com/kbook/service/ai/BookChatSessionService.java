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
import com.kbook.service.ai.rag.ListQueryDetector;
import com.kbook.service.ai.rag.ListQueryRetriever;
import com.kbook.service.ai.rag.ListQueryStrategySelector;
import com.kbook.service.ai.rag.ItemListRetriever;
import com.kbook.service.ai.rag.RagPipelineComponents;
import com.kbook.service.ai.core.ChatModelManager;
import com.kbook.service.ai.core.AiAssistantProvider;
import com.kbook.service.ai.core.AiSceneConfigService;
import com.kbook.service.ai.core.AiUsageContext;
import com.kbook.constants.ai.BookChatPrompts;
import com.kbook.constants.ai.RagPrompts;

/**
 * 图书问答会话服务 — 会话查询/导出/持久化原语/历史加载与压缩/读者风格与画像上下文。
 */
@Slf4j
@Service
@LogModule("图书问答")
public class BookChatSessionService {

    private final AiConversationRepository conversationRepository;
    private final AiSessionRepository sessionRepository;
    private final BookService bookService;
    private final UserService userService;
    private final AiConfigProvider aiConfigProvider;
    private final ChatHistoryCompressor chatHistoryCompressor;
    private final AiSceneConfigService aiSceneConfigService;
    private final UserBehaviorProfileRepository behaviorProfileRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public BookChatSessionService(
            AiConversationRepository conversationRepository,
            AiSessionRepository sessionRepository,
            BookService bookService,
            UserService userService,
            AiConfigProvider aiConfigProvider,
            ChatHistoryCompressor chatHistoryCompressor,
            AiSceneConfigService aiSceneConfigService,
            UserBehaviorProfileRepository behaviorProfileRepository,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher) {
        this.conversationRepository = conversationRepository;
        this.sessionRepository = sessionRepository;
        this.bookService = bookService;
        this.userService = userService;
        this.aiConfigProvider = aiConfigProvider;
        this.chatHistoryCompressor = chatHistoryCompressor;
        this.aiSceneConfigService = aiSceneConfigService;
        this.behaviorProfileRepository = behaviorProfileRepository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
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
     * 获取指定会话中最近一次 AI 回复内容
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return 最近一条 assistant 消息的内容，无则返回 null
     */
    String getLastAiAnswer(Long userId, String sessionId) {
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
     * 确保会话记录存在，不存在则自动创建（含 bookId）
     */
    void ensureSession(Long userId, String sessionId, String userMessage, Long bookId) {
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

    void updateSessionTimestamp(String sessionId) {
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
    void saveMessage(Long userId, String sessionId, String role, String content, Long bookId,
                      String thinkingContent) {
        saveMessage(userId, sessionId, role, content, bookId, thinkingContent, null);
    }

    /**
     * 保存图书问答消息记录（可带 finishReason；assistant 行供 §4.5 outline 关联与截断诊断）。
     *
     * @return 落库成功的记录（含 ID）；失败返回 null
     */
    AiConversation saveMessage(Long userId, String sessionId, String role, String content, Long bookId,
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
    void publishUserSignal(Long userId, String sessionId, Long bookId, String question, boolean manual) {
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
    UserBehaviorProfile loadBehaviorProfile(Long userId) {
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
    String getChatStyleForUser(Long userId) {
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
    String getSystemPromptForStyle(String style) {
        // 优先从外部配置加载
        String configPrompt = aiConfigProvider.getChatStylePrompt(style);
        if (!configPrompt.isEmpty()) {
            return configPrompt;
        }
        // 回退到代码常量
        if (style != null) {
            return switch (style.toUpperCase()) {
                case "CASUAL" -> BookChatPrompts.BOOK_CHAT_STYLE_CASUAL;
                case "CONCISE" -> BookChatPrompts.BOOK_CHAT_STYLE_CONCISE;
                case "WITTY" -> BookChatPrompts.BOOK_CHAT_STYLE_WITTY;
                default -> BookChatPrompts.BOOK_CHAT_STYLE_DEEP;
            };
        }
        return BookChatPrompts.BOOK_CHAT_STYLE_CASUAL;
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
    List<AiConversation> loadAndCompressHistory(Long userId, String sessionId, int currentOverheadChars) {
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
     * 按行 ID 定向更新 answer_outline（不整行 save，避免与压缩等字段写入互相覆盖）。
     */
    void updateOutlineById(Long rowId, String outlineJson) {
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
    AiConversation findLastAssistantRow(Long userId, String sessionId) {
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

    /** 上下文预算（字符）：场景配置 maxTokens × 1.5；解析失败按 32K tokens 估。 */
    int contextCharLimit() {
        try {
            AiProviderConfig cfg = aiSceneConfigService.resolveConfig(AiScene.BOOK_QA);
            if (cfg != null && cfg.getMaxTokens() != null) {
                return (int) (cfg.getMaxTokens() * TOKEN_TO_CHAR_RATIO);
            }
        } catch (Exception ignored) {
        }
        return (int) (DEFAULT_MAX_TOKENS * TOKEN_TO_CHAR_RATIO);
    }

    /**
     * 会话类型标识：图书问答。与读侧（看板聚合、画像 DEEP 口径）共用 {@link BookChatScope}，
     * 否则新增一种带 book_id 的会话时，画像的"深度互动读者"会静默换人群。
     */
    private static final String TYPE = BookChatScope.TYPE;
}
