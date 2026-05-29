package com.kbook.service;

import com.kbook.common.util.CommonUtils;
import com.kbook.config.ChatModelFactory;
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
    public void asyncGenerateQuestions(Long bookId) {
        log.info("开始异步生成图书预设问题: bookId={}", bookId);
        Book book = bookService.getBookById(bookId);
        if (book == null) return;

        try {
            String prompt = String.format(
                    """
                        你是一位资深阅读引导专家。请根据提供的图书信息，为读者生成20个可以向AI深入探讨本书的问题。
                        
                        要求：
                        1. 问题角度必须多样化且均衡分配，每个类别至少包含3个问题，整体覆盖：
                         - 核心主旨与深层思想
                         - 关键情节、冲突与转折（若为非虚构类，则为核心观点与论证逻辑）
                         - 主要人物（或研究对象）的性格、动机与成长
                         - 写作风格、叙事技巧与结构特色
                         - 对现实生活的启示、与读者的个人关联
                        2. 仔细阅读【简介】和【目录】，问题必须紧密结合书中的具体细节（如某一章节、某一事件或某一观点），避免空泛笼统。
                        3. 问题采用读者在阅读后自然产生的口吻，语气带有探索和讨论的意味，就像在参加一场深度读书会。
                        4. 问题要真正体现标签所指向的主题气质，但不要直接堆砌标签词汇。
                        5. 严格遵守输出格式：
                         - 只输出20个问题本身
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
                    book.getChapterSummary() != null ? book.getChapterSummary() : "暂无摘要"
            );

            ChatModel chatModel = chatModelFactory.buildChatModelWithoutThinkingFromYml();

            // 使用Future实现超时控制
            long startTime = System.currentTimeMillis();
            Future<ChatResponse> future = executor.submit(() -> chatModel.chat(List.of(UserMessage.from(prompt))));
            ChatResponse response;
            try {
                response = future.get(2, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new RuntimeException("AI调用超时（超过" + 2 + "分钟）", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("AI调用被中断", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("AI调用执行异常", e.getCause());
            }
            long elapsed = System.currentTimeMillis() - startTime;
            String aiText = response.aiMessage().text();

            int inputTokens = response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null
                    ? response.tokenUsage().inputTokenCount() : 0;
            int outputTokens = response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null
                    ? response.tokenUsage().outputTokenCount() : 0;

            if (aiText != null && !aiText.isBlank()) {
                List<String> generated = parseQuestions(aiText);
                if (!generated.isEmpty()) {
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
                    CommonUtils.logAiCall("生成预设问题", elapsed, inputTokens, outputTokens,
                            String.format("bookId=%d, questions=%d", bookId, toSave.size()));
                }
            } else {
                CommonUtils.logAiCall("生成预设问题", elapsed, inputTokens, outputTokens,
                        String.format("bookId=%d, questions=0(空)", bookId));
            }
        } catch (Exception e) {
            log.error("AI 生成预设任务失败: bookId={}, error={}", bookId, e.getMessage());
        }
    }

    /**
     * 解析 AI 返回的文本，提取问题列表
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
