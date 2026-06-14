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
import lombok.extern.slf4j.Slf4j;
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
 * 图书元数据清洗测试类
 * <p>
 * 使用 AI 对图书的书名和作者信息进行规范化处理，例如：
 * - "台北人-白先勇(完整)" → 书名："台北人"，作者："白先勇"
 * - "活着_余华.txt" → 书名："活着"，作者："余华"
 * - 空作者字段根据书名推断作者
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("dev")
public class BookMetadataCleanerTest {

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

    /**
     * 批量清洗所有图书的书名和作者信息
     */
    @Test
    public void cleanAllBookMetadata() {
        List<Book> allBooks = bookRepository.findAll();
        log.info("共 {} 本图书需要处理", allBooks.size());

        // 筛选出需要处理的图书（书名包含特殊字符或作者为空）
        List<Book> booksToProcess = allBooks.stream()
                .filter(book -> needsCleaning(book))
                .toList();

        log.info("筛选出 {} 本需要清洗的图书", booksToProcess.size());

        if (booksToProcess.isEmpty()) {
            log.info("没有需要清洗的图书");
            return;
        }

        long totalStartTime = System.currentTimeMillis();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);

        int threadCount = 5; // AI 调用较慢，使用较小的线程数
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(booksToProcess.size());

        // 进度监控线程
        Thread progressThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(3000);
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
                int remaining = booksToProcess.size() - done;
                double estMin = remaining * avgMs / 60000.0;
                java.time.LocalDateTime finishTime = java.time.LocalDateTime.now().plusSeconds((long) (estMin * 60));
                log.info("[进度] {}/{} ({:.1f}%) | 成功={} 失败={} 跳过={} | 已用={:.1f}分钟 | 预计剩余={:.1f}分钟 | 预计完成={}",
                        done, booksToProcess.size(), done * 100.0 / booksToProcess.size(),
                        ok, fail, skip, elapsedMin, estMin,
                        finishTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
            }
        });
        progressThread.setDaemon(true);
        progressThread.start();

        for (int i = 0; i < booksToProcess.size(); i++) {
            final int index = i;
            final Book book = booksToProcess.get(i);
            executor.submit(() -> {
                try {
                    log.info("处理 [{}/{}] id={} 《{}》 作者:{}", 
                            index + 1, booksToProcess.size(), 
                            book.getId(), book.getTitle(), book.getAuthor());

                    // 使用 AI 清洗元数据
                    boolean updated = cleanBookMetadata(book);

                    if (updated) {
                        // 保存更新后的书籍信息到数据库
                        bookRepository.save(book);

                        // 更新 Elasticsearch 索引
                        bookSearchService.indexBook(book);

                        // 重建基础信息向量（因为书名/作者变化了）
                        embeddingService.removeBookEmbedding(book.getId());
                        embeddingService.upsertBookEmbedding(book);

                        successCount.incrementAndGet();
                        log.info("  ✓ 成功更新: 《{}》 作者:{}", book.getTitle(), book.getAuthor());
                    } else {
                        skippedCount.incrementAndGet();
                        log.info("  ⊘ 无需更新: 《{}》", book.getTitle());
                    }

                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.error("  ✗ 失败: 《{}》 - {}", book.getTitle(), e.getMessage(), e);
                } finally {
                    completedCount.incrementAndGet();
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
            executor.shutdown();
        } catch (InterruptedException e) {
            log.error("等待任务完成时被中断", e);
            Thread.currentThread().interrupt();
        }

        long totalElapsedMs = System.currentTimeMillis() - totalStartTime;
        log.info("\n========== 处理完成 ==========");
        log.info("总数: {}", booksToProcess.size());
        log.info("成功: {}", successCount.get());
        log.info("失败: {}", failCount.get());
        log.info("跳过: {}", skippedCount.get());
        log.info("总耗时: {:.2f} 分钟", totalElapsedMs / 60000.0);
    }

    /**
     * 判断图书是否需要清洗
     */
    private boolean needsCleaning(Book book) {
        String title = book.getTitle();
        String author = book.getAuthor();

        // 作者为空或为空字符串
        if (author == null || author.trim().isEmpty()) {
            return true;
        }

        // 书名包含常见的分隔符（- _ 《 》 ( ) 【 】等）
        if (title != null && title.matches(".*[-_《》()【】\\(\\)].*")) {
            return true;
        }

        // 作者和书名相同（可能是错误数据）
        if (author != null && title != null && author.trim().equals(title.trim())) {
            return true;
        }

        return false;
    }

    /**
     * 使用 AI 清洗单本图书的元数据
     *
     * @return 是否有更新
     */
    private boolean cleanBookMetadata(Book book) throws Exception {
        String originalTitle = book.getTitle();
        String originalAuthor = book.getAuthor();

        // 构建提示词
        String prompt = buildCleaningPrompt(book);

        // 调用 AI
        ChatModel model = chatModelFactory.buildChatModelWithoutThinkingFromYml();
        if (model == null) {
            log.warn("AI 模型未配置，跳过: 《{}》", book.getTitle());
            return false;
        }

        long startTime = System.currentTimeMillis();
        ChatResponse response = model.chat(List.of(
                SystemMessage.from("你是一个专业的图书元数据清洗助手。你的任务是从可能混乱的书名字段中提取出干净的书名和作者信息。"),
                UserMessage.from(prompt)
        ));
        long elapsed = System.currentTimeMillis() - startTime;

        String aiResponse = response.aiMessage().text();
        if (aiResponse == null || aiResponse.isBlank()) {
            log.warn("AI 返回空结果: 《{}》", book.getTitle());
            return false;
        }

        aiResponse = aiResponse.trim();

        // 解析 JSON 响应
        JsonNode jsonNode = objectMapper.readTree(aiResponse);
        String cleanedTitle = jsonNode.has("title") && !jsonNode.get("title").isNull() 
                ? jsonNode.get("title").asText().trim() : null;
        String cleanedAuthor = jsonNode.has("author") && !jsonNode.get("author").isNull() 
                ? jsonNode.get("author").asText().trim() : null;

        // 验证结果
        if (cleanedTitle == null || cleanedTitle.isEmpty()) {
            log.warn("AI 未提取到书名，跳过: 《{}》", book.getTitle());
            return false;
        }

        // 检查是否有变化
        boolean titleChanged = !cleanedTitle.equals(originalTitle);
        boolean authorChanged = !java.util.Objects.equals(cleanedAuthor, originalAuthor);

        if (!titleChanged && !authorChanged) {
            return false; // 没有变化
        }

        // 应用更改
        if (titleChanged) {
            log.info("  书名: {} → {}", originalTitle, cleanedTitle);
            book.setTitle(cleanedTitle);
        }

        if (authorChanged) {
            log.info("  作者: {} → {}", originalAuthor, cleanedAuthor);
            book.setAuthor(cleanedAuthor);
        }

        log.info("AI 调用耗时: {}ms, input_tokens={}, output_tokens={}", 
                elapsed,
                response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null 
                        ? response.tokenUsage().inputTokenCount() : 0,
                response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null 
                        ? response.tokenUsage().outputTokenCount() : 0);

        return true;
    }

    /**
     * 构建清洗提示词
     */
    private String buildCleaningPrompt(Book book) {
        StringBuilder sb = new StringBuilder();
        sb.append("请从以下图书信息中提取干净的书名和作者，以 JSON 格式返回。\n\n");
        sb.append("当前数据:\n");
        sb.append("- 书名字段: ").append(book.getTitle()).append("\n");
        sb.append("- 作者字段: ").append(book.getAuthor() != null ? book.getAuthor() : "(空)").append("\n");
        sb.append("- 文件格式: ").append(book.getFormat()).append("\n");
        
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            String desc = book.getDescription().length() > 200 
                    ? book.getDescription().substring(0, 200) + "..." 
                    : book.getDescription();
            sb.append("- 简介: ").append(desc).append("\n");
        }

        sb.append("\n要求:\n");
        sb.append("1. 从书名字段中分离出真正的书名和作者（如果存在）\n");
        sb.append("2. 去除书名中的多余信息，如格式标记、版本号、完整性标记等\n");
        sb.append("3. 如果作者字段为空，尝试从书名或简介中推断作者\n");
        sb.append("4. 如果无法确定作者，author 字段返回 null\n");
        sb.append("5. 书名应该简洁明了，不包含作者名\n");
        sb.append("\n示例:\n");
        sb.append("- \"台北人-白先勇(完整)\" → {\"title\": \"台北人\", \"author\": \"白先勇\"}\n");
        sb.append("- \"活着_余华.txt\" → {\"title\": \"活着\", \"author\": \"余华\"}\n");
        sb.append("- \"百年孤独\" (作者空) → {\"title\": \"百年孤独\", \"author\": \"加西亚·马尔克斯\"}\n");
        sb.append("- \"Python编程_从入门到实践\" (作者空) → {\"title\": \"Python编程：从入门到实践\", \"author\": null}\n");
        sb.append("\n只返回 JSON，不要其他文字。格式: {\"title\": \"...\", \"author\": \"...\"}");

        return sb.toString();
    }

    /**
     * 测试单本图书的清洗效果（不保存）
     */
    @Test
    public void testCleanSingleBook() {
        // 可以手动指定要测试的图书 ID
        Long bookId = 1L;
        Book book = bookRepository.findById(bookId).orElse(null);
        
        if (book == null) {
            log.error("图书不存在: id={}", bookId);
            return;
        }

        log.info("原始数据:");
        log.info("  书名: {}", book.getTitle());
        log.info("  作者: {}", book.getAuthor());
        log.info("  格式: {}", book.getFormat());

        try {
            String prompt = buildCleaningPrompt(book);
            log.info("\n提示词:\n{}", prompt);

            ChatModel model = chatModelFactory.buildChatModelWithoutThinkingFromYml();
            if (model == null) {
                log.error("AI 模型未配置");
                return;
            }

            ChatResponse response = model.chat(List.of(
                    SystemMessage.from("你是一个专业的图书元数据清洗助手。你的任务是从可能混乱的书名字段中提取出干净的书名和作者信息。"),
                    UserMessage.from(prompt)
            ));

            String aiResponse = response.aiMessage().text();
            log.info("\nAI 响应:\n{}", aiResponse);

            // 解析并展示结果
            JsonNode jsonNode = objectMapper.readTree(aiResponse);
            String cleanedTitle = jsonNode.has("title") ? jsonNode.get("title").asText() : null;
            String cleanedAuthor = jsonNode.has("author") && !jsonNode.get("author").isNull() 
                    ? jsonNode.get("author").asText() : null;

            log.info("\n清洗结果:");
            log.info("  书名: {} → {}", book.getTitle(), cleanedTitle);
            log.info("  作者: {} → {}", book.getAuthor(), cleanedAuthor);

        } catch (Exception e) {
            log.error("处理失败", e);
        }
    }
}
