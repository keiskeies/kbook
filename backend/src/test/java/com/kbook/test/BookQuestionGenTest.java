package com.kbook.test;

import com.kbook.common.util.CommonUtils;
import com.kbook.entity.Book;
import com.kbook.entity.BookSuggestedQuestion;
import com.kbook.repository.BookRepository;
import com.kbook.repository.BookSuggestedQuestionRepository;
import com.kbook.service.AiProviderConfigService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@SpringBootTest
@ActiveProfiles("test")
public class BookQuestionGenTest {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private AiProviderConfigService aiProviderConfigService;

    @Autowired
    private BookSuggestedQuestionRepository suggestedQuestionRepository;

    @Test
    public void generateQuestionsForAllBooks() {
        ChatModel chatModel = aiProviderConfigService.buildTagChatModel();
        if (chatModel == null) {
            System.err.println("AI 模型不可用，跳过测试");
            return;
        }

        List<Book> allBooks = bookRepository.findAll();
        System.out.println("共 " + allBooks.size() + " 本图书需要生成预设问题");

        int successCount = 0;
        int skipCount = 0;
        int failCount = 0;
        int totalAiCalls = 0;
        long totalElapsed = 0;
        int totalInputTokens = 0;
        int totalOutputTokens = 0;

        for (int i = 0; i < allBooks.size(); i++) {
            Book book = allBooks.get(i);
            System.out.println("\n处理第 " + (i + 1) + "/" + allBooks.size() + " 本: [" + book.getId() + "] " + book.getTitle());

            List<BookSuggestedQuestion> existing = suggestedQuestionRepository.findByBookId(book.getId());
            if (!existing.isEmpty()) {
                skipCount++;
                System.out.println("  已有 " + existing.size() + " 个预设问题，跳过");
                for (BookSuggestedQuestion sq : existing) {
                    System.out.println("    - " + sq.getQuestion());
                }
                continue;
            }

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
                        目录：%s
                        
                        """,
                        book.getTitle(),
                        book.getAuthor() != null ? book.getAuthor() : "未知",
                        book.getFormatTags() != null ? book.getFormatTags() : "暂无标签",
                        book.getDescription() != null ? book.getDescription() : "暂无简介",
                        book.getToc() != null ? book.getToc() : "暂无目录"
                );

                long startTime = System.currentTimeMillis();
                ChatResponse response = chatModel.chat(List.of(UserMessage.from(prompt)));
                long elapsed = System.currentTimeMillis() - startTime;

                String aiText = response.aiMessage().text();

                int apiInputTokens = response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null
                        ? response.tokenUsage().inputTokenCount() : 0;
                int apiOutputTokens = response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null
                        ? response.tokenUsage().outputTokenCount() : 0;

                System.out.println("  AI 原始返回:");
                System.out.println("  " + aiText);
                System.out.println("  ---------- AI 调用日志 ----------");
                System.out.println("  耗时: " + elapsed + "ms");
                System.out.println("  API token: 输入=" + apiInputTokens + ", 输出=" + apiOutputTokens + ", 总=" + (apiInputTokens + apiOutputTokens));
                System.out.println("  --------------------------------");

                if (aiText != null && !aiText.isBlank()) {
                    List<String> generated = parseQuestions(aiText);
                    if (!generated.isEmpty()) {
                        List<BookSuggestedQuestion> toSave = generated.stream()
                                .map(q -> {
                                    BookSuggestedQuestion sq = new BookSuggestedQuestion();
                                    sq.setBookId(book.getId());
                                    sq.setQuestion(q);
                                    return sq;
                                })
                                .collect(Collectors.toList());
                        suggestedQuestionRepository.saveAll(toSave);

                        System.out.println("  生成并保存 " + toSave.size() + " 个预设问题:");
                        for (int j = 0; j < toSave.size(); j++) {
                            System.out.println("    " + (j + 1) + ". " + toSave.get(j).getQuestion());
                        }

                        successCount++;
                        totalAiCalls++;
                        totalElapsed += elapsed;
                        totalInputTokens += apiInputTokens;
                        totalOutputTokens += apiOutputTokens;

                        CommonUtils.logAiCall("生成预设问题", elapsed, apiInputTokens, apiOutputTokens,
                                "[" + book.getId() + "] " + book.getTitle() + " | 生成 " + toSave.size() + " 个问题");
                    } else {
                        failCount++;
                        System.err.println("  ✗ AI 返回内容无法解析为问题列表");
                    }
                } else {
                    failCount++;
                    System.err.println("  ✗ AI 返回内容为空");
                }

            } catch (Exception e) {
                failCount++;
                System.err.println("  ✗ 失败: " + book.getTitle() + " - " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("\n========== 全部处理完成 ==========");
        System.out.println("总数: " + allBooks.size());
        System.out.println("成功生成: " + successCount);
        System.out.println("已有跳过: " + skipCount);
        System.out.println("失败: " + failCount);
        if (totalAiCalls > 0) {
            System.out.println("AI 调用统计: " + totalAiCalls + " 次, 总耗时 " + totalElapsed + "ms, "
                    + "平均 " + (totalElapsed / totalAiCalls) + "ms/次, "
                    + "总输入 " + totalInputTokens + " tokens, 总输出 " + totalOutputTokens + " tokens");
        }
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
