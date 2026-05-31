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
    public void updateBookDescription() {
        // 1. 从数据库中筛选简介小于100字的图书
        List<Book> allBooks = bookRepository.findAll();
        List<Book> shortDescBooks = allBooks.stream()
                .filter(book -> book.getDescription() == null || book.getDescription().length() < 50)
                .toList();

        System.out.println("找到 " + shortDescBooks.size() + " 本简介小于100字的图书");

        int successCount = 0;
        int failCount = 0;

        // 2. 遍历每本书，重新生成AI数据并更新ES和向量
        for (int i = 0; i < shortDescBooks.size(); i++) {
            Book book = shortDescBooks.get(i);
            try {
                System.out.println("处理第 " + (i + 1) + "/" + shortDescBooks.size() + " 本: [" + book.getId() + "] " + book.getTitle());

                // 3. 使用 BookParserService.generateAllAiData 重新生成书籍信息（包括简介）
                bookParserService.generateAllAiData(book);

                // 4. 保存更新后的书籍信息到数据库
                bookService.updateBook(book.getId(), book);

                // 5. 更新 Elasticsearch 索引
                bookSearchService.indexBook(book);

                // 6. 重建基础信息向量
                embeddingService.removeBookEmbedding(book.getId());
                embeddingService.upsertBookEmbedding(book);

                successCount++;
                System.out.println("  ✓ 成功更新: " + book.getTitle() + ", 简介长度: " +
                        (book.getDescription() != null ? book.getDescription().length() : 0));

            } catch (Exception e) {
                failCount++;
                System.err.println("  ✗ 失败: " + book.getTitle() + " - " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("\n========== 处理完成 ==========");
        System.out.println("总数: " + shortDescBooks.size());
        System.out.println("成功: " + successCount);
        System.out.println("失败: " + failCount);
    }

    @Test
    public void getTags() {
//        List<Book> all = bookRepository.findByRatingGreaterThan(3.0);
        List<Book> all = bookRepository.findAll();

        Map<String, Long> tagCount = new HashMap<>();

        for (Book book : all) {
            if (book.getFormatTags() == null || book.getFormatTags().isBlank()) continue;
            // 移除 JSON 数组符号和引号: ["a","b"] -> a,b
            String tags = book.getFormatTags().replaceAll("[\\[\\]\"]", "");
            for (String tag : tags.split("[,，]")) {
                String t = tag.trim();
                if (!t.isEmpty()) {
                    tagCount.merge(t, 1L, Long::sum);
                }
            }
        }

        List<String> list = tagCount.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .limit(2000)
//                .filter(tagStat -> tagStat.getCount() > 5)
                .toList();

        System.out.println(JSONArray.toJSONString(list));
    }

    @Test
    public void deleteBookById() {
        bookService.deleteBook(1901L);

    }
    @Test
    public void updateBookBaseInfo() {
        int rl = "{'0-9':0.5,'10-19':0.5,'20-29':0.5,'30-39':0.5,'40-49':0.5,'50-59':0.5,'60+':0.5,'male':0.5,'female':0.5,'married':0.5,'unmarried':0.5,'children_0_2':0.5,'children_3_6':0.5,'children_7_12':0.5,'children_13_17':0.5,'children_18_plus':0.5,'no_children':0.5,'INTJ':0.5,'INTP':0.5,'ENTJ':0.5,'ENTP':0.5,'INFJ':0.5,'INFP':0.5,'ENFJ':0.5,'ENFP':0.5,'ISTJ':0.5,'ISFJ':0.5,'ESTJ':0.5,'ESFJ':0.5,'ISTP':0.5,'ISFP':0.5,'ESTP':0.5,'ESFP':0.5,'student':0.5,'tech':0.5,'finance':0.5,'education':0.5,'medical':0.5,'arts':0.5,'management':0.5,'freelance':0.5,'retired':0.5,'other':0.5,'high_school':0.5,'college':0.5,'bachelor':0.5,'master':0.5,'doctorate':0.5,'other_edu':0.5,'entrepreneur_or_want':0.5,'notInterested':0.5,'under_50k':0.5,'50k_150k':0.5,'150k_300k':0.5,'300k_500k':0.5,'500k_1m':0.5,'over_1m':0.5,'prefer_not_to_say':0.5,'happy':0.5,'calm':0.5,'anxious':0.5,'sad':0.5,'frustrated':0.5,'tired':0.5,'growth':0.5,'comfort':0.5,'escape':0.5,'excite':0.5,'insight':0.5}".length();
        List<Book> allBooks = bookRepository.findAllByOrderByRatingDesc();
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

                    boolean needAi = false;
                    if (book.getRelevanceScores() == null || book.getRelevanceScores().isBlank()) {
                        needAi = true;
                    } else if (book.getRelevanceScores().length() < rl) {
                        needAi = true;
                    }
                    if (!needAi) {
                        skippedCount.incrementAndGet();
                        return;
                    }

                    bookParserService.parseAndFill(book, filePath);
                    bookParserService.finalizeCover(book);
                    bookParserService.generateAllAiData(book);
                    bookService.updateBookAll(book.getId(), book);

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
}
