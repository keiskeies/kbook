package com.kbook.service.ai;
import com.kbook.service.book.BookService;

import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.config.annotation.RedisLock;
import com.kbook.constants.AiPromptConstants;
import com.kbook.entity.AiScene;
import com.kbook.entity.Book;
import com.kbook.entity.BookSuggestedQuestion;
import com.kbook.repository.BookSuggestedQuestionRepository;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
@LogModule("图书问题生成")
public class BookQuestionGenService {

    private final BookService bookService;
    private final ChatModelManager chatModelManager;
    private final BookSuggestedQuestionRepository suggestedQuestionRepository;
    private final ExecutorService sseExecutor;

    public BookQuestionGenService(
            BookService bookService,
            ChatModelManager chatModelManager,
            BookSuggestedQuestionRepository suggestedQuestionRepository,
            @Qualifier("sseExecutor") ExecutorService sseExecutor) {
        this.bookService = bookService;
        this.chatModelManager = chatModelManager;
        this.suggestedQuestionRepository = suggestedQuestionRepository;
        this.sseExecutor = sseExecutor;
    }

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
            String summary = bookService.resolveBookSummary(book);

            // 构建用户消息（动态图书信息）
            String userPrompt = String.format("""
                    书名：《%s》
                    作者：%s
                    标签：%s
                    简介：%s
                    目录：%s
                    摘要：%s""",
                    book.getTitle(),
                    book.getAuthor() != null ? book.getAuthor() : "未知",
                    book.getFormatTags() != null ? book.getFormatTags() : "暂无标签",
                    book.getDescription() != null ? book.getDescription() : "暂无简介",
                    book.getToc() != null ? book.getToc() : "暂无目录",
                    summary != null ? summary : "暂无摘要");

            List<ChatMessage> messages = List.of(
                    SystemMessage.from(AiPromptConstants.BOOK_QUESTION_GEN_SYSTEM_PROMPT),
                    UserMessage.from(userPrompt));

            // 使用 Future 实现超时控制，防止 AI 调用阻塞线程
            Future<String> future = sseExecutor.submit(() ->
                    chatModelManager.callAiForScene(AiScene.PRESET_QUESTION, "生成预设问题",
                            String.format("bookId=%d", bookId),
                            messages));

            String aiText;
            try {
                // 设置 2 分钟超时
                aiText = future.get(2, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new RuntimeException("AI调用超时（超过2分钟）", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("AI调用被中断", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("AI调用执行异常", e.getCause());
            }

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
                }
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
