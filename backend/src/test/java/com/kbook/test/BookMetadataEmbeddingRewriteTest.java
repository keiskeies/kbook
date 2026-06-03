package com.kbook.test;

import com.kbook.config.ChatModelFactory;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.BookParserService;
import com.kbook.service.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@SpringBootTest
@ActiveProfiles("test")
public class BookMetadataEmbeddingRewriteTest {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private BookParserService bookParserService;

    @Autowired
    private ChatModelFactory chatModelFactory;

    @Test
    public void rewriteAllBookMetadataEmbeddings() {
        if (!embeddingService.isAvailable()) {
            System.err.println("Embedding 服务不可用，跳过测试");
            return;
        }

        List<Book> allBooks = bookRepository.findAll();
        long totalBooks = allBooks.size();
        System.out.println("========== 重写图书元数据向量 ==========");
        System.out.println("总图书数: " + totalBooks);

        int concurrency = chatModelFactory.getEmbeddingConcurrency();
        System.out.println("并行数: " + concurrency);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        AtomicLong lastLogTime = new AtomicLong(System.currentTimeMillis());

        long startTime = System.currentTimeMillis();
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Future<?>> futures = new ArrayList<>();

        for (Book book : allBooks) {
            Long bookId = book.getId();
            String title = book.getTitle();
            futures.add(executor.submit(() -> {
                try {
                    bookParserService.generateBookEmbedding(book);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.err.println("  ✗ 失败: bookId=" + bookId + " 《" + title + "》 - " + e.getMessage());
                }
                int done = successCount.get() + failCount.get();
                long now = System.currentTimeMillis();
                long last = lastLogTime.get();
                if (done % 100 == 0 || done == totalBooks || now - last > 5000) {
                    if (lastLogTime.compareAndSet(last, now)) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        System.out.printf("  进度: %d/%d (成功=%d, 失败=%d, 耗时=%ds)%n",
                                done, totalBooks, successCount.get(), failCount.get(), elapsed / 1000);
                    }
                }
            }));
        }

        executor.shutdown();
        try {
            executor.awaitTermination(2, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        System.out.println("\n========== 重写完成 ==========");
        System.out.println("总图书数: " + totalBooks);
        System.out.println("成功重写: " + successCount.get());
        System.out.println("失败: " + failCount.get());
        System.out.println("总耗时: " + elapsed + "ms");
        if (successCount.get() > 0) {
            System.out.println("平均耗时: " + (elapsed / successCount.get()) + "ms/本");
        }
    }
}
