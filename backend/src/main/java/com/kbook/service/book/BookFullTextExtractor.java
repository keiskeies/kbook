package com.kbook.service.book;

import com.kbook.service.user.UserService;
import com.kbook.service.ai.BookContentUtils;
import com.kbook.service.ai.core.ChatModelManager;
import com.kbook.service.ai.core.UserProfileBuilder;
import com.kbook.service.ai.streaming.StreamingSseHandler;
import com.kbook.service.embedding.EmbeddingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.config.ChatModelFactory;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.config.annotation.RedisLock;
import com.kbook.config.properties.BookStorageProperties;
import com.kbook.dto.book.BookSpeedReadVO;
import com.kbook.entity.AiScene;
import com.kbook.entity.Book;
import com.kbook.entity.User;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import nl.siegmann.epublib.domain.MediaType;
import nl.siegmann.epublib.domain.TOCReference;
import nl.siegmann.epublib.epub.EpubReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import com.kbook.constants.ai.BookDigestPrompts;
import com.kbook.constants.ai.BookMetaPrompts;

/**
 * 图书全文/内容提取 — 标签内容构建、分层采样、EPUB/PDF 全文提取与视觉 OCR。
 */
@Slf4j
@Service
public class BookFullTextExtractor {

    /** AI 内容预算字符上限 */
    private static final int MAX_CONTENT_FOR_AI = 15000;
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\s+");

    /** PDF OCR 每批页数 */
    private static final int OCR_BATCH_SIZE = 1;

    /** 分层采样段数 */
    private static final int STRATIFIED_SEGMENTS = 5;

    /**
     * 提取 EPUB 目录文本（供 buildChapterBasedContent / extractEpubChapterSummary 复用）
     */
    String buildEpubTocInternal(nl.siegmann.epublib.domain.Book epubBook) {
        StringBuilder sb = new StringBuilder();
        if (epubBook.getTableOfContents() != null) {
            extractEpubTocChildren(epubBook.getTableOfContents().getTocReferences(), sb, 0);
        }
        return sb.toString().trim();
    }

    /**
     * 递归提取 EPUB 目录子节点（TOCReference 树形结构）
     */
    void extractEpubTocChildren(List<TOCReference> children,
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

    private final BookStorageProperties storageProps;
    private final ChatModelManager chatModelManager;

    public BookFullTextExtractor(BookStorageProperties storageProps, ChatModelManager chatModelManager) {
        this.storageProps = storageProps;
        this.chatModelManager = chatModelManager;
    }

    /**
     * 按章均匀分配预算构建内容（目录 + 正文）
     * 遍历 spine 去 HTML 标签后，将 maxChars 均分给每章，最后一章吃剩余
     */
    String buildChapterBasedContent(nl.siegmann.epublib.domain.Book epubBook) {
        String toc = buildEpubTocInternal(epubBook);

        java.util.List<String> chapters = new java.util.ArrayList<>();
        for (var spineRef : epubBook.getSpine().getSpineReferences()) {
            try {
                var resource = spineRef.getResource();
                if (resource == null || resource.getData() == null) continue;
                String html = new String(resource.getData(), StandardCharsets.UTF_8);
                String text = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                if (text.length() >= 50) chapters.add(text);
                if (chapters.size() >= 200) break;
            } catch (Exception ignored) {
            }
        }

        StringBuilder sb = new StringBuilder();
        if (!toc.isEmpty()) {
            sb.append("【目录】\n").append(toc).append("\n\n");
        }
        sb.append("【正文】\n");

        int remaining = MAX_CONTENT_FOR_AI - sb.length();
        int perChapter = chapters.isEmpty() || remaining <= 0 ? 0 : Math.max(1, remaining / chapters.size());

        for (int i = 0; i < chapters.size() && sb.length() < MAX_CONTENT_FOR_AI; i++) {
            String text = chapters.get(i);
            int take = Math.min(text.length(), perChapter);
            if (i == chapters.size() - 1) {
                take = Math.min(text.length(), MAX_CONTENT_FOR_AI - sb.length());
            }
            sb.append(text, 0, take).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 构建用于 AI 标签/评分/简介生成的内容摘要
     * 提供更多正文内容，帮助模型深度理解图书质量
     * 32k 上下文约 8-10 万字符，充分利用空间
     * 分层采样：从全文中等距取 STRATIFIED_SEGMENTS 段，确保 AI 看到开头、中间、结尾。
     * 内容不足 totalChars 则原样返回，不做截断。
     */
    String stratifiedSample(String content) {
        int len = content.length();
        if (len <= MAX_CONTENT_FOR_AI) return content;

        int segmentSize = MAX_CONTENT_FOR_AI / STRATIFIED_SEGMENTS;
        int remaining = MAX_CONTENT_FOR_AI % STRATIFIED_SEGMENTS;
        StringBuilder sb = new StringBuilder(MAX_CONTENT_FOR_AI + STRATIFIED_SEGMENTS * 30);
        int segments = STRATIFIED_SEGMENTS;
        for (int i = 0; i < segments; i++) {
            int posPercent = Math.round(100f * i / (segments - 1));
            // 用浮点定位避免 int 截断导致最后一段偏前
            int center = (int) ((double) i / (segments - 1) * (len - segmentSize));
            int start = Math.max(0, center);
            int end = Math.min(start + segmentSize + (i == segments - 1 ? remaining : 0), len);
            // 尽量在段落边界断开
            if (end < len && end + 200 < len) {
                int breakAt = content.indexOf('\n', end);
                if (breakAt > end && breakAt - end < 200) end = breakAt;
            }
            sb.append("--- [").append(posPercent).append("% 位置] ---\n");
            sb.append(content, start, end).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 构建用于AI标签/评分/简介生成的内容摘要
     * <p>
     * 组合图书元数据（书名、作者、简介）和正文内容，
     * 为大模型提供完整的上下文信息，用于生成标签、评分和简介。
     *
     * @param book         图书实体对象
     * @param extraContent 额外的正文内容（如目录、章节摘要等）
     * @return 格式化后的完整内容字符串
     */
    String buildContentForTags(Book book, String extraContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("书名：").append(book.getTitle()).append("\n");
        if (book.getAuthor() != null && !book.getAuthor().isBlank()) {
            sb.append("作者：").append(book.getAuthor()).append("\n");
        }
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            sb.append("简介：").append(CommonUtils.truncateText(book.getDescription(), 1500)).append("\n");
        }
        if (extraContent != null && !extraContent.isBlank()) {
            sb.append("内容/目录：\n").append(CommonUtils.truncateText(extraContent, MAX_CONTENT_FOR_AI)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 从图书文件中重新提取内容用于标签生成
     */
    String extractContentForTags(Book book) {
        if (book.getFileUrl() == null) {
            return null;
        }
        Path filePath = storageProps.resolveBookPath(book.getFileUrl(), book.getFormat());
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
     * 提取 EPUB 内容 — 重新读文件，委托给 buildChapterBasedContent（用于评分和标签生成）
     */
    private String extractEpubContent(Book book, Path filePath) {
        try (InputStream is = Files.newInputStream(filePath)) {
            EpubReader epubReader = new EpubReader();
            nl.siegmann.epublib.domain.Book epubBook = epubReader.readEpub(is);
            return buildContentForTags(book, buildChapterBasedContent(epubBook));
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("SAXParseException")) {
                log.warn("EPUB XML 解析失败 (文件损坏或格式不规范): {} - {}", book.getTitle(), e.getMessage());
            } else {
                log.warn("提取 EPUB 内容失败: {} - {}", book.getTitle(), e.getMessage());
            }
            return null;
        }
    }

    /**
     * 提取 PDF 内容 — 从全书均匀分布取 20 页，再分层采样（用于评分和标签生成）
     */
    private String extractPdfContent(Book book, Path filePath) throws Exception {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = document.getNumberOfPages();
            if (totalPages <= 20) {
                // 小文档直接全读
                String text = stripper.getText(document);
                return buildContentForTags(book, text);
            }
            // 均匀分布取 20 页：页 1, 1+step, 1+2*step, ...
            int step = Math.max(1, totalPages / 20);
            StringBuilder sb = new StringBuilder(MAX_CONTENT_FOR_AI * 2);
            for (int i = 0; i < 20 && i * step < totalPages; i++) {
                int page = i * step + 1;
                stripper.setStartPage(page);
                stripper.setEndPage(Math.min(page + 1, totalPages));
                String pageText = stripper.getText(document).trim();
                if (!pageText.isBlank()) {
                    sb.append(pageText).append("\n");
                }
            }
            // 再用分层采样压到预算
            return buildContentForTags(book, stratifiedSample(sb.toString()));
        }
    }

    /**
     * 提取 TXT 内容 — 分层采样全文（用于评分和标签生成）
     */
    private String extractTxtContent(Book book, Path filePath) throws Exception {
        String content = Files.readString(filePath);
        return buildContentForTags(book, stratifiedSample(content));
    }

    /**
     * 提取图书内容用于 RAG 向量化
     */
    String extractContentForRAG(Book book) {
        if (book.getFileUrl() == null) return null;
        Path filePath = storageProps.resolveBookPath(book.getFileUrl(), book.getFormat());
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
     * 增强：捕获 XML 解析异常，降级为直接解压 ZIP 提取文本，防止不规范 EPUB 导致向量化跳过
     */
    private String extractEpubFullText(Book book, Path filePath) {
        try (InputStream is = Files.newInputStream(filePath)) {
            EpubReader epubReader = new EpubReader();
            nl.siegmann.epublib.domain.Book epubBook = epubReader.readEpub(is);
            String text = extractEpubFullTextFromEpubBook(epubBook);
            if (text != null && !text.isBlank()) return text;
        } catch (Exception e) {
            log.warn("epublib 解析 EPUB 失败，尝试 ZIP 降级提取: {} - {}", book.getTitle(), e.getMessage());
        }
        return extractEpubTextViaZip(filePath);
    }

    /**
     * 降级方案：将 EPUB 视为 ZIP 文件，直接提取所有 HTML/XHTML 文本
     */
    String extractEpubTextViaZip(Path filePath) {
        StringBuilder text = new StringBuilder(200000);
        try (var zip = new java.util.zip.ZipFile(filePath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName().toLowerCase();
                if (name.endsWith(".html") || name.endsWith(".xhtml") || name.endsWith(".htm")) {
                    try (var is = zip.getInputStream(entry)) {
                        String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        String plain = HTML_TAG_PATTERN.matcher(html).replaceAll("").trim();
                        if (!plain.isBlank()) {
                            text.append(plain).append("\n");
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            log.warn("ZIP 降级提取也失败: {}", e.getMessage());
        }
        return !text.isEmpty() ? text.toString() : null;
    }

    /**
     * 从已解析的 EPUB 对象中提取全文（用于 RAG）
     * 在 parseEpub 时调用，避免后续二次读取文件
     */
    String extractEpubFullTextFromEpubBook(nl.siegmann.epublib.domain.Book epubBook) {
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
            return !text.isEmpty() ? text.toString() : null;
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
    String extractPdfFullTextFromDocument(Book book, PDDocument document, boolean isScanned) throws Exception {
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
        return ocrPdfWithVisionModel(book, storageProps.resolveBookPath(book.getFileUrl(), book.getFormat()), totalPages);
    }

    /**
     * 使用大模型视觉能力 OCR 识别 PDF 全部页面
     * <p>
     * 流程：
     * 1. 将 PDF 每页渲染为图片（PNG）
     * 2. 每 OCR_BATCH_SIZE 页为一批，将图片转为 Base64 发送给大模型
     * 3. 大模型识别图片中的文字并返回
     * 4. 拼接所有批次的识别结果
     * <p>
     * 走 {@link AiScene#PDF_OCR} 场景路由：用户可在后台显式绑定视觉模型，
     * 未绑定时优先用 YML {@code kbook.ai.vision} 配置，再回退到 QA 配置。
     */
    private String ocrPdfWithVisionModel(Book book, Path filePath, int totalPages) {
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
                    String ocrResult = callVisionOcr(imageDataUris, batchDesc);

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
    String imageToJpegBase64(BufferedImage image) throws IOException {
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
     * 将 data URI 或纯 base64 字符串转换为 ImageContent。
     * <p>
     * langchain4j 的 Ollama 客户端不支持 data URI（会报 "Unsupported url scheme: data"），
     * 需要提取纯 base64 数据后用 {@code ImageContent.from(base64, mimeType)} 构建，
     * 这样 Image 对象的 base64Data 字段被设置，Ollama 会直接用 base64 数据而非 URL 加载。
     * OpenAI 兼容客户端同样支持这种方式。
     *
     * @param dataUri data URI（"data:image/jpeg;base64,xxxx"）或纯 base64 字符串
     * @return ImageContent 实例
     */
    static ImageContent toImageContent(String dataUri) {
        String base64 = dataUri;
        String mimeType = "image/jpeg";
        if (dataUri.startsWith("data:")) {
            int commaIdx = dataUri.indexOf(',');
            if (commaIdx > 0) {
                // 解析 data:image/jpeg;base64,xxxx 格式
                String header = dataUri.substring(5, commaIdx); // image/jpeg;base64
                int semiIdx = header.indexOf(';');
                if (semiIdx > 0) {
                    mimeType = header.substring(0, semiIdx);
                }
                base64 = dataUri.substring(commaIdx + 1);
            }
        }
        return ImageContent.from(base64, mimeType);
    }

    /**
     * 调用大模型视觉能力进行 OCR 识别
     * <p>
     * 将多张图片 + OCR 提示词发送给支持视觉的 ChatModel，
     * 要求模型识别图片中的所有文字内容并原样输出。
     * <p>
     * 走 {@link AiScene#PDF_OCR} 场景路由，日志由 {@code callAiForScene} 统一记录
     * （含模型名/场景/思考配置/请求消息/响应/token 统计）。
     * <p>
     * 使用 OpenAI 兼容 API 的多模态消息格式（image_url 方式），
     * 兼容 Ollama / OpenAI / DeepSeek 等支持视觉的模型。
     */
    private String callVisionOcr(List<String> imageDataUris, String batchDesc) {
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
                contents.add(toImageContent(dataUri));
            }

            UserMessage userMessage =
                    UserMessage.from(contents);

            SystemMessage systemMessage =
                    SystemMessage.from(
                            "你是一个专业的 OCR 文字识别助手。你的唯一任务是准确识别图片中的文字内容并原样输出。" +
                                    "不要添加任何额外的解释、总结或评论。只输出图片中出现的文字。");
            // /no_think 由 callAiForScene 自动追加（PDF_OCR thinking=false）

            return chatModelManager.callAiForScene(AiScene.PDF_OCR, "PDF OCR", batchDesc,
                    List.of(systemMessage, userMessage));
        } catch (Exception e) {
            log.warn("大模型 OCR 调用失败: {} - {}", batchDesc, e.getMessage());
            return null;
        }
    }
}
