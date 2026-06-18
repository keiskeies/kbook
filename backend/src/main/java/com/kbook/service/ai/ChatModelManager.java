package com.kbook.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.config.ChatModelFactory;
import com.kbook.constants.AiPromptConstants;
import com.kbook.entity.Book;
import com.kbook.entity.User;
import com.kbook.service.recommend.RecommendMatchCalculator;
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

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    private static final int SPEED_READ_CONTENT_LIMIT = 15000;

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
        // 获取 ChatModel 实例，如果模型未配置则直接返回 null
        ChatModel model = modelSupplier.get();
        if (model == null) {
            log.warn("AI 模型未配置，跳过: {}", logName);
            return null;
        }
        // 记录调用开始时间，用于计算耗时
        long startTime = System.currentTimeMillis();
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
        log.debug("logName: {}, logDetail: {}", logName, logDetail);
        CommonUtils.logAiCall(logName, elapsed, inputTokens, outputTokens, logDetail);
        return text;
    }


    /**
     * 带完整消息列表的公共 AI 调用入口（消息已由调用方组装）。
     *
     * @param logName   日志标识
     * @param logDetail 日志详情
     * @param messages  完整的 ChatMessage 列表
     * @return AI 响应文本
     */
    public String callAi(String logName, String logDetail, List<ChatMessage> messages) {
        return callAi(logName, logDetail, chatModelFactory::buildToolChatModel, messages);
    }

    /**
     * 不带思考模式的公共 AI 调用入口（用于标签生成、元数据推断等无需复杂推理的场景）。
     *
     * @param logName   日志标识
     * @param logDetail 日志详情
     * @param messages  完整的 ChatMessage 列表
     * @return AI 响应文本
     */
    public String callAiWithoutThinking(String logName, String logDetail, List<ChatMessage> messages) {
        return callAi(logName, logDetail, chatModelFactory::buildChatModelWithoutThinking, messages);
    }

    // ================================================================
    // 业务方法
    // ================================================================

    /**
     * 将内容压缩到 200 字以内，用于历史对话记忆的精简。
     *
     * <p>压缩策略：如果内容长度小于等于 200 字则直接返回原内容，否则调用 AI 进行压缩。
     * 压缩失败时返回 null，由调用方决定如何处理。</p>
     *
     * @param original 原始内容文本
     * @return 压缩后的内容，无需压缩或失败时返回 null
     */
    public String compressContent(String original) {
        // 如果内容为空或已足够短，直接返回原内容
        if (original == null || original.length() <= 200) return original;
        try {
            // 调用 AI 进行内容压缩，系统提示词与动态内容分离以复用 KV Cache
            return callAi("历史压缩", String.format("%d→? chars", original.length()),
                    chatModelFactory::buildToolChatModel,
                    List.of(
                            SystemMessage.from("将以下内容压缩到200字以内，保留核心观点和信息。"),
                            UserMessage.from(original)));
        } catch (Exception e) {
            // 压缩失败不影响主流程，返回 null 由调用方处理
            log.warn("调用 AI 压缩内容失败: {}", e.getMessage());
            return null;
        }
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
        try {
            String prompt = "根据以下书籍内容，推断并提取以下信息，以JSON格式返回：\n" +
                    "- author: 作者名（如果内容中能看出来，否则填 null）\n" +
                    "- description: 简短的内容简介（50-200字，概括书籍主题和内容，如果内容中自带简介则提取原简介）\n" +
                    "只返回JSON，不要其他文字。\n\n" +
                    "书籍内容：\n" + CommonUtils.truncateText(content, SPEED_READ_CONTENT_LIMIT);

            // 调用 AI 推断元数据，使用专用的系统提示词
            String result = callAi("元数据推断", "TXT/PDF 元数据推断",
                    chatModelFactory::buildToolChatModel,
                    List.of(SystemMessage.from(AiPromptConstants.BOOK_INFO_EXTRACT_SYSTEM_PROMPT),
                            UserMessage.from(prompt)));
            // 移除 AI 响应中的代码围栏
            result = CommonUtils.stripCodeFence(result);
            if (result != null) {
                // 解析 JSON 响应
                var node = objectMapper.readTree(result);

                if ((book.getAuthor() == null || book.getAuthor().isBlank())
                        && node.has("author") && !node.get("author").isNull()) {
                    String author = node.get("author").asText().trim();
                    if (!author.isBlank() && !"null".equalsIgnoreCase(author)) {
                        book.setAuthor(author);
                    }
                }

                if (node.has("description") && !node.get("description").isNull()) {
                    String desc = node.get("description").asText().trim();
                    if (!desc.isBlank() && !"null".equalsIgnoreCase(desc)) {
                        book.setDescription(desc);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从内容推断元数据失败: {} - {}", book.getTitle(), e.getMessage());
        }
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
        String userProfileDesc = buildUserProfileDesc(user);
        String bookInfo = buildSpeedReadContent(book);

        try {
            // 固定指令作为 SystemMessage（与动态内容分离，复用 KV Cache 前缀）
            String systemPrompt = """
                    你正在和读者讨论一本书。根据图书基本信息、读者画像和上轮问答，审视你刚才的回答，找出其中3个最可能引发这位读者追问的逻辑缝隙，将其转化为问题。逻辑缝隙包括但不限于：
                    - 你说了一个结论，但没有给出这个结论成立的条件或前提
                    - 你使用了一个关键概念，但它的含义在语境中可能被误解
                    - 你的论证存在一个隐含的预设，这个预设本身是可以被质疑的
                    - 你提出了一个判断，但没有说明它适用的边界或反例
                    
                    要求：
                    - 每个问题直接指向回答中的具体逻辑点，不是泛泛的延伸讨论
                    - 问题要贴合读者的背景和处境，能引发他的共鸣或思考
                    - 问题要与本书内容紧密相关，不要偏离书籍主题
                    - 问题的提问对象是你这个AI，问题本身你必须能回答
                    - 每行一个，不超25字，无序号
                    """;

            // 消息顺序优化 KV Cache：SystemMessage(固定指令) → UserMessage(图书信息) → UserMessage(用户画像) → UserMessage(上轮问答)
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(systemPrompt));
            messages.add(UserMessage.from("【图书信息】\n" + bookInfo));
            if (!userProfileDesc.isBlank()) {
                messages.add(UserMessage.from("【读者画像】\n" + userProfileDesc));
            }
            messages.add(UserMessage.from("读者问：" + question + "\n你回答：" + answer));

            String aiText = callAi("生成深入追问问题",
                    String.format("title=%s", title),
                    chatModelFactory::buildToolChatModel, messages);
            if (aiText != null) {
                return parseQuestions(aiText).stream().limit(3).collect(Collectors.toList());
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
            String systemPrompt = """
                    你是一个向量检索查询生成器。将用户问题拆解为多个短小的检索关键词短语，每行一个。

                    【拆解步骤】
                    1. 提取问题中的核心名词
                    2. 找出这些名词在书中可能对应的章节/主题
                    3. 用"名词+名词"或"名词+动词"组合成5-15字的短语
                    4. 从3个角度生成：核心概念、相关主题、上下位概念

                    【硬性要求】
                    - 每个短语必须≤15字
                    - 短语必须是名词性词组，不要完整句子
                    - 短语必须像"书中某个章节的小标题"
                    - 禁止使用"的关系""的影响""的作用"等学术后缀
                    - 禁止出现用户原话中的口语化表达

                    【宏观问题】当用户问全书性问题（"讲了什么""核心观点"等）：
                    - 从目录中提取每个章节的核心主题词

                    【具体问题】当用户问具体问题时：
                    - 问题中的核心概念
                    - 问题涉及的书中相关章节主题
                    - 问题的上位/下位概念

                    只输出短语，每行一个，不带序号、引号、解释。""";

            // 消息顺序：SystemMessage(固定指令) → UserMessage(图书信息) → UserMessage(动态上下文+问题)
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(systemPrompt));
            messages.add(UserMessage.from("【书籍信息】\n" + bookContext.trim()));

            // 动态内容（上轮回答 + 用户问题）
            StringBuilder dynamicContext = new StringBuilder();
            if (lastAiAnswer != null && !lastAiAnswer.isBlank()) {
                dynamicContext.append("上轮AI回答：").append(lastAiAnswer).append("\n");
            }
            dynamicContext.append("\n用户问题：").append(question);
            messages.add(UserMessage.from("【上下文】\n" + dynamicContext.toString().trim()));

            String aiText = callAi("RAG查询扩展",
                    String.format("原始: %s", question),
                    chatModelFactory::buildToolChatModel, messages);
            if (aiText != null) {
                for (String line : aiText.split("\n")) {
                    line = line.trim();
                    // 过滤：非空、不过长、不重复
                    if (!line.isBlank() && line.length() <= 15 && !queries.contains(line)) {
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
            String systemPrompt = """
                    你是一个图书搜索查询扩展器。用户输入了口语化的搜索词，你的任务是推断用户真正的阅读需求，从多个维度生成检索关键词。
                    
                    关键原则：不要改写或解释用户的原话，而是思考——一个有这种需求的人，真正需要读什么书？从哪些不同方向能找到能满足他的书？
                    
                    规则：
                    1. 生成3-5个不同维度的关键词短语，每行一个
                    2. 每个短语2-8个字，简洁精准
                    3. 各关键词覆盖不同维度，有本质差异，避免同义重复
                    4. 关键词应是书籍标签、分类或简介中可能出现的短语
                    5. 只输出关键词，不要序号、引号或任何额外文字
                    """;

            // 动态内容（用户查询）作为 UserMessage
            List<ChatMessage> chatMessages = List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(query));

            // 调用 AI 生成扩展关键词
            String result = callAi("向量查询扩展",
                    String.format("q=%s", query.substring(0, Math.min(20, query.length()))),
                    chatModelFactory::buildToolChatModel, chatMessages);
            if (result != null) {
                // 解析 AI 响应，按行分割并过滤无效内容
                List<String> expanded = Arrays.stream(result.split("\n"))
                        .map(String::trim)
                        .filter(line -> !line.isBlank() && line.length() <= 30)
                        .distinct()
                        .limit(5)
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

            String systemPrompt = """
                    你是一位专业的图书编辑。请根据提供的图书信息，生成一份精炼的结构化摘要。
                    
                    要求：
                    1. 精炼高于简短：不设字数上限，但每个字都要有价值，不废话
                    2. 保留所有关键信息：核心论点、论证思路、重要概念、章节脉络
                    3. 结构化输出：
                    
                    【一句话概括】用一句话概括本书的核心内容（≤100字）
                    
                    【核心论点】列出 3-5 个核心论点或观点，每条用 1-2 句话说明
                    
                    【章节脉络】按目录顺序，说明各主要章节/部分的核心内容及其关系（每章一句话）
                    
                    【关键概念】列出书中重要的术语、概念及其简要定义
                    
                    【独特贡献】本书在该领域中的独特贡献或与同类书的差异（如有）
                    
                    【适合读者】适合什么样的读者阅读
                    """;

            // 动态内容（图书信息）作为 UserMessage
            List<ChatMessage> chatMessages = List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(input.toString()));

            String result = callAi("图书摘要精炼",
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
            // 获取流式模型实例
            StreamingChatModel model = chatModelFactory.buildStreamingToolChatModel();
            if (model == null) {
                SseHelper.sendErrorAndComplete(emitter, "AI 模型未配置，无法生成速读摘要");
                return;
            }

            // 构建书籍内容和用户画像
            String bookContent = buildSpeedReadContent(book);
            String userProfileDesc = buildUserProfileDesc(user);

            // 固定角色 + 格式指令作为 SystemMessage（与动态内容分离，复用 KV Cache 前缀）
            String systemPrompt = """
                    你是一位资深阅读顾问。请基于书籍信息和读者画像，生成一份「3分钟速读」摘要，帮助读者快速判断这本书是否值得阅读。
                    
                    请严格按照以下格式输出，每个标题占一行，标题下的每条内容各占一行：
                    
                    ### 难度
                    入门/中等/进阶
                    
                    ### 核心观点
                    xxxxx
                    xxxxx
                    xxxxx
                    xxxxx
                    
                    ### 适合谁读
                    xxxxx
                    xxxxx
                    xxxxx
                    
                    ### 不适合谁读
                    xxxxx
                    xxxxx
                    xxxxx
                    
                    ### 读完能收获什么
                    xxxxx
                    xxxxx
                    xxxxx
                    
                    要求：
                    - 难度: 根据内容深度判断阅读难度，只输出"入门"、"中等"或"进阶"。
                    - 核心观点: 3-10个最核心的观点或主题，每个不超过30字，**直接输出内容文字，不加任何序号、编号、前缀符号**。如提供读者画像，请突出与其最相关的内容。
                    - 适合谁读: 2-3类最适合阅读的人群描述，**每条直接输出，不加序号、编号、符号前缀**。如提供读者画像，请特别说明为什么适合这位读者。
                    - 不适合谁读: 2-3类不适合阅读的人群描述，**每条直接输出，不加序号、编号、符号前缀**。
                    - 读完能收获什么: 2-3个读完能获得的具体收获，**每条直接输出，不加序号、编号、符号前缀**。如提供读者画像，请结合其职业和人生阶段给出个性化收获。
                    - **绝对禁止**：不要使用任何序号（如1. 2. 3.）、编号、列表符号（如-、*、）、前缀字符，每条内容必须是纯自然段落文字。
                    - 不要输出任何其他内容，不要使用Markdown加粗或列表符号。
                    - 每个标题占一行，标题下的每条内容各占一行
                    """;

            // 消息顺序优化 KV Cache：SystemMessage(固定指令) → UserMessage(图书信息) → UserMessage(用户画像)
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(systemPrompt));
            messages.add(UserMessage.from("【书籍信息】\n" + bookContent));
            if (!userProfileDesc.isBlank()) {
                messages.add(UserMessage.from("【读者画像】\n" + userProfileDesc));
            }

            long startTime = System.currentTimeMillis();
            final boolean[] connectionClosed = {false};

            model.chat(
                    messages,
                    new StreamingChatResponseHandler() {
                        StreamingHandle streamingHandle;

                        @Override
                        public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                            if (streamingHandle == null) {
                                streamingHandle = context.streamingHandle();
                            }
                            if (connectionClosed[0] || (streamingHandle != null && streamingHandle.isCancelled()))
                                return;
                            String text = partialResponse.text();
                            if (text != null && !text.isEmpty()) {
                                if (!SseHelper.safeSendEvent(emitter, "message", text)) {
                                    connectionClosed[0] = true;
                                    if (streamingHandle != null) streamingHandle.cancel();
                                    log.warn("SSE 连接已关闭，停止 AI 输出: bookId={}", book.getId());
                                }
                            }
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse completeResponse) {
                            long elapsed = System.currentTimeMillis() - startTime;
                            int inputTokens = completeResponse.tokenUsage() != null && completeResponse.tokenUsage().inputTokenCount() != null
                                    ? completeResponse.tokenUsage().inputTokenCount() : 0;
                            int outputTokens = completeResponse.tokenUsage() != null && completeResponse.tokenUsage().outputTokenCount() != null
                                    ? completeResponse.tokenUsage().outputTokenCount() : 0;
                            CommonUtils.logAiCall("3分钟速读(流式)", elapsed, inputTokens, outputTokens,
                                    String.format("bookId=%d, title=%s", book.getId(), book.getTitle()));

                            if (connectionClosed[0]) {
                                log.warn("SSE 连接已断开，跳过发送done事件: bookId={}", book.getId());
                            } else {
                                try {
                                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                    emitter.complete();
                                } catch (Exception ignored) {
                                }
                            }
                        }

                        @Override
                        public void onError(Throwable error) {
                            if (connectionClosed[0] || (streamingHandle != null && streamingHandle.isCancelled())) {
                                log.warn("SSE 连接已断开，跳过错误处理: bookId={}", book.getId());
                                return;
                            }
                            log.warn("流式速读摘要失败: bookId={} - {}", book.getId(), error.getMessage());
                            SseHelper.sendErrorAndComplete(emitter, SseHelper.extractFriendlyError(error));
                        }
                    }
            );
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) return;
            log.warn("流式速读摘要异常: bookId={} - {}", book.getId(), e.getMessage());
            SseHelper.sendErrorAndComplete(emitter, SseHelper.extractFriendlyError(e));
        }
    }

    /**
     * 构建用户画像描述文本，用于个性化 AI 推荐。
     *
     * <p>从用户实体中提取年龄、性别、婚姻状况、子女信息、MBTI、职业、
     * 期望学历、创业意向、期望收入、阅读意图和心情等信息，
     * 格式化为结构化文本供 AI 模型使用。</p>
     *
     * @param user 用户实体（可为 null）
     * @return 用户画像描述文本，用户为 null 时返回空字符串
     */
    public static String buildUserProfileDesc(User user) {
        // 用户为 null 时返回空字符串
        if (user == null) return "";
        StringBuilder profileBuilder = new StringBuilder();
        // 计算年龄
        if (user.getBirthday() != null) {
            int age = Period.between(user.getBirthday(), LocalDate.now()).getYears();
            profileBuilder.append("年龄：").append(age).append("岁\n");
        }
        if (user.getGender() != null) {
            profileBuilder.append("性别：").append(switch (user.getGender()) {
                case "MALE" -> "男";
                case "FEMALE" -> "女";
                default -> "其他";
            }).append("\n");
        }
        if (user.getMarried() != null) {
            profileBuilder.append("婚姻：").append(user.getMarried() ? "已婚" : "未婚").append("\n");
        }
        if (user.getChildrenAgeRanges() != null && !user.getChildrenAgeRanges().isBlank()) {
            String labels = java.util.Arrays.stream(user.getChildrenAgeRanges().split(","))
                    .map(String::trim)
                    .map(RecommendMatchCalculator::getChildRangeLabel)
                    .collect(java.util.stream.Collectors.joining("、"));
            profileBuilder.append("子女年龄段：").append(labels).append("\n");
        } else if (user.getHasChildren() != null) {
            profileBuilder.append("子女：").append(user.getHasChildren() ? "有孩子" : "无孩子").append("\n");
        }
        if (user.getMbti() != null) {
            profileBuilder.append("MBTI：").append(user.getMbti()).append("\n");
        }
        if (user.getOccupation() != null && !user.getOccupation().isBlank()) {
            profileBuilder.append("职业：").append(RecommendMatchCalculator.getOccupationLabel(user.getOccupation())).append("\n");
        }
        if (user.getAspirationEducation() != null) {
            profileBuilder.append("期望学历：").append(RecommendMatchCalculator.getEducationLabel(user.getAspirationEducation())).append("\n");
        }
        if (user.getEntrepreneurship() != null) {
            profileBuilder.append("创业意向：").append(RecommendMatchCalculator.getEntrepreneurshipLabel(user.getEntrepreneurship())).append("\n");
        }
        if (user.getAspirationIncome() != null) {
            profileBuilder.append("期望年收入：").append(RecommendMatchCalculator.getAnnualIncomeLabel(user.getAspirationIncome())).append("\n");
        }
        if (user.getMood() != null && !user.getMood().isBlank()) {
            String moodRaw = user.getMood();
            int pipeIdx = moodRaw.indexOf('|');
            if (pipeIdx > 0) {
                String intentKey = moodRaw.substring(0, pipeIdx);
                String moodKey = moodRaw.substring(pipeIdx + 1);
                profileBuilder.append("阅读意图：").append(RecommendMatchCalculator.getIntentLabel(intentKey)).append("\n");
                profileBuilder.append("当前心情：").append(RecommendMatchCalculator.getMoodLabel(moodKey)).append("\n");
            }
        }
        return profileBuilder.toString();
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
        return callAi("辩论辩题生成", "bookInfo", List.of(
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

        return callAi("辩论辩题优化", String.format("bookId=%d, topic=%s", bookId, topic), List.of(
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

        return callAi("辩论自由辩论发言人选择", "sessionId=" + sessionId, List.of(
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

        return callAi("辩论评分", String.format("sessionId=%s, roleKey=%s, round=%d", sessionId, roleKey, roundNumber), List.of(
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
    public String callAiForRoleSelection(long bookId, String bookTitle, String bookInfo) {
        return callAi("圆桌派角色推荐", String.format("bookId=%d, title=%s", bookId, bookTitle), List.of(
                SystemMessage.from(AiPromptConstants.ROUND_TABLE_ROLE_SELECTION_SYSTEM_PROMPT),
                UserMessage.from("书籍信息：\n" + bookInfo)));
    }

    /**
     * 回退模式：纯关键词硬匹配角色推荐。
     *
     * @return AI 原始响应文本（逗号分隔的角色 key 列表）
     */
    public String callAiForRoleSelectionFallback(long bookId, String bookTitle, String bookInfo, String roleList) {
        return callAi("圆桌派角色推荐(回退)", String.format("bookId=%d, title=%s", bookId, bookTitle), List.of(
                SystemMessage.from("根据提供的书籍信息，从角色列表中选出最适合参与讨论的4-6个角色（不含HOST）。只输出角色key，用逗号分隔，不要输出任何解释。"),
                UserMessage.from("角色列表：" + roleList + "\n\n书籍信息：\n" + bookInfo)));
    }

    /**
     * 为特定角色生成向量检索查询文本。
     */
    public String callAiForRoleSearchQuery(String roleKey, String bookTitle, String roleName,
                                            String roleTitle, String roleKeywords, String recentDiscussion) {
        String systemPrompt = """
                你是一个检索查询生成器。请根据提供的信息，生成一段用于在书中检索相关段落的查询文本。

                要求：
                1. 查询应该从该角色的专业视角出发，结合角色关注的关键词
                2. 查询要紧扣当前讨论话题，让检索结果能帮助该角色发表有深度的观点
                3. 只输出查询文本本身，不要输出任何解释或前缀
                4. 查询长度控制在30-80字
                """;

        String userPrompt = String.format("""
                【图书】%s
                【角色】%s（%s）
                【角色关注领域】%s
                【最近讨论】
                %s""", bookTitle, roleName, roleTitle, roleKeywords, recentDiscussion);

        return callAi("圆桌派角色检索查询", "role=" + roleKey, List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userPrompt)));
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
        String logName = "外部知识生成";
        String logDetail = "领域=" + roleDomain + ", 话题=" + topic;

        List<ChatMessage> messages = List.of(
                SystemMessage.from(AiPromptConstants.EXTERNAL_KNOWLEDGE_SYSTEM_PROMPT),
                UserMessage.from("角色专业领域：" + roleDomain + "\n讨论话题：" + topic));

        return callAiWithoutThinking(logName, logDetail, messages);
    }

    /**
     * 为奇葩说辩手生成外部知识
     */
    public String generateDebateExternalKnowledge(String topic, String side, String stance) {
        String logName = "辩论外部知识生成";
        String logDetail = "辩题=" + topic + ", 立场=" + side;

        List<ChatMessage> messages = List.of(
                SystemMessage.from(AiPromptConstants.DEBATE_EXTERNAL_KNOWLEDGE_SYSTEM_PROMPT),
                UserMessage.from("辩题：" + topic + "\n立场：" + side + "\n辩手视角：" + stance));

        return callAiWithoutThinking(logName, logDetail, messages);
    }

    public String callAiForLlmOutline(String contentInfo, int minBlocks, int maxBlocks) {
        String systemPrompt = String.format("""
                你是一个书籍内容分析专家。请根据提供的图书信息，生成该书的内容大纲。

                要求：
                1. 将全书内容划分为 %d-%d 个主题块
                2. 每个块要有「标题」和「一句话摘要」
                3. 标题要能反映该块的内容主题
                4. 输出格式为 JSON 数组，不要任何其他内容

                输出格式：
                [
                  {"title": "标题1", "summary": "一句话摘要"},
                  {"title": "标题2", "summary": "一句话摘要"}
                ]
                """, minBlocks, maxBlocks);

        return callAi("圆桌派覆盖度评估", "LLM大纲生成", List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from("图书信息：\n" + contentInfo)));
    }

}
