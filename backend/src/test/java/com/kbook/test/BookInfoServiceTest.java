package com.kbook.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.book.BookParserService;
import com.kbook.service.book.BookSearchService;
import com.kbook.service.book.BookService;
import com.kbook.service.embedding.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.kbook.constants.AiPromptConstants;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Slf4j
@SpringBootTest
@ActiveProfiles("dev")
public class BookInfoServiceTest {

    @Autowired
    private BookService bookService;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private BookParserService bookParserService;

    @Autowired
    private BookSearchService bookSearchService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private ObjectMapper objectMapper;


    @Test
    public void updateBookBaseInfo() {
//        List<Book> allBooks = bookRepository.findAllByIdGreaterThan(24625L);
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
                    bookParserService.generateAllAiData(book);
//                     将请求文字写入项目根目录下的questions目录
//                    try {
//                        String content = book.getParsedContent();
//                        if (content == null || content.isBlank()) {
//                            java.lang.reflect.Method method = bookParserService.getClass()
//                                    .getDeclaredMethod("extractContentForTags", Book.class);
//                            method.setAccessible(true);
//                            content = (String) method.invoke(bookParserService, book);
//                        }
//                        if (content != null && !content.isBlank()) {
//                            String prompt = AiPromptConstants.COMBINED_PROMPT_SYSTEM_PROMPT;
//                            String safeTitle = book.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_");
//                            String filename = book.getId() + "_" + safeTitle + ".txt";
//                            java.nio.file.Path questionsDir = java.nio.file.Paths.get("..", "questions");
//                            java.nio.file.Files.createDirectories(questionsDir);
//                            String fileContent = "问题:\n1. 提示词:" + prompt + "\n2. 问题内容:" + content + "\n回答:\n";
//                            java.nio.file.Files.writeString(questionsDir.resolve(filename), fileContent, java.nio.charset.StandardCharsets.UTF_8);
//                        }
//                    } catch (Exception e) {
//                        System.err.printf("  ✗ [%d] 写入请求文件失败: id=%d %s — %s%n", index + 1, book.getId(), book.getTitle(), e.getMessage());
//                    }
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

    private static String rawResult = """
            {
              "tags": ["历史", "政治学", "汉学研究", "中外关系", "国际关系"],
              "concept": ["帝王儒学", "内亚武力传统", "条约体制", "群众路线", "毛泽东思想", "专制王朝", "外族统治", "科举制度"],
              "reader_need": ["了解西方中国史观", "研究中外关系史", "理解政治体制演变", "学习汉学研究方法", "分析中国现代化进程", "探讨专制传统"],
              "target_reader": ["历史学者", "政治学研究者", "汉学研究者", "国际关系学者", "中国问题研究者"],
              "style": ["学术严谨", "西方视角", "历史分析", "政治比较", "专题研究"],
              "description": "本书是美国汉学家费正清晚年撰写的中国通史著作，从旧石器时代写至天安门事件。全书采用'详近略远、重政治轻文化、取统一舍分裂'的主线，重点阐释近代中国的发展与演变。借助近三十年西方汉学研究成果，费正清提出'帝王儒学'与内亚武力传统相结合的中国政权观，将中共政权视为专制王朝的现代翻版，并公开反思早年对中共的浪漫化解读。书中详细论述了从朝贡体制到条约体制的转变、外族统治的二元模式、科举制度的演变、群众路线的实践以及毛泽东思想的中国化过程。适合希望了解西方主流中国史观的读者，以及对中外关系、政治体制比较感兴趣的学者与研究者。",
              "rating": 3.8,
              "relevance": {
                "0-9": 0.1,
                "10-19": 0.2,
                "20-29": 0.5,
                "30-39": 0.7,
                "40-49": 0.7,
                "50-59": 0.6,
                "60+": 0.5,
                "male": 0.6,
                "female": 0.5,
                "married": 0.5,
                "unmarried": 0.5,
                "children_0_2": 0.3,
                "children_3_6": 0.3,
                "children_7_12": 0.3,
                "children_13_17": 0.3,
                "children_18_plus": 0.5,
                "no_children": 0.5,
                "INTJ": 0.7,
                "INTP": 0.7,
                "ENTJ": 0.6,
                "ENTP": 0.6,
                "INFJ": 0.5,
                "INFP": 0.4,
                "ENFJ": 0.4,
                "ENFP": 0.4,
                "ISTJ": 0.6,
                "ISFJ": 0.4,
                "ESTJ": 0.5,
                "ESFJ": 0.3,
                "ISTP": 0.4,
                "ISFP": 0.3,
                "ESTP": 0.3,
                "ESFP": 0.2,
                "student": 0.5,
                "tech": 0.4,
                "finance": 0.3,
                "education": 0.7,
                "medical": 0.2,
                "arts": 0.4,
                "management": 0.5,
                "freelance": 0.3,
                "retired": 0.4,
                "other": 0.3,
                "high_school": 0.2,
                "college": 0.4,
                "bachelor": 0.6,
                "master": 0.7,
                "doctorate": 0.8,
                "other_edu": 0.3,
                "entrepreneur_or_want": 0.3,
                "notInterested": 0.5,
                "under_50k": 0.3,
                "50k_150k": 0.5,
                "150k_300k": 0.6,
                "300k_500k": 0.5,
                "500k_1m": 0.4,
                "over_1m": 0.3,
                "prefer_not_to_say": 0.4,
                "happy": 0.3,
                "calm": 0.6,
                "anxious": 0.4,
                "sad": 0.3,
                "frustrated": 0.3,
                "tired": 0.3,
                "growth": 0.7,
                "comfort": 0.3,
                "escape": 0.2,
                "excite": 0.3,
                "insight": 0.8
              }
            }
            """;

    @Test
    public void updateBookAiInfo() {

        Long bookId = 10701L;
        Book book = bookService.getBookById(bookId);

        BookParserService.CombinedAiResult result = callAiCombined();

        // 记录AI调用结果日志
        log.info("========== AI合并调用结果 start ==========");
        log.info("AI合并调用结果: bookId={}", bookId);
        log.info("AI合并调用结果: tags: {}", result != null ? result.tags() : "null");
        log.info("AI合并调用结果: concept: {}", result != null ? result.concept() : "null");
        log.info("AI合并调用结果: readerNeed: {}", result != null ? result.readerNeed() : "null");
        log.info("AI合并调用结果: targetReader: {}", result != null ? result.targetReader() : "null");
        log.info("AI合并调用结果: rating: {}", result != null ? result.rating() : "null");
        log.info("AI合并调用结果: relevanceScoresJson: {}", result != null ? result.relevanceScoresJson() : "null");
        log.info("AI合并调用结果: description: {}", result != null ? result.description() : "null");
        log.info("AI合并调用结果: description长度: {}", result != null && result.description() != null ? result.description().length() : 0);
        log.info("========== AI合并调用结果 end ==========");

        if (result == null) {
            log.warn("合并AI调用返回空结果: bookId={}", bookId);
            return;
        }

        // 填充标签（将标签列表转换为JSON数组字符串）
        if (result.tags() != null && !result.tags().isEmpty()) {
            String tagsJson = result.tags().stream()
                    .map(t -> "\"" + t + "\"") // 为每个标签添加引号
                    .collect(Collectors.joining(",", "[", "]")); // 拼接为JSON数组
            book.setFormatTags(tagsJson); // 设置标签
        }

        // 填充核心概念标签
        if (result.concept() != null && !result.concept().isEmpty()) {
            String conceptJson = result.concept().stream()
                    .map(t -> "\"" + t + "\"")
                    .collect(Collectors.joining(",", "[", "]"));
            book.setConceptTags(conceptJson);
        }

        // 填充读者需求标签
        if (result.readerNeed() != null && !result.readerNeed().isEmpty()) {
            String readerNeedJson = result.readerNeed().stream()
                    .map(t -> "\"" + t + "\"")
                    .collect(Collectors.joining(",", "[", "]"));
            book.setReaderNeedTags(readerNeedJson);
        }

        // 填充目标读者标签
        if (result.targetReader() != null && !result.targetReader().isEmpty()) {
            String targetReaderJson = result.targetReader().stream()
                    .map(t -> "\"" + t + "\"")
                    .collect(Collectors.joining(",", "[", "]"));
            book.setTargetReaderTags(targetReaderJson);
        }

        // 填充评分
        if (result.rating() != null) {
            book.setRating(result.rating()); // 设置评分
        }

        // 填充8维度相关度得分（JSON字符串）
        if (result.relevanceScoresJson() != null && !result.relevanceScoresJson().isBlank()) {
            book.setRelevanceScores(result.relevanceScoresJson());
        }

        // 填充AI生成的简介
        if (result.description() != null && !result.description().isBlank()) {
            book.setDescription(result.description()); // 设置简介
        }
    }


    /**
     * 一次 LLM 调用同时生成标签、评分、8维度相关度得分
     */
    private BookParserService.CombinedAiResult callAiCombined() {

        try {
            // 提取 JSON 部分
            rawResult = rawResult.trim();
            int jsonStart = rawResult.indexOf('{');
            int jsonEnd = rawResult.lastIndexOf('}');
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                log.warn("AI 合并调用返回内容无有效JSON: {}", rawResult);
                return null;
            }
            String jsonStr = rawResult.substring(jsonStart, jsonEnd + 1);

            // 解析 JSON
            JsonNode root = objectMapper.readTree(jsonStr);

            // 解析标签
            List<String> tags = null;
            if (root.has("tags") && !root.get("tags").isNull()) {
                JsonNode conceptNode = root.get("tags");
                if (conceptNode.isArray()) {
                    tags = StreamSupport.stream(conceptNode.spliterator(), false)
                            .map(JsonNode::asText)
                            .map(String::trim)
                            .filter(t -> !t.isBlank())
                            .toList();
                } else {
                    String conceptStr = conceptNode.asText();
                    if (conceptStr != null && !conceptStr.isBlank()) {
                        tags = Stream.of(conceptStr.split("[,，、]"))
                                .map(String::trim)
                                .filter(t -> !t.isBlank())
                                .toList();
                    }
                }
            }

            // 解析核心概念标签
            List<String> concept = null;
            if (root.has("concept") && !root.get("concept").isNull()) {
                JsonNode conceptNode = root.get("concept");
                if (conceptNode.isArray()) {
                    concept = StreamSupport.stream(conceptNode.spliterator(), false)
                            .map(JsonNode::asText)
                            .map(String::trim)
                            .filter(t -> !t.isBlank())
                            .toList();
                } else {
                    String conceptStr = conceptNode.asText();
                    if (conceptStr != null && !conceptStr.isBlank()) {
                        concept = Stream.of(conceptStr.split("[,，、]"))
                                .map(String::trim)
                                .filter(t -> !t.isBlank())
                                .toList();
                    }
                }
            }

            // 解析读者需求标签
            List<String> readerNeed = null;
            if (root.has("reader_need") && !root.get("reader_need").isNull()) {
                JsonNode readerNeedNode = root.get("reader_need");
                if (readerNeedNode.isArray()) {
                    readerNeed = StreamSupport.stream(readerNeedNode.spliterator(), false)
                            .map(JsonNode::asText)
                            .map(String::trim)
                            .filter(t -> !t.isBlank())
                            .toList();
                } else {
                    String readerNeedStr = readerNeedNode.asText();
                    if (readerNeedStr != null && !readerNeedStr.isBlank()) {
                        readerNeed = Stream.of(readerNeedStr.split("[,，、]"))
                                .map(String::trim)
                                .filter(t -> !t.isBlank())
                                .toList();
                    }
                }
            }

            // 解析目标读者标签
            List<String> targetReader = null;
            if (root.has("target_reader") && !root.get("target_reader").isNull()) {
                JsonNode targetReaderNode = root.get("target_reader");
                if (targetReaderNode.isArray()) {
                    targetReader = StreamSupport.stream(targetReaderNode.spliterator(), false)
                            .map(JsonNode::asText)
                            .map(String::trim)
                            .filter(t -> !t.isBlank())
                            .toList();
                } else {
                    String targetReaderStr = targetReaderNode.asText();
                    if (targetReaderStr != null && !targetReaderStr.isBlank()) {
                        targetReader = Stream.of(targetReaderStr.split("[,，、]"))
                                .map(String::trim)
                                .filter(t -> !t.isBlank())
                                .toList();
                    }
                }
            }

            // 解析评分
            Double rating = null;
            if (root.has("rating") && !root.get("rating").isNull()) {
                try {
                    double r = root.get("rating").asDouble();
                    rating = Math.max(1.0, Math.min(5.0, Math.round(r * 10.0) / 10.0));
                } catch (Exception e) {
                    log.warn("评分解析失败: {}", e.getMessage());
                }
            }

            // 解析8维度相关度得分
            String relevanceScoresJson = null;
            if (root.has("relevance") && !root.get("relevance").isNull()) {
                JsonNode relevanceNode = root.get("relevance");
                if (relevanceNode.isObject()) {
                    relevanceScoresJson = objectMapper.writeValueAsString(relevanceNode);
                }
            }

            // 解析简介
            String description = null;
            if (root.has("description") && !root.get("description").isNull()) {
                String desc = root.get("description").asText().trim();
                if (!desc.isBlank() && !"null".equalsIgnoreCase(desc)) {
                    description = desc;
                }
            }

            // 至少有一项结果才算成功
            if (tags == null && concept == null && readerNeed == null && targetReader == null && rating == null && relevanceScoresJson == null && description == null) {
                return null;
            }

            return new BookParserService.CombinedAiResult(tags, concept, readerNeed, targetReader, rating, relevanceScoresJson, description);

        } catch (Exception e) {
            log.warn("AI 合并调用结果解析失败: {}", e.getMessage());
            return null;
        }
    }
}
