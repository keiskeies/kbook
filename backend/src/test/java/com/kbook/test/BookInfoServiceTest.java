package com.kbook.test;

import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.BookParserService;
import com.kbook.service.BookService;
import net.minidev.json.JSONArray;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.kbook.constants.AiPromptConstants;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@ActiveProfiles("test")
public class BookInfoServiceTest {

    @Autowired
    private BookService bookService;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private BookParserService bookParserService;

    @Autowired
    private com.kbook.service.BookSearchService bookSearchService;

    @Autowired
    private com.kbook.service.EmbeddingService embeddingService;


    @Test
    public void updateBookBaseInfo() {
        List<Book> allBooks0 = bookRepository.findAllByOrderByRatingDesc();
        List<Book> allBooks = allBooks0.stream().filter(book -> null == book.getReaderNeedTags() || book.getConceptTags() == null || book.getTargetReaderTags() == null).toList();
        System.out.println("共 " + allBooks.size() + " 本图书需要处理");

        long totalStartTime = System.currentTimeMillis();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);

        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(allBooks.size());

        Thread progressThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                }
                int done = completedCount.get();
                int ok = successCount.get();
                int fail = failCount.get();
                int skip = skippedCount.get();
                long elapsedMs = System.currentTimeMillis() - totalStartTime;
                double elapsedMin = elapsedMs / 60000.0;
                int processed = ok + fail;
                double avgMs = processed > 0 ? (double) elapsedMs / processed : 0;
                int remaining = allBooks.size() - done;
                double estMin = remaining * avgMs / 60000.0;
                java.time.LocalDateTime finishTime = java.time.LocalDateTime.now().plusSeconds((long)(estMin * 60));
                System.out.printf("[进度] %d/%d (%.1f%%) | 成功=%d 失败=%d 跳过=%d | 已用=%.1f分钟 | 预计剩余=%.1f分钟 | 预计完成=%s%n",
                        done, allBooks.size(), done * 100.0 / allBooks.size(),
                        ok, fail, skip, elapsedMin, estMin,
                        finishTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
            }
        });
        progressThread.setDaemon(true);
        progressThread.start();

        for (int i = 0; i < allBooks.size(); i++) {
            final int index = i;
            final Book book = allBooks.get(i);
            executor.submit(() -> {
                try {
                    if (book.getFileUrl() == null || book.getFileUrl().isBlank()) {
                        skippedCount.incrementAndGet();
                        return;
                    }

                    Path filePath = Path.of(book.getFileUrl());
                    if (!java.nio.file.Files.exists(filePath)) {
                        skippedCount.incrementAndGet();
                        return;
                    }
                    bookParserService.parseAndFill(book, filePath);
                    bookParserService.finalizeCover(book);
                    //bookParserService.generateAllAiData(book);
                    // 将请求文字写入项目根目录下的questions目录
                    try {
                        String content = book.getParsedContent();
                        if (content == null || content.isBlank()) {
                            java.lang.reflect.Method method = bookParserService.getClass()
                                    .getDeclaredMethod("extractContentForTags", Book.class);
                            method.setAccessible(true);
                            content = (String) method.invoke(bookParserService, book);
                        }
                        if (content != null && !content.isBlank()) {
                            String prompt = AiPromptConstants.COMBINED_PROMPT_SYSTEM_PROMPT;
                            String safeTitle = book.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_");
                            String filename = book.getId() + "_" + safeTitle + ".txt";
                            java.nio.file.Path questionsDir = java.nio.file.Paths.get("..", "questions");
                            java.nio.file.Files.createDirectories(questionsDir);
                            String fileContent = "问题:\n1. 提示词:" + prompt + "\n2. 问题内容:" + content + "\n回答:\n";
                            java.nio.file.Files.writeString(questionsDir.resolve(filename), fileContent, java.nio.charset.StandardCharsets.UTF_8);
                        }
                    } catch (Exception e) {
                        System.err.printf("  ✗ [%d] 写入请求文件失败: id=%d %s — %s%n", index + 1, book.getId(), book.getTitle(), e.getMessage());
                    }
//                    bookService.updateBookAll(book.getId(), book);

                    successCount.incrementAndGet();
                    System.out.printf("  ✓ [%d] id=%d %s%n", index + 1, book.getId(), book.getTitle());

                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.err.printf("  ✗ [%d] id=%d %s — %s%n", index + 1, book.getId(), book.getTitle(), e.getMessage());
                } finally {
                    completedCount.incrementAndGet();
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
        progressThread.interrupt();

        long totalElapsedMs = System.currentTimeMillis() - totalStartTime;
        double totalElapsedMin = totalElapsedMs / 60000.0;
        int processed = successCount.get() + failCount.get();
        double avgMs = processed > 0 ? (double) totalElapsedMs / processed : 0;

        System.out.println("\n========== 处理完成 ==========");
        System.out.println("总数: " + allBooks.size());
        System.out.println("成功: " + successCount.get());
        System.out.println("失败: " + failCount.get());
        System.out.println("跳过: " + skippedCount.get());
        System.out.printf("总耗时: %.2f分钟%n", totalElapsedMin);
        System.out.printf("平均每本: %.2f秒 (仅计算实际处理的)%n", avgMs / 1000.0);
    }






    @Test
    public void updateBookBaseInfo_new() {
        List<Book> allBooks0 = bookRepository.findAllByOrderByRatingDesc();
        List<Book> allBooks = allBooks0.stream().filter(book -> null == book.getReaderNeedTags() || book.getConceptTags() == null || book.getTargetReaderTags() == null).toList();
        System.out.println("共 " + allBooks.size() + " 本图书需要处理");

        long totalStartTime = System.currentTimeMillis();
        int successCount = 0;
        int failCount = 0;
        int skippedCount = 0;
        int completedCount =0;


        for (int i = 0; i < allBooks.size(); i++) {
            final Book book = allBooks.get(i);
                try {
                    if (book.getFileUrl() == null || book.getFileUrl().isBlank()) {
                        skippedCount ++;
                        continue;
                    }

                    Path filePath = Path.of(book.getFileUrl());
                    if (!java.nio.file.Files.exists(filePath)) {
                        skippedCount ++;
                        continue;
                    }
                    String safeTitle = book.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_");
                    String filename = book.getId() + "_" + safeTitle + ".txt";
                    if (java.nio.file.Files.exists(java.nio.file.Paths.get("..", "questions", filename))) {
                        skippedCount ++;
                        continue;
                    }

                    bookParserService.parseAndFill(book, filePath);
                    bookParserService.finalizeCover(book);
                    //bookParserService.generateAllAiData(book);
                    // 将请求文字写入项目根目录下的questions目录
                    try {
                        String content = book.getParsedContent();
                        if (content == null || content.isBlank()) {
                            java.lang.reflect.Method method = bookParserService.getClass()
                                    .getDeclaredMethod("extractContentForTags", Book.class);
                            method.setAccessible(true);
                            content = (String) method.invoke(bookParserService, book);
                        }
                        if (content != null && !content.isBlank()) {
                            String prompt = AiPromptConstants.COMBINED_PROMPT_SYSTEM_PROMPT;
                            java.nio.file.Path questionsDir = java.nio.file.Paths.get("..", "questions");
                            java.nio.file.Files.createDirectories(questionsDir);
                            String fileContent = "问题:\n1. 提示词:" + prompt + "\n2. 问题内容:" + content + "\n回答:\n";
                            java.nio.file.Files.writeString(questionsDir.resolve(filename), fileContent, java.nio.charset.StandardCharsets.UTF_8);
                        }
                    } catch (Exception e) {
                        System.err.printf("  ✗ [%d] 写入请求文件失败: id=%d %s — %s%n", i + 1, book.getId(), book.getTitle(), e.getMessage());
                    }
//                    bookService.updateBookAll(book.getId(), book);

                    successCount++;
                    System.out.printf("  ✓ [%d] id=%d %s%n", i + 1, book.getId(), book.getTitle());

                } catch (Exception e) {
                    failCount++;
                    System.err.printf("  ✗ [%d] id=%d %s — %s%n", i + 1, book.getId(), book.getTitle(), e.getMessage());
                } finally {
                    completedCount++;
                }
        }


        long totalElapsedMs = System.currentTimeMillis() - totalStartTime;
        double totalElapsedMin = totalElapsedMs / 60000.0;
        int processed = successCount + failCount;
        double avgMs = processed > 0 ? (double) totalElapsedMs / processed : 0;

        System.out.println("\n========== 处理完成 ==========");
        System.out.println("总数: " + allBooks.size());
        System.out.println("成功: " + successCount);
        System.out.println("失败: " + failCount);
        System.out.println("跳过: " + skippedCount);
        System.out.printf("总耗时: %.2f分钟%n", totalElapsedMin);
        System.out.printf("平均每本: %.2f秒 (仅计算实际处理的)%n", avgMs / 1000.0);
    }
}
