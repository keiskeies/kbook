package com.kbook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.util.CommonUtils;
import com.kbook.config.properties.BookStorageProperties;
import com.kbook.constants.AiPromptConstants;
import com.kbook.entity.Book;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.siegmann.epublib.domain.TOCReference;
import nl.siegmann.epublib.epub.EpubReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 图书解析服务 — EPUB/PDF/TXT 元数据提取与封面生成 + AI 标签生成
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookParserService {

    private final AiProviderConfigService aiProviderConfigService;
    private final BookService bookService;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final BookStorageProperties storageProps;

    /**
     * 封面最大宽度（px）
     */
    private static final int COVER_MAX_WIDTH = 300;

    /**
     * AI 调用操作类型常量
     */
    private static final String AI_OP_TAGS = "标签生成";
    private static final String AI_OP_RATING = "评分生成";
    private static final String AI_OP_RELEVANCE = "相关度得分生成";
    private static final String AI_OP_COMBINED = "合并请求（标签+评分+相关度）";


    /**
     * 初始化时打印封面目录的绝对路径
     */
    @PostConstruct
    public void init() {
        Path absolutePath = Paths.get(storageProps.getCoverPath()).toAbsolutePath();
        log.info("封面存储目录: {}", absolutePath);
    }

    /**
     * 解析图书元数据并填充到 Book 对象
     * - EPUB: 提取作者 + 简介 + 封面图片
     * - PDF:  首页渲染为封面图片
     * - TXT:  提取开头文本
     */
    public void parseAndFill(Book book, Path filePath) {
        switch (book.getFormat()) {
            case "EPUB" -> parseEpub(book, filePath);
            case "PDF" -> parsePdf(book, filePath);
            case "TXT" -> parseTxt(book, filePath);
            default -> log.warn("不支持的格式: {}", book.getFormat());
        }
    }

    /**
     * 解析 EPUB — 提取作者、简介、目录、核心章节摘要、封面
     */
    private void parseEpub(Book book, Path filePath) {
        try (InputStream is = Files.newInputStream(filePath)) {
            EpubReader epubReader = new EpubReader();
            nl.siegmann.epublib.domain.Book epubBook = epubReader.readEpub(is);

            // 提取作者
            if (epubBook.getMetadata() != null && !epubBook.getMetadata().getAuthors().isEmpty()) {
                var author = epubBook.getMetadata().getAuthors().get(0);
                String authorName = (author.getFirstname() != null ? author.getFirstname() + " " : "")
                        + (author.getLastname() != null ? author.getLastname() : "");
                if (!authorName.isBlank()) {
                    book.setAuthor(authorName.trim());
                }
            }

            // 提取简介（优化：使用预编译正则）
            if (epubBook.getMetadata() != null && epubBook.getMetadata().getDescriptions() != null
                    && !epubBook.getMetadata().getDescriptions().isEmpty()) {
                String desc = epubBook.getMetadata().getDescriptions().get(0);
                if (desc != null && !desc.isBlank()) {
                    book.setDescription(HTML_TAG_PATTERN.matcher(desc).replaceAll("").trim());
                }
            }

            // 提取目录信息（递归提取多级目录）
            StringBuilder tocBuilder = new StringBuilder();
            if (epubBook.getTableOfContents() != null) {
                extractEpubTocChildren(epubBook.getTableOfContents().getTocReferences(), tocBuilder, 0);
            }
            // 持久化目录（用于增强元数据向量）
            if (!tocBuilder.isEmpty()) {
                book.setToc(tocBuilder.toString().trim());
            }

            // 提取核心章节摘要（前3章正文内容，每章取前500字）
            String chapterSummary = extractEpubChapterSummary(epubBook);
            if (chapterSummary != null && !chapterSummary.isBlank()) {
                book.setChapterSummary(chapterSummary);
            }

            book.setParsedContent(buildContentForTags(book, tocBuilder.toString()));

            // 提取正文前15000字用于 AI 评分（深度理解内容需要正文，而非仅目录）
            StringBuilder epubBodyForTags = new StringBuilder(20000);
            for (var spineRef : epubBook.getSpine().getSpineReferences()) {
                try {
                    var resource = spineRef.getResource();
                    String html = new String(resource.getData(), java.nio.charset.StandardCharsets.UTF_8);
                    String text = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                    if (!text.isBlank()) {
                        epubBodyForTags.append(text).append("\n");
                    }
                } catch (Exception ignored) {
                }
                if (epubBodyForTags.length() >= 15000) break;
            }
            // 合并目录+正文，让 AI 评分基于实际内容
            String combinedForTags = (!tocBuilder.isEmpty() ? "【目录】\n" + tocBuilder + "\n\n【正文】\n" : "")
                    + epubBodyForTags;
            book.setParsedContent(buildContentForTags(book, combinedForTags));

            // 同时提取全文用于RAG（避免后续 generateContentEmbedding 二次读取文件）
            book.setRagContent(extractEpubFullTextFromEpubBook(epubBook));

            // 提取封面图片
            var coverImage = epubBook.getCoverImage();
            if (coverImage != null && coverImage.getData() != null) {
                long ts = System.currentTimeMillis();
                String ext = "jpg";
                if (coverImage.getMediaType() != null) {
                    String mediaTypeName = coverImage.getMediaType().getName().toLowerCase();
                    if (mediaTypeName.contains("png")) ext = "png";
                    else if (mediaTypeName.contains("gif")) ext = "gif";
                    else if (mediaTypeName.contains("webp")) ext = "webp";
                }
                String tempFileName = "book_new_" + ts + "_cover." + ext;
                Path coverDir = Paths.get(storageProps.getCoverPath());
                Files.createDirectories(coverDir);
                Path coverFilePath = coverDir.resolve(tempFileName);

                // 等比例压缩封面
                BufferedImage srcImage = ImageIO.read(new ByteArrayInputStream(coverImage.getData()));
                if (srcImage != null) {
                    BufferedImage resized = CommonUtils.compressImage(srcImage, ext, COVER_MAX_WIDTH);
                    ImageIO.write(resized, ext, coverFilePath.toFile());
                } else {
                    Files.write(coverFilePath, coverImage.getData());
                }

                book.setCoverUrl("/api/books/cover/" + tempFileName);
                log.info("EPUB 封面保存: {}", tempFileName);
            }

        } catch (Exception e) {
            log.warn("EPUB 解析失败: {} - {}", book.getTitle(), e.getMessage());
        }
    }

    /**
     * 提取 EPUB 核心章节摘要：前3章正文，每章取前500字
     * 优化版本：使用预编译正则 + 减少字符串操作
     */
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");

    private String extractEpubChapterSummary(nl.siegmann.epublib.domain.Book epubBook) {
        try {
            var spineRefs = epubBook.getSpine().getSpineReferences();
            StringBuilder summary = new StringBuilder(1500); // 预分配容量
            int chapterCount = 0;

            for (var spineRef : spineRefs) {
                if (chapterCount >= 3) break;
                try {
                    var resource = spineRef.getResource();
                    if (resource == null || resource.getData() == null) continue;

                    // 直接使用预编译的正则，避免每次重新编译
                    String html = new String(resource.getData(), StandardCharsets.UTF_8);
                    String plainText = HTML_TAG_PATTERN.matcher(html).replaceAll("").trim();

                    // 跳过太短的章节（可能是封面页、版权页等）
                    if (plainText.length() < 50) continue;

                    // 每章取前500字
                    if (plainText.length() > 500) {
                        summary.append(plainText, 0, 500);
                    } else {
                        summary.append(plainText);
                    }
                    summary.append("\n\n");
                    chapterCount++;
                } catch (Exception ignored) {
                }
            }

            // 总摘要限制在2000字以内
            if (summary.length() > 2000) {
                summary.setLength(2000);
            }
            return summary.toString().trim();
        } catch (Exception e) {
            log.debug("提取EPUB章节摘要失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 预编译正则表达式，避免重复编译
     */
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /**
     * 解析 PDF — 首页渲染为封面 + 提取目录 + 核心章节摘要
     */
    private void parsePdf(Book book, Path filePath) {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            // 提取页数
            book.setTotalUnits((long) document.getNumberOfPages());

            // 1. 尝试从 PDF 元数据提取书名和作者
            extractPdfMetadata(book, document);

            // 2. 提取目录（尝试从 PDF 书签/大纲获取）
            String toc = extractPdfToc(document);
            if (toc != null && !toc.isBlank()) {
                book.setToc(toc);
            }

            // 3. 提取前20页文本（用于 AI 评分 + 标签生成 + 核心章节摘要 + 简介生成）
            String firstPagesText = null;
            boolean isScanned = true;
            try {
                PDFTextStripper stripper = new PDFTextStripper();
                int pagesToRead = Math.min(20, document.getNumberOfPages());
                stripper.setStartPage(1);
                stripper.setEndPage(pagesToRead);
                firstPagesText = stripper.getText(document);

                // 判断是否为扫描版（前5页每页平均不到50字）
                String cleaned = WHITESPACE_PATTERN.matcher(firstPagesText).replaceAll("").trim();
                isScanned = cleaned.length() < (long) pagesToRead * 50;
            } catch (Exception e) {
                log.debug("PDF 文本提取失败: {} - {}", book.getTitle(), e.getMessage());
            }

            // 4. 核心章节摘要
            if (firstPagesText != null && !firstPagesText.isBlank() && !isScanned) {
                String cleaned = WHITESPACE_PATTERN.matcher(firstPagesText).replaceAll(" ").trim();
                book.setChapterSummary(cleaned.length() > 2000 ? cleaned.substring(0, 2000) : cleaned);
            }

            // 5. 扫描版 PDF：使用大模型 OCR 提取前几页，获取书名/作者/简介/目录
            if (isScanned) {
                log.info("PDF 疑似扫描版，使用大模型提取元数据: bookId={}", book.getId());
                extractPdfMetadataWithOcr(book, document);
            }

            // 6. 文字型 PDF 缺失作者时，或始终用大模型生成更完整简介
            /* 始终用AI生成更完整的简介 */
            if (!isScanned) {
                inferMetadataFromContent(book, firstPagesText);
            }

            // 7. 构建 AI 标签生成的内容
            book.setParsedContent(buildContentForTags(book, firstPagesText));

            // 8. 提取全文用于RAG（在PDDocument已打开时提取，避免后续二次加载文件）
            try {
                String fullText = extractPdfFullTextFromDocument(book, document, isScanned);
                if (fullText != null && !fullText.isBlank()) {
                    book.setRagContent(fullText);
                }
            } catch (Exception e) {
                log.debug("PDF全文提取（RAG缓存）失败: {} - {}", book.getTitle(), e.getMessage());
            }

            // 9. 首页渲染为封面图片
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(0, 150);

            // 等比例压缩封面
            BufferedImage resized = CommonUtils.compressImage(image, "png", COVER_MAX_WIDTH);

            long ts = System.currentTimeMillis();
            String tempFileName = "book_new_" + ts + "_cover.png";
            Path coverDir = Paths.get(storageProps.getCoverPath());
            Files.createDirectories(coverDir);
            Path coverFilePath = coverDir.resolve(tempFileName);
            ImageIO.write(resized, "png", coverFilePath.toFile());

            book.setCoverUrl("/api/books/cover/" + tempFileName);
            log.info("PDF 封面生成: {}", tempFileName);

        } catch (Exception e) {
            log.warn("PDF 解析失败: {} - {}", book.getTitle(), e.getMessage());
        }
    }

    /**
     * 从 PDF 文档元数据提取书名和作者
     */
    private void extractPdfMetadata(Book book, PDDocument document) {
        try {
            var info = document.getDocumentInformation();
            if (info == null) return;

            String title = info.getTitle();
            if (title != null && !title.isBlank()) {
                book.setTitle(title.trim());
            }

            String author = info.getAuthor();
            if (author != null && !author.isBlank()) {
                book.setAuthor(author.trim());
            }
        } catch (Exception e) {
            log.debug("PDF 元数据提取失败: {}", e.getMessage());
        }
    }

    /**
     * 扫描版 PDF：使用大模型 OCR 提取前几页内容，解析书名/作者/简介/目录
     */
    private void extractPdfMetadataWithOcr(Book book, PDDocument document) {
        ChatModel chatModel = aiProviderConfigService.buildVisionChatModel();
        if (chatModel == null) {
            log.warn("无可用的 AI 模型，PDF 元数据 OCR 解析跳过: bookId={}", book.getId());
            return;
        }

        try {
            // OCR 前3页获取封面/版权页/目录页的文字
            int pagesToOcr = Math.min(3, document.getNumberOfPages());
            PDFRenderer renderer = new PDFRenderer(document);
            List<String> imageDataUris = new ArrayList<>();

            for (int i = 0; i < pagesToOcr; i++) {
                BufferedImage image = null;
                try {
                    image = renderer.renderImageWithDPI(i, 100);
                    String base64 = imageToJpegBase64(image);
                    imageDataUris.add("data:image/jpeg;base64," + base64);
                } finally {
                    if (image != null) image.flush();
                }
            }

            if (imageDataUris.isEmpty()) return;

            // 构建多模态消息：要求模型识别书名/作者/简介/目录
            String ocrPrompt = AiPromptConstants.OCR_METADATA_USER_PROMPT;

            List<Content> contents = new ArrayList<>();
            contents.add(TextContent.from(ocrPrompt));
            for (String dataUri : imageDataUris) {
                contents.add(ImageContent.from(dataUri));
            }

            UserMessage userMessage =
                    UserMessage.from(contents);
            SystemMessage systemMessage =
                    SystemMessage.from(AiPromptConstants.OCR_METADATA_SYSTEM_PROMPT);

            ChatResponse response = chatModel.chat(List.of(systemMessage, userMessage));
            String result = response.aiMessage().text();

            // 清理 markdown 格式
            if (result != null) {
                result = result.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }

            // 解析 JSON 结果
            if (result != null && !result.isBlank()) {
                parseOcrMetadataResult(book, result);
            }

            imageDataUris.clear();

        } catch (Exception e) {
            log.warn("PDF 元数据 OCR 提取失败: bookId={} - {}", book.getId(), e.getMessage());
        }
    }

    /**
     * 解析 OCR 元数据 JSON 结果并填充到 Book 对象
     */
    private void parseOcrMetadataResult(Book book, String json) {
        try {
            var node = objectMapper.readTree(json);

            // 书名（仅当 PDF 元数据也没提取到时才用 OCR 结果）
            if ((book.getTitle() == null || book.getTitle().isBlank() || book.getTitle().matches(".*\\.(pdf|PDF)$"))
                    && node.has("title") && !node.get("title").isNull()) {
                String title = node.get("title").asText().trim();
                if (!title.isBlank()) {
                    book.setTitle(title);
                }
            }

            // 作者
            if ((book.getAuthor() == null || book.getAuthor().isBlank())
                    && node.has("author") && !node.get("author").isNull()) {
                String author = node.get("author").asText().trim();
                if (!author.isBlank()) {
                    book.setAuthor(author);
                }
            }

            // 简介（始终覆盖，OCR提取的更准确）
            if (node.has("description") && !node.get("description").isNull()) {
                String desc = node.get("description").asText().trim();
                if (!desc.isBlank()) {
                    book.setDescription(desc);
                }
            }

            // 目录
            if ((book.getToc() == null || book.getToc().isBlank())
                    && node.has("toc") && !node.get("toc").isNull()) {
                String toc = node.get("toc").asText().trim();
                if (!toc.isBlank()) {
                    book.setToc(toc);
                }
            }

            log.info("PDF OCR 元数据提取成功: bookId={}, title={}, author={}, hasDesc={}, hasToc={}",
                    book.getId(), book.getTitle(), book.getAuthor(),
                    book.getDescription() != null && !book.getDescription().isBlank(),
                    book.getToc() != null && !book.getToc().isBlank());

        } catch (Exception e) {
            log.warn("解析 OCR 元数据 JSON 失败: {} - {}", json.substring(0, Math.min(200, json.length())), e.getMessage());
        }
    }

    /**
     * 提取 PDF 书签/大纲作为目录
     */
    private String extractPdfToc(PDDocument document) {
        try {
            var outline = document.getDocumentCatalog().getDocumentOutline();
            if (outline == null) return null;

            StringBuilder tocBuilder = new StringBuilder();
            extractPdfOutlineChildren(outline.children(), tocBuilder, 0);

            String toc = tocBuilder.toString().trim();
            return toc.isBlank() ? null : toc;
        } catch (Exception e) {
            log.debug("PDF 书签提取失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 递归提取 PDF 大纲子节点
     */
    private void extractPdfOutlineChildren(Iterable<PDOutlineItem> children,
                                           StringBuilder tocBuilder, int depth) {
        if (children == null || depth > 3) return; // 最多3层深度
        for (var item : children) {
            String title = item.getTitle();
            if (title != null && !title.isBlank()) {
                tocBuilder.append("  ".repeat(depth)).append(title.trim()).append("\n");
            }
            extractPdfOutlineChildren(item.children(), tocBuilder, depth + 1);
        }
    }

    /**
     * 递归提取 EPUB 目录子节点（TOCReference 树形结构）
     */
    private void extractEpubTocChildren(List<TOCReference> children,
                                        StringBuilder tocBuilder, int depth) {
        if (children == null || depth > 3) return; // 最多3层深度
        for (var item : children) {
            String title = item.getTitle();
            if (title != null && !title.isBlank()) {
                tocBuilder.append("  ".repeat(depth)).append(title.trim()).append("\n");
            }
            extractEpubTocChildren(item.getChildren(), tocBuilder, depth + 1);
        }
    }

    /**
     * 解析 TXT — 提取开头文本 + 核心章节摘要
     */
    private void parseTxt(Book book, Path filePath) {
        try {
            String content = Files.readString(filePath);
            book.setTotalUnits((long) content.length());

            // 缓存全文用于RAG（避免后续 generateContentEmbedding 二次读取文件）
            book.setRagContent(content);

            // 取前15000字符用于 AI 评分和标签生成
            String preview = content.length() > 15000 ? content.substring(0, 15000) : content;
            book.setParsedContent(buildContentForTags(book, preview));

            // 核心章节摘要：取开头3000字（去掉前200字可能的书名/版权信息）
            int summaryStart = Math.min(200, content.length());
            int summaryEnd = Math.min(summaryStart + 3000, content.length());
            if (summaryEnd > summaryStart) {
                String chapterSummary = content.substring(summaryStart, summaryEnd)
                        .replaceAll("\\s+", " ").trim();
                book.setChapterSummary(chapterSummary.length() > 2000
                        ? chapterSummary.substring(0, 2000) : chapterSummary);
            }

            // 尝试从内容中识别目录（匹配"第X章"或"Chapter X"格式）
            String toc = extractTxtToc(content);
            if (toc != null && !toc.isBlank()) {
                book.setToc(toc);
            }

            // TXT 没有结构化元数据，用大模型从前2000字推断作者和简介
            // 简介始终生成（AI基于正文生成更完整），作者仅在缺失时推断
            inferMetadataFromContent(book, preview);
        } catch (Exception e) {
            log.warn("TXT 解析失败: {} - {}", book.getTitle(), e.getMessage());
        }
    }

    /**
     * 使用大模型从文本内容推断书名/作者/简介
     * 适用于 TXT 和 PDF 文字型（元数据缺失时）
     */
    private void inferMetadataFromContent(Book book, String content) {
        ChatModel chatModel = aiProviderConfigService.buildTagChatModel();
        if (chatModel == null) return;

        try {
            String prompt = "根据以下书籍内容，推断并提取以下信息，以JSON格式返回：\n" +
                    "- author: 作者名（如果内容中能看出来，否则填 null）\n" +
                    "- description: 简短的内容简介（50-200字，概括书籍主题和内容，如果内容中自带简介则提取原简介）\n" +
                    "只返回JSON，不要其他文字。\n\n" +
                    "书籍内容：\n" + CommonUtils.truncateText(content, 2000);

            ChatResponse response = chatModel.chat(List.of(
                    SystemMessage.from(AiPromptConstants.BOOK_INFO_EXTRACT_SYSTEM_PROMPT),
                    UserMessage.from(prompt)
            ));

            String result = response.aiMessage().text();
            if (result != null) {
                result = result.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
                var node = objectMapper.readTree(result);

                if ((book.getAuthor() == null || book.getAuthor().isBlank())
                        && node.has("author") && !node.get("author").isNull()) {
                    String author = node.get("author").asText().trim();
                    if (!author.isBlank() && !"null".equalsIgnoreCase(author)) {
                        book.setAuthor(author);
                    }
                }

                // 简介（始终覆盖，AI生成的更完整）
                if (node.has("description") && !node.get("description").isNull()) {
                    String desc = node.get("description").asText().trim();
                    if (!desc.isBlank() && !"null".equalsIgnoreCase(desc)) {
                        book.setDescription(desc);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从内容推断元数据失败: {} - {}", book.getTitle(), e.getMessage());
        }
    }

    /**
     * 预编译章节匹配正则
     */
    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
            "^\\s*(第[一二三四五六七八九十百千万零\\d]+[章节卷部篇]|Chapter\\s+\\d+).*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );

    /**
     * 尝试从 TXT 内容中识别章节目录（匹配“第X章”或“Chapter X”格式）
     * 优化版本：使用预编译正则 + 限制扫描范围
     */
    private String extractTxtToc(String content) {
        try {
            // 只扫描前 20000 字符寻找目录
            String scanArea = content.length() > 20000 ? content.substring(0, 20000) : content;
            List<String> chapters = new ArrayList<>(50); // 预分配容量
            var matcher = CHAPTER_PATTERN.matcher(scanArea);
            while (matcher.find() && chapters.size() < 50) {
                String line = matcher.group().trim();
                if (!line.isBlank()) {
                    chapters.add(line);
                }
            }

            if (chapters.isEmpty()) return null;

            // 限制目录总长度
            String toc = String.join("\n", chapters);
            return toc.length() > 3000 ? toc.substring(0, 3000) : toc;
        } catch (Exception e) {
            log.debug("TXT 目录提取失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建用于 AI 标签/评分/简介生成的内容摘要
     * 提供更多正文内容，帮助模型深度理解图书质量
     * 32k 上下文约 8-10 万字符，充分利用空间
     */
    private String buildContentForTags(Book book, String extraContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("书名：").append(book.getTitle()).append("\n");
        if (book.getAuthor() != null && !book.getAuthor().isBlank()) {
            sb.append("作者：").append(book.getAuthor()).append("\n");
        }
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            sb.append("简介：").append(CommonUtils.truncateText(book.getDescription(), 1500)).append("\n");
        }
        if (extraContent != null && !extraContent.isBlank()) {
            sb.append("内容/目录：\n").append(CommonUtils.truncateText(extraContent, 15000)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 为图书生成 AI 标签（入库后调用）
     *
     * @param force 是否强制重新生成（即使已有标签）
     */
    public void generateTags(Long bookId, boolean force) {
        try {
            Book book = bookService.getBookById(bookId);
            // 如果已有标签且非强制，跳过
            if (!force && book.getFormatTags() != null && !book.getFormatTags().isBlank()) {
                log.debug("图书已有标签，跳过 AI 生成: bookId={}", bookId);
                return;
            }

            String content = book.getParsedContent();
            // parsedContent 是 @Transient 字段，数据库取出的 book 没有此字段，需重新解析
            if (content == null || content.isBlank()) {
                content = extractContentForTags(book);
            }
            if (content == null || content.isBlank()) {
                log.debug("图书无内容可供生成标签: bookId={}", bookId);
                return;
            }

            String tags = callAiForTags(content);
            if (tags != null && !tags.isBlank()) {
                // 将标签字符串转为 JSON 数组格式
                List<String> tagList = List.of(tags.split("[,，、]"));
                String tagsJson = tagList.stream()
                        .map(String::trim)
                        .filter(t -> !t.isBlank())
                        .map(t -> "\"" + t + "\"")
                        .collect(Collectors.joining(",", "[", "]"));

                bookService.updateFormatTags(bookId, tagList.stream()
                        .map(String::trim)
                        .filter(t -> !t.isBlank())
                        .toList());
                log.info("AI 标签生成成功: bookId={}, tags={}", bookId, tagsJson);
            }
        } catch (Exception e) {
            log.warn("AI 标签生成失败: bookId={} - {}", bookId, e.getMessage());
        }
    }


    /**
     * 从图书文件中重新提取内容用于标签生成
     */
    private String extractContentForTags(Book book) {
        if (book.getFileUrl() == null) {
            return null;
        }
        Path filePath = Paths.get(book.getFileUrl());
        if (!Files.exists(filePath)) {
            return null;
        }

        try {
            return switch (book.getFormat()) {
                case "EPUB" -> extractEpubContent(book, filePath);
                case "PDF" -> extractPdfContent(book, filePath);
                case "TXT" -> extractTxtContent(book, filePath);
                default -> null;
            };
        } catch (Exception e) {
            log.warn("提取图书内容失败: {} - {}", book.getTitle(), e.getMessage());
            return null;
        }
    }

    /**
     * 提取 EPUB 内容 — 正文前15000字 + 目录（用于评分和标签生成）
     */
    private String extractEpubContent(Book book, Path filePath) throws Exception {
        try (InputStream is = Files.newInputStream(filePath)) {
            EpubReader epubReader = new EpubReader();
            nl.siegmann.epublib.domain.Book epubBook = epubReader.readEpub(is);

            // 提取正文内容（前15000字）
            StringBuilder bodyBuilder = new StringBuilder(20000);
            for (var spineRef : epubBook.getSpine().getSpineReferences()) {
                try {
                    var resource = spineRef.getResource();
                    String html = new String(resource.getData(), java.nio.charset.StandardCharsets.UTF_8);
                    String text = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                    if (!text.isBlank()) {
                        bodyBuilder.append(text).append("\n");
                    }
                } catch (Exception ignored) {
                }
                if (bodyBuilder.length() >= 15000) break;
            }

            // 提取目录
            StringBuilder tocBuilder = new StringBuilder();
            if (epubBook.getTableOfContents() != null) {
                extractEpubTocChildren(epubBook.getTableOfContents().getTocReferences(), tocBuilder, 0);
            }

            String combined = (!tocBuilder.isEmpty() ? "【目录】\n" + tocBuilder + "\n\n【正文】\n" : "")
                    + bodyBuilder;
            return buildContentForTags(book, combined);
        }
    }

    /**
     * 提取 PDF 内容（前20页，用于评分和标签生成）
     */
    private String extractPdfContent(Book book, Path filePath) throws Exception {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pagesToRead = Math.min(20, document.getNumberOfPages());
            stripper.setStartPage(1);
            stripper.setEndPage(pagesToRead);
            String text = stripper.getText(document);
            return buildContentForTags(book, text);
        }
    }

    /**
     * 提取 TXT 内容（前15000字符，用于评分和标签生成）
     */
    private String extractTxtContent(Book book, Path filePath) throws Exception {
        String content = Files.readString(filePath);
        String preview = content.length() > 15000 ? content.substring(0, 15000) : content;
        return buildContentForTags(book, preview);
    }

    /**
     * 调用 AI 模型生成标签
     */
    private String callAiForTags(String content) {
        try {
            log.info("========== AI 标签生成请求 ==========");
            log.info("callAiForTags 输入内容: {}", content);

            ChatModel chatModel = aiProviderConfigService.buildTagChatModel();
            if (chatModel == null) {
                log.debug("无可用的 AI 模型，跳过标签生成");
                return null;
            }

            long startTime = System.currentTimeMillis();
            ChatResponse response = chatModel.chat(List.of(
                    SystemMessage.from(AiPromptConstants.TAG_SYSTEM_PROMPT),
                    UserMessage.from(content)
            ));
            long elapsed = System.currentTimeMillis() - startTime;

            String result = response.aiMessage().text();
            // 清理可能的 markdown 格式
            if (result != null) {
                result = result.replaceAll("```[\\s\\S]*?```", "")
                        .replaceAll("[\\[\\]\"'`]", "")
                        .trim();
            }

            // 记录 AI 调用日志
            int inputTokens = CommonUtils.estimateTokens(content);
            int outputTokens = CommonUtils.estimateTokens(result);
            CommonUtils.logAiCall(AI_OP_TAGS, elapsed, inputTokens, outputTokens, result);

            return result;
        } catch (Exception e) {
            log.warn("AI 标签生成调用失败: {}", e.getMessage());
            return null;
        }
    }


    /**
     * 为图书生成 AI 评分
     */
    public void generateRating(Long bookId, boolean force) {
        try {
            Book book = bookService.getBookById(bookId);
            if (!force && book.getRating() != null && book.getRating() > 0) {
                log.debug("图书已有评分，跳过 AI 评分: bookId={}", bookId);
                return;
            }

            String content = book.getParsedContent();
            if (content == null || content.isBlank()) {
                content = extractContentForTags(book);
            }
            if (content == null || content.isBlank()) {
                log.debug("图书无内容可供生成评分: bookId={}", bookId);
                return;
            }

            Double rating = callAiForRating(content);
            if (rating != null) {
                bookService.updateRating(bookId, rating);
                log.info("AI 评分生成成功: bookId={}, rating={}", bookId, rating);
            }
        } catch (Exception e) {
            log.warn("AI 评分生成失败: bookId={} - {}", bookId, e.getMessage());
        }
    }

    /**
     * 为图书生成 AI 评分 - 非强制版本
     */
    public void generateRating(Long bookId) {
        generateRating(bookId, false);
    }

    /**
     * 调用 AI 模型生成评分
     */
    private Double callAiForRating(String content) {
        try {
            log.info("========== AI 评分生成请求 ==========");

            ChatModel chatModel = aiProviderConfigService.buildTagChatModel();
            if (chatModel == null) {
                log.debug("无可用的 AI 模型，跳过评分生成");
                return null;
            }

            long startTime = System.currentTimeMillis();
            ChatResponse response = chatModel.chat(List.of(
                    UserMessage.from(AiPromptConstants.RATING_PROMPT + content)
            ));
            long elapsed = System.currentTimeMillis() - startTime;

            String result = response.aiMessage().text();
            if (result != null) {
                result = result.trim().replaceAll("[^0-9.]", "");
            }

            double rating = 0D;
            try {
                if (result != null) {
                    rating = Double.parseDouble(result);
                }
            } catch (Exception e) {
                log.warn("AI 评分解析失败: result={}", result);
                return null;
            }

            // 限制范围 1.0-5.0
            rating = Math.max(1.0, Math.min(5.0, Math.round(rating * 10.0) / 10.0));

            // 记录 AI 调用日志
            int inputTokens = CommonUtils.estimateTokens(content);
            int outputTokens = CommonUtils.estimateTokens(result);
            CommonUtils.logAiCall(AI_OP_RATING, elapsed, inputTokens, outputTokens, String.valueOf(rating));

            return rating;
        } catch (Exception e) {
            log.warn("AI 评分生成调用失败: {}", e.getMessage());
            return null;
        }
    }


    /**
     * 为图书生成8维度相关度得分
     */
    public void generateRelevanceScores(Long bookId, boolean force) {
        try {
            Book book = bookService.getBookById(bookId);
            if (!force && book.getRelevanceScores() != null && !book.getRelevanceScores().isBlank()) {
                log.debug("图书已有相关度得分，跳过: bookId={}", bookId);
                return;
            }

            String content = book.getParsedContent();
            if (content == null || content.isBlank()) {
                content = extractContentForTags(book);
            }
            if (content == null || content.isBlank()) {
                log.debug("图书无内容可供生成相关度得分: bookId={}", bookId);
                return;
            }

            String scoresJson = callAiForRelevanceScores(content);
            if (scoresJson != null && !scoresJson.isBlank()) {
                bookService.updateRelevanceScores(bookId, scoresJson);
                log.info("8维度相关度得分生成成功: bookId={}", bookId);
            }
        } catch (Exception e) {
            log.warn("8维度相关度得分生成失败: bookId={} - {}", bookId, e.getMessage());
        }
    }

    /**
     * 为图书生成8维度相关度得分 - 非强制版本
     */
    public void generateRelevanceScores(Long bookId) {
        generateRelevanceScores(bookId, false);
    }

    // ======================== 合并 AI 请求（标签 + 评分 + 相关度，一次调用） ========================

    /**
     * 为图书一次性生成所有 AI 数据（标签 + 评分 + 8维度相关度得分），合并为一次 LLM 调用以减少 token 消耗
     *
     * @param bookId 图书ID
     * @param force  是否强制重新生成（即使已有数据）
     */
    public void generateAllAiData(Long bookId, boolean force) {
        try {
            Book book = bookService.getBookById(bookId);

            // 如果非强制且所有数据都已存在，跳过
            if (!force
                    && book.getFormatTags() != null && !book.getFormatTags().isBlank()
                    && book.getRating() != null && book.getRating() > 0
                    && book.getRelevanceScores() != null && !book.getRelevanceScores().isBlank()) {
                log.debug("图书已有全部AI数据，跳过合并生成: bookId={}", bookId);
                return;
            }

            String content = book.getParsedContent();
            if (content == null || content.isBlank()) {
                content = extractContentForTags(book);
            }
            if (content == null || content.isBlank()) {
                log.debug("图书无内容可供生成AI数据: bookId={}", bookId);
                return;
            }

            // 合并调用 AI
            CombinedAiResult result = callAiCombined(content);
            if (result == null) {
                log.warn("合并AI调用返回空结果，回退到单独生成: bookId={}", bookId);
                // 回退到单独调用
                if (force || book.getFormatTags() == null || book.getFormatTags().isBlank()) {
                    generateTags(bookId, force);
                }
                if (force || book.getRating() == null || book.getRating() <= 0) {
                    generateRating(bookId, force);
                }
                if (force || book.getRelevanceScores() == null || book.getRelevanceScores().isBlank()) {
                    generateRelevanceScores(bookId, force);
                }
                // 回退分支也需要生成元数据向量
                embeddingService.generateBookEmbedding(bookId);
                return;
            }

            // 保存标签
            if (result.tags != null && !result.tags.isEmpty()) {
                if (force || book.getFormatTags() == null || book.getFormatTags().isBlank()) {
                    bookService.updateFormatTags(bookId, result.tags);
                    log.info("合并AI - 标签生成成功: bookId={}, tags={}", bookId, result.tags);
                }
            }

            // 保存评分
            if (result.rating != null) {
                if (force || book.getRating() == null || book.getRating() <= 0) {
                    bookService.updateRating(bookId, result.rating);
                    log.info("合并AI - 评分生成成功: bookId={}, rating={}", bookId, result.rating);
                }
            }

            // 保存8维度相关度得分
            if (result.relevanceScoresJson != null && !result.relevanceScoresJson.isBlank()) {
                if (force || book.getRelevanceScores() == null || book.getRelevanceScores().isBlank()) {
                    bookService.updateRelevanceScores(bookId, result.relevanceScoresJson);
                    log.info("合并AI - 相关度得分生成成功: bookId={}", bookId);
                }
            }

            // 保存AI生成的简介（始终覆盖原简介，AI基于正文生成更完整）
            if (result.description != null && !result.description.isBlank()) {
                bookService.updateDescription(bookId, result.description);
                log.info("合并AI - 简介生成成功: bookId={}, 字数={}", bookId, result.description.length());
            }

            // 生成书籍元数据向量（异步执行，不阻塞后续流程）
            // 标签/评分/相关度已保存完毕，元数据向量不依赖这些写入的返回值
            CompletableFuture.runAsync(() -> {
                try {
                    embeddingService.generateBookEmbedding(bookId);
                } catch (Exception ex) {
                    log.warn("异步生成元数据向量失败: bookId={} - {}", bookId, ex.getMessage());
                }
            });

        } catch (Exception e) {
            log.warn("合并AI数据生成失败: bookId={} - {}，回退到单独生成", bookId, e.getMessage());
            // 回退到单独调用
            try {
                generateTags(bookId, force);
                generateRating(bookId, force);
                generateRelevanceScores(bookId, force);
                // 回退分支也异步生成元数据向量
                CompletableFuture.runAsync(() -> {
                    try {
                        embeddingService.generateBookEmbedding(bookId);
                    } catch (Exception ex) {
                        log.warn("异步生成元数据向量失败: bookId={} - {}", bookId, ex.getMessage());
                    }
                });
            } catch (Exception ex) {
                log.warn("回退单独生成也失败: bookId={} - {}", bookId, ex.getMessage());
            }
        }
    }

    /**
     * 为图书一次性生成所有 AI 数据 - 非强制版本
     */
    public void generateAllAiData(Long bookId) {
        generateAllAiData(bookId, false);
    }

    /**
     * 为图书生成 RAG 内容向量（书籍内容分块 → Qdrant）
     * 在图书入库后调用，将书籍全文分块生成 embedding 存入 Qdrant content 集合
     *
     * @param bookId 图书ID
     */
    public void generateContentEmbedding(Long bookId) {
        try {
            Book book = bookService.getBookById(bookId);
            String content = extractContentForRAG(book);
            generateContentEmbedding(bookId, content);
        } catch (Exception e) {
            log.warn("触发RAG内容向量生成失败: bookId={} - {}", bookId, e.getMessage());
        }
    }

    /**
     * 为图书生成 RAG 内容向量（带预提取内容，避免重复解析文件）
     * 扫描流程中可传入已提取的全书内容，省去二次文件读取和解析
     *
     * @param bookId  图书ID
     * @param content 已提取的全文内容（可为null，将自动提取）
     */
    public void generateContentEmbedding(Long bookId, String content) {
        try {
            if (content == null || content.isBlank()) {
                // 优先从缓存的 ragContent 获取（parseAndFill 时已提取）
                Book book = bookService.getBookById(bookId);
                if (book.getRagContent() != null && !book.getRagContent().isBlank()) {
                    content = book.getRagContent();
                    log.debug("使用缓存的RAG内容: bookId={}, contentLen={}", bookId, content.length());
                } else {
                    content = extractContentForRAG(book);
                }
            }
            if (content == null || content.isBlank()) {
                log.debug("图书无内容可供生成RAG向量: bookId={}", bookId);
                return;
            }
            embeddingService.generateContentEmbedding(bookId, content);
            log.info("触发RAG内容向量生成: bookId={}, contentLen={}", bookId, content.length());
        } catch (Exception e) {
            log.warn("触发RAG内容向量生成失败: bookId={} - {}", bookId, e.getMessage());
        }
    }

    /**
     * 为图书生成元数据向量（标题+作者+标签+简介 → 1个 embedding，用于推荐召回）
     *
     * @param bookId 图书ID
     */
    public void generateBookEmbedding(Long bookId) {
        try {
            embeddingService.generateBookEmbedding(bookId);
        } catch (Exception e) {
            log.warn("触发元数据向量生成失败: bookId={} - {}", bookId, e.getMessage());
        }
    }

    /**
     * 提取图书内容用于 RAG 向量化
     */
    private String extractContentForRAG(Book book) {
        if (book.getFileUrl() == null) return null;
        Path filePath = Paths.get(book.getFileUrl());
        if (!Files.exists(filePath)) return null;

        try {
            return switch (book.getFormat()) {
                case "TXT" -> Files.readString(filePath);
                case "EPUB" -> extractEpubFullText(book, filePath);
                case "PDF" -> extractPdfFullText(book, filePath);
                default -> null;
            };
        } catch (Exception e) {
            log.warn("提取RAG内容失败: {} - {}", book.getTitle(), e.getMessage());
            return null;
        }
    }

    /**
     * 提取 EPUB 全文（用于 RAG）
     * 优化版本：使用预分配 StringBuilder + 预编译正则
     */
    private String extractEpubFullText(Book book, Path filePath) throws Exception {
        try (InputStream is = Files.newInputStream(filePath)) {
            EpubReader epubReader = new EpubReader();
            nl.siegmann.epublib.domain.Book epubBook = epubReader.readEpub(is);
            return extractEpubFullTextFromEpubBook(epubBook);
        }
    }

    /**
     * 从已解析的 EPUB 对象中提取全文（用于 RAG）
     * 在 parseEpub 时调用，避免后续二次读取文件
     */
    private String extractEpubFullTextFromEpubBook(nl.siegmann.epublib.domain.Book epubBook) {
        try {
            StringBuilder text = new StringBuilder(200000);
            for (var spineRef : epubBook.getSpine().getSpineReferences()) {
                try {
                    var resource = spineRef.getResource();
                    if (resource == null || resource.getData() == null) continue;
                    String html = new String(resource.getData(), StandardCharsets.UTF_8);
                    String plainText = HTML_TAG_PATTERN.matcher(html).replaceAll("").trim();
                    if (!plainText.isBlank()) {
                        text.append(plainText).append("\n");
                    }
                } catch (Exception ignored) {
                }
            }
            return text.toString();
        } catch (Exception e) {
            log.debug("从EPUB对象提取全文失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 提取 PDF 全文（用于 RAG）
     * <p>
     * 优先尝试 PDFTextStripper 提取文本；
     * 如果提取的文本过少（可能是扫描版 PDF），则使用大模型 OCR 逐页识别图片内容。
     */
    private String extractPdfFullText(Book book, Path filePath) throws Exception {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            int totalPages = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(totalPages);
            String textContent = stripper.getText(document);
            String cleanedText = WHITESPACE_PATTERN.matcher(textContent).replaceAll("").trim();
            boolean isScanned = cleanedText.length() < (long) totalPages * 50;
            return extractPdfFullTextFromDocument(book, document, isScanned);
        }
    }

    /**
     * 从已打开的 PDDocument 中提取全文（用于 RAG）
     * 在 parsePdf 时调用，避免后续二次加载 PDF 文件
     *
     * @param book      图书对象
     * @param document  已打开的 PDDocument
     * @param isScanned 是否为扫描版（由 parsePdf 中判断）
     */
    private String extractPdfFullTextFromDocument(Book book, PDDocument document, boolean isScanned) throws Exception {
        int totalPages = document.getNumberOfPages();

        if (!isScanned) {
            // 文字型 PDF：直接提取全部文本
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(totalPages);
            String textContent = stripper.getText(document);
            log.info("PDF 全文提取（文字型）: bookId={}, pages={}, chars={}",
                    book.getId(), totalPages, WHITESPACE_PATTERN.matcher(textContent).replaceAll("").trim().length());
            return textContent;
        }

        // 扫描版 PDF：使用大模型 OCR 逐页识别
        log.info("PDF 全文提取（扫描版，使用OCR）: bookId={}, pages={}", book.getId(), totalPages);
        return ocrPdfWithVisionModel(book, Paths.get(book.getFileUrl()), totalPages);
    }

    /**
     * OCR 每批次处理的页数（减少批次大小以降低内存峰值）
     */
    private static final int OCR_BATCH_SIZE = 1;

    /**
     * 使用大模型视觉能力 OCR 识别 PDF 全部页面
     * <p>
     * 流程：
     * 1. 将 PDF 每页渲染为图片（PNG）
     * 2. 每 OCR_BATCH_SIZE 页为一批，将图片转为 Base64 发送给大模型
     * 3. 大模型识别图片中的文字并返回
     * 4. 拼接所有批次的识别结果
     */
    private String ocrPdfWithVisionModel(Book book, Path filePath, int totalPages) {
        ChatModel chatModel = aiProviderConfigService.buildVisionChatModel();
        if (chatModel == null) {
            log.warn("无可用的 AI 模型，PDF OCR 解析跳过: bookId={}", book.getId());
            return "";
        }

        StringBuilder fullText = new StringBuilder();

        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);

            for (int batchStart = 0; batchStart < totalPages; batchStart += OCR_BATCH_SIZE) {
                int batchEnd = Math.min(batchStart + OCR_BATCH_SIZE, totalPages);
                log.info("PDF OCR 批次: bookId={}, pages={}-{}/{}", book.getId(), batchStart + 1, batchEnd, totalPages);

                try {
                    // 渲染本批次页面为图片，构建 data URI 列表
                    // 使用 JPEG 格式 + 较低DPI，大幅减少内存占用（JPEG比PNG小5-10倍）
                    List<String> imageDataUris = new ArrayList<>();
                    for (int page = batchStart; page < batchEnd; page++) {
                        BufferedImage image = null;
                        try {
                            image = renderer.renderImageWithDPI(page, 100);
                            String base64 = imageToJpegBase64(image);
                            imageDataUris.add("data:image/jpeg;base64," + base64);
                        } finally {
                            // 及时释放 BufferedImage 内存
                            if (image != null) image.flush();
                        }
                    }

                    // 构建多模态消息发送给大模型
                    String batchDesc = String.format("第%d-%d页（共%d页）", batchStart + 1, batchEnd, totalPages);
                    String ocrResult = callVisionOcr(chatModel, imageDataUris, batchDesc);

                    if (ocrResult != null && !ocrResult.isBlank()) {
                        fullText.append(ocrResult).append("\n\n");
                        log.debug("PDF OCR 批次完成: bookId={}, pages={}-{}, resultLen={}",
                                book.getId(), batchStart + 1, batchEnd, ocrResult.length());
                    }

                    // 释放本批次 data URI 内存
                    imageDataUris.clear();
                } catch (Exception e) {
                    log.warn("PDF OCR 批次失败: bookId={}, pages={}-{} - {}",
                            book.getId(), batchStart + 1, batchEnd, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("PDF OCR 解析异常: bookId={} - {}", book.getId(), e.getMessage());
        }

        String result = fullText.toString().trim();
        log.info("PDF OCR 解析完成: bookId={}, totalPages={}, resultChars={}", book.getId(), totalPages, result.length());
        return result;
    }

    /**
     * 将 BufferedImage 编码为 JPEG Base64 字符串
     * JPEG 格式比 PNG 小 5-10 倍，大幅降低内存和传输开销
     */
    private String imageToJpegBase64(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality((float) 0.85);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    /**
     * 调用大模型视觉能力进行 OCR 识别
     * <p>
     * 将多张图片 + OCR 提示词发送给支持视觉的 ChatModel，
     * 要求模型识别图片中的所有文字内容并原样输出。
     * <p>
     * 使用 OpenAI 兼容 API 的多模态消息格式（image_url 方式），
     * 兼容 Ollama / OpenAI / DeepSeek 等支持视觉的模型。
     */
    private String callVisionOcr(ChatModel chatModel, List<String> imageDataUris, String batchDesc) {
        try {
            // 构建用户消息文本
            String ocrPrompt = String.format(
                    "请仔细识别以下PDF页面图片中的所有文字内容，原样输出，不要遗漏任何文字。" +
                            "保持原文的段落结构，如果图片中有表格，用文本形式还原表格内容。" +
                            "不要添加任何解释、总结或评论，只输出识别到的原文。" +
                            "（当前处理的是%s）", batchDesc);

            // 构建多模态消息内容列表
            List<Content> contents = new ArrayList<>();
            contents.add(TextContent.from(ocrPrompt));
            for (String dataUri : imageDataUris) {
                contents.add(ImageContent.from(dataUri));
            }

            UserMessage userMessage =
                    UserMessage.from(contents);

            SystemMessage systemMessage =
                    SystemMessage.from(
                            "你是一个专业的 OCR 文字识别助手。你的唯一任务是准确识别图片中的文字内容并原样输出。" +
                                    "不要添加任何额外的解释、总结或评论。只输出图片中出现的文字。");

            long startTime = System.currentTimeMillis();
            ChatResponse response = chatModel.chat(List.of(systemMessage, userMessage));
            long elapsed = System.currentTimeMillis() - startTime;

            String result = response.aiMessage().text();

            // 记录 AI 调用日志
            CommonUtils.logAiCall("PDF OCR", elapsed, 0, CommonUtils.estimateTokens(result), batchDesc);

            return result;
        } catch (Exception e) {
            log.warn("大模型 OCR 调用失败: {} - {}", batchDesc, e.getMessage());
            return null;
        }
    }

    /**
     * 合并AI调用结果
     */
    private record CombinedAiResult(List<String> tags, Double rating, String relevanceScoresJson, String description) {
    }

    /**
     * 一次 LLM 调用同时生成标签、评分、8维度相关度得分
     */
    private CombinedAiResult callAiCombined(String content) {
        try {
            log.info("========== AI 合并请求（标签+评分+相关度） ==========");
            log.info("callAiCombined 输入内容: {}", content.replaceAll("\\n", " "));

            ChatModel chatModel = aiProviderConfigService.buildTagChatModel();
            if (chatModel == null) {
                log.debug("无可用的 AI 模型，跳过合并生成");
                return null;
            }

            long startTime = System.currentTimeMillis();
            ChatResponse response = chatModel.chat(List.of(
                    UserMessage.from(AiPromptConstants.COMBINED_PROMPT + content)
            ));
            long elapsed = System.currentTimeMillis() - startTime;

            String rawResult = response.aiMessage().text();
            if (rawResult == null || rawResult.isBlank()) {
                log.warn("AI 合并调用返回空结果");
                log.info("====================================\n");
                return null;
            }

            // 提取 JSON 部分
            rawResult = rawResult.trim();
            int jsonStart = rawResult.indexOf('{');
            int jsonEnd = rawResult.lastIndexOf('}');
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                log.warn("AI 合并调用返回内容无有效JSON: {}", rawResult);
                log.info("====================================\n");
                return null;
            }
            String jsonStr = rawResult.substring(jsonStart, jsonEnd + 1);

            // 解析 JSON
            JsonNode root = objectMapper.readTree(jsonStr);

            // 解析标签
            List<String> tags = null;
            if (root.has("tags") && !root.get("tags").isNull()) {
                String tagsStr = root.get("tags").asText();
                if (tagsStr != null && !tagsStr.isBlank()) {
                    tags = Stream.of(tagsStr.split("[,，、]"))
                            .map(String::trim)
                            .filter(t -> !t.isBlank())
                            .toList();
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
                // 验证是对象格式
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

            // 记录 AI 调用日志
            int inputTokens = CommonUtils.estimateTokens(content);
            int outputTokens = CommonUtils.estimateTokens(rawResult);
            CommonUtils.logAiCall(AI_OP_COMBINED, elapsed, inputTokens, outputTokens,
                    String.format("标签: %s | 评分: %s | 相关度: %s | 简介: %s",
                            tags, rating, relevanceScoresJson != null ? "已生成" : "无",
                            description != null ? "已生成(" + description.length() + "字)" : "无"));

            // 至少有一项结果才算成功
            if (tags == null && rating == null && relevanceScoresJson == null && description == null) {
                return null;
            }

            return new CombinedAiResult(tags, rating, relevanceScoresJson, description);

        } catch (Exception e) {
            log.warn("AI 合并调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 调用 AI 模型生成8维度相关度得分
     */
    private String callAiForRelevanceScores(String content) {
        try {
            log.info("========== AI 相关度得分生成请求 ==========");
            log.info("callAiForRelevanceScores 输入内容: {}", content);

            ChatModel chatModel = aiProviderConfigService.buildTagChatModel();
            if (chatModel == null) {
                log.debug("无可用的 AI 模型，跳过相关度得分生成");
                return null;
            }

            long startTime = System.currentTimeMillis();
            ChatResponse response = chatModel.chat(List.of(
                    UserMessage.from(AiPromptConstants.RELEVANCE_PROMPT + content)
            ));
            long elapsed = System.currentTimeMillis() - startTime;

            String result = response.aiMessage().text();
            if (result != null) {
                // 提取 JSON 部分
                result = result.trim();
                int start = result.indexOf('{');
                int end = result.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    result = result.substring(start, end + 1);
                }
                // 验证 JSON 格式
                objectMapper.readTree(result);
            }

            // 记录 AI 调用日志
            int inputTokens = CommonUtils.estimateTokens(content);
            int outputTokens = CommonUtils.estimateTokens(result);
            CommonUtils.logAiCall(AI_OP_RELEVANCE, elapsed, inputTokens, outputTokens,
                    result != null ? "已生成" : "无");

            return result;
        } catch (Exception e) {
            log.warn("AI 相关度得分生成调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 为新入库的图书重命名封面文件（使用正式的 bookId）
     */
    public void finalizeCover(Book book) {
        if (book.getCoverUrl() == null || book.getId() == null) {
            return;
        }

        String url = book.getCoverUrl();
        String oldFileName = url.substring(url.lastIndexOf('/') + 1);

        if (!oldFileName.startsWith("book_new_")) {
            return;
        }

        try {
            Path coverDir = Paths.get(storageProps.getCoverPath());
            Path oldFile = coverDir.resolve(oldFileName);

            if (!Files.exists(oldFile)) {
                return;
            }

            String ext = oldFileName.substring(oldFileName.lastIndexOf('.'));
            String newFileName = "book_" + book.getId() + "_cover" + ext;
            Path newFile = coverDir.resolve(newFileName);

            Files.move(oldFile, newFile);
            book.setCoverUrl("/api/books/cover/" + newFileName);
            log.info("封面重命名: {} -> {}", oldFileName, newFileName);

        } catch (Exception e) {
            log.warn("封面重命名失败: {}", e.getMessage());
        }
    }
}
