package com.kbook.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.config.ChatModelFactory;
import com.kbook.config.properties.BookStorageProperties;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.book.BookSearchService;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 图书作者信息补全工具（基于摘要内容分析 + 封面 OCR）
 * <p>
 * 针对 author 为空或 author 为网址的图书，使用 LLM 分析摘要/简介内容，
 * 尝试从中提取作者信息并更新。
 * <p>
 * 如果摘要中未提取到作者，且图书有封面图片，则使用视觉模型对封面进行 OCR 识别，
 * 从封面文字中提取作者信息。
 * <p>
 * 典型场景：
 * - author 为 null 或空字符串
 * - author 为网址格式，如 "xxx.com"、"www.xxx.com"、"https://xxx.com"
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("dev")
public class BookAuthorFromDescriptionTest {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private ChatModelFactory chatModelFactory;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BookSearchService bookSearchService;

    @Autowired
    private BookStorageProperties storageProps;

    /** true=只预览不保存, false=保存到数据库 */
    private static final boolean DRY_RUN = false;

    /** 网址正则：匹配 xxx.com / www.xxx.com / https://xxx.com 等格式 */
    private static final Pattern URL_PATTERN = Pattern.compile(
            "^(https?://)?(www\\.)?[a-zA-Z0-9-]+(\\.[a-zA-Z]{2,})+(/.*)?$",
            Pattern.CASE_INSENSITIVE
    );

    private static final String SYSTEM_PROMPT = """
            你是一个专业的图书元数据助手。你的任务是从图书的简介和章节摘要中分析并提取作者信息。
            
            规则：
            1. 仔细阅读提供的简介和章节摘要内容，寻找作者相关信息
            2. 作者可能以以下形式出现：
               - "作者：xxx" / "作者: xxx" / "by xxx"
               - "xxx 著" / "xxx 作品"
               - 书名中包含的作者信息
               - 摘要中提到的创作者/作者
            3. 如果能确定作者，返回作者名
            4. 如果无法确定作者，author 返回 null
            5. 不要凭空推断或编造作者
            6. 只返回 JSON，不要其他文字
            """;

    private static final String VISION_SYSTEM_PROMPT = """
            你是一个专业的图书封面OCR助手。你的任务是从图书封面图片中识别并提取作者信息。
            
            规则：
            1. 仔细观察封面图片中的所有文字内容
            2. 作者通常出现在封面的以下位置：
               - 书名下方或上方
               - 封面底部
               - 标注为"作者：xxx"、"by xxx"、"xxx 著"
            3. 如果能从封面中识别出作者，返回作者名
            4. 如果封面中没有作者信息，author 返回 null
            5. 不要凭空推断或编造作者
            6. 只返回 JSON，不要其他文字
            """;

    /**
     * 批量处理：从摘要内容中提取作者信息
     */
    @Test
    public void extractAuthorsFromDescriptions() {
        List<Book> allBooks = bookRepository.findAll();

        List<Book> booksToProcess = allBooks.stream()
                .filter(this::needsAuthorExtraction)
                .toList();

        log.info("总图书数: {}, 需要处理: {}, 模式: {}",
                allBooks.size(), booksToProcess.size(),
                DRY_RUN ? "预览(不保存)" : "正式(保存)");

        if (booksToProcess.isEmpty()) {
            log.info("没有需要处理的图书");
            return;
        }

        long totalStartTime = System.currentTimeMillis();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(booksToProcess.size());

        Thread progressThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                }
                int done = completedCount.get();
                if (done >= booksToProcess.size()) break;
                long elapsedMs = System.currentTimeMillis() - totalStartTime;
                int processed = successCount.get() + failCount.get();
                double avgMs = processed > 0 ? (double) elapsedMs / processed : 0;
                int remaining = booksToProcess.size() - done;
                double estMin = remaining * avgMs / 60000.0;
                log.info("[进度] {}/{} | 成功={} 失败={} 跳过={} | 预计剩余={:.1f}分钟",
                        done, booksToProcess.size(),
                        successCount.get(), failCount.get(), skippedCount.get(), estMin);
            }
        });
        progressThread.setDaemon(true);
        progressThread.start();

        for (Book book : booksToProcess) {
            executor.submit(() -> {
                try {
                    String originalAuthor = book.getAuthor();
                    boolean updated = extractAuthorFromDescription(book);

                    if (updated) {
                        successCount.incrementAndGet();
                        log.info("✓ id={} 《{}》 作者: {} → {}",
                                book.getId(), book.getTitle(),
                                originalAuthor == null ? "(空)" : originalAuthor,
                                book.getAuthor());

                        if (!DRY_RUN) {
                            bookRepository.save(book);
                            bookSearchService.indexBook(book);
                        }
                    } else {
                        skippedCount.incrementAndGet();
                        log.info("⊘ id={} 《{}》 未提取到作者", book.getId(), book.getTitle());
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.error("✗ id={} 《{}》 - {}", book.getId(), book.getTitle(), e.getMessage());
                } finally {
                    completedCount.incrementAndGet();
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
            executor.shutdown();
            progressThread.interrupt();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long totalMs = System.currentTimeMillis() - totalStartTime;
        log.info("\n========== 处理完成 ==========");
        log.info("总数: {}, 成功: {}, 失败: {}, 跳过: {}, 耗时: {:.2f}分钟",
                booksToProcess.size(), successCount.get(), failCount.get(),
                skippedCount.get(), totalMs / 60000.0);
        if (DRY_RUN) {
            log.info("（预览模式，未保存到数据库）");
        }
    }

    /**
     * 预览：列出所有需要处理的图书
     */
    @Test
    public void previewOnly() {
        List<Book> allBooks = bookRepository.findAll();
        List<Book> booksToProcess = allBooks.stream()
                .filter(this::needsAuthorExtraction)
                .toList();

        log.info("=== 需要处理的图书: {} 本 ===", booksToProcess.size());
        for (Book b : booksToProcess) {
            log.info("  id={} 《{}》 author={} format={}",
                    b.getId(), b.getTitle(), b.getAuthor(), b.getFormat());
        }
    }

    /**
     * 测试单本图书的作者提取（不保存）
     */
    @Test
    public void testSingleBook() {
        Long bookId = 1L;
        Book book = bookRepository.findById(bookId).orElse(null);

        if (book == null) {
            log.error("图书不存在: id={}", bookId);
            return;
        }

        log.info("图书信息:");
        log.info("  id: {}", book.getId());
        log.info("  书名: {}", book.getTitle());
        log.info("  作者: {}", book.getAuthor());
        log.info("  格式: {}", book.getFormat());
        log.info("  封面: {}", book.getCoverUrl());

        String desc = book.getDescription();
        if (desc != null) {
            log.info("  简介前200字: {}",
                    desc.length() > 200 ? desc.substring(0, 200) + "..." : desc);
        } else {
            log.info("  简介: (空)");
        }

        String chapterSummary = book.getChapterSummary();
        if (chapterSummary != null) {
            log.info("  章节摘要前200字: {}",
                    chapterSummary.length() > 200 ? chapterSummary.substring(0, 200) + "..." : chapterSummary);
        } else {
            log.info("  章节摘要: (空)");
        }

        try {
            // 测试摘要提取
            log.info("\n--- 测试摘要提取 ---");
            ChatModel textModel = chatModelFactory.buildToolChatModel();
            String prompt = buildPrompt(book);
            log.info("提示词:\n{}", prompt);

            ChatResponse response = textModel.chat(List.of(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from(prompt)
            ));

            String aiResponse = response.aiMessage().text();
            log.info("AI 响应:\n{}", aiResponse);

            String author = parseAuthorFromResponse(aiResponse);
            log.info("提取结果: author={}", author);

            // 测试封面 OCR（如果摘要未提取到且有封面）
            if ((author == null || author.isEmpty()) && hasCover(book)) {
                log.info("\n--- 测试封面 OCR ---");
                Path coverPath = resolveCoverPath(book);
                log.info("封面路径: {}", coverPath);

                if (coverPath != null && Files.exists(coverPath)) {
                    String dataUri = imageToDataUri(coverPath);
                    log.info("图片转 Base64: {}", dataUri != null ? "成功 (长度=" + dataUri.length() + ")" : "失败");

                    if (dataUri != null) {
                        ChatModel visionModel = chatModelFactory.buildVisionChatModel();
                        String coverPrompt = "请从以下图书封面图片中识别作者信息。\n\n"
                                + "书名: " + book.getTitle() + "\n"
                                + "当前作者: " + (book.getAuthor() != null ? book.getAuthor() : "(空)") + "\n\n"
                                + "请仔细观察封面图片中的文字，提取作者信息。返回 JSON: {\"author\": \"作者名\"}\n"
                                + "如果封面中没有作者信息，返回: {\"author\": null}";

                        UserMessage userMessage = UserMessage.from(
                                TextContent.from(coverPrompt),
                                ImageContent.from(dataUri)
                        );
                        ChatResponse visionResponse = visionModel.chat(List.of(
                                SystemMessage.from(VISION_SYSTEM_PROMPT),
                                userMessage
                        ));

                        String visionAiResponse = visionResponse.aiMessage().text();
                        log.info("封面 OCR AI 响应:\n{}", visionAiResponse);

                        String coverAuthor = parseAuthorFromResponse(visionAiResponse);
                        log.info("封面提取结果: author={}", coverAuthor);
                    }
                } else {
                    log.warn("封面文件不存在");
                }
            }

        } catch (Exception e) {
            log.error("处理失败", e);
        }
    }

    /**
     * 判断图书是否需要提取作者
     */
    private boolean needsAuthorExtraction(Book book) {
        String author = book.getAuthor();

        // author 不为空且不是网址，跳过
        if (author != null && !author.trim().isEmpty()
                && !URL_PATTERN.matcher(author.toLowerCase(Locale.ROOT).trim()).matches()) {
            return false;
        }

        // author 为空或为网址，检查是否有可用内容（摘要或封面）
        return hasContent(book) || hasCover(book);
    }

    /**
     * 图书是否有可用于分析的内容
     */
    private boolean hasContent(Book book) {
        return (book.getDescription() != null && !book.getDescription().trim().isEmpty())
                || (book.getChapterSummary() != null && !book.getChapterSummary().trim().isEmpty());
    }

    /**
     * 从图书摘要内容中提取作者，如果失败且有封面则尝试封面 OCR
     */
    private boolean extractAuthorFromDescription(Book book) throws Exception {
        String originalAuthor = book.getAuthor();

        // 第一步：从摘要/简介中提取
        ChatModel textModel = chatModelFactory.buildToolChatModel();
        if (textModel == null) {
            log.warn("AI 模型未配置，跳过: 《{}》", book.getTitle());
            return false;
        }

        String prompt = buildPrompt(book);
        long startTime = System.currentTimeMillis();
        ChatResponse response = textModel.chat(List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(prompt)
        ));
        long elapsed = System.currentTimeMillis() - startTime;

        String aiResponse = response.aiMessage().text();
        String extractedAuthor = parseAuthorFromResponse(aiResponse);

        if (extractedAuthor != null && !extractedAuthor.isEmpty()
                && !extractedAuthor.equals(originalAuthor)) {
            log.info("  [摘要] AI 提取作者: {} (耗时: {}ms)", extractedAuthor, elapsed);
            book.setAuthor(extractedAuthor);
            return true;
        }

        // 第二步：摘要未提取到，尝试封面 OCR
        if (hasCover(book)) {
            log.info("  [摘要] 未提取到作者，尝试封面 OCR: 《{}》", book.getTitle());
            String coverAuthor = extractAuthorFromCover(book);
            if (coverAuthor != null && !coverAuthor.isEmpty()
                    && !coverAuthor.equals(originalAuthor)) {
                log.info("  [封面] AI 提取作者: {}", coverAuthor);
                book.setAuthor(coverAuthor);
                return true;
            }
        }

        return false;
    }

    /**
     * 从封面图片中通过视觉模型提取作者
     */
    private String extractAuthorFromCover(Book book) {
        try {
            Path coverFilePath = resolveCoverPath(book);
            if (coverFilePath == null || !Files.exists(coverFilePath)) {
                log.warn("  封面文件不存在: {}", book.getCoverUrl());
                return null;
            }

            // 读取封面图片并转为 base64 data URI
            String dataUri = imageToDataUri(coverFilePath);
            if (dataUri == null) {
                log.warn("  封面图片读取失败: {}", coverFilePath);
                return null;
            }

            // 使用视觉模型识别
            ChatModel visionModel = chatModelFactory.buildVisionChatModel();
            if (visionModel == null) {
                log.warn("  视觉模型未配置");
                return null;
            }

            String coverPrompt = "请从以下图书封面图片中识别作者信息。\n\n"
                    + "书名: " + book.getTitle() + "\n"
                    + "当前作者: " + (book.getAuthor() != null ? book.getAuthor() : "(空)") + "\n\n"
                    + "请仔细观察封面图片中的文字，提取作者信息。返回 JSON: {\"author\": \"作者名\"}\n"
                    + "如果封面中没有作者信息，返回: {\"author\": null}";

            long startTime = System.currentTimeMillis();
            UserMessage userMessage = UserMessage.from(
                    TextContent.from(coverPrompt),
                    ImageContent.from(dataUri)
            );
            ChatResponse response = visionModel.chat(List.of(
                    SystemMessage.from(VISION_SYSTEM_PROMPT),
                    userMessage
            ));
            long elapsed = System.currentTimeMillis() - startTime;

            String aiResponse = response.aiMessage().text();
            log.info("  [封面] AI 响应耗时: {}ms", elapsed);

            return parseAuthorFromResponse(aiResponse);

        } catch (Exception e) {
            log.error("  封面 OCR 失败: 《{}》 - {}", book.getTitle(), e.getMessage());
            return null;
        }
    }

    /**
     * 根据 coverUrl 解析封面文件的本地路径
     */
    private Path resolveCoverPath(Book book) {
        String coverUrl = book.getCoverUrl();
        if (coverUrl == null || coverUrl.isBlank()) return null;

        // coverUrl 格式: /api/books/cover/filename.jpg
        String filename = coverUrl.substring(coverUrl.lastIndexOf('/') + 1);
        Path coverDir = Paths.get(storageProps.getCoverPath());
        return coverDir.resolve(filename);
    }

    /**
     * 将图片文件转为 base64 data URI
     */
    private String imageToDataUri(Path imagePath) {
        try {
            BufferedImage image = ImageIO.read(imagePath.toFile());
            if (image == null) return null;

            String format = imagePath.toString().toLowerCase(Locale.ROOT);
            String imageFormat = format.endsWith(".png") ? "png" : "jpg";

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, imageFormat, baos);
            byte[] bytes = baos.toByteArray();

            String base64 = Base64.getEncoder().encodeToString(bytes);
            return "data:image/" + imageFormat + ";base64," + base64;

        } catch (Exception e) {
            log.error("图片转 Base64 失败: {}", imagePath, e);
            return null;
        }
    }

    /**
     * 图书是否有封面
     */
    private boolean hasCover(Book book) {
        return book.getCoverUrl() != null && !book.getCoverUrl().trim().isEmpty();
    }

    /**
     * 从 AI 响应中解析作者
     */
    private String parseAuthorFromResponse(String aiResponse) {
        try {
            String jsonStr = aiResponse.trim();
            // 兼容 markdown 代码块包裹
            if (jsonStr.contains("```")) {
                int start = jsonStr.indexOf('{');
                int end = jsonStr.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    jsonStr = jsonStr.substring(start, end + 1);
                }
            }

            JsonNode json = objectMapper.readTree(jsonStr);
            if (json.has("author") && !json.get("author").isNull()) {
                String author = json.get("author").asText().trim();
                return author.isEmpty() ? null : author;
            }
        } catch (Exception e) {
            log.warn("解析 AI 响应失败: {}", aiResponse, e);
        }
        return null;
    }

    /**
     * 构建提示词
     */
    private String buildPrompt(Book book) {
        StringBuilder sb = new StringBuilder();
        sb.append("请从以下图书信息中分析并提取作者。\n\n");
        sb.append("书名: ").append(book.getTitle()).append("\n");
        sb.append("当前作者: ").append(
                book.getAuthor() != null && !book.getAuthor().trim().isEmpty()
                        ? book.getAuthor() : "(空)")
                .append("\n");
        sb.append("格式: ").append(book.getFormat()).append("\n\n");

        if (book.getDescription() != null && !book.getDescription().trim().isEmpty()) {
            String desc = book.getDescription().length() > 500
                    ? book.getDescription().substring(0, 500) + "..."
                    : book.getDescription();
            sb.append("简介:\n").append(desc).append("\n\n");
        } else {
            sb.append("简介: (空)\n\n");
        }

        if (book.getChapterSummary() != null && !book.getChapterSummary().trim().isEmpty()) {
            String summary = book.getChapterSummary().length() > 500
                    ? book.getChapterSummary().substring(0, 500) + "..."
                    : book.getChapterSummary();
            sb.append("章节摘要:\n").append(summary).append("\n\n");
        } else {
            sb.append("章节摘要: (空)\n\n");
        }

        sb.append("请分析以上内容，提取作者信息。返回 JSON: {\"author\": \"作者名\"}\n");
        sb.append("如果无法确定作者，返回: {\"author\": null}");

        return sb.toString();
    }
}
