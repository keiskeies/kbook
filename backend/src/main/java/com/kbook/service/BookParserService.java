package com.kbook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.util.CommonUtils;
import com.kbook.entity.Book;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

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

    @Value("${kbook.cover-path}")
    private String coverPath;

    /** 封面最大宽度（px） */
    private static final int COVER_MAX_WIDTH = 300;

    /** AI 调用操作类型常量 */
    private static final String AI_OP_TAGS = "标签生成";
    private static final String AI_OP_RATING = "评分生成";
    private static final String AI_OP_RELEVANCE = "相关度得分生成";
    private static final String AI_OP_COMBINED = "合并请求（标签+评分+相关度）";

    /**
     * AI 标签生成的系统提示词（单独调用时使用）
     */
    private static final String TAG_SYSTEM_PROMPT = """
            你是一个专业的图书标签生成助手。根据提供的图书信息（书名、作者、简介或目录），生成3-8个精准的标签。
            
            规则：
            - 标签应涵盖：类型/题材、风格、主题、读者群体等维度
            - 每个标签2-4个字，简洁准确
            - 只返回标签，用逗号分隔，不要编号和解释
            - 示例：科幻,太空歌剧,经典,冒险
            
            图书信息如下：
            """;

    /**
     * 合并AI请求的系统提示词 — 一次调用同时生成标签、评分、8维度相关度得分
     */
    private static final String COMBINED_PROMPT = """
            你是一个专业的图书分析助手。根据提供的图书信息（书名、作者、简介或目录），同时完成以下三项任务：

            任务1：生成3-8个精准的标签
            - 标签应涵盖：类型/题材、风格、主题、读者群体等维度
            - 每个标签2-4个字，简洁准确
            - 用逗号分隔，不要编号和解释
            - 示例：科幻,太空歌剧,经典,冒险

            任务2：给出1-5之间的评分（一位小数）
            评分标准（5星制）：
            - 1-2星：较差或平庸
            - 2-3星：一般，有一定可读性
            - 3-4星：良好，值得推荐
            - 4-5星：优秀，强烈推荐
            注意：未知信息较多的书给中等评分（3.0-3.5），经典名著一般4.0-5.0，普通书籍2.5-4.0

            任务3：为以下维度打分（0-1之间的小数），返回JSON格式
            年龄段："0-9","10-19","20-29","30-39","40-49","50-59","60+"
            性别："male","female"
            婚姻："married","unmarried"
            子女："hasChildren","noChildren"
            MBTI："INTJ","INTP","ENTJ","ENTP","INFJ","INFP","ENFJ","ENFP","ISTJ","ISFJ","ESTJ","ESFJ","ISTP","ISFP","ESTP","ESFP"

            只返回以下JSON格式，不要其他文字：
            {
              "tags": "标签1,标签2,标签3",
              "rating": 3.8,
              "relevance": {"0-9":0.1,"10-19":0.3,"20-29":0.8,"30-39":0.7,"40-49":0.5,"50-59":0.3,"60+":0.2,"male":0.6,"female":0.7,"married":0.5,"unmarried":0.8,"hasChildren":0.4,"noChildren":0.8,"INTJ":0.7,"INTP":0.6,"ENTJ":0.5,"ENTP":0.6,"INFJ":0.8,"INFP":0.9,"ENFJ":0.7,"ENFP":0.7,"ISTJ":0.4,"ISFJ":0.5,"ESTJ":0.3,"ESFJ":0.4,"ISTP":0.4,"ISFP":0.5,"ESTP":0.3,"ESFP":0.4}
            }

            图书信息如下：
            """;

    /**
     * 初始化时打印封面目录的绝对路径
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        Path absolutePath = Paths.get(coverPath).toAbsolutePath();
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
            nl.siegmann.epublib.epub.EpubReader epubReader = new nl.siegmann.epublib.epub.EpubReader();
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

            // 提取简介
            if (epubBook.getMetadata() != null && epubBook.getMetadata().getDescriptions() != null
                    && !epubBook.getMetadata().getDescriptions().isEmpty()) {
                String desc = epubBook.getMetadata().getDescriptions().get(0);
                if (desc != null && !desc.isBlank()) {
                    book.setDescription(desc.replaceAll("<[^>]+>", "").trim());
                }
            }

            // 提取目录信息
            StringBuilder tocBuilder = new StringBuilder();
            if (epubBook.getTableOfContents() != null) {
                for (var tocItem : epubBook.getTableOfContents().getTocReferences()) {
                    if (tocItem.getTitle() != null && !tocItem.getTitle().isBlank()) {
                        tocBuilder.append(tocItem.getTitle()).append("\n");
                    }
                }
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
                Path coverDir = Paths.get(coverPath);
                Files.createDirectories(coverDir);
                Path coverFilePath = coverDir.resolve(tempFileName);

                // 等比例压缩封面
                BufferedImage srcImage = ImageIO.read(new java.io.ByteArrayInputStream(coverImage.getData()));
                if (srcImage != null) {
                    BufferedImage resized = CommonUtils.compressCover(srcImage, ext, COVER_MAX_WIDTH);
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
     */
    private String extractEpubChapterSummary(nl.siegmann.epublib.domain.Book epubBook) {
        try {
            var spineRefs = epubBook.getSpine().getSpineReferences();
            StringBuilder summary = new StringBuilder();
            int chapterCount = 0;

            for (var spineRef : spineRefs) {
                if (chapterCount >= 3) break;
                try {
                    var resource = spineRef.getResource();
                    if (resource == null || resource.getData() == null) continue;

                    String html = new String(resource.getData(), java.nio.charset.StandardCharsets.UTF_8);
                    String plainText = html.replaceAll("<[^>]+>", "").trim();

                    // 跳过太短的章节（可能是封面页、版权页等）
                    if (plainText.length() < 50) continue;

                    // 每章取前500字
                    String chapterExcerpt = plainText.length() > 500
                            ? plainText.substring(0, 500)
                            : plainText;
                    summary.append(chapterExcerpt).append("\n\n");
                    chapterCount++;
                } catch (Exception ignored) {}
            }

            // 总摘要限制在2000字以内
            String result = summary.toString().trim();
            return result.length() > 2000 ? result.substring(0, 2000) : result;
        } catch (Exception e) {
            log.debug("提取EPUB章节摘要失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 PDF — 首页渲染为封面 + 提取目录 + 核心章节摘要
     */
    private void parsePdf(Book book, Path filePath) {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            // 提取页数
            book.setTotalUnits((long) document.getNumberOfPages());

            // 提取目录（尝试从 PDF 书签/大纲获取）
            String toc = extractPdfToc(document);
            if (toc != null && !toc.isBlank()) {
                book.setToc(toc);
            }

            // 提取前5页文本（用于 AI 标签生成 + 核心章节摘要）
            String firstPagesText = null;
            try {
                PDFTextStripper stripper = new PDFTextStripper();
                int pagesToRead = Math.min(5, document.getNumberOfPages());
                stripper.setStartPage(1);
                stripper.setEndPage(pagesToRead);
                firstPagesText = stripper.getText(document);
            } catch (Exception e) {
                log.debug("PDF 文本提取失败: {} - {}", book.getTitle(), e.getMessage());
            }

            // 核心章节摘要：取前5页的摘要文本
            if (firstPagesText != null && !firstPagesText.isBlank()) {
                String cleaned = firstPagesText.replaceAll("\\s+", " ").trim();
                // 限制在2000字以内
                book.setChapterSummary(cleaned.length() > 2000 ? cleaned.substring(0, 2000) : cleaned);
            }

            // 构建 AI 标签生成的内容
            book.setParsedContent(buildContentForTags(book, firstPagesText));

            // 首页渲染为封面图片
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(0, 150);

            // 等比例压缩封面
            BufferedImage resized = CommonUtils.compressCover(image, "png", COVER_MAX_WIDTH);

            long ts = System.currentTimeMillis();
            String tempFileName = "book_new_" + ts + "_cover.png";
            Path coverDir = Paths.get(coverPath);
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
    private void extractPdfOutlineChildren(Iterable<org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem> children,
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
     * 解析 TXT — 提取开头文本 + 核心章节摘要
     */
    private void parseTxt(Book book, Path filePath) {
        try {
            String content = Files.readString(filePath);
            book.setTotalUnits((long) content.length());

            // 取前2000字符用于 AI 标签生成
            String preview = content.length() > 2000 ? content.substring(0, 2000) : content;
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
        } catch (Exception e) {
            log.warn("TXT 解析失败: {} - {}", book.getTitle(), e.getMessage());
        }
    }

    /**
     * 尝试从 TXT 内容中识别章节目录（匹配"第X章"或"Chapter X"格式）
     */
    private String extractTxtToc(String content) {
        try {
            // 只扫描前 20000 字符寻找目录
            String scanArea = content.length() > 20000 ? content.substring(0, 20000) : content;
            java.util.List<String> chapters = new java.util.ArrayList<>();
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "^\\s*(第[一二三四五六七八九十百千万零\\d]+[章节卷部篇]|Chapter\\s+\\d+).*$",
                    java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.CASE_INSENSITIVE
            );
            var matcher = pattern.matcher(scanArea);
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
     * 构建用于 AI 标签生成的内容摘要
     */
    private String buildContentForTags(Book book, String extraContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("书名：").append(book.getTitle()).append("\n");
        if (book.getAuthor() != null && !book.getAuthor().isBlank()) {
            sb.append("作者：").append(book.getAuthor()).append("\n");
        }
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            sb.append("简介：").append(CommonUtils.truncateText(book.getDescription(), 500)).append("\n");
        }
        if (extraContent != null && !extraContent.isBlank()) {
            sb.append("内容/目录：\n").append(CommonUtils.truncateText(extraContent, 1500)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 异步为图书生成 AI 标签（入库后调用）
     *
     * @param force 是否强制重新生成（即使已有标签）
     */
    @Async
    public void generateTagsAsync(Long bookId, boolean force) {
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
     * 异步为图书生成 AI 标签（入库后调用）- 非强制版本
     */
    @Async
    public void generateTagsAsync(Long bookId) {
        generateTagsAsync(bookId, false);
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
     * 提取 EPUB 内容
     */
    private String extractEpubContent(Book book, Path filePath) throws Exception {
        try (InputStream is = Files.newInputStream(filePath)) {
            nl.siegmann.epublib.epub.EpubReader epubReader = new nl.siegmann.epublib.epub.EpubReader();
            nl.siegmann.epublib.domain.Book epubBook = epubReader.readEpub(is);

            StringBuilder tocBuilder = new StringBuilder();
            if (epubBook.getTableOfContents() != null) {
                for (var tocItem : epubBook.getTableOfContents().getTocReferences()) {
                    if (tocItem.getTitle() != null && !tocItem.getTitle().isBlank()) {
                        tocBuilder.append(tocItem.getTitle()).append("\n");
                    }
                }
            }
            return buildContentForTags(book, tocBuilder.toString());
        }
    }

    /**
     * 提取 PDF 内容（前5页）
     */
    private String extractPdfContent(Book book, Path filePath) throws Exception {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pagesToRead = Math.min(5, document.getNumberOfPages());
            stripper.setStartPage(1);
            stripper.setEndPage(pagesToRead);
            String text = stripper.getText(document);
            return buildContentForTags(book, text);
        }
    }

    /**
     * 提取 TXT 内容（前2000字符）
     */
    private String extractTxtContent(Book book, Path filePath) throws Exception {
        String content = Files.readString(filePath);
        String preview = content.length() > 2000 ? content.substring(0, 2000) : content;
        return buildContentForTags(book, preview);
    }

    /**
     * 调用 AI 模型生成标签
     */
    private String callAiForTags(String content) {
        try {
            log.info("========== AI 标签生成请求 ==========");
            log.info("输入内容: {}", content);

            AiProviderConfigService service = aiProviderConfigService;
            ChatModel chatModel = service.buildTagChatModel();
            if (chatModel == null) {
                log.debug("无可用的 AI 模型，跳过标签生成");
                return null;
            }

            long startTime = System.currentTimeMillis();
            String thinkingSuffix = aiProviderConfigService.getThinkingPromptSuffix();
            ChatResponse response = chatModel.chat(List.of(
                    UserMessage.from(TAG_SYSTEM_PROMPT + content + thinkingSuffix)
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
     * AI 评分生成的系统提示词
     */
    private static final String RATING_PROMPT = """
            你是一个专业的图书评分助手。根据提供的图书信息（书名、作者、简介或目录），给出一个1.0-5.0之间的评分（5星制，一位小数）。

            评分标准（5星制）：
            - 1-2星：较差或平庸
            - 2-3星：一般，有一定可读性
            - 3-4星：良好，值得推荐
            - 4-5星：优秀，强烈推荐

            规则：
            - 只返回一个数字（1.0-5.0之间的一位小数），不要其他文字
            - 未知信息较多的书给中等评分（3.0-3.5）
            - 经典名著一般4.0-5.0
            - 普通书籍2.5-4.0
            - 示例：3.8

            图书信息如下：
            """;

    /**
     * 异步为图书生成 AI 评分
     */
    @Async
    public void generateRatingAsync(Long bookId, boolean force) {
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
     * 异步为图书生成 AI 评分 - 非强制版本
     */
    @Async
    public void generateRatingAsync(Long bookId) {
        generateRatingAsync(bookId, false);
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
            String thinkingSuffix = aiProviderConfigService.getThinkingPromptSuffix();
            ChatResponse response = chatModel.chat(List.of(
                    UserMessage.from(RATING_PROMPT + content + thinkingSuffix)
            ));
            long elapsed = System.currentTimeMillis() - startTime;

            String result = response.aiMessage().text();
            if (result != null) {
                result = result.trim().replaceAll("[^0-9.]", "");
            }

            double rating;
            try {
                rating = Double.parseDouble(result);
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
     * 8维度相关度得分 AI 提示词
     */
    private static final String RELEVANCE_PROMPT = """
            你是一个专业的图书读者匹配分析助手。根据提供的图书信息（书名、作者、简介或目录），分析这本书对不同读者群体的适合程度。
            
            请为以下8个维度打分（0-1之间的小数，0表示完全不适合，1表示非常适合），并以JSON格式返回：
            
            年龄段维度（每10年一个区间）：
            - "0-9": 儿童适合度
            - "10-19": 青少年适合度
            - "20-29": 青年适合度
            - "30-39": 壮年适合度
            - "40-49": 中年适合度
            - "50-59": 中老年适合度
            - "60+": 老年适合度
            
            性别维度：
            - "male": 男性适合度
            - "female": 女性适合度
            
            婚姻维度：
            - "married": 已婚人群适合度
            - "unmarried": 未婚人群适合度
            
            子女维度：
            - "hasChildren": 有孩子人群适合度
            - "noChildren": 无孩子人群适合度
            
            MBTI维度（16种人格）：
            - "INTJ","INTP","ENTJ","ENTP","INFJ","INFP","ENFJ","ENFP",
              "ISTJ","ISFJ","ESTJ","ESFJ","ISTP","ISFP","ESTP","ESFP"
            
            只返回JSON，不要其他文字。格式示例：
            {"0-9":0.1,"10-19":0.3,"20-29":0.8,"30-39":0.7,"40-49":0.5,"50-59":0.3,"60+":0.2,"male":0.6,"female":0.7,"married":0.5,"unmarried":0.8,"hasChildren":0.4,"noChildren":0.8,"INTJ":0.7,"INTP":0.6,"ENTJ":0.5,"ENTP":0.6,"INFJ":0.8,"INFP":0.9,"ENFJ":0.7,"ENFP":0.7,"ISTJ":0.4,"ISFJ":0.5,"ESTJ":0.3,"ESFJ":0.4,"ISTP":0.4,"ISFP":0.5,"ESTP":0.3,"ESFP":0.4}
            
            图书信息如下：
            """;

    /**
     * 异步为图书生成8维度相关度得分
     */
    @Async
    public void generateRelevanceScoresAsync(Long bookId, boolean force) {
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
     * 异步为图书生成8维度相关度得分 - 非强制版本
     */
    @Async
    public void generateRelevanceScoresAsync(Long bookId) {
        generateRelevanceScoresAsync(bookId, false);
    }

    // ======================== 合并 AI 请求（标签 + 评分 + 相关度，一次调用） ========================

    /**
     * 异步为图书一次性生成所有 AI 数据（标签 + 评分 + 8维度相关度得分），合并为一次 LLM 调用以减少 token 消耗
     *
     * @param bookId 图书ID
     * @param force  是否强制重新生成（即使已有数据）
     */
    @Async
    public void generateAllAiDataAsync(Long bookId, boolean force) {
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
                    generateTagsAsync(bookId, force);
                }
                if (force || book.getRating() == null || book.getRating() <= 0) {
                    generateRatingAsync(bookId, force);
                }
                if (force || book.getRelevanceScores() == null || book.getRelevanceScores().isBlank()) {
                    generateRelevanceScoresAsync(bookId, force);
                }
                // 回退分支也需要生成元数据向量
                embeddingService.generateBookEmbeddingAsync(bookId);
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

            // 异步生成书籍元数据向量（用于推荐召回）
            embeddingService.generateBookEmbeddingAsync(bookId);

        } catch (Exception e) {
            log.warn("合并AI数据生成失败: bookId={} - {}，回退到单独生成", bookId, e.getMessage());
            // 回退到单独调用
            try {
                generateTagsAsync(bookId, force);
                generateRatingAsync(bookId, force);
                generateRelevanceScoresAsync(bookId, force);
                // 回退分支也需要生成元数据向量
                embeddingService.generateBookEmbeddingAsync(bookId);
            } catch (Exception ex) {
                log.warn("回退单独生成也失败: bookId={} - {}", bookId, ex.getMessage());
            }
        }
    }

    /**
     * 异步为图书一次性生成所有 AI 数据 - 非强制版本
     */
    @Async
    public void generateAllAiDataAsync(Long bookId) {
        generateAllAiDataAsync(bookId, false);
    }

    /**
     * 异步为图书生成 RAG 内容向量（书籍内容分块 → Qdrant）
     * 在图书入库后调用，将书籍全文分块生成 embedding 存入 Qdrant content 集合
     *
     * @param bookId 图书ID
     */
    @Async
    public void generateContentEmbeddingAsync(Long bookId) {
        try {
            Book book = bookService.getBookById(bookId);
            String content = extractContentForRAG(book);
            if (content == null || content.isBlank()) {
                log.debug("图书无内容可供生成RAG向量: bookId={}", bookId);
                return;
            }
            embeddingService.generateContentEmbeddingAsync(bookId, content);
            log.info("触发RAG内容向量生成: bookId={}, contentLen={}", bookId, content.length());
        } catch (Exception e) {
            log.warn("触发RAG内容向量生成失败: bookId={} - {}", bookId, e.getMessage());
        }
    }

    /**
     * 异步为图书生成元数据向量（标题+作者+标签+简介 → 1个 embedding，用于推荐召回）
     *
     * @param bookId 图书ID
     */
    @Async
    public void generateBookEmbeddingAsync(Long bookId) {
        try {
            embeddingService.generateBookEmbeddingAsync(bookId);
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
     */
    private String extractEpubFullText(Book book, Path filePath) throws Exception {
        try (InputStream is = Files.newInputStream(filePath)) {
            nl.siegmann.epublib.epub.EpubReader epubReader = new nl.siegmann.epublib.epub.EpubReader();
            nl.siegmann.epublib.domain.Book epubBook = epubReader.readEpub(is);

            StringBuilder text = new StringBuilder();
            for (var spineRef : epubBook.getSpine().getSpineReferences()) {
                try {
                    var resource = spineRef.getResource();
                    if (resource == null || resource.getData() == null) continue;
                    String html = new String(resource.getData(), java.nio.charset.StandardCharsets.UTF_8);
                    String plainText = html.replaceAll("<[^>]+>", "").trim();
                    if (!plainText.isBlank()) {
                        text.append(plainText).append("\n");
                    }
                } catch (Exception ignored) {}
            }
            return text.toString();
        }
    }

    /**
     * 提取 PDF 全文（用于 RAG）
     */
    private String extractPdfFullText(Book book, Path filePath) throws Exception {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(document.getNumberOfPages());
            return stripper.getText(document);
        }
    }

    /**
     * 合并AI调用结果
     */
    private record CombinedAiResult(List<String> tags, Double rating, String relevanceScoresJson) {}

    /**
     * 一次 LLM 调用同时生成标签、评分、8维度相关度得分
     */
    private CombinedAiResult callAiCombined(String content) {
        try {
            log.info("========== AI 合并请求（标签+评分+相关度） ==========");
            log.info("输入内容: {}", content);

            ChatModel chatModel = aiProviderConfigService.buildTagChatModel();
            if (chatModel == null) {
                log.debug("无可用的 AI 模型，跳过合并生成");
                return null;
            }

            long startTime = System.currentTimeMillis();
            String thinkingSuffix = aiProviderConfigService.getThinkingPromptSuffix();
            ChatResponse response = chatModel.chat(List.of(
                    UserMessage.from(COMBINED_PROMPT + content + thinkingSuffix)
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
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(jsonStr);

            // 解析标签
            List<String> tags = null;
            if (root.has("tags") && !root.get("tags").isNull()) {
                String tagsStr = root.get("tags").asText();
                if (tagsStr != null && !tagsStr.isBlank()) {
                    tags = List.of(tagsStr.split("[,，、]")).stream()
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
                com.fasterxml.jackson.databind.JsonNode relevanceNode = root.get("relevance");
                // 验证是对象格式
                if (relevanceNode.isObject()) {
                    relevanceScoresJson = objectMapper.writeValueAsString(relevanceNode);
                }
            }

            // 记录 AI 调用日志
            int inputTokens = CommonUtils.estimateTokens(content);
            int outputTokens = CommonUtils.estimateTokens(rawResult);
            CommonUtils.logAiCall(AI_OP_COMBINED, elapsed, inputTokens, outputTokens,
                    String.format("标签: %s | 评分: %s | 相关度: %s",
                            tags, rating, relevanceScoresJson != null ? "已生成" : "无"));

            // 至少有一项结果才算成功
            if (tags == null && rating == null && relevanceScoresJson == null) {
                return null;
            }

            return new CombinedAiResult(tags, rating, relevanceScoresJson);

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
            log.info("输入内容: {}", content);

            ChatModel chatModel = aiProviderConfigService.buildTagChatModel();
            if (chatModel == null) {
                log.debug("无可用的 AI 模型，跳过相关度得分生成");
                return null;
            }

            long startTime = System.currentTimeMillis();
            String thinkingSuffix = aiProviderConfigService.getThinkingPromptSuffix();
            ChatResponse response = chatModel.chat(List.of(
                    UserMessage.from(RELEVANCE_PROMPT + content + thinkingSuffix)
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
            Path coverDir = Paths.get(coverPath);
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
