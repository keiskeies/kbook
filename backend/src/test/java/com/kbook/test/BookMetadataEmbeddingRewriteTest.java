package com.kbook.test;

import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.BookParserService;
import com.kbook.service.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@ActiveProfiles("test")
public class BookMetadataEmbeddingRewriteTest {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private BookParserService bookParserService;

    @Test
    public void rewriteAllBookMetadataEmbeddings() {
        if (!embeddingService.isAvailable()) {
            System.err.println("Embedding 服务不可用，跳过测试");
            return;
        }

        long totalBooks = bookRepository.count();
        System.out.println("========== 重写图书元数据向量 ==========");
        System.out.println("总图书数: " + totalBooks);

        int pageSize = 100;
        int pageNumber = 0;
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        long startTime = System.currentTimeMillis();

        Page<Book> bookPage;
        do {
            Pageable pageable = PageRequest.of(pageNumber, pageSize);
            bookPage = bookRepository.findAll(pageable);
            List<Book> books = bookPage.getContent();

            for (Book book : books) {
                try {
                    bookParserService.generateBookEmbedding(book);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.err.println("  ✗ 失败: bookId=" + book.getId() + " 《" + book.getTitle() + "》 - " + e.getMessage());
                }
            }

            int processed = (pageNumber + 1) * pageSize;
            System.out.printf("  进度: %d/%d (成功=%d, 失败=%d)%n",
                    Math.min(processed, totalBooks), totalBooks,
                    successCount.get(), failCount.get());

            pageNumber++;
        } while (bookPage.hasNext());

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
