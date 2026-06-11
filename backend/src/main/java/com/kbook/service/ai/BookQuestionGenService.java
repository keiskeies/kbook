package com.kbook.service.ai;
import com.kbook.service.book.BookService;

import com.kbook.common.util.CommonUtils;
import com.kbook.config.ChatModelFactory;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.config.annotation.RedisLock;
import com.kbook.entity.Book;
import com.kbook.entity.BookSuggestedQuestion;
import com.kbook.repository.BookSuggestedQuestionRepository;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 图书预设问题生成服务
 * 独立拆分出来以避免 BookChatService 的自引用循环依赖
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogModule("图书问题生成")
public class BookQuestionGenService {

    private final BookService bookService;
    private final ChatModelFactory chatModelFactory;
    private final BookSuggestedQuestionRepository suggestedQuestionRepository;

    // 创建线程池用于超时控制
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 异步生成预设问题
     * 使用 @RedisLock 确保同一本书同一时间只有一个线程在生成。
     * 如果锁被占用（说明已有用户在触发 AI 生成），则直接跳过。
     */
    @Async
    @RedisLock(key = "'book:suggest:gen:' + #bookId", leaseTime = 10, timeUnit = TimeUnit.MINUTES)
    @LogAction("异步生成预设问题")
    public void asyncGenerateQuestions(Long bookId) {
        log.info("开始异步生成图书预设问题: bookId={}", bookId);
        // 获取书籍信息
        Book book = bookService.getBookById(bookId);
        if (book == null) return;

        try {
            // 构建提示词，要求 AI 生成 20 个多样化问题
            String summary = bookService.resolveBookSummary(book);
            String prompt = String.format(
                    """
                        你是一位资深阅读引导专家。请根据提供的图书信息，生成20个可以向AI深入探讨本书的问题。
                        
                        要求：
                        1. 问题角度多样化且均衡，必须覆盖以下五个维度，每个维度至少3个问题：
                           - 核心命题与深层思辨：书籍究竟想回答什么问题？其背后的思想根基或理论野心何在？
                           - 关键节点与论证骨架：若是虚构叙事，指向情节冲突、转折与决定性场景；若是非虚构，指向核心观点、关键论据、实验或逻辑转折点。
                           - 核心行动元与视角演变：虚构作品中主要人物的欲望、选择与成长；非虚构中则指研究对象的特质、作者立场的变化，或书中关键思想家、案例主体的行动逻辑。
                           - 表达技艺与结构设计：叙事视角、语言质感、章节编排的用意；对非虚构而言，还包括论证策略、跨学科方法、数据呈现方式等。
                           - 现实投射与个人映照：书中的洞见如何照进我们的日常生活、社会议题或个体决策，能与读者产生何种私人联结。
                        2. 仔细阅读【简介】【目录】和【摘要】，每个问题必须明确指向某个章节标题、简介中的具体概念，或摘要里出现的专有名词/事件，避免空泛笼统。
                        3. 问题采用读者在深度阅读后自然发生的口吻，带有探索、质疑或联想意味，仿佛在参加一场高质量的对谈。即便讨论理论，也保持对话感和好奇心，拒绝考试式发问。
                        4. 问题应体现【标签】所暗示的领域气质（如哲学则重概念辨析，科技则重机制与影响，历史则重史料与叙事），但严禁直接堆砌标签词语。
                        5. 严格遵守输出格式：
                           - 只输出20个问题本身
                           - 每个问题不超过50个汉字
                           - 一行一个问题
                           - 不加任何序号、项目符号、空行或解释性文字
                           - 不要出现“问题1”“以下是……问题”等前缀
                        
                        图书信息：
                        书名：《%s》
                        作者：%s
                        标签：%s
                        简介：%s
                        目录：%s
                        摘要: %s
                        
                        """,
                    book.getTitle(),
                    book.getAuthor() != null ? book.getAuthor() : "未知",
                    book.getFormatTags() != null ? book.getFormatTags() : "暂无标签",
                    book.getDescription() != null ? book.getDescription() : "暂无简介",
                    book.getToc() != null ? book.getToc() : "暂无目录",
                    summary != null ? summary : "暂无摘要"
            );

            // 获取 ChatModel 实例
            ChatModel chatModel = chatModelFactory.buildChatModelWithoutThinkingFromYml();

            // 使用 Future 实现超时控制，防止 AI 调用阻塞线程
            long startTime = System.currentTimeMillis();
            Future<ChatResponse> future = executor.submit(() -> chatModel.chat(List.of(UserMessage.from(prompt))));
            ChatResponse response;
            try {
                // 设置 2 分钟超时
                response = future.get(2, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                // 超时则取消任务
                future.cancel(true);
                throw new RuntimeException("AI调用超时（超过" + 2 + "分钟）", e);
            } catch (InterruptedException e) {
                // 中断时恢复中断状态
                Thread.currentThread().interrupt();
                throw new RuntimeException("AI调用被中断", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("AI调用执行异常", e.getCause());
            }
            long elapsed = System.currentTimeMillis() - startTime;
            // 获取 AI 响应文本
            String aiText = response.aiMessage().text();

            int inputTokens = response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null
                    ? response.tokenUsage().inputTokenCount() : 0;
            int outputTokens = response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null
                    ? response.tokenUsage().outputTokenCount() : 0;

            // 解析 AI 响应，提取问题列表
            if (aiText != null && !aiText.isBlank()) {
                List<String> generated = parseQuestions(aiText);
                if (!generated.isEmpty()) {
                    // 将问题列表转换为实体并批量保存
                    List<BookSuggestedQuestion> toSave = generated.stream()
                            .map(q -> {
                                BookSuggestedQuestion sq = new BookSuggestedQuestion();
                                sq.setBookId(bookId);
                                sq.setQuestion(q);
                                return sq;
                            })
                            .collect(Collectors.toList());
                    suggestedQuestionRepository.saveAll(toSave);
                    log.info("AI 生成并保存预设问题成功: bookId={}, count={}", bookId, toSave.size());
                    // 记录 AI 调用日志
                    CommonUtils.logAiCall("生成预设问题", elapsed, inputTokens, outputTokens,
                            String.format("bookId=%d, questions=%d", bookId, toSave.size()));
                }
            } else {
                // AI 响应为空，记录日志
                CommonUtils.logAiCall("生成预设问题", elapsed, inputTokens, outputTokens,
                        String.format("bookId=%d, questions=0(空)", bookId));
            }
        } catch (Exception e) {
            log.error("AI 生成预设任务失败: bookId={}, error={}", bookId, e.getMessage());
        }
    }

    /**
     * 解析 AI 返回的文本，提取问题列表。
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
}
