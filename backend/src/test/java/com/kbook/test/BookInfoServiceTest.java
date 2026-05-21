package com.kbook.test;

import com.kbook.dto.TagStat;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    public void updateBookImages() {
        String errs = "book_new_1778875775817_cover.jpg," +
                "book_196_cover.jpg," +
                "book_1610_cover.jpg," +
                "book_3029_cover.jpg," +
                "book_3204_cover.jpg," +
                "book_4335_cover.jpg," +
                "book_new_1779094890365_cover.jpg," +
                "book_new_1779183283137_cover.jpg," +
                "book_new_1779183283319_cover.jpg," +
                "book_new_1779183283486_cover.jpg," +
                "book_new_1779183283548_cover.jpg," +
                "book_new_1779183283726_cover.jpg," +
                "book_4308_cover.jpg," +
                "book_new_1779183283659_cover.jpg," +
                "book_2442_cover.jpg," +
                "book_4145_cover.jpg," +
                "book_4441_cover.jpg," +
                "book_4608_cover.jpg," +
                "book_5064_cover.jpg," +
                "book_5444_cover.jpg," +
                "book_5478_cover.jpg," +
                "book_5576_cover.jpg," +
                "book_5986_cover.jpg," +
                "book_6038_cover.jpg," +
                "book_6073_cover.jpg," +
                "book_6160_cover.jpg," +
                "book_6252_cover.jpg," +
                "book_6360_cover.jpg," +
                "book_6385_cover.jpg," +
                "book_6404_cover.jpg," +
                "book_6429_cover.jpg," +
                "book_6441_cover.jpg," +
                "book_6511_cover.jpg," +
                "book_6553_cover.jpg," +
                "book_6569_cover.jpg," +
                "book_6643_cover.jpg," +
                "book_6682_cover.jpg," +
                "book_6778_cover.jpg," +
                "book_6791_cover.jpg," +
                "book_6809_cover.jpg," +
                "book_6884_cover.jpg," +
                "book_6900_cover.jpg," +
                "book_6913_cover.jpg," +
                "book_6945_cover.jpg," +
                "book_new_1778873313831_cover.jpg";

        List<String> errFiles = Arrays.stream(errs.split(",")).map(e -> "/api/books/cover/" + e).toList();
        List<Book> books = bookRepository.findAllByCoverUrlIn(errFiles);
        for (Book book : books) {
            bookParserService.parseEpub(book, Path.of(book.getFileUrl()));
            bookParserService.finalizeCover(book);
            bookService.setCoverUrl(book.getId(), book.getCoverUrl());

        }
    }


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

                // 6. 重建基础信息向量（参考 EmbeddingService.rebuildAllBookEmbeddingsWithProgress 中对单本书的向量操作）
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

        List<TagStat> list = tagCount.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(e -> TagStat.builder()
                        .name(e.getKey())
                        .count(e.getValue())
                        .build())
                .limit(100)
//                .filter(tagStat -> tagStat.getCount() > 5)
                .toList();

        System.out.println(JSONArray.toJSONString(list));
    }
}
