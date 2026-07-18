package com.kbook.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.config.ChatModelFactory;
import com.kbook.config.ai.AiConfigProvider;
import com.kbook.constants.AiPromptConstants;
import com.kbook.entity.AiScene;
import com.kbook.entity.Book;
import com.kbook.entity.User;
import com.kbook.service.ai.core.ChatHistoryCompressor;
import com.kbook.service.ai.core.UserProfileBuilder;
import com.kbook.service.ai.core.ExternalKnowledgeGenerator;
import com.kbook.service.ai.streaming.StreamingSseHandler;
import com.kbook.service.book.BookMetadataInferrer;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * AI 模型调用管理器，封装所有与大语言模型交互的通用方法。
 * <p>
 * 职责：
 * 1. 提供统一的 AI 调用入口，屏蔽底层模型工厂的复杂性；
 * 2. 封装常见的 AI 业务逻辑，如内容压缩、元数据推断、问题生成、RAG 查询扩展等；
 * 3. 管理流式与非流式两种调用模式，支持 SSE 实时推送；
 * 4. 提供用户画像构建、书籍内容格式化等公共工具方法。
 * </p>
 * <p>
 * 所有方法均包含异常处理与日志记录，确保 AI 调用失败时不会影响主业务流程。
 * </p>
 *
 * @author kbook
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatModelManager {

    private final ChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;
    private final AiConfigProvider aiConfigProvider;
    private final UserProfileBuilder userProfileBuilder;
    private final ChatHistoryCompressor chatHistoryCompressor;
    private final BookMetadataInferrer bookMetadataInferrer;
    private final ExternalKnowledgeGenerator externalKnowledgeGenerator;

    private static final int SPEED_READ_CONTENT_LIMIT = 15000;

    // ================================================================
    // AI 调用日志上下文 — 携带场景/模型/思考配置，供 logAiSummary 使用
    // ================================================================

    /**
     * AI 调用日志上下文 — 一次 LLM 调用的场景/模型/思考配置元数据。
     * <p>
     * 由 {@link ChatModelFactory#buildLogContext} 构建，传递给
     * {@link CommonUtils#logAiSummary} 打印统一摘要日志。
     *
     * @param scene           场景名（如"ROUND_TABLE_SPEECH"）
     * @param modelName       模型名（如"agnes-2.0-flash"）
     * @param configName      配置名（如"ai-gateway-agnes-2.0-flash"）
     * @param thinkingMode    思考模式（如"SWITCH"/"REASONING_EFFORT"/"NONE"）
     * @param thinkingEnabled 思考是否开启
     * @param reasoningEffort reasoning effort（可为 null）
     */
    public record AiCallLogContext(String scene, String modelName, String configName,
                                    String thinkingMode, boolean thinkingEnabled, String reasoningEffort) {}

    // ================================================================
    // 核心 AI 调用模板
    // ================================================================

    /**
     * 核心 AI 调用方法，通过模型供应器获取 ChatModel 并执行对话。
     *
     * @param logName       日志标识，用于区分不同的 AI 调用场景
     * @param logDetail     日志详情，记录调用参数或上下文信息
     * @param modelSupplier 模型供应器，延迟获取 ChatModel 实例（避免空指针）
     * @param messages      对话消息列表，包含系统消息和用户消息
     * @return AI 响应文本，如果模型未配置或调用失败则返回 null
     */
    public String callAi(String logName, String logDetail,
                         Supplier<ChatModel> modelSupplier, List<ChatMessage> messages) {
        return callAi(logName, logDetail, modelSupplier, messages, null);
    }

    /**
     * 核心 AI 调用方法（带日志上下文）— 所有日志合并为一条 INFO 级别的统一摘要。
     *
     * @param logContext 日志上下文（场景/模型/思考配置），可为 null（摘要中显示"未知"）
     */
    public String callAi(String logName, String logDetail,
                         Supplier<ChatModel> modelSupplier, List<ChatMessage> messages,
                         AiCallLogContext logContext) {
        // 获取 ChatModel 实例，如果模型未配置则直接返回 null
        ChatModel model = modelSupplier.get();
        if (model == null) {
            log.warn("AI 模型未配置，跳过: {}", logName);
            return null;
        }
        // 记录调用开始时间，用于计算耗时
        long startTime = System.currentTimeMillis();
        // DEBUG: 打印完整对话消息
        CommonUtils.logAiMessages(logName, messages);
        // 执行 AI 对话
        ChatResponse response = model.chat(messages);
        // 计算耗时
        long elapsed = System.currentTimeMillis() - startTime;
        // 提取 token 使用量（输入和输出），避免空指针
        int inputTokens = response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null
                ? response.tokenUsage().inputTokenCount() : 0;
        int outputTokens = response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null
                ? response.tokenUsage().outputTokenCount() : 0;
        // 获取 AI 响应文本并去除首尾空白
        String text = response.aiMessage().text();
        if (text != null && !text.isBlank()) {
            text = text.trim();
        }

        // INFO: 统一摘要日志（一次 LLM 调用只打一条）
        String systemPromptText = extractSystemPromptText(messages);
        String scene = logContext != null ? logContext.scene() : null;
        String modelName = logContext != null ? logContext.modelName() : null;
        String configName = logContext != null ? logContext.configName() : null;
        String thinkingMode = logContext != null ? logContext.thinkingMode() : null;
        boolean thinkingEnabled = logContext != null && logContext.thinkingEnabled();
        String reasoningEffort = logContext != null ? logContext.reasoningEffort() : null;
        CommonUtils.logAiSummary(logName, scene, modelName, configName,
                thinkingMode, thinkingEnabled, reasoningEffort,
                messages.size(), systemPromptText,
                text, null,
                elapsed, inputTokens, outputTokens);
        return text;
    }

    /**
     * 从消息列表中提取第一条 SystemMessage 的文本（用于摘要日志的请求预览）。
     */
    private static String extractSystemPromptText(List<ChatMessage> messages) {
        if (messages == null) return null;
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage sm) {
                return sm.text();
            }
        }
        return null;
    }


    /**
     * 带完整消息列表的公共 AI 调用入口（消息已由调用方组装）。
     * 使用 TOOL 角色模型（默认无思考），并在 SystemMessage 后追加 /no_think 禁用推理。
     *
     * @param logName   日志标识
     * @param logDetail 日志详情
     * @param messages  完整的 ChatMessage 列表
     * @return AI 响应文本
     */
    public String callAi(String logName, String logDetail, List<ChatMessage> messages) {
        return callAi(logName, logDetail, chatModelFactory::buildToolChatModel,
                appendNoThink(messages));
    }

    /**
     * 不带思考模式的公共 AI 调用入口（用于标签生成、元数据推断等无需复杂推理的场景）。
     * 使用 QA 角色无思考模型，并在 SystemMessage 后追加 /no_think 双重确保不产生推理 token。
     *
     * @param logName   日志标识
     * @param logDetail 日志详情
     * @param messages  完整的 ChatMessage 列表
     * @return AI 响应文本
     */
    public String callAiWithoutThinking(String logName, String logDetail, List<ChatMessage> messages) {
        return callAi(logName, logDetail, chatModelFactory::buildChatModelWithoutThinking,
                appendNoThink(messages));
    }

    // ================================================================
    // 场景路由入口（推荐使用）— 由 AiSceneConfigService 解析场景→配置
    // ================================================================

    /**
     * 按场景调用 AI（非流式）— 推荐入口。
     * <p>
     * 场景的 {@link AiScene#isThinking()} 决定是否启用思考：
     * <ul>
     *   <li>thinking=true：不追加 /no_think，模型按 reasoningEffort 配置思考</li>
     *   <li>thinking=false：追加 /no_think，并强制 returnThinking=false</li>
     * </ul>
     *
     * @param scene    AI 场景
     * @param logName  日志标识
     * @param logDetail 日志详情
     * @param messages 完整的 ChatMessage 列表
     * @return AI 响应文本
     */
    public String callAiForScene(AiScene scene, String logName, String logDetail, List<ChatMessage> messages) {
        List<ChatMessage> finalMessages = scene.isThinking() ? messages : appendNoThink(messages);
        AiCallLogContext logContext = chatModelFactory.buildLogContext(scene);
        return callAi(logName, logDetail, () -> chatModelFactory.buildForScene(scene), finalMessages, logContext);
    }

    /**
     * 按场景获取流式 ChatModel — 推荐入口。
     */
    public StreamingChatModel getStreamingModelForScene(AiScene scene) {
        return chatModelFactory.buildStreamingForScene(scene);
    }

    /**
     * 按场景构建压缩专用模型（温度 0.1、关闭思考）。
     */
    public ChatModel getCompressionModelForScene(AiScene scene) {
        return chatModelFactory.buildCompressionForScene(scene);
    }

    /**
     * 构建场景的日志上下文（供流式调用方传递给 StreamingSseHandler）。
     */
    public AiCallLogContext buildLogContext(AiScene scene) {
        return chatModelFactory.buildLogContext(scene);
    }

    /**
     * 在消息列表中追加 /no_think 到 SystemMessage，用于禁用 LLM 推理 token。
     * <p>
     * 新建一个不可变的 List（避免修改调用方传入的可变列表），找到第一条 SystemMessage
     * 并将 " /no_think" 追加到其文本末尾。如果列表中没有 SystemMessage，则原样返回。
     * </p>
     */
    private static List<ChatMessage> appendNoThink(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return messages;
        List<ChatMessage> result = new ArrayList<>(messages);
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i) instanceof SystemMessage sysMsg) {
                result.set(i, SystemMessage.from(sysMsg.text() + " \n\n /no_think"));
                break;
            }
        }
        return result;
    }

    // ================================================================
    // 业务方法
    // ================================================================

    /**
     * 将内容按信息密度动态压缩精简，用于历史对话记忆。
     *
     * <p>信息密度高的核心内容少压缩，冗余啰嗦内容多压缩。
     * 短内容（≤50 字）直接返回原内容。压缩失败时返回 null。</p>
     *
     * @param original 原始内容文本
     * @return 压缩后的内容，无需压缩或失败时返回 null
     */
    public String compressContent(String original) {
        return chatHistoryCompressor.compressContent(original);
    }

    /**
     * 圆桌派讨论历史压缩 — 保留发言者的态度、论点、问题和情绪方向
     */
    public String compressRoundTableContent(String original) {
        return chatHistoryCompressor.compressRoundTableContent(original);
    }

    /**
     * 批量压缩多条通用对话内容 — 单次 LLM 调用，返回与输入索引对齐的结果列表
     */
    public List<String> compressContentBatch(List<String> originals) {
        return chatHistoryCompressor.compressContentBatch(originals);
    }

    /**
     * 批量压缩多条圆桌派发言 — 单次 LLM 调用，返回与输入索引对齐的结果列表
     */
    public List<String> compressRoundTableContentBatch(List<String> originals) {
        return chatHistoryCompressor.compressRoundTableContentBatch(originals);
    }

    /**
     * 从文本内容推断书籍的作者和简介，并更新 Book 实体。
     *
     * <p>适用于 TXT、PDF 等无法自动提取元数据的格式。AI 会分析内容片段，
     * 尝试识别作者信息和生成内容概要。仅当对应字段为空时才更新，避免覆盖已有数据。</p>
     *
     * @param book    书籍实体，将被更新作者和简介字段
     * @param content 书籍内容文本（会被截断到 2000 字以内）
     */
    public void inferMetadataFromContent(Book book, String content) {
        bookMetadataInferrer.infer(book, content);
    }

    /**
     * 根据已有问答生成深入追问问题，引导读者进行深度思考。
     *
     * <p>基于 AI 刚才的回答，找出其中 3 个逻辑缝隙（如未说明的前提、可质疑的预设等），
     * 将其转化为追问问题。如果提供了用户画像，会生成更贴合读者背景的问题。</p>
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
        String bookInfo = buildSpeedReadContent(book);

        try {
            // 消息顺序优化 KV Cache：SystemMessage(固定指令) → UserMessage(图书信息) → UserMessage(用户画像) → UserMessage(上轮问答)
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(AiPromptConstants.FOLLOW_UP_QUESTION_SYSTEM_PROMPT));
            messages.add(UserMessage.from("【图书信息】\n" + bookInfo));
            if (!userProfileDesc.isBlank()) {
                messages.add(UserMessage.from("【读者画像】\n" + userProfileDesc));
            }
            messages.add(UserMessage.from("读者问：" + question + "\n你回答：" + answer));

            String aiText = callAiForScene(AiScene.FOLLOW_UP_QUESTION,
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
     * <p>策略：
     * <ul>
     *   <li>宏观问题（如"讲了什么"）：按目录章节生成多个查询，确保覆盖全书内容</li>
     *   <li>具体问题：生成精准定位和宽泛召回两种粒度的查询</li>
     * </ul>
     * </p>
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
            // 拆分独立消息：背景信息与扩展目标定位清晰，避免上轮回答过长淹没用户问题
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(systemPrompt));
            messages.add(UserMessage.from("【书籍信息】\n" + bookContext.trim()));
            if (lastAiAnswer != null && !lastAiAnswer.isBlank()) {
                messages.add(UserMessage.from("【上轮AI回答】\n" + lastAiAnswer.trim()));
            }
            messages.add(UserMessage.from("【用户问题】\n" + question));

            String aiText = callAiForScene(AiScene.QUERY_EXPAND,
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
     * 向量搜索查询扩展，将口语化搜索词转化为多维度检索关键词。
     *
     * <p>核心思路：不改写用户原话，而是推断用户真正的阅读需求，从不同方向生成关键词短语，
     * 提高图书推荐的匹配精度。生成的关键词应是书籍标签、分类或简介中可能出现的短语。</p>
     *
     * @param query 用户口语化搜索词
     * @return 扩展后的关键词列表（3-5 个），失败时返回原始查询
     */
    public List<String> expandVectorSearchQuery(String query) {
        try {
            // 系统提示词（固定，可复用 KV Cache）
            String systemPrompt = AiPromptConstants.EXPAND_VECTOR_SEARCH_SYSTEM_PROMPT;

            // 动态内容（用户查询）作为 UserMessage
            List<ChatMessage> chatMessages = List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(query));

            // 调用 AI 生成扩展关键词
            String result = callAiForScene(AiScene.VECTOR_QUERY_EXPAND,
                    "向量查询扩展",
                    String.format("q=%s", query.substring(0, Math.min(20, query.length()))),
                    chatMessages);
            if (result != null) {
                // 解析 AI 响应，按行分割并过滤无效内容
                List<String> raw = Arrays.stream(result.split("\n"))
                        .map(String::trim)
                        .filter(line -> !line.isBlank() && line.length() <= 20)
                        .filter(line -> line.length() >= 2)  // 单字无意义
                        .distinct()
                        .toList();

                // 去除与原词高度相似的扩展词（包含关系），避免重复查询
                // 放宽到 18 个：覆盖同义/反义/根因/跨学科/学术/经典/路径/场景/人群/流派/共现 等维度
                List<String> expanded = raw.stream()
                        .filter(line -> !isSimilarToQuery(line, query))
                        .limit(18)
                        .collect(Collectors.toList());

                if (!expanded.isEmpty()) {
                    log.debug("向量查询扩展: '{}' → {}", query, expanded);
                    return expanded;
                }
            }
        } catch (Exception e) {
            // 扩展失败时使用原始查询，不影响搜索功能
            log.warn("向量查询扩展失败，使用原始查询: {}", e.getMessage());
        }
        // 默认返回原始查询
        return List.of(query);
    }

    /**
     * 判断扩展词与原词是否高度相似（包含关系），相似则跳过避免重复查询。
     * "两性"与"两性关系"相似；"婚姻沟通"与"两性关系"不相似。
     */
    private boolean isSimilarToQuery(String expansion, String query) {
        String e = expansion.trim();
        String q = query.trim();
        if (e.equals(q)) return true;
        // 包含关系：短词是长词的子串，且短词长度≥2
        if (e.length() >= 2 && q.length() >= 2) {
            if (q.contains(e) || e.contains(q)) return true;
        }
        return false;
    }

    /**
     * 生成图书精炼摘要：将 chapterSummary + 标签 + 目录 压缩为高信息密度的结构化摘要。
     *
     * <p>一次 LLM 调用，生成后存入 Book.compressedSummary，后续问答直接复用。
     * 不设长度上限，以精炼为目标，保留所有关键信息。</p>
     *
     * @param book 书籍实体（需含 chapterSummary, description, toc, 各类标签）
     * @return 精炼后的摘要文本，失败时返回 null
     */
    public String generateCompressedSummary(Book book) {
        try {
            StringBuilder input = new StringBuilder();

            if (book.getDescription() != null && !book.getDescription().isBlank()) {
                input.append("【图书简介】\n").append(book.getDescription()).append("\n\n");
            }
            if (book.getFormatTags() != null && !book.getFormatTags().isBlank()) {
                input.append("【格式标签】").append(book.getFormatTags()).append("\n");
            }
            if (book.getConceptTags() != null && !book.getConceptTags().isBlank()) {
                input.append("【核心概念标签】").append(book.getConceptTags()).append("\n");
            }
            if (book.getReaderNeedTags() != null && !book.getReaderNeedTags().isBlank()) {
                input.append("【读者需求标签】").append(book.getReaderNeedTags()).append("\n");
            }
            if (book.getTargetReaderTags() != null && !book.getTargetReaderTags().isBlank()) {
                input.append("【目标读者标签】").append(book.getTargetReaderTags()).append("\n");
            }
            if (book.getToc() != null && !book.getToc().isBlank()) {
                input.append("\n【图书目录】\n").append(book.getToc()).append("\n");
            }
            if (book.getChapterSummary() != null && !book.getChapterSummary().isBlank()) {
                input.append("\n【章节原文摘录】\n").append(book.getChapterSummary()).append("\n");
            }

            String systemPrompt = AiPromptConstants.COMPRESSED_SUMMARY_SYSTEM_PROMPT;

            // 动态内容（图书信息）作为 UserMessage
            List<ChatMessage> chatMessages = List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(input.toString()));

            String result = callAiForScene(AiScene.BOOK_SUMMARY_REFINE,
                    "图书摘要精炼",
                    String.format("book=%s, inputLen=%d", book.getTitle(), input.length()),
                    chatMessages);

            if (result != null && !result.isBlank()) {
                log.info("图书摘要精炼成功: bookId={}, title={}, resultLen={}",
                        book.getId(), book.getTitle(), result.length());
                return result;
            }

        } catch (Exception e) {
            log.warn("图书摘要精炼失败: bookId={}, title={} - {}", book.getId(), book.getTitle(), e.getMessage());
        }
        return null;
    }


    // ================================================================
    // 公共工具方法（供其他服务使用，如流式速读）
    // ================================================================

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
     * 获取流式聊天模型实例（不带思考模式）。
     *
     * @return StreamingChatModel 实例，未配置时返回 null
     */
    public StreamingChatModel getStreamingChatModelWithoutThinking() {
        return chatModelFactory.buildStreamingChatModelWithoutThinking();
    }



    /**
     * 流式生成 3 分钟速读摘要，通过 SSE 实时推送内容。
     * <p>
     * 提升用户体验。输出格式为 Markdown 标题 + 内容行，便于前端渲染。</p>
     *
     * @param book    书籍实体
     * @param user    用户实体（可为 null）
     * @param emitter SSE 发送器，用于推送流式数据
     */
    public void streamSpeedRead(Book book, User user, SseEmitter emitter) {
        try {
            // 获取流式模型实例（按场景路由）
            StreamingChatModel model = chatModelFactory.buildStreamingForScene(AiScene.SPEED_READ);
            if (model == null) {
                SseHelper.sendErrorAndComplete(emitter, "AI 模型未配置，无法生成速读摘要");
                return;
            }
            var logContext = chatModelFactory.buildLogContext(AiScene.SPEED_READ);

            // 构建书籍内容和用户画像
            String bookContent = buildSpeedReadContent(book);
            String userProfileDesc = userProfileBuilder.build(user);

            // 固定角色 + 格式指令作为 SystemMessage（与动态内容分离，复用 KV Cache 前缀）
            String systemPrompt = AiPromptConstants.SPEED_READ_SYSTEM_PROMPT;

            // 消息顺序优化 KV Cache：SystemMessage(固定指令) → UserMessage(图书信息) → UserMessage(用户画像)
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(systemPrompt + "\n\n /no_think"));
            messages.add(UserMessage.from("【书籍信息】\n" + bookContent + "\n\n /no_think"));
            if (!userProfileDesc.isBlank()) {
                messages.add(UserMessage.from("【读者画像】\n" + userProfileDesc + "\n\n /no_think"));
            }

            long startTime = System.currentTimeMillis();

            StreamingSseHandler.stream(model, messages, emitter, new StreamingSseHandler.Callback() {
                @Override
                public String getOperationName() { return "3分钟速读"; }

                @Override
                public void onComplete(String fullResponse, ChatResponse completeResponse) {
                    // 统一摘要日志已由 StreamingSseHandler.onCompleteResponse 打印
                }
            }, 2, logContext);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) return;
            log.warn("流式速读摘要异常: bookId={} - {}", book.getId(), e.getMessage());
            SseHelper.sendErrorAndComplete(emitter, SseHelper.extractFriendlyError(e));
        }
    }

    /**
     * 从长问题中提取关键词，用于向量检索。
     * <p>
     * 策略：去掉口语化表达，提取核心名词组合，限制在15字以内。
     * </p>
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
     * <p>按行分割，去除序号、空白行和过短的行，返回最多 20 个去重后的问题。</p>
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

    // ================================================================
    // 辩论域 AI 调用（封装 SystemMessage + UserMessage 拼装 → callAi）
    // ================================================================

    /**
     * 从书籍信息生成辩论话题列表。
     *
     * @param bookInfo 书籍元数据文本（书名、作者、标签、简介等）
     * @return AI 原始响应文本（JSON 数组），由调用方解析
     */
    public String callAiForDebateTopics(String bookInfo) {
        return callAiForScene(AiScene.DEBATE_TOPIC_GENERATE, "辩论辩题生成", "bookInfo", List.of(
                SystemMessage.from(AiPromptConstants.DEBATE_TOPIC_GENERATION_SYSTEM_PROMPT),
                UserMessage.from("书籍信息：" + bookInfo)));
    }

    /**
     * 优化用户自定义辩论话题。
     */
    public String callAiForDebateTopicOptimization(long bookId, String topic, String bookInfo, String proArg, String conArg) {
        String userPrompt = String.format("""
                书籍信息：
                %s

                用户的原始输入：
                辩题：%s
                正方观点：%s
                反方观点：%s""", bookInfo, topic, proArg, conArg);

        return callAiForScene(AiScene.DEBATE_TOPIC_OPTIMIZE, "辩论辩题优化",
                String.format("bookId=%d, topic=%s", bookId, topic), List.of(
                SystemMessage.from(AiPromptConstants.DEBATE_OPTIMIZE_TOPIC_SYSTEM_PROMPT),
                UserMessage.from(userPrompt)));
    }

    /**
     * 自由辩论环节选择下一个发言人。
     */
    public String callAiForFreeDebaterSelection(String sessionId, String topic, String rolesInfo,
                                                 String lastSpeaker, String lastSide, String countInfo) {
        String userPrompt = String.format("""
                当前辩题：%s

                参与辩手：
                %s

                上一位发言者：%s（%s方）

                发言次数统计：
                %s""", topic, rolesInfo, lastSpeaker, lastSide, countInfo);

        return callAiForScene(AiScene.DEBATE_SPEAKER_SELECT, "辩论自由辩论发言人选择",
                "sessionId=" + sessionId, List.of(
                SystemMessage.from(AiPromptConstants.DEBATE_NEXT_SPEAKER_FREE_SYSTEM_PROMPT),
                UserMessage.from(userPrompt)));
    }

    /**
     * 对单次辩论发言进行 7 维度评分。
     *
     * @return AI 原始响应文本（JSON 对象），由调用方解析
     */
    public String callAiForDebateScoring(String sessionId, String roleKey, int roundNumber,
                                          String topic, String side, String roundTypeLabel, String content) {
        String userPrompt = String.format("""
                辩题：%s
                发言者：%s（%s方）
                当前轮次：%s
                发言内容：%s""", topic, roleKey, side, roundTypeLabel, content);

        return callAiForScene(AiScene.DEBATE_SCORING, "辩论评分",
                String.format("sessionId=%s, roleKey=%s, round=%d", sessionId, roleKey, roundNumber), List.of(
                SystemMessage.from(AiPromptConstants.DEBATE_SCORING_SYSTEM_PROMPT),
                UserMessage.from(userPrompt)));
    }

    // ================================================================
    // 圆桌派域 AI 调用
    // ================================================================

    /**
     * 从书籍信息选择最适合的讨论嘉宾。
     *
     * @return AI 原始响应文本（JSON 数组），由调用方解析
     */
    public String callAiForRoleSelection(long bookId, String bookTitle, String bookInfo, List<String> excludeKeys) {
        String roleList = aiConfigProvider.buildRoundTableRoleListForPrompt();
        String excludeClause = (excludeKeys != null && !excludeKeys.isEmpty())
                ? "4. 【刷新】以下角色已选过，这次必须换一批不同的人：" + String.join(", ", excludeKeys)
                : "";
        String systemPrompt = String.format(AiPromptConstants.ROUND_TABLE_ROLE_SELECTION_SYSTEM_PROMPT_TEMPLATE, excludeClause, roleList);
        return callAiForScene(AiScene.ROUND_TABLE_ROLE_RECOMMEND, "圆桌派角色推荐",
                String.format("bookId=%d, title=%s", bookId, bookTitle), List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from("书籍信息：\n" + bookInfo)));
    }

    /**
     * 回退模式：纯关键词硬匹配角色推荐。
     *
     * @return AI 原始响应文本（逗号分隔的角色 key 列表）
     */
    public String callAiForRoleSelectionFallback(long bookId, String bookTitle, String bookInfo, String roleList) {
        return callAiForScene(AiScene.ROUND_TABLE_ROLE_RECOMMEND, "圆桌派角色推荐(回退)",
                String.format("bookId=%d, title=%s", bookId, bookTitle), List.of(
                SystemMessage.from(AiPromptConstants.ROLE_SELECTION_FALLBACK_SYSTEM_PROMPT),
                UserMessage.from("角色列表：" + roleList + "\n\n书籍信息：\n" + bookInfo)));
    }

    /**
     * 为特定角色生成向量检索查询文本。
     */
    public String callAiForRoleSearchQuery(String roleKey, String bookTitle, String roleName,
                                            String roleTitle, String roleKeywords, String recentDiscussion) {
        String systemPrompt = AiPromptConstants.ROLE_SEARCH_QUERY_SYSTEM_PROMPT;

        String userPrompt = String.format("""
                【图书】%s
                【角色】%s（%s）
                【角色关注领域】%s
                【最近讨论】
                %s""", bookTitle, roleName, roleTitle, roleKeywords, recentDiscussion);

        return callAiForScene(AiScene.ROUND_TABLE_ROLE_SEARCH, "圆桌派角色检索查询",
                "role=" + roleKey, List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userPrompt)));
    }

    /**
     * 为特定角色生成多个向量检索查询短语（每行一个），用于多子查询 RAG 检索。
     * <p>
     * 与 {@link #callAiForRoleSearchQuery} 的区别：
     * - 单查询版本：生成一段 30-80 字的查询文本
     * - 多查询版本：生成 N 个 10-30 字的查询短语，覆盖不同维度
     *
     * @param roleKey         角色键名
     * @param bookTitle       书名
     * @param roleName        角色名
     * @param roleTitle       角色头衔
     * @param roleKeywords    角色关键词（空格分隔）
     * @param recentDiscussion 最近两轮发言
     * @param subQueryCount   要生成的子查询数量
     * @param perspectiveHint 视角提示（如"从商业落地、产品思维角度解读"），null 时用默认视角
     * @return 子查询列表（已 trim、去空、去重）；失败返回空列表
     */
    public List<String> callAiForRoleSearchQueries(String roleKey, String bookTitle, String roleName,
                                                   String roleTitle, String roleKeywords,
                                                   String recentDiscussion, int subQueryCount,
                                                   String perspectiveHint) {
        String perspective = (perspectiveHint == null || perspectiveHint.isBlank())
                ? "从该角色的专业视角出发" : perspectiveHint;
        String systemPrompt = String.format(
                AiPromptConstants.ROLE_SEARCH_QUERIES_SYSTEM_PROMPT_TEMPLATE,
                subQueryCount, perspective);

        String userPrompt = String.format("""
                【图书】%s
                【角色】%s（%s）
                【角色关注领域】%s
                【最近讨论】
                %s""", bookTitle, roleName, roleTitle, roleKeywords,
                recentDiscussion == null || recentDiscussion.isBlank() ? "（讨论尚未开始）" : recentDiscussion);

        String aiText = callAiForScene(AiScene.ROUND_TABLE_ROLE_SEARCH, "圆桌派角色检索查询(多)",
                "role=" + roleKey + ", count=" + subQueryCount, List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userPrompt)));
        if (aiText == null || aiText.isBlank()) return List.of();

        List<String> queries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : aiText.split("\n")) {
            String q = line.trim()
                    .replaceAll("^(查询|检索|搜索|关键词)[：:]", "")
                    .trim();
            if (q.isBlank() || q.length() > 30 || seen.contains(q)) continue;
            seen.add(q);
            queries.add(q);
        }
        return queries;
    }

    // ================================================================
    // 覆盖度域 AI 调用
    // ================================================================

    /**
     * 从图书摘要生成 LLM 内容大纲（主题块）。
     *
     * @return AI 原始响应文本（JSON 数组），由调用方解析
     */
    // ================================================================
    // 外部知识生成
    // ================================================================

    /**
     * 为圆桌派角色生成外部知识
     */
    public String generateExternalKnowledge(String roleDomain, String topic) {
        return externalKnowledgeGenerator.generateForRoundTable(roleDomain, topic);
    }

    public String generateDebateExternalKnowledge(String topic, String side, String stance) {
        return externalKnowledgeGenerator.generateForDebate(topic, side, stance);
    }

    public String callAiForLlmOutline(String contentInfo, int minBlocks, int maxBlocks) {
        String systemPrompt = String.format(AiPromptConstants.LLM_OUTLINE_SYSTEM_PROMPT_TEMPLATE, minBlocks, maxBlocks);

        return callAiForScene(AiScene.ROUND_TABLE_COVERAGE, "圆桌派覆盖度评估",
                "LLM大纲生成", List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from("图书信息：\n" + contentInfo)));
    }

}
