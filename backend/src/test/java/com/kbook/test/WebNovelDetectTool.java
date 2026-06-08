package com.kbook.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.config.ChatModelFactory;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@SpringBootTest
@ActiveProfiles("test")
public class WebNovelDetectTool {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private ChatModelFactory chatModelFactory;

    @Autowired
    private ObjectMapper objectMapper;

    private static final int THREAD_COUNT = 2;

    @Test
    public void detectWebNovels() {
        ChatModel chatModel = chatModelFactory.buildChatModelWithoutThinkingFromYml();
        if (chatModel == null) {
            System.err.println("AI 模型不可用，跳过测试");
            return;
        }

        List<Book> allBooks = bookRepository.findAll();
        System.out.println("共 " + allBooks.size() + " 本图书需要判断");

        String systemPrompt = """
                你是一个图书分类专家。请根据图书信息判断它是否为"网络小说"。
                
                网络小说的典型特征：
                - 在网络平台（起点中文网、晋江文学城、纵横中文网等）连载发布
                - 常见的网络小说类型：玄幻、修仙、穿越、重生、系统、同人、种田、后宫、都市异能、言情（古代/现代）、官场、架空历史、网游、轻小说、无限流、末日废土、科幻未来
                - 标题通常带有网络小说特色（如"XX之XX"、"XX传"、"XX记"、"重生XX"、"穿越XX"、"XX系统"等）
                - 长篇连载，章节众多，每章篇幅适中
                - 写作风格偏向通俗、爽文、节奏快
                
                非网络小说（传统出版物）的典型特征：
                - 传统文学、经典名著（如《红楼梦》、《百年孤独》等）
                - 严肃文学、纯文学、现实主义文学作品
                - 学术著作、教材、专业书籍
                - 传记、纪实文学、历史研究
                - 科普读物、技术书籍
                - 散文集、诗歌集
                
                只返回 JSON 格式，不要额外文字：
                {"isWebNovel": true} 或 {"isWebNovel": false}
                """;

        List<Long> webNovelIds = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger completed = new AtomicInteger(0);
        long totalStart = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(allBooks.size());

        for (Book book : allBooks) {
            executor.submit(() -> {
                try {
                    String userPrompt = String.format("""
                            图书信息：
                            【书名】：%s
                            【作者】：%s
                            【简介】：%s
                            【章节摘要】：%s
                            
                            请判断这本书是否为网络小说。
                            """,
                            book.getTitle() != null ? book.getTitle() : "",
                            book.getAuthor() != null ? book.getAuthor() : "",
                            book.getDescription() != null ? book.getDescription() : "",
                            book.getChapterSummary() != null ? book.getChapterSummary().substring(0, Math.min(15000, book.getChapterSummary().length())) : ""
                    );

                    ChatResponse response = chatModel.chat(List.of(
                            SystemMessage.from(systemPrompt),
                            UserMessage.from(userPrompt)
                    ));

                    String result = response.aiMessage().text().trim();
                    if (result.contains("```")) {
                        int start = result.indexOf('{');
                        int end = result.lastIndexOf('}');
                        if (start >= 0 && end > start) {
                            result = result.substring(start, end + 1);
                        }
                    }

                    boolean isWebNovel = objectMapper.readTree(result).path("isWebNovel").asBoolean(false);

                    int done = completed.incrementAndGet();
                    long elapsed = (System.currentTimeMillis() - totalStart) / 1000;
                    System.out.printf("[%d/%d %.1fs] %s (id=%d) → %s%n",
                            done, allBooks.size(), (double) elapsed,
                            book.getTitle(), book.getId(),
                            isWebNovel ? "网络小说" : "非网络小说");

                    if (isWebNovel) {
                        webNovelIds.add(book.getId());
                    }

                } catch (Exception e) {
                    int done = completed.incrementAndGet();
                    System.err.printf("[%d/%d] %s (id=%d) - 判断失败: %s%n",
                            done, allBooks.size(), book.getTitle(), book.getId(), e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdown();

        long totalSec = (System.currentTimeMillis() - totalStart) / 1000;
        System.out.printf("%n总耗时: %d分%d秒%n", totalSec / 60, totalSec % 60);

        if (webNovelIds.isEmpty()) {
            System.out.println("未检测到网络小说");
        } else {
            String idsStr = webNovelIds.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));
            System.out.println("网络小说 ID: " + idsStr);
        }
    }
}
