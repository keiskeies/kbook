package com.kbook.test;

import com.kbook.common.util.CommonUtils;
import com.kbook.config.ChatModelFactory;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.book.BookService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 图书目录（TOC）生成测试工具
 * <p>
 * 针对没有目录（toc 为 null 或空白）或目录过短的图书，
 * 使用 LLM 对图书前 15000 字进行分析，提取/生成章节标题作为目录。
 * <p>
 * 使用方法：
 * <ol>
 *   <li>previewOnly(): 只列出待处理图书，不调用 LLM</li>
 *   <li>generateTocForBooks(): 调用 LLM 处理，DRY_RUN=true 只预览不保存，false 保存到数据库</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("dev")
public class BookTocGenerateTool {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private ChatModelFactory chatModelFactory;

    @Autowired
    private BookService bookService;

    /** true=只预览不保存, false=保存到数据库 */
    private static final boolean DRY_RUN = true;

    /** 送给 LLM 分析的最大字符数（图书正文前 N 字） */
    private static final int MAX_CONTENT_CHARS = 15000;

    /** 目录过短的判定阈值：少于 N 行视为无有效目录 */
    private static final int MIN_TOC_LINES = 5;

    /** 目录过短的判定阈值：少于 N 字符视为无有效目录 */
    private static final int MIN_TOC_CHARS = 60;

    /** HTML 标签正则 */
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /** 并发线程数 */
    private static final int THREAD_COUNT = 5;

    private static final String SYSTEM_PROMPT = """
            你是一个图书目录提取专家。你的任务是从图书正文开头部分的内容中，提取或推断出本书的章节目录。
            
            规则：
            1. 仔细阅读提供的图书正文开头内容
            2. 提取正文中出现的所有章节标题（如"第一章"、"第1章"、"Chapter 1"、"一、"等格式）
            3. 如果正文中没有显式的章节标题，根据内容分段和主题转换推断出合理的章节结构
            4. 注意识别各种章节格式：中文数字（第一章）、阿拉伯数字（第1章）、"Chapter X"、
               "Part X"、"Section X"、罗马数字等
            5. 保留章节的层级缩进（如有子章节，用两个空格缩进表示）
            6. 每一行一个章节标题
            7. 如果确实无法提取任何章节信息，返回空字符串
            8. 只返回目录文本，不要添加任何解释、总结或评论
            """;

    /**
     * 预览模式：只列出待处理图书，不调用 LLM
     */
    @Test
    public void previewOnly() {
        List<Book> allBooks = bookRepository.findAll();
        List<Book> targetBooks = filterBooksWithoutToc(allBooks);

        System.out.println("=== 需要生成目录的图书 ===");
        System.out.printf("总图书数: %d, 需处理: %d%n%n", allBooks.size(), targetBooks.size());

        for (Book b : targetBooks) {
            String tocPreview = b.getToc() != null && !b.getToc().isBlank()
                    ? truncatePreview(b.getToc(), 60) : "(无目录)";
            System.out.printf("  id=%-6d %-30s format=%-4s 现有目录: %s%n",
                    b.getId(), "《" + b.getTitle() + "》", b.getFormat(), tocPreview);
        }
    }

    /**
     * 批量生成目录
     */
    @Test
    public void generateTocForBooks() {
        ChatModel chatModel = chatModelFactory.buildToolChatModel();
        if (chatModel == null) {
            System.err.println("AI 模型不可用，跳过测试");
            return;
        }

        List<Book> allBooks = bookRepository.findAll();
        List<Book> targetBooks = filterBooksWithoutToc(allBooks);

        System.out.printf("总图书数: %d, 需处理: %d, 模式: %s%n%n",
                allBooks.size(), targetBooks.size(), DRY_RUN ? "预览(不保存)" : "正式(保存)");

        if (targetBooks.isEmpty()) {
            System.out.println("没有需要处理的图书，所有图书已有完整目录。");
            return;
        }

        long totalStartTime = System.currentTimeMillis();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(targetBooks.size());

        for (Book book : targetBooks) {
            executor.submit(() -> {
                long bookStartTime = System.currentTimeMillis();
                try {
                    // 检查文件是否存在
                    if (book.getFileUrl() == null || book.getFileUrl().isBlank()) {
                        System.out.printf("  ⊘ [%d] 《%s》 无文件路径，跳过%n", book.getId(), book.getTitle());
                        skippedCount.incrementAndGet();
                        return;
                    }
                    Path filePath = Path.of(book.getFileUrl());
                    if (!Files.exists(filePath)) {
                        System.out.printf("  ⊘ [%d] 《%s》 文件不存在: %s，跳过%n",
                                book.getId(), book.getTitle(), filePath);
                        skippedCount.incrementAndGet();
                        return;
                    }

                    // 提取前 MAX_CONTENT_CHARS 字符的正文
                    String contentBeginning = extractContentBeginning(book, filePath);
                    if (contentBeginning == null || contentBeginning.isBlank()) {
                        System.out.printf("  ⊘ [%d] 《%s》 无法提取正文内容，跳过%n", book.getId(), book.getTitle());
                        skippedCount.incrementAndGet();
                        return;
                    }

                    // 调用 LLM 提取目录
                    String newToc = callLlmForToc(chatModel, book, contentBeginning);
                    if (newToc == null) {
                        System.out.printf("  ⊘ [%d] 《%s》 LLM 未返回目录，跳过%n", book.getId(), book.getTitle());
                        skippedCount.incrementAndGet();
                        return;
                    }

                    String oldToc = book.getToc() != null ? book.getToc() : "(无目录)";
                    String oldPreview = truncatePreview(oldToc, 80);
                    String newPreview = truncatePreview(newToc, 80);

                    boolean hasChange = !newToc.isBlank() && (book.getToc() == null
                            || book.getToc().isBlank()
                            || !book.getToc().equals(newToc));

                    if (!hasChange) {
                        System.out.printf("  ⊘ [%d] 《%s》 目录无变化 (已有 %d 行)%n",
                                book.getId(), book.getTitle(),
                                book.getToc() != null ? book.getToc().split("\n").length : 0);
                        skippedCount.incrementAndGet();
                        return;
                    }

                    System.out.printf("  ✓ [%d] 《%s»%n      旧目录: %s%n      新目录: %s%n",
                            book.getId(), book.getTitle(), oldPreview, newPreview);

                    // 保存到数据库
                    if (!DRY_RUN) {
                        book.setToc(newToc);
                        bookService.updateBook(book.getId(), book);
                    }

                    long elapsed = System.currentTimeMillis() - bookStartTime;
                    int inputTokens = CommonUtils.estimateTokens(SYSTEM_PROMPT + contentBeginning);
                    int outputTokens = CommonUtils.estimateTokens(newToc);
                    CommonUtils.logAiCall("目录提取", elapsed, inputTokens, outputTokens,
                            "[" + book.getId() + "] " + book.getTitle() + " | " + newPreview);

                    successCount.incrementAndGet();

                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.err.printf("  ✗ [%d] 《%s》 - %s%n", book.getId(), book.getTitle(), e.getMessage());
                } finally {
                    completedCount.incrementAndGet();
                    latch.countDown();
                }
            });
        }

        // 进度监控线程
        Thread progressThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                }
                int done = completedCount.get();
                if (done >= targetBooks.size()) break;
                long elapsedMs = System.currentTimeMillis() - totalStartTime;
                int processed = successCount.get() + failCount.get();
                double avgMs = processed > 0 ? (double) elapsedMs / processed : 0;
                int remaining = targetBooks.size() - done;
                double estMin = remaining * avgMs / 60000.0;
                System.out.printf("[进度] %d/%d | 成功=%d 失败=%d 跳过=%d | 已用=%.1f分 预计剩余=%.1f分%n",
                        done, targetBooks.size(),
                        successCount.get(), failCount.get(), skippedCount.get(),
                        elapsedMs / 60000.0, estMin);
            }
        });
        progressThread.setDaemon(true);
        progressThread.start();

        try {
            latch.await();
            executor.shutdown();
            progressThread.interrupt();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long totalMs = System.currentTimeMillis() - totalStartTime;
        int totalProcessed = successCount.get() + failCount.get() + skippedCount.get();
        System.out.printf("%n========== 处理完成 ==========%n");
        System.out.printf("需处理: %d, 成功: %d, 失败: %d, 跳过: %d, 耗时: %.1f分钟%n",
                targetBooks.size(), successCount.get(), failCount.get(), skippedCount.get(), totalMs / 60000.0);
        if (DRY_RUN) {
            System.out.println("（预览模式，未保存到数据库。将 DRY_RUN=false 以保存）");
        }
    }

    // ======================== 筛选逻辑 ========================

    /**
     * 筛选出没有目录或目录过短的图书
     */
    private List<Book> filterBooksWithoutToc(List<Book> books) {
        List<Book> result = new ArrayList<>();
        for (Book book : books) {
            String toc = book.getToc();
            if (toc == null || toc.isBlank()) {
                result.add(book);
            } else {
                String trimmed = toc.trim();
                int lines = trimmed.split("\n").length;
                int chars = trimmed.length();
                if (lines < MIN_TOC_LINES || chars < MIN_TOC_CHARS) {
                    result.add(book);
                }
            }
        }
        return result;
    }

    /**
     * 统计文本行数
     */
    private int countLines(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.trim().split("\n").length;
    }

    // ======================== 正文提取 ========================

    /**
     * 根据图书格式提取前 MAX_CONTENT_CHARS 字符的正文
     */
    private String extractContentBeginning(Book book, Path filePath) throws IOException {
        return switch (book.getFormat()) {
            case "TXT" -> extractTxtBeginning(filePath);
            case "EPUB" -> extractEpubBeginning(filePath);
            case "PDF" -> extractPdfBeginning(filePath);
            default -> {
                System.err.printf("  不支持的格式: %s (bookId=%d)%n", book.getFormat(), book.getId());
                yield null;
            }
        };
    }

    /**
     * 提取 TXT 格式前 MAX_CONTENT_CHARS 字符
     */
    private String extractTxtBeginning(Path filePath) throws IOException {
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        // 清理连续空白
        content = WHITESPACE_PATTERN.matcher(content).replaceAll(" ").trim();
        return content.length() > MAX_CONTENT_CHARS
                ? content.substring(0, MAX_CONTENT_CHARS)
                : content;
    }

    /**
     * 提取 EPUB 格式前 MAX_CONTENT_CHARS 字符（通过 ZIP 解压提取 HTML 正文）
     */
    private String extractEpubBeginning(Path filePath) {
        StringBuilder text = new StringBuilder(MAX_CONTENT_CHARS * 2);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(filePath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase();
                if (name.endsWith(".html") || name.endsWith(".xhtml") || name.endsWith(".htm")) {
                    if (text.length() >= MAX_CONTENT_CHARS) break;
                    byte[] data = zis.readAllBytes();
                    String html = new String(data, StandardCharsets.UTF_8);
                    String plain = HTML_TAG_PATTERN.matcher(html).replaceAll("").trim();
                    plain = WHITESPACE_PATTERN.matcher(plain).replaceAll(" ").trim();
                    if (!plain.isBlank()) {
                        text.append(plain).append("\n");
                    }
                }
            }
        } catch (Exception e) {
            System.err.printf("  EPUB 内容提取失败: %s - %s%n", filePath, e.getMessage());
            return null;
        }
        String result = text.toString().trim();
        return result.length() > MAX_CONTENT_CHARS
                ? result.substring(0, MAX_CONTENT_CHARS)
                : result;
    }

    /**
     * 提取 PDF 格式前 MAX_CONTENT_CHARS 字符（提取前 N 页）
     */
    private String extractPdfBeginning(Path filePath) {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            int totalPages = document.getNumberOfPages();
            int pagesToRead = Math.min(15, totalPages);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(pagesToRead);
            String text = stripper.getText(document);
            String cleaned = WHITESPACE_PATTERN.matcher(text).replaceAll(" ").trim();
            return cleaned.length() > MAX_CONTENT_CHARS
                    ? cleaned.substring(0, MAX_CONTENT_CHARS)
                    : cleaned;
        } catch (Exception e) {
            System.err.printf("  PDF 内容提取失败: %s - %s%n", filePath, e.getMessage());
            return null;
        }
    }

    // ======================== LLM 调用 ========================

    /**
     * 调用 LLM 提取目录
     */
    private String callLlmForToc(ChatModel chatModel, Book book, String contentBeginning) {
        String userPrompt = String.format("""
                请从以下图书正文开头内容中提取章节目录。
                
                图书信息：
                书名：%s
                作者：%s
                格式：%s
                现有目录：%s
                
                以下是正文开头的内容（约 %d 字）：
                ————————————————————————
                %s
                ————————————————————————
                
                请提取或推断出本书的章节目录，每行一个章节标题。
                如果有层级结构，子章节用两个空格缩进。
                如果无法提取任何章节，返回空字符串。
                只输出目录文本，不要添加任何解释。
                """,
                book.getTitle() != null ? book.getTitle() : "",
                book.getAuthor() != null ? book.getAuthor() : "",
                book.getFormat() != null ? book.getFormat() : "",
                book.getToc() != null && !book.getToc().isBlank() ? truncatePreview(book.getToc(), 100) : "(无)",
                contentBeginning.length(),
                contentBeginning
        );

        ChatResponse response = chatModel.chat(List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(userPrompt)
        ));

        String result = response.aiMessage().text();
        if (result == null || result.isBlank()) return null;

        // 清理 markdown 代码块包裹
        result = CommonUtils.stripCodeFence(result);

        // 清理可能的 JSON 包裹（LLM 有时会返回 JSON 格式）
        if (result.startsWith("[") && result.endsWith("]")) {
            // 如果返回的是 JSON 数组，尝试解析为纯文本行
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                List<String> items = mapper.readValue(result,
                        new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                result = String.join("\n", items);
            } catch (Exception ignored) {
                // 不是有效的 JSON 数组，保持原样
            }
        }

        result = result.trim();
        return result.isBlank() ? null : result;
    }

    // ======================== 工具方法 ========================

    /**
     * 截断文本用于打印预览
     */
    private String truncatePreview(String text, int maxLen) {
        if (text == null) return "(空)";
        String oneLine = text.replace("\n", " | ");
        if (oneLine.length() <= maxLen) return oneLine;
        return oneLine.substring(0, maxLen) + "...";
    }
}
