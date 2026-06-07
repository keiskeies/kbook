package com.kbook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.config.ChatModelFactory;
import com.kbook.constants.AiPromptConstants;
import com.kbook.dto.BookSpeedReadVO;
import com.kbook.entity.Book;
import com.kbook.entity.User;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
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

    public String callAi(String logName, String logDetail,
                          Supplier<ChatModel> modelSupplier, List<ChatMessage> messages) {
        ChatModel model = modelSupplier.get();
        if (model == null) {
            log.warn("AI 模型未配置，跳过: {}", logName);
            return null;
        }
        long startTime = System.currentTimeMillis();
        ChatResponse response = model.chat(messages);
        long elapsed = System.currentTimeMillis() - startTime;
        int inputTokens = response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null
                ? response.tokenUsage().inputTokenCount() : 0;
        int outputTokens = response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null
                ? response.tokenUsage().outputTokenCount() : 0;
        String text = response.aiMessage().text();
        if (text != null && !text.isBlank()) {
            text = text.trim();
        }
        CommonUtils.logAiCall(logName, elapsed, inputTokens, outputTokens, logDetail);
        return text;
    }

    public String callAi(String logName, String logDetail,
                          Supplier<ChatModel> modelSupplier, String userPrompt) {
        return callAi(logName, logDetail, modelSupplier, List.of(UserMessage.from(userPrompt)));
    }

    // ================================================================
    // 公共 AI 调用入口
    // ================================================================

    public String callAi(String logName, String logDetail, String userPrompt) {
        return callAi(logName, logDetail, chatModelFactory::buildChatModelWithoutThinkingFromYml, userPrompt);
    }

    public String callAi(String logName, String logDetail, String systemPrompt, String userPrompt) {
        return callAi(logName, logDetail, chatModelFactory::buildChatModelWithoutThinkingFromYml,
                List.of(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt)));
    }

    // ================================================================
    // 业务方法
    // ================================================================

    /** 将内容压缩到 200 字以内；无需压缩或失败时返回 null */
    public String compressContent(String original) {
        if (original == null || original.length() <= 200) return original;
        try {
            return callAi("历史压缩", String.format("%d→? chars", original.length()),
                    chatModelFactory::buildChatModelWithoutThinkingFromYml,
                    String.format("将以下内容压缩到200字以内，保留核心观点和信息：\n\n%s", original));
        } catch (Exception e) {
            log.warn("调用 AI 压缩内容失败: {}", e.getMessage());
            return null;
        }
    }

    /** 从文本内容推断书名/作者/简介，并更新 Book 实体 */
    public void inferMetadataFromContent(Book book, String content) {
        try {
            String prompt = "根据以下书籍内容，推断并提取以下信息，以JSON格式返回：\n" +
                    "- author: 作者名（如果内容中能看出来，否则填 null）\n" +
                    "- description: 简短的内容简介（50-200字，概括书籍主题和内容，如果内容中自带简介则提取原简介）\n" +
                    "只返回JSON，不要其他文字。\n\n" +
                    "书籍内容：\n" + CommonUtils.truncateText(content, 2000);

            String result = callAi("元数据推断", "TXT/PDF 元数据推断",
                    chatModelFactory::buildChatModelWithoutThinkingFromYml,
                    List.of(SystemMessage.from(AiPromptConstants.BOOK_INFO_EXTRACT_SYSTEM_PROMPT),
                            UserMessage.from(prompt)));
            result = stripCodeFence(result);
            if (result != null) {
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

    /** 根据已有问答生成深入追问问题 */
    public List<String> generateFollowUpQuestions(String title, String question, String answer) {
        if (answer == null || answer.isBlank() || question == null || question.isBlank()) {
            return List.of();
        }

        try {
            String prompt = String.format("""
                    你正在和读者讨论《%s》这本书。你刚给出了一个回答。
                    
                    读者问：%s
                    你回答：%s
                    
                    现在，审视你刚才的回答，找出其中3个最可能引发读者追问的逻辑缝隙，将其转化为问题。逻辑缝隙包括但不限于：
                    - 你说了一个结论，但没有给出这个结论成立的条件或前提
                    - 你使用了一个关键概念，但它的含义在语境中可能被误解
                    - 你的论证存在一个隐含的预设，这个预设本身是可以被质疑的
                    - 你提出了一个判断，但没有说明它适用的边界或反例
                    
                    要求：
                    - 每个问题直接指向回答中的具体逻辑点，不是泛泛的延伸讨论
                    - 问题的提问对象是你这个AI，问题本身你必须能回答
                    - 每行一个，不超25字，无序号
                    """,
                    title, question, answer);

            String aiText = callAi("生成深入追问问题",
                    String.format("title=%s", title), prompt);
            if (aiText != null) {
                return parseQuestions(aiText).stream().limit(3).collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.debug("生成深入追问问题失败: {}", e.getMessage());
        }

        return List.of();
    }

    /** RAG 查询扩展 — 宏观问题按目录章节生成查询，具体问题生成多粒度查询 */
    public List<String> expandQuery(String question, String bookTitle, String author, String lastAiAnswer, String toc) {
        List<String> queries = new ArrayList<>();
        queries.add(question);

        try {
            StringBuilder contextBuilder = new StringBuilder();
            contextBuilder.append("书名：《").append(bookTitle).append("》\n");
            if (author != null && !author.isBlank()) {
                contextBuilder.append("作者：").append(author).append("\n");
            }
            if (toc != null && !toc.isBlank()) {
                String truncatedToc = toc.length() > 2000
                        ? toc.substring(0, 2000) + "..."
                        : toc;
                contextBuilder.append("目录：\n").append(truncatedToc).append("\n");
            }
            if (lastAiAnswer != null && !lastAiAnswer.isBlank()) {
                String truncated = lastAiAnswer.length() > 500
                        ? lastAiAnswer.substring(0, 500) + "..."
                        : lastAiAnswer;
                contextBuilder.append("上轮AI回答摘要：").append(truncated).append("\n");
            }

            String prompt = String.format("""
                    你是一个向量检索查询生成器。根据以下上下文，为用户的问题生成向量搜索查询，每行一个。

                    上下文：
                    %s

                    用户问题：%s

                    请先判断问题类型，然后按对应策略生成查询：

                    【宏观问题】当用户问的是全书性、概览性问题（如"讲了什么""核心观点""主要内容""框架""概述""思路""核心思想""这本书的主题"等）时，根据目录为每个主要章节生成一个检索查询，确保覆盖全书内容。每个查询用该章节的核心主题词组合而成，使用书中可能出现的措辞。最多8个查询。

                    【具体问题】当用户问的是具体的、局部的问题时，生成2-3个不同粒度的查询：
                    - 精准定位：提取核心名词/关键论断，组合成书中可能出现的原文级短语
                    - 宽泛召回：将问题抽象到上一层主题或相关概念，补充检索遗漏

                    通用要求：
                    1. 先解析代词指代（"上面""这些""该理论"等），在查询中替换为具体实体
                    2. 如果用户追问上轮回答，查询应指向书中原文出处而非复述AI回答
                    3. 使用书籍中可能出现的措辞，避免口语化
                    4. 每行只输出查询文本，不带序号、引号或任何额外文字

                    示例（宏观问题）：
                    用户问题：这本书主要讲了什么？
                    输出：
                    情绪心理学的基本概念与理论框架
                    情绪的生理基础与神经机制
                    认知评价理论的核心观点
                    情绪建构论的主要论点与证据
                    情绪调节的策略与心理过程
                    情绪与社会互动的关系
                    情绪的个体差异与文化影响

                    示例（具体问题）：
                    用户问题：情绪是否完全由生理反应决定？
                    输出：
                    情绪理论中生理反应与认知评价的关系
                    情绪产生的生理机制与詹姆斯-兰格理论
                    情绪建构论对生理反应的解释
                    """, contextBuilder.toString().trim(), question);

            String aiText = callAi("RAG查询扩展",
                    String.format("原始: %s", question), prompt);
            if (aiText != null) {
                for (String line : aiText.split("\n")) {
                    line = line.trim();
                    if (!line.isBlank() && !line.equals(question)) {
                        queries.add(line);
                        if (queries.size() >= 9) break;
                    }
                }
            }

            log.debug("[RAG查询扩展] 原始: {} → 扩展后: {}", question, queries);
        } catch (Exception e) {
            log.warn("[RAG查询扩展] 失败，使用原始查询: {}", e.getMessage());
        }

        return queries;
    }

    /** 生成查询改写（Multi-Query Retrieval） */
    public List<String> generateQueryRewrites(String query, String context) {
        List<String> rewrites = new ArrayList<>();
        rewrites.add(query);

        try {
            String prompt = String.format("""
                    请为以下用户查询生成 2 个语义相似的改写版本，用于向量检索召回。

                    要求：
                    1. 不改变查询的核心意图。
                    2. 从不同角度表达同一问题（如同义词替换、句式变换、问法侧重不同）。
                    3. 改写要自然，像真实用户会问的问题。
                    4. 每行输出一个改写版本，不带序号、不带引号、不输出任何额外文字（如"好的""以下是"等）。

                    %s

                    用户查询：%s
                    """, context, query);

            String aiText = callAi("查询改写",
                    String.format("query=%s", query.substring(0, Math.min(30, query.length()))), prompt);
            if (aiText != null) {
                for (String line : aiText.split("\n")) {
                    line = line.trim();
                    if (!line.isBlank() && !line.equals(query)) {
                        rewrites.add(line);
                        if (rewrites.size() >= 3) break;
                    }
                }
            }
            log.debug("LLM 向量检索相似改写结果: {} -> {}", query, rewrites);
        } catch (Exception e) {
            log.warn("LLM 查询改写失败，使用原始查询: {}", e.getMessage());
        }

        return rewrites;
    }

    /** 向量搜索查询扩展：从多个维度推断用户真正的阅读需求，生成多组检索关键词 */
    public List<String> expandVectorSearchQuery(String query) {
        try {
            String prompt = String.format("""
                    你是一个图书搜索查询扩展器。用户输入了口语化的搜索词，你的任务是推断用户真正的阅读需求，从多个维度生成检索关键词。

                    关键原则：不要改写或解释用户的原话，而是思考——一个有这种需求的人，真正需要读什么书？从哪些不同方向能找到能满足他的书？

                    规则：
                    1. 生成3-5个不同维度的关键词短语，每行一个
                    2. 每个短语2-8个字，简洁精准
                    3. 各关键词覆盖不同维度，有本质差异，避免同义重复
                    4. 关键词应是书籍标签、分类或简介中可能出现的短语
                    5. 只输出关键词，不要序号、引号或任何额外文字

                    用户查询：%s
                    """, query);

            String result = callAi("向量查询扩展",
                    String.format("q=%s", query.substring(0, Math.min(20, query.length()))), prompt);
            if (result != null) {
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
            log.warn("向量查询扩展失败，使用原始查询: {}", e.getMessage());
        }
        return List.of(query);
    }

    /** 生成 3 分钟速读摘要 */
    public BookSpeedReadVO generateSpeedRead(Book book) {
        return generateSpeedRead(book, null);
    }

    /** 生成 3 分钟速读摘要（含读者画像） */
    public BookSpeedReadVO generateSpeedRead(Book book, User user) {
        try {
            String bookContent = buildSpeedReadContent(book);
            String userProfileDesc = buildUserProfileDesc(user);

            String prompt;
            if (!userProfileDesc.isBlank()) {
                prompt = """
                        你是一位资深阅读顾问。请基于以下书籍信息，为特定读者生成一份「3分钟速读」摘要。
                        
                        【读者画像】
                        %s
                        
                        【书籍信息】
                        %s
                        
                        请严格按照以下JSON格式输出（不要输出其他内容）：
                        {
                          "corePoints": ["核心观点1", "核心观点2", "核心观点3"],
                          "suitableFor": ["适合人群1", "适合人群2"],
                          "notSuitableFor": ["不适合人群1", "不适合人群2"],
                          "takeaways": ["读完能收获什么1", "读完能收获什么2"],
                          "difficulty": "入门/中等/进阶"
                        }
                        
                        要求：
                        - corePoints: 3个最核心的观点或主题，每个不超过30字。请结合读者画像，突出与其最相关的内容。
                        - suitableFor: 2-3类最适合阅读的人群描述，请特别说明为什么适合这位读者（如果匹配的话）。
                        - notSuitableFor: 2-3类不适合阅读的人群描述。
                        - takeaways: 2-3个读完能获得的具体收获，请结合读者的职业和人生阶段给出个性化收获。
                        - difficulty: 根据内容深度和读者的背景判断阅读难度。
                        """.formatted(userProfileDesc, bookContent);
            } else {
                prompt = """
                        你是一位资深阅读顾问。请基于以下书籍信息，生成一份「3分钟速读」摘要，帮助读者快速判断这本书是否值得阅读。
                        
                        %s
                        
                        请严格按照以下JSON格式输出（不要输出其他内容）：
                        {
                          "corePoints": ["核心观点1", "核心观点2", "核心观点3"],
                          "suitableFor": ["适合人群1", "适合人群2"],
                          "notSuitableFor": ["不适合人群1", "不适合人群2"],
                          "takeaways": ["读完能收获什么1", "读完能收获什么2"],
                          "difficulty": "入门/中等/进阶"
                        }
                        
                        要求：
                        - corePoints: 3个最核心的观点或主题，每个不超过30字
                        - suitableFor: 2-3类最适合阅读的人群描述
                        - notSuitableFor: 2-3类不适合阅读的人群描述
                        - takeaways: 2-3个读完能获得的具体收获
                        - difficulty: 根据内容深度判断阅读难度
                        """.formatted(bookContent);
            }

            String aiText = callAi("3分钟速读",
                    String.format("bookId=%d, title=%s", book.getId(), book.getTitle()), prompt);
            aiText = stripCodeFence(aiText);
            if (aiText == null || aiText.isBlank()) {
                log.warn("AI 速读摘要为空: bookId={}", book.getId());
                return null;
            }

            BookSpeedReadVO vo = objectMapper.readValue(aiText, BookSpeedReadVO.class);
            vo.setBookId(book.getId());
            vo.setRawContent(aiText);

            return vo;
        } catch (Exception e) {
            log.warn("生成速读摘要失败: bookId={} - {}", book.getId(), e.getMessage());
            return null;
        }
    }

    // ================================================================
    // 公共工具方法（供其他服务使用，如流式速读）
    // ================================================================

    /** 构建速读摘要的书籍内容部分 */
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

    /** 流式生成 3 分钟速读摘要 */
    public void streamSpeedRead(Book book, User user, SseEmitter emitter) {
        try {
            StreamingChatModel model = chatModelFactory.buildStreamingChatModelWithoutThinkingFromYml();
            if (model == null) {
                SseHelper.sendErrorAndComplete(emitter, "AI 模型未配置，无法生成速读摘要");
                return;
            }

            String bookContent = buildSpeedReadContent(book);
            String userProfileDesc = buildUserProfileDesc(user);

            String prompt;
            if (!userProfileDesc.isBlank()) {
                prompt = """
                        你是一位资深阅读顾问。请基于以下书籍信息，为特定读者生成一份「3分钟速读」摘要。

                        【读者画像】
                        %s

                        【书籍信息】
                        %s

                        请严格按照以下格式输出，每个标题占一行，标题下的每条内容各占一行：

                        ### 核心观点
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

                        ### 难度
                        入门/中等/进阶

                        要求：
                        - 核心观点: 3个最核心的观点或主题，每个不超过30字。请结合读者画像，突出与其最相关的内容。
                        - 适合谁读: 2-3类最适合阅读的人群描述，请特别说明为什么适合这位读者（如果匹配的话）。
                        - 不适合谁读: 2-3类不适合阅读的人群描述。
                        - 读完能收获什么: 2-3个读完能获得的具体收获，请结合读者的职业和人生阶段给出个性化收获。
                        - 难度: 根据内容深度和读者的背景判断阅读难度，只输出"入门"、"中等"或"进阶"。
                        - 不要输出任何其他内容，不要使用Markdown加粗或列表符号。
                        - 每个标题占一行，标题下的每条内容各占一行
                        """.formatted(userProfileDesc, bookContent);
            } else {
                prompt = """
                        你是一位资深阅读顾问。请基于以下书籍信息，生成一份「3分钟速读」摘要，帮助读者快速判断这本书是否值得阅读。

                        %s

                        请严格按照以下格式输出，每个标题占一行，标题下的每条内容各占一行：

                        ### 核心观点
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

                        ### 难度
                        入门/中等/进阶

                        要求：
                        - 核心观点: 3个最核心的观点或主题，每个不超过30字
                        - 适合谁读: 2-3类最适合阅读的人群描述
                        - 不适合谁读: 2-3类不适合阅读的人群描述
                        - 读完能收获什么: 2-3个读完能获得的具体收获
                        - 难度: 根据内容深度判断阅读难度，只输出"入门"、"中等"或"进阶"
                        - 不要输出任何其他内容，不要使用Markdown加粗或列表符号。
                        - 每个标题占一行，标题下的每条内容各占一行
                        """.formatted(bookContent);
            }

            long startTime = System.currentTimeMillis();

            model.chat(
                    List.of(UserMessage.from(prompt)),
                    new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String partialResponse) {
                            if (Thread.currentThread().isInterrupted()) return;
                            if (partialResponse != null && !partialResponse.isEmpty()) {
                                if (!SseHelper.safeSendEvent(emitter, "message", partialResponse)) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse completeResponse) {
                            if (Thread.currentThread().isInterrupted()) return;
                            long elapsed = System.currentTimeMillis() - startTime;
                            int inputTokens = completeResponse.tokenUsage() != null && completeResponse.tokenUsage().inputTokenCount() != null
                                    ? completeResponse.tokenUsage().inputTokenCount() : 0;
                            int outputTokens = completeResponse.tokenUsage() != null && completeResponse.tokenUsage().outputTokenCount() != null
                                    ? completeResponse.tokenUsage().outputTokenCount() : 0;
                            CommonUtils.logAiCall("3分钟速读(流式)", elapsed, inputTokens, outputTokens,
                                    String.format("bookId=%d, title=%s", book.getId(), book.getTitle()));

                            try {
                                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                emitter.complete();
                            } catch (Exception ignored) {
                            }
                        }

                        @Override
                        public void onError(Throwable error) {
                            if (Thread.currentThread().isInterrupted()) return;
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

    /** 构建用户画像描述 */
    public static String buildUserProfileDesc(User user) {
        if (user == null) return "";
        StringBuilder profileBuilder = new StringBuilder();
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

    // ================================================================
    // 私有工具方法
    // ================================================================

    private static String stripCodeFence(String text) {
        if (text == null) return null;
        String result = text.trim();
        if (result.startsWith("```json")) {
            result = result.substring(7);
        } else if (result.startsWith("```")) {
            result = result.substring(3);
        }
        if (result.endsWith("```")) {
            result = result.substring(0, result.length() - 3);
        }
        return result.trim();
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
}
