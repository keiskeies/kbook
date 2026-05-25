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
        List<Book> allBooks = bookRepository.findAll();
        System.out.println("共 " + allBooks.size() + " 本图书需要处理");





        int successCount = 0;
        int failCount = 0;

        for (int i = 1900; i < allBooks.size(); i++) {
            Book book = allBooks.get(i);
            try {
                System.out.println("处理第 " + (i + 1) + "/" + allBooks.size() + " 本: [" + book.getId() + "] " + book.getTitle());

                if (book.getFileUrl() == null || book.getFileUrl().isBlank()) {
                    System.out.println("  跳过: 无文件路径");
                    continue;
                }

                Path filePath = Path.of(book.getFileUrl());
                if (!java.nio.file.Files.exists(filePath)) {
                    System.out.println("  跳过: 文件不存在 - " + book.getFileUrl());
                    continue;
                }


                boolean needAi = false;
                // 检查简介是否需要AI补充
                if (book.getDescription() == null) {
                    System.out.println("  简介为空，需AI补充");
                    needAi = true;
                } else if (book.getDescription().length() < 50) {
                    System.out.println("  简介长度不足50字，需AI补充");
                    needAi = true;
                } else if (book.getDescription().startsWith("基于小说内容生成的100-300字图书简介")) {
                    System.out.println("  简介为占位符文本（小说），需AI补充");
                    needAi = true;
                } else if (book.getDescription().startsWith("基于正文内容生成的100-300字图书简介")) {
                    System.out.println("  简介为占位符文本（正文），需AI补充");
                    needAi = true;
                } else if (!book.getDescription().matches(".*[\\u4e00-\\u9fa5].*")) {
                    System.out.println("  简介不包含中文字符，需AI补充");
                    needAi = true;
                }
                book.setDescription(null);
                if (book.getFormatTags() == null || book.getFormatTags().isBlank()) {
                    System.out.println("  缺少标签数据，需AI补充");
                    needAi = true;
                } else {
                    int len = book.getFormatTags().split(",").length;
                    if (len < 3) {
                        System.out.println("  标签数量不足3个，需AI补充");
                        needAi = true;
                    }
//                    else if (len > 8) {
//                        System.out.println("  标签数量超过8个，需AI补充");
//                        needAi = true;
//                    }
                }
                if (book.getRating() == null || book.getRating() == 0) {
                    System.out.println("  评分为空或为0，需AI补充");
                    needAi = true;
                }
                if (book.getRelevanceScores() == null || book.getRelevanceScores().isBlank()) {
                    System.out.println("  缺少维度得分，需AI补充");
                    needAi = true;
                }

                bookParserService.parseAndFill(book, filePath);
                bookParserService.finalizeCover(book);

                if (needAi) {
                    bookParserService.generateAllAiData(book);
                }

                bookService.updateBook(book.getId(), book);

                bookParserService.generateBookEmbedding(book);

                successCount++;
                System.out.println("  ✓ 处理完成: " + book.getTitle());

            } catch (Exception e) {
                failCount++;
                System.err.println("  ✗ 失败: " + book.getTitle() + " - " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("\n========== 处理完成 ==========");
        System.out.println("总数: " + allBooks.size());
        System.out.println("成功: " + successCount);
        System.out.println("失败: " + failCount);
    }
}
