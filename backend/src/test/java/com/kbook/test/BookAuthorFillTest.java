package com.kbook.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.config.ChatModelFactory;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.book.BookSearchService;
import com.kbook.service.embedding.EmbeddingService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 图书作者信息补全测试
 * <p>
 * 针对书名中可能包含作者信息的图书，使用 LLM 提取作者并清理书名。
 * 典型场景：书名为 "图书名称-作者" 或 "图书名称_作者" 格式。
 * <p>
 * 两个测试方法：
 * - previewOnly(): 只列出待处理图书，不调用 LLM
 * - fillAllMissingAuthors(): 调用 LLM 处理，dryRun=true 只预览不保存，dryRun=false 保存到数据库
 */
@SpringBootTest
@ActiveProfiles("dev")
public class BookAuthorFillTest {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private ChatModelFactory chatModelFactory;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BookSearchService bookSearchService;

    @Autowired
    private EmbeddingService embeddingService;

    /** true=只预览不保存, false=保存到数据库 */
    private static final boolean DRY_RUN = false;

    private static final String SYSTEM_PROMPT = """
            你是图书元数据助手。从书名字段中提取作者信息并清理书名。
            书名常见格式：
            - "书名-作者" 或 "书名_作者"
            - "书名(作者)" 或 "书名（作者）"
            - "书名·作者"
            - "作者-书名"（作者在前）
            
            规则：
            1. 从书名中分离出干净的书名和作者
            2. 如果作者字段已有值，以书名中提取的为准（书名中的更原始）
            3. 如果书名中没有作者信息，author 返回 null
            4. 不要凭空推断作者
            5. 只返回 JSON，不要其他文字
            """;

    /**
     * 批量处理书名中包含作者信息的图书（补全作者 + 清理书名）
     * DRY_RUN=true 时只打印 LLM 处理结果，不保存数据库
     */
    @Test
    public void fillAllMissingAuthors() {
        List<Book> allBooks = bookRepository.findAll();

        List<Book> booksToProcess = allBooks.stream()
                .filter(book -> book.getTitle() != null
                        && book.getTitle().matches(".*[-_·()（）].*"))
                .toList();

        System.out.printf("总图书数: %d, 书名含分隔符: %d, 模式: %s%n%n",
                allBooks.size(), booksToProcess.size(), DRY_RUN ? "预览(不保存)" : "正式(保存)");

        if (booksToProcess.isEmpty()) {
            System.out.println("没有需要处理的图书");
            return;
        }

        long totalStartTime = System.currentTimeMillis();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(booksToProcess.size());

        for (Book book : booksToProcess) {
            executor.submit(() -> {
                try {
                    String originalTitle = book.getTitle();
                    String originalAuthor = book.getAuthor();

                    boolean updated = fillAuthorForBook(book);

                    if (updated) {
                        successCount.incrementAndGet();
                        System.out.printf("  ✓ id=%d 《%s》→《%s》 作者: %s→%s%n",
                                book.getId(), originalTitle, book.getTitle(),
                                originalAuthor == null ? "(空)" : originalAuthor,
                                book.getAuthor());

                        if (!DRY_RUN) {
                            bookRepository.save(book);
                            bookSearchService.indexBook(book);
//                            embeddingService.removeBookEmbedding(book.getId());
//                            embeddingService.upsertBookEmbedding(book);
                        }
                    } else {
                        skippedCount.incrementAndGet();
                        System.out.printf("  ⊘ id=%d 《%s》 无变化%n", book.getId(), originalTitle);
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.out.printf("  ✗ id=%d 《%s》 - %s%n", book.getId(), book.getTitle(), e.getMessage());
                } finally {
                    completedCount.incrementAndGet();
                    latch.countDown();
                }
            });
        }

        // 进度监控
        Thread progressThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                }
                int done = completedCount.get();
                if (done >= booksToProcess.size()) break;
                long elapsedMs = System.currentTimeMillis() - totalStartTime;
                int processed = successCount.get() + failCount.get();
                double avgMs = processed > 0 ? (double) elapsedMs / processed : 0;
                int remaining = booksToProcess.size() - done;
                double estMin = remaining * avgMs / 60000.0;
                System.out.printf("[进度] %d/%d | 成功=%d 失败=%d 跳过=%d | 预计剩余=%.1f分钟%n",
                        done, booksToProcess.size(),
                        successCount.get(), failCount.get(), skippedCount.get(), estMin);
            }
        });
        progressThread.setDaemon(true);
        progressThread.start();

        try {
            latch.await();
            executor.shutdown();
            progressThread.interrupt();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long totalMs = System.currentTimeMillis() - totalStartTime;
        System.out.printf("%n========== 处理完成 ==========%n");
        System.out.printf("总数: %d, 成功: %d, 失败: %d, 跳过: %d, 耗时: %.1f分钟%n",
                booksToProcess.size(), successCount.get(), failCount.get(), skippedCount.get(), totalMs / 60000.0);
        if (DRY_RUN) {
            System.out.println("（预览模式，未保存到数据库）");
        }
    }

    /**
     * 预览模式：只列出待处理图书，不调用 LLM
     */
    @Test
    public void previewOnly() {
        List<Book> allBooks = bookRepository.findAll();
        List<Book> booksToProcess = allBooks.stream()
                .filter(book -> book.getTitle() != null
                        && book.getTitle().matches(".*[-_·()（）].*"))
                .toList();

        System.out.printf("=== 书名含分隔符的图书: %d 本 ===%n", booksToProcess.size());
        for (Book b : booksToProcess) {
            System.out.printf("  id=%d 《%s》 author=%s format=%s%n",
                    b.getId(), b.getTitle(), b.getAuthor(), b.getFormat());
        }
    }

    private boolean fillAuthorForBook(Book book) throws Exception {
        String originalTitle = book.getTitle();
        String originalAuthor = book.getAuthor();

        ChatModel model = chatModelFactory.buildToolChatModel();
        ChatResponse response = model.chat(List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(buildPrompt(book))
        ));

        String aiResponse = response.aiMessage().text();
        if (aiResponse == null || aiResponse.isBlank()) return false;

        // 提取 JSON（兼容 markdown 代码块包裹）
        String jsonStr = aiResponse.trim();
        if (jsonStr.contains("```")) {
            int start = jsonStr.indexOf('{');
            int end = jsonStr.lastIndexOf('}');
            if (start >= 0 && end > start) {
                jsonStr = jsonStr.substring(start, end + 1);
            }
        }

        JsonNode json = objectMapper.readTree(jsonStr);
        String cleanedTitle = json.has("title") ? json.get("title").asText().trim() : null;
        String cleanedAuthor = json.has("author") && !json.get("author").isNull()
                ? json.get("author").asText().trim() : null;

        boolean titleChanged = cleanedTitle != null && !cleanedTitle.isEmpty() && !cleanedTitle.equals(originalTitle);
        boolean authorChanged = cleanedAuthor != null && !cleanedAuthor.isEmpty()
                && !cleanedAuthor.equals(originalAuthor == null ? "" : originalAuthor);

        if (!titleChanged && !authorChanged) {
            return false;
        }

        if (titleChanged) {
            book.setTitle(cleanedTitle);
        }
        if (authorChanged) {
            book.setAuthor(cleanedAuthor);
        }

        return true;
    }

    private String buildPrompt(Book book) {
        StringBuilder sb = new StringBuilder();
        sb.append("书名字段: ").append(book.getTitle()).append("\n");
        sb.append("作者字段: ").append(book.getAuthor() != null && !book.getAuthor().isBlank()
                ? book.getAuthor() : "(空)").append("\n");
        sb.append("格式: ").append(book.getFormat()).append("\n");

        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            String desc = book.getDescription();
            sb.append("简介: ").append(desc).append("\n");
        }

        sb.append("\n请从书名字段中提取作者和干净书名，返回 JSON: {\"title\": \"...\", \"author\": \"...\"}\n");
        sb.append("如果书名中没有作者信息，author 返回 null。");
        return sb.toString();
    }
}
