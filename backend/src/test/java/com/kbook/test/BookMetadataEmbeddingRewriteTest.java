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

import java.util.List;

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
        int total = allBooks.size();
        System.out.println("========== 重写图书元数据向量 ==========");
        System.out.println("总图书数: " + total);

        int success = 0;
        int fail = 0;
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < total; i++) {
            Book book = allBooks.get(i);
            try {
                bookParserService.generateBookEmbedding(book);
                success++;
            } catch (Exception e) {
                fail++;
                System.err.println("  ✗ 失败: bookId=" + book.getId() + " 《" + book.getTitle() + "》 - " + e.getMessage());
            }
            int done = success + fail;
            if (done % 100 == 0 || done == total) {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                System.out.printf("  进度: %d/%d (成功=%d, 失败=%d, 耗时=%ds)%n", done, total, success, fail, elapsed);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println("\n========== 重写完成 ==========");
        System.out.println("总图书数: " + total);
        System.out.println("成功重写: " + success);
        System.out.println("失败: " + fail);
        System.out.println("总耗时: " + elapsed + "ms");
        if (success > 0) {
            System.out.println("平均耗时: " + (elapsed / success) + "ms/本");
        }
    }
}
