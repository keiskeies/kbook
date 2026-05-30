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

        // 记录总开始时间
        long totalStartTime = System.currentTimeMillis();

        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < allBooks.size(); i++) {
            Book book = allBooks.get(i);
            long bookStartTime = System.currentTimeMillis();
            try {
                System.out.println("\n========== 进度: " + (i + 1) + "/" + allBooks.size() + " ==========");
                System.out.println("处理图书: [" + book.getId() + "] " + book.getTitle());

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
//                // 检查简介是否需要AI补充
//                if (book.getDescription() == null) {
//                    System.out.println("  简介为空，需AI补充");
//                    needAi = true;
//                } else if (book.getDescription().length() < 50) {
//                    System.out.println("  简介长度不足50字，需AI补充");
//                    needAi = true;
//                } else if (book.getDescription().startsWith("基于小说内容生成的100-300字图书简介")) {
//                    System.out.println("  简介为占位符文本（小说），需AI补充");
//                    needAi = true;
//                } else if (book.getDescription().startsWith("基于正文内容生成的100-300字图书简介")) {
//                    System.out.println("  简介为占位符文本（正文），需AI补充");
//                    needAi = true;
//                } else if (!book.getDescription().matches(".*[\\u4e00-\\u9fa5].*")) {
////                    System.out.println("  简介不包含中文字符，需AI补充");
////                    needAi = true;
//                }
//                book.setDescription(null);
//                if (book.getFormatTags() == null || book.getFormatTags().isBlank()) {
//                    System.out.println("  缺少标签数据，需AI补充");
//                    needAi = true;
//                } else {
//                    int len = book.getFormatTags().split(",").length;
//                    if (len < 3) {
//                        System.out.println("  标签数量不足3个，需AI补充");
//                        needAi = true;
//                    }
////                    else if (len > 8) {
////                        System.out.println("  标签数量超过8个，需AI补充");
////                        needAi = true;
////                    }
//                }
//                if (book.getRating() == null || book.getRating() == 0) {
//                    System.out.println("  评分为空或为0，需AI补充");
//                    needAi = true;
//                }
//                if (book.getRelevanceScores() == null || book.getRelevanceScores().isBlank()) {
//                    System.out.println("  缺少维度得分，需AI补充");
//                    needAi = true;
//                }
                if (book.getRelevanceScores() == null || book.getRelevanceScores().isBlank()) {
                    System.out.println("  缺少维度得分，需AI补充");
                    needAi = true;
                } else if (book.getRelevanceScores().length() < rl) {
                    System.out.println("  维度得分长度不足"+rl+"字，需AI补充");
                    needAi = true;
                }
                if (!needAi) {
                    continue;
                }


                bookParserService.parseAndFill(book, filePath);
                bookParserService.finalizeCover(book);

                bookParserService.generateAllAiData(book);

                bookService.updateBookAll(book.getId(), book);

//                bookParserService.generateBookEmbedding(book);

                successCount++;
                
                // 计算当前书籍处理耗时
                long bookEndTime = System.currentTimeMillis();
                long bookElapsedMs = bookEndTime - bookStartTime;
                double bookElapsedSec = bookElapsedMs / 1000.0;
                
                // 计算总体进度
                long currentTime = System.currentTimeMillis();
                long totalElapsedMs = currentTime - totalStartTime;
                double totalElapsedSec = totalElapsedMs / 1000.0;
                
                // 计算平均每本耗时（基于已处理的书籍）
                int processedCount = successCount + failCount;
                double avgMsPerBook = processedCount > 0 ? (double) totalElapsedMs / processedCount : 0;
                
                // 估算剩余时间
                int remainingBooks = allBooks.size() - (i + 1);
                long estimatedRemainingMs = (long) (avgMsPerBook * remainingBooks);
                double estimatedRemainingMin = estimatedRemainingMs / 60000.0;
                
                // 估算总完成时间
                long estimatedTotalMs = totalElapsedMs + estimatedRemainingMs;
                java.time.LocalDateTime estimatedFinishTime = java.time.LocalDateTime.now().plusSeconds(estimatedRemainingMs / 1000);
                
                System.out.printf("  ✓ 处理完成: %s%n", book.getTitle());
                System.out.printf("  ⏱️  当前书籍耗时: %.2f秒%n", bookElapsedSec);
                System.out.printf("  \uD83D\uDCCA 总体进度: %d/%d (%.1f%%)%n", i + 1, allBooks.size(), (i + 1) * 100.0 / allBooks.size());
                System.out.printf("  ⏳ 已用时间: %.2f分钟 (%.2f秒)%n", totalElapsedSec / 60, totalElapsedSec);
                System.out.printf("  \uD83D\uDD2E 预计剩余: %.1f分钟 (约%d本 × %.2f秒/本)%n",
                    estimatedRemainingMin, remainingBooks, avgMsPerBook / 1000.0);
                System.out.printf("  \uD83C\uDFAF 预计完成时间: %s%n", estimatedFinishTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
//                try {
//                    Thread.sleep(2000);
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }

            } catch (Exception e) {
                failCount++;
                long bookEndTime = System.currentTimeMillis();
                long bookElapsedMs = bookEndTime - bookStartTime;
                double bookElapsedSec = bookElapsedMs / 1000.0;
                
                System.err.printf("  ✗ 失败: %s - %s (耗时: %.2f秒)%n", book.getTitle(), e.getMessage(), bookElapsedSec);
                e.printStackTrace();
            }
        }

        // 计算总耗时
        long totalEndTime = System.currentTimeMillis();
        long totalElapsedMs = totalEndTime - totalStartTime;
        double totalElapsedMin = totalElapsedMs / 60000.0;
        double totalElapsedSec = totalElapsedMs / 1000.0;
        double avgMsPerBook = allBooks.size() > 0 ? (double) totalElapsedMs / allBooks.size() : 0;

        System.out.println("\n========== 处理完成 ==========");
        System.out.println("总数: " + allBooks.size());
        System.out.println("成功: " + successCount);
        System.out.println("失败: " + failCount);
        System.out.println(String.format("⏱️  总耗时: %.2f分钟 (%.2f秒)", totalElapsedMin, totalElapsedSec));
        System.out.println(String.format("📈 平均每本: %.2f秒", avgMsPerBook / 1000.0));
    }
}
