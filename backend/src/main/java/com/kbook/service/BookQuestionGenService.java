package com.kbook.service;

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
import java.util.concurrent.TimeUnit;
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
    private final AiProviderConfigService aiProviderConfigService;
    private final BookSuggestedQuestionRepository suggestedQuestionRepository;

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
                            请根据以下图书信息，生成20个适合读者向AI提问的问题。
                            要求：
                            1. 问题多样化，涵盖主旨、人物、情节、写作风格、现实启示等角度。
                            2. 仅输出问题列表，每行一个问题，不要带序号或其他多余文字。
                            3. 问题语言自然，具有启发性，紧密结合书籍的标签主题。
                            
                            书名：《%s》
                            作者：%s
                            标签：%s
                            简介：%s
                            目录：%s""",
                    book.getTitle(),
                    book.getAuthor() != null ? book.getAuthor() : "未知",
                    book.getFormatTags() != null ? book.getFormatTags() : "暂无标签",
                    book.getDescription() != null ? book.getDescription() : "暂无简介",
                    book.getToc() != null ? book.getToc() : "暂无目录"
            );

            ChatModel chatModel = aiProviderConfigService.buildTagChatModel();
            ChatResponse response = chatModel.chat(List.of(UserMessage.from(prompt)));
            String aiText = response.aiMessage().text();

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
                }
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
