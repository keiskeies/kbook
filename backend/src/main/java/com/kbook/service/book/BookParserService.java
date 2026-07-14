package com.kbook.service.book;
import com.kbook.service.user.UserService;
import com.kbook.service.ai.ChatModelManager;

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
import com.kbook.constants.AiPromptConstants;
import com.kbook.dto.book.BookSpeedReadVO;
import com.kbook.entity.Book;
import com.kbook.entity.User;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 图书解析服务类
 * <p>
 * 负责解析EPUB、PDF、TXT三种格式的图书文件，提取元数据、封面、目录、正文等内容
 * 支持AI辅助生成标签、评分、相关度得分和简介
 * <p>
 * 主要功能：
 * - EPUB/PDF/TXT 元数据提取与封面生成
 * - AI 标签生成、质量评分、8维度相关度分析
 * - RAG内容向量化（用于智能搜索和推荐）
 * - 扫描版PDF的OCR文字识别
 */
@Slf4j
@Service
@LogModule("图书解析")
public class BookParserService {

    private final ChatModelFactory chatModelFactory;
    private final ChatModelManager chatModelManager;
    private final BookService bookService; // 书籍服务
    private final UserService userService; // 用户服务
    private final EmbeddingService embeddingService; // 嵌入向量服务
    private final ObjectMapper objectMapper; // JSON对象映射器
    private final BookStorageProperties storageProps; // 书籍存储配置属性
    private final ExecutorService sseExecutor;
    private final com.kbook.service.ai.RagAnswerCache ragAnswerCache;

    public BookParserService(
            ChatModelFactory chatModelFactory,
            ChatModelManager chatModelManager,
            BookService bookService,
            UserService userService,
            EmbeddingService embeddingService,
            ObjectMapper objectMapper,
            BookStorageProperties storageProps,
            com.kbook.service.ai.RagAnswerCache ragAnswerCache,
            @Qualifier("sseExecutor") ExecutorService sseExecutor) {
        this.chatModelFactory = chatModelFactory;
        this.chatModelManager = chatModelManager;
        this.bookService = bookService;
        this.userService = userService;
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
        this.storageProps = storageProps;
        this.ragAnswerCache = ragAnswerCache;
        this.sseExecutor = sseExecutor;
    }

    /**
     * 封面最大宽度（像素）
     * 用于等比例压缩封面图片，确保统一尺寸
     */
    private static final int COVER_MAX_WIDTH = 300;

    /**
     * AI 调用操作类型常量
     * 用于日志记录，标识合并请求的类型（标签+评分+相关度）
     */
    private static final String AI_OP_COMBINED = "合并请求（标签+评分+相关度）";


    /**
     * 初始化方法，在Spring容器启动时自动执行
     * 打印封面存储目录的绝对路径，便于调试和验证配置
     */
    @LogAction("初始化")
    @PostConstruct
    public void init() {
        Path absolutePath = Paths.get(storageProps.getCoverPath()).toAbsolutePath(); // 获取封面目录的绝对路径
        log.info("封面存储目录: {}", absolutePath); // 记录封面目录路径
    }

    /**
     * 解析图书元数据并填充到 Book 对象
     * 根据图书格式调用相应的解析方法
     * - EPUB: 提取作者 + 简介 + 目录 + 核心章节摘要 + 封面图片
     * - PDF:  提取元数据 + 目录 + 核心章节摘要 + 首页渲染为封面图片
     * - TXT:  提取开头文本 + 章节目录 + AI推断作者和简介
     *
     * @param book     图书实体对象
     * @param filePath 图书文件路径
     */
    @LogAction("解析并填充图书元数据")
    public void parseAndFill(Book book, Path filePath) {
        // 根据图书格式选择对应的解析方法
        switch (book.getFormat()) {
            case "EPUB" -> parseEpub(book, filePath); // 解析EPUB格式
            case "PDF" -> parsePdf(book, filePath); // 解析PDF格式
            case "TXT" -> parseTxt(book, filePath); // 解析TXT格式
            default -> log.warn("不支持的格式: {}", book.getFormat()); // 记录不支持的格式警告
        }
    }

    /**
     * 解析 EPUB 格式图书
     * 提取作者、简介、目录、核心章节摘要、封面图片等元数据
     * 支持标准EPUB解析和ZIP降级模式（针对不规范EPUB文件）
     *
     * @param book     图书实体对象
     * @param filePath EPUB文件路径
     */
    @LogAction("解析EPUB")
    public void parseEpub(Book book, Path filePath) {
        // 预验证：检查 EPUB 结构是否可被 epublib 解析
        if (!isValidEpubStructure(filePath)) {
            log.warn("EPUB 结构缺失 OPF 文件，跳过 epublib，直接使用 ZIP 降级: bookId={}", book.getId());
            fallbackToZipExtraction(book, filePath);
            return;
        }

        nl.siegmann.epublib.domain.Book epubBook = null; // EPUB图书对象
        try (InputStream is = Files.newInputStream(filePath)) {
            EpubReader epubReader = new EpubReader(); // 创建EPUB阅读器
            epubBook = epubReader.readEpub(is); // 读取EPUB文件
        } catch (Exception e) {
            log.warn("epublib 解析 EPUB 元数据失败，尝试 ZIP 降级模式: {} - {}", book.getTitle(), e.getMessage());
        }

        if (epubBook == null) {
            // 标准解析失败，尝试修复 HTML 实体后重试
            log.info("尝试修复 HTML 实体后重试 epublib: bookId={}", book.getId());
            try (InputStream fixedIs = fixEpubEntities(filePath)) {
                EpubReader epubReader = new EpubReader();
                epubBook = epubReader.readEpub(fixedIs);
                log.info("修复实体后 epublib 解析成功: bookId={}", book.getId());
            } catch (Exception e2) {
                log.warn("修复实体后 epublib 仍解析失败: {} - {}", book.getTitle(), e2.getMessage());
            }
        }

        if (epubBook != null) {
            // 标准EPUB解析成功，提取元数据、文本内容和封面
            extractEpubMetadata(book, epubBook); // 提取作者等元数据
            extractEpubTextContent(book, epubBook); // 提取目录、章节摘要、正文内容
            extractEpubCover(book, epubBook); // 提取封面图片
        } else {
            fallbackToZipExtraction(book, filePath);
        }

        // 如果标准解析未提取到内容，再次尝试ZIP降级模式
        if (book.getRagContent() == null || book.getRagContent().isBlank()) {
            log.info("EPUB 标准解析未提取到内容，尝试 ZIP 降级: bookId={}", book.getId());
            String fullText = extractEpubTextViaZip(filePath); // 通过ZIP方式提取文本
            if (fullText != null && !fullText.isBlank()) {
                book.setRagContent(fullText); // 设置RAG全文内容
                book.setFullText(fullText); // 持久化全文，用于向量重建
                // 构建用于AI标签生成的内容（分层采样全文）
                book.setParsedContent(buildContentForTags(book, stratifiedSample(fullText)));
            }
        }
    }

    /**
     * ZIP 降级提取全文
     */
    private void fallbackToZipExtraction(Book book, Path filePath) {
        log.info("EPUB 降级模式: 尝试 ZIP 提取全文: bookId={}", book.getId());
        String fullText = extractEpubTextViaZip(filePath);
        if (fullText != null && !fullText.isBlank()) {
            book.setRagContent(fullText);
            book.setFullText(fullText); // 持久化全文，用于向量重建
            book.setParsedContent(buildContentForTags(book, fullText.substring(0, Math.min(fullText.length(), 15000))));
        }
    }

    /**
     * 预验证 EPUB 结构：META-INF/container.xml 中引用的 OPF 文件是否存在
     */
    private boolean isValidEpubStructure(Path filePath) {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(filePath))) {
            // 收集所有条目名称
            java.util.Set<String> entryNames = new java.util.HashSet<>();
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryNames.add(entry.getName());
                entryNames.add("/" + entry.getName()); // 也匹配可能带前导斜杠的路径
            }

            // 检查 META-INF/container.xml 是否存在
            if (!entryNames.contains("META-INF/container.xml") && !entryNames.contains("/META-INF/container.xml")) {
                return false;
            }

            // 重新读取 container.xml 提取 OPF 路径
            try (ZipInputStream zis2 = new ZipInputStream(Files.newInputStream(filePath))) {
                ZipEntry containerEntry;
                while ((containerEntry = zis2.getNextEntry()) != null) {
                    if ("META-INF/container.xml".equals(containerEntry.getName())) {
                        String content = new String(zis2.readAllBytes(), StandardCharsets.UTF_8);
                        // 提取 rootfile 的 full-path 属性
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("full-path\\s*=\\s*\"([^\"]+)\"").matcher(content);
                        if (m.find()) {
                            String opfPath = m.group(1);
                            return entryNames.contains(opfPath) || entryNames.contains("/" + opfPath);
                        }
                        return false;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            log.debug("EPUB 结构预验证失败: {}", e.getMessage());
            return true; // 验证明细失败时放行，让 epublib 自己处理
        }
    }

    /**
     * 从EPUB图书中提取元数据（作者信息）
     * <p>
     * 提取EPUB元数据中的第一个作者，将名字和姓氏组合后设置到图书对象中
     *
     * @param book     图书实体对象，用于存储提取的作者信息
     * @param epubBook EPUB图书对象，包含元数据信息
     */
    private void extractEpubMetadata(Book book, nl.siegmann.epublib.domain.Book epubBook) {
        // 检查元数据是否存在且包含作者信息
        if (epubBook.getMetadata() != null && !epubBook.getMetadata().getAuthors().isEmpty()) {
            var author = epubBook.getMetadata().getAuthors().get(0);
            String authorName = (author.getFirstname() != null ? author.getFirstname() + " " : "")
                    + (author.getLastname() != null ? author.getLastname() : "");
            if (!authorName.isBlank()) {
                book.setAuthor(authorName.trim());
            }
        }
    }

    /**
     * 从EPUB图书中提取文本内容
     * <p>
     * 提取内容包括：
     * 1. 目录结构（TOC）
     * 2. 核心章节摘要（目录 + 按章均匀分配）
     * 3. 用于AI标签生成的正文内容（目录 + 按章均匀分配，上限 MAX_CONTENT_FOR_AI）
     * 4. 用于RAG的完整全文内容
     *
     * @param book     图书实体对象，用于存储提取的文本内容
     * @param epubBook EPUB图书对象，包含所有章节和资源
     */
    private void extractEpubTextContent(Book book, nl.siegmann.epublib.domain.Book epubBook) {
        // 提取目录结构并保存到 book.toc
        String toc = buildEpubToc(epubBook);
        if (!toc.isEmpty()) {
            book.setToc(toc);
        }

        // 提取核心章节摘要（目录 + 按章均匀分配）
        book.setChapterSummary(extractEpubChapterSummary(epubBook));

        // 提取用于AI标签生成的正文内容（目录 + 按章均匀分配）
        book.setParsedContent(buildContentForTags(book, buildChapterBasedContent(epubBook)));

        // 提取用于RAG的完整全文内容
        book.setRagContent(extractEpubFullTextFromEpubBook(epubBook));
        book.setFullText(book.getRagContent()); // 持久化全文，用于向量重建
    }

    /**
     * 提取 EPUB 目录文本（供 buildChapterBasedContent / extractEpubChapterSummary 复用）
     */
    private String buildEpubToc(nl.siegmann.epublib.domain.Book epubBook) {
        StringBuilder sb = new StringBuilder();
        if (epubBook.getTableOfContents() != null) {
            extractEpubTocChildren(epubBook.getTableOfContents().getTocReferences(), sb, 0);
        }
        return sb.toString().trim();
    }

    /**
     * 按章均匀分配预算构建内容（目录 + 正文）
     * 遍历 spine 去 HTML 标签后，将 maxChars 均分给每章，最后一章吃剩余
     */
    private String buildChapterBasedContent(nl.siegmann.epublib.domain.Book epubBook) {
        String toc = buildEpubToc(epubBook);

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

        int remaining = BookParserService.MAX_CONTENT_FOR_AI - sb.length();
        int perChapter = chapters.isEmpty() || remaining <= 0 ? 0 : Math.max(1, remaining / chapters.size());

        for (int i = 0; i < chapters.size() && sb.length() < BookParserService.MAX_CONTENT_FOR_AI; i++) {
            String text = chapters.get(i);
            int take = Math.min(text.length(), perChapter);
            if (i == chapters.size() - 1) {
                take = Math.min(text.length(), BookParserService.MAX_CONTENT_FOR_AI - sb.length());
            }
            sb.append(text, 0, take).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 从EPUB图书中提取封面图片
     * <p>
     * 采用两级提取策略：
     * 1. 优先使用EPUB元数据中定义的封面图片
     * 2. 如果元数据封面不存在或比例异常，则从正文中提取第一张非正方形图片
     * <p>
     * 提取的封面会经过压缩处理并保存到指定目录
     *
     * @param book     图书实体对象，用于存储封面URL
     * @param epubBook EPUB图书对象，包含封面图片和资源信息
     */
    private void extractEpubCover(Book book, nl.siegmann.epublib.domain.Book epubBook) {
        try {
            byte[] bestCoverData = null;
            nl.siegmann.epublib.domain.MediaType coverMediaType = null;

            // 尝试从EPUB元数据获取封面图片
            var coverImage = epubBook.getCoverImage();
            if (coverImage != null && coverImage.getData() != null) {
                if (isCoverImage(coverImage.getData())) {
                    bestCoverData = coverImage.getData();
                    coverMediaType = coverImage.getMediaType();
                    log.info("EPUB 使用元数据封面");
                } else {
                    log.info("EPUB 元数据封面为非正常比例图片，跳过，尝试从正文提取");
                }
            }

            // 如果元数据中没有合适的封面，则从正文中提取
            if (bestCoverData == null) {
                CoverExtractionResult result = extractFirstNonSquareImageFromEpub(epubBook);
                if (result != null) {
                    bestCoverData = result.data;
                    coverMediaType = result.mediaType;
                    log.info("EPUB 从正文提取到正常比例封面图片");
                }
            }

            // 保存提取到的封面图片
            if (bestCoverData != null) {
                saveCoverImage(book, bestCoverData, coverMediaType);
                log.info("EPUB 封面保存成功: {}", book.getCoverUrl());
            } else {
                book.setCoverUrl(null);
                log.info("EPUB 未找到合适的封面图片（可能均为非正常比例图片）");
            }
        } catch (Exception e) {
            book.setCoverUrl(null);
            log.warn("EPUB 封面提取失败: {}", e.getMessage());
        }
    }

    /**
     * 保存封面图片
     */
    private void saveCoverImage(Book book, byte[] imageData, nl.siegmann.epublib.domain.MediaType mediaType) {
        long ts = System.currentTimeMillis();
        String ext = "jpg";
        if (mediaType != null) {
            String mediaTypeName = mediaType.getName().toLowerCase();
            if (mediaTypeName.contains("png")) ext = "png";
            else if (mediaTypeName.contains("gif")) ext = "gif";
            else if (mediaTypeName.contains("webp")) ext = "webp";
        }
        String tempFileName = "book_new_" + ts + "_cover." + ext;
        try {
            Path coverDir = Paths.get(storageProps.getCoverPath());
            Files.createDirectories(coverDir);
            Path coverFilePath = coverDir.resolve(tempFileName);

            BufferedImage srcImage = ImageIO.read(new ByteArrayInputStream(imageData));
            if (srcImage != null) {
                BufferedImage resized = CommonUtils.compressImage(srcImage, ext, COVER_MAX_WIDTH);
                ImageIO.write(resized, ext, coverFilePath.toFile());
            } else {
                Files.write(coverFilePath, imageData);
            }
            book.setCoverUrl("/api/books/cover/" + tempFileName);
        } catch (Exception e) {
            log.warn("保存封面图片失败: {}", e.getMessage());
        }
    }

    /**
     * 检查图片是否为非正常比例图片
     */
    private boolean isCoverImage(byte[] imageData) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(imageData)) {
            BufferedImage img = ImageIO.read(bais);
            if (img != null) {
                return img.getWidth() >= 100 && !(img.getWidth() > img.getHeight() * 0.8);
            }
        } catch (Exception e) {
            // 读取失败不视为正方形，避免误杀
        }
        return true;
    }

    /**
     * 内部类：用于返回提取的图片数据和类型
     */
    private record CoverExtractionResult(byte[] data, MediaType mediaType) {
    }

    /**
     * 从 EPUB 正文中提取第一张非正方形图片作为封面
     * <p>
     * 遍历 EPUB 的 spine（阅读顺序）中的所有 HTML 资源，
     * 使用正则表达式提取 img 标签的图片路径，跳过正方形图片（1:1比例），
     * 返回第一张符合条件的非正方形图片数据。
     *
     * @param epubBook EPUB 图书对象
     * @return 封面提取结果（包含图片数据和媒体类型），未找到则返回 null
     */
    private CoverExtractionResult extractFirstNonSquareImageFromEpub(nl.siegmann.epublib.domain.Book epubBook) {
        try {
            // 编译图片标签正则表达式，匹配 img 标签的 src 属性
            Pattern imgPattern = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

            // 遍历 EPUB 的 spine（阅读顺序）中的所有章节
            for (var spineRef : epubBook.getSpine().getSpineReferences()) {
                try {
                    var resource = spineRef.getResource();
                    if (resource == null || resource.getData() == null) continue;

                    // 将章节内容转换为 HTML 字符串
                    String html = new String(resource.getData(), StandardCharsets.UTF_8);
                    var matcher = imgPattern.matcher(html);

                    // 查找所有图片标签
                    while (matcher.find()) {
                        String imgSrc = matcher.group(1);
                        if (imgSrc == null || imgSrc.isBlank()) continue;

                        // 解析图片的相对路径为绝对路径
                        String imgPath = resolveEpubImagePath(imgSrc, resource);
                        if (imgPath == null) continue;

                        // 获取图片资源数据
                        var imageResource = epubBook.getResources().getByHref(imgPath);
                        if (imageResource != null && imageResource.getData() != null) {
                            byte[] imgData = imageResource.getData();

                            // 检查是否为非正常比例图片，符合条件则返回
                            if (imgData != null && imgData.length > 0 && isCoverImage(imgData)) {
                                return new CoverExtractionResult(imgData, imageResource.getMediaType());
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // 单个章节解析失败不影响其他章节
                }
            }
        } catch (Exception e) {
            log.debug("从EPUB正文提取非正方形图片失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 解析 EPUB 中的图片路径（处理相对路径）
     */
    private String resolveEpubImagePath(String imgSrc, nl.siegmann.epublib.domain.Resource contextResource) {
        try {
            // 如果是绝对路径（以 / 开头），直接使用
            if (imgSrc.startsWith("/")) {
                return imgSrc.substring(1);
            }

            // 获取当前资源的 href（文件路径）
            String contextHref = contextResource.getHref();
            if (contextHref == null) return imgSrc;

            // 获取当前资源所在目录
            int lastSlash = contextHref.lastIndexOf('/');
            if (lastSlash >= 0) {
                String contextDir = contextHref.substring(0, lastSlash + 1);
                return contextDir + imgSrc;
            }

            return imgSrc;
        } catch (Exception e) {
            log.debug("解析EPUB图片路径失败: {}", e.getMessage());
            return imgSrc;
        }
    }

    /**
     * 提取 EPUB 核心章节摘要：使用 Jsoup 解析 h 和 p 标签提取正文，目录 + 按章均分，上限 MAX_CONTENT_FOR_AI
     */
    private String extractEpubChapterSummary(nl.siegmann.epublib.domain.Book epubBook) {
        try {
            var spineRefs = epubBook.getSpine().getSpineReferences();
            int totalBudget = MAX_CONTENT_FOR_AI;

            // 1. 提取目录
            String toc = buildEpubToc(epubBook);
            int tocLen = toc.length();

            // 2. 遍历所有章节，提取文本并记录有效章节的索引和内容
            List<String> chapters = new java.util.ArrayList<>();
            for (var spineRef : spineRefs) {
                try {
                    var resource = spineRef.getResource();
                    if (resource == null || resource.getData() == null) continue;

                    String html = new String(resource.getData(), StandardCharsets.UTF_8);
                    org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);

                    StringBuilder chapterText = new StringBuilder();
                    doc.select("h1, h2, h3, h4, h5, h6").forEach(el -> {
                        String text = el.text().trim();
                        if (!text.isBlank()) chapterText.append(text).append("\n\n");
                    });
                    doc.select("p").forEach(el -> {
                        String text = el.text().trim();
                        if (!text.isBlank()) chapterText.append(text).append("\n\n");
                    });

                    String plainText = chapterText.toString().trim();
                    if (plainText.length() >= 50) {
                        chapters.add(plainText);
                    }
                } catch (Exception ignored) {
                }
            }

            // 3. 组装：目录 + 均分剩余字数给每章
            StringBuilder summary = new StringBuilder(totalBudget);
            if (tocLen > 0) {
                summary.append("目录：\n").append(toc).append("\n\n");
            }

            int remaining = totalBudget - summary.length();
            int perChapter = 0;
            if (!chapters.isEmpty() && remaining > 0) {
                perChapter = Math.max(1, remaining / chapters.size());
            }

            summary.append("章节内容：\n");
            for (int i = 0; i < chapters.size() && summary.length() < totalBudget; i++) {
                String text = chapters.get(i);
                int take = Math.min(text.length(), perChapter);
                // 最后一个章节尽量用完剩余预算
                if (i == chapters.size() - 1 && summary.length() + take < totalBudget) {
                    take = Math.min(text.length(), totalBudget - summary.length());
                }
                summary.append(text, 0, take);
                summary.append("\n\n");
            }

            if (summary.length() > totalBudget) {
                summary.setLength(totalBudget);
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
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /**
     * AI 标签生成时送入大模型的正文最大字符数（128k 上下文窗口内留出提示词和输出空间）
     */
    private static final int MAX_CONTENT_FOR_AI = 15000;

    /**
     * 分层采样段数 — 从全书开头/25%/50%/75%/结尾各取一段
     */
    private static final int STRATIFIED_SEGMENTS = 5;

    /**
     * HTML 实体 → 数值字符引用映射，用于修复 EPUB 中未声明的实体
     */
    private static final Map<String, String> HTML_ENTITIES = Map.ofEntries(
            Map.entry("&nbsp;", "&#160;"),
            Map.entry("&mdash;", "&#8212;"),
            Map.entry("&ndash;", "&#8211;"),
            Map.entry("&ldquo;", "&#8220;"),
            Map.entry("&rdquo;", "&#8221;"),
            Map.entry("&lsquo;", "&#8216;"),
            Map.entry("&rsquo;", "&#8217;"),
            Map.entry("&hellip;", "&#8230;"),
            Map.entry("&bull;", "&#8226;"),
            Map.entry("&middot;", "&#183;"),
            Map.entry("&copy;", "&#169;"),
            Map.entry("&reg;", "&#174;"),
            Map.entry("&trade;", "&#8482;")
    );

    private static final Pattern BARE_AMPERSAND_PATTERN = Pattern.compile("&(?!((?:#\\d+|#x[\\da-fA-F]+|\\w+);))");

    /**
     * 修复 EPUB 中 XML/XHTML 文件内未声明的 HTML 实体及裸 &
     */
    private InputStream fixEpubEntities(Path filePath) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(filePath));
             ZipOutputStream zos = new ZipOutputStream(bos)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                ZipEntry newEntry = new ZipEntry(entry.getName());
                newEntry.setMethod(entry.getMethod());
                newEntry.setTime(entry.getTime());
                zos.putNextEntry(newEntry);
                String name = entry.getName().toLowerCase();
                if (name.endsWith(".opf") || name.endsWith(".xml") || name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".ncx")) {
                    // 限制单个条目最大 5MB，防止恶意/异常 EPUB 耗尽内存
                    byte[] raw = readEntryLimited(zis, 5 * 1024 * 1024);
                    if (raw != null) {
                        String content = new String(raw, StandardCharsets.UTF_8);
                        for (var e : HTML_ENTITIES.entrySet()) {
                            content = content.replace(e.getKey(), e.getValue());
                        }
                        content = BARE_AMPERSAND_PATTERN.matcher(content).replaceAll("&amp;");
                        zos.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                } else {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        zos.write(buf, 0, n);
                    }
                }
                zos.closeEntry();
            }
        }
        return new ByteArrayInputStream(bos.toByteArray());
    }

    /**
     * 限制读取 ZipInputStream 条目字节数，防止 OOM
     */
    private byte[] readEntryLimited(ZipInputStream zis, int maxBytes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int total = 0;
        int n;
        while ((n = zis.read(tmp)) > 0) {
            total += n;
            if (total > maxBytes) {
                log.warn("EPUB 条目超过 {}MB 限制，跳过", maxBytes / 1024 / 1024);
                return null;
            }
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }

    /**
     * 解析 PDF 格式图书
     * 提取元数据、目录、核心章节摘要，并将首页渲染为封面图片
     * 支持文字型PDF和扫描版PDF（使用OCR识别）
     *
     * @param book     图书实体对象
     * @param filePath PDF文件路径
     */
    private void parsePdf(Book book, Path filePath) {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) { // 加载PDF文档
            // 提取页数并设置总单元数
            book.setTotalUnits((long) document.getNumberOfPages());

            // 1. 尝试从 PDF 元数据提取书名和作者
            extractPdfMetadata(book, document);

            // 2. 提取目录（尝试从 PDF 书签/大纲获取）
            String toc = extractPdfToc(document);
            if (toc != null && !toc.isBlank()) {
                book.setToc(toc); // 设置目录
            }

            // 3. 提取前20页文本（用于 AI 评分 + 标签生成 + 核心章节摘要 + 简介生成）
            String firstPagesText = null; // 前几页的文本内容
            boolean isScanned = true; // 是否为扫描版PDF的标志
            try {
                PDFTextStripper stripper = new PDFTextStripper(); // 创建PDF文本提取器
                int pagesToRead = Math.min(20, document.getNumberOfPages()); // 最多读取20页
                stripper.setStartPage(1); // 设置起始页
                stripper.setEndPage(pagesToRead); // 设置结束页
                firstPagesText = stripper.getText(document); // 提取文本

                // 判断是否为扫描版（前5页每页平均不到50字则认为是扫描版）
                String cleaned = WHITESPACE_PATTERN.matcher(firstPagesText).replaceAll("").trim();
                isScanned = cleaned.length() < (long) pagesToRead * 50;
            } catch (Exception e) {
                log.debug("PDF 文本提取失败: {} - {}", book.getTitle(), e.getMessage());
            }

            // 4. 提取核心章节摘要（仅文字型PDF）
            if (firstPagesText != null && !firstPagesText.isBlank() && !isScanned) {
                String cleaned = WHITESPACE_PATTERN.matcher(firstPagesText).replaceAll(" ").trim(); // 清理空白字符
                // 截取前2000字符作为章节摘要
                book.setChapterSummary(cleaned.length() > 2000 ? cleaned.substring(0, 2000) : cleaned);
            }

            // 5. 扫描版 PDF：使用大模型 OCR 提取前几页，获取书名/作者/简介/目录
            if (isScanned) {
                log.info("PDF 疑似扫描版，使用大模型提取元数据: bookId={}", book.getId());
                extractPdfMetadataWithOcr(book, document); // 使用OCR提取元数据
            }

            // 6. 文字型 PDF 缺失作者时，或始终用大模型生成更完整简介
            /* 始终用AI生成更完整的简介 */
            if (!isScanned) {
                chatModelManager.inferMetadataFromContent(book, firstPagesText); // 从内容推断元数据
            }

            // 7. 构建 AI 标签生成的内容
            book.setParsedContent(buildContentForTags(book, firstPagesText));

            // 8. 提取全文用于RAG（在PDDocument已打开时提取，避免后续二次加载文件）
            try {
                String fullText = extractPdfFullTextFromDocument(book, document, isScanned); // 提取全文
                if (fullText != null && !fullText.isBlank()) {
                    book.setRagContent(fullText); // 设置RAG全文内容
                    book.setFullText(fullText); // 持久化全文，用于向量重建
                }
            } catch (Exception e) {
                log.debug("PDF全文提取（RAG缓存）失败: {} - {}", book.getTitle(), e.getMessage());
            }

            // 9. 首页渲染为封面图片
            PDFRenderer renderer = new PDFRenderer(document); // 创建PDF渲染器
            BufferedImage image = renderer.renderImageWithDPI(0, 150); // 渲染第一页为图片（150 DPI）

            // 等比例压缩封面图片
            BufferedImage resized = CommonUtils.compressImage(image, "png", COVER_MAX_WIDTH);

            long ts = System.currentTimeMillis(); // 获取时间戳
            String tempFileName = "book_new_" + ts + "_cover.png"; // 生成临时文件名
            Path coverDir = Paths.get(storageProps.getCoverPath()); // 获取封面目录
            Files.createDirectories(coverDir); // 创建目录（如果不存在）
            Path coverFilePath = coverDir.resolve(tempFileName); // 构建封面文件路径
            ImageIO.write(resized, "png", coverFilePath.toFile()); // 保存封面图片

            book.setCoverUrl("/api/books/cover/" + tempFileName); // 设置封面URL
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
        ChatModel chatModel = chatModelFactory.buildVisionChatModel();
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
                contents.add(toImageContent(dataUri));
            }

            UserMessage userMessage =
                    UserMessage.from(contents);
            SystemMessage systemMessage =
                    SystemMessage.from(AiPromptConstants.OCR_METADATA_SYSTEM_PROMPT + " \n\n /no_think");

            ChatResponse response = chatModel.chat(List.of(systemMessage, userMessage));
            String result = response.aiMessage().text();

            result = CommonUtils.stripCodeFence(result);

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
     * 解析 TXT 格式图书
     * 提取开头文本、章节目录，并使用AI推断作者和简介
     *
     * @param book     图书实体对象
     * @param filePath TXT文件路径
     */
    private void parseTxt(Book book, Path filePath) {
        try {
            String content = Files.readString(filePath); // 读取TXT文件全部内容
            book.setTotalUnits((long) content.length()); // 设置总字符数

            // 缓存全文用于RAG（避免后续 generateContentEmbedding 二次读取文件）
            book.setRagContent(content);
            book.setFullText(content); // 持久化全文，用于向量重建

            // 取前15000字符用于 AI 评分和标签生成
            String preview = content.length() > 15000 ? content.substring(0, 15000) : content;
            book.setParsedContent(buildContentForTags(book, preview)); // 构建AI标签生成内容

            // 核心章节摘要：取开头3000字（去掉前200字可能的书名/版权信息）
            int summaryStart = Math.min(200, content.length()); // 跳过前200字（可能是书名或版权信息）
            int summaryEnd = Math.min(summaryStart + 3000, content.length()); // 截取接下来3000字
            if (summaryEnd > summaryStart) {
                // 提取章节摘要并清理空白字符
                String chapterSummary = content.substring(summaryStart, summaryEnd)
                        .replaceAll("\\s+", " ").trim();
                book.setChapterSummary(chapterSummary.length() > MAX_CONTENT_FOR_AI
                        ? chapterSummary.substring(0, MAX_CONTENT_FOR_AI) : chapterSummary);
            }

            // 尝试从内容中识别目录（匹配"第X章"或"Chapter X"格式）
            String toc = extractTxtToc(content);
            if (toc != null && !toc.isBlank()) {
                book.setToc(toc); // 设置目录
            }

            // TXT 没有结构化元数据，用大模型从前2000字推断作者和简介
            // 简介始终生成（AI基于正文生成更完整），作者仅在缺失时推断
            chatModelManager.inferMetadataFromContent(book, preview); // 从内容推断元数据
        } catch (Exception e) {
            log.warn("TXT 解析失败: {} - {}", book.getTitle(), e.getMessage());
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
     * 分层采样：从全文中等距取 STRATIFIED_SEGMENTS 段，确保 AI 看到开头、中间、结尾。
     * 内容不足 totalChars 则原样返回，不做截断。
     */
    private String stratifiedSample(String content) {
        int len = content.length();
        if (len <= BookParserService.MAX_CONTENT_FOR_AI) return content;

        int segmentSize = BookParserService.MAX_CONTENT_FOR_AI / STRATIFIED_SEGMENTS;
        int remaining = BookParserService.MAX_CONTENT_FOR_AI % STRATIFIED_SEGMENTS;
        StringBuilder sb = new StringBuilder(BookParserService.MAX_CONTENT_FOR_AI + STRATIFIED_SEGMENTS * 30);
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
            sb.append("内容/目录：\n").append(CommonUtils.truncateText(extraContent, MAX_CONTENT_FOR_AI)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 从图书文件中重新提取内容用于标签生成
     */
    private String extractContentForTags(Book book) {
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

    // ======================== 合并 AI 请求（标签 + 评分 + 相关度，一次调用） ========================

    /**
     * 为图书一次性生成所有 AI 数据（标签 + 评分 + 8维度相关度得分 + 简介）
     * 合并为一次 LLM 调用，提高效率和降低成本
     * 仅填充 book 实体字段，不做任何数据库操作
     *
     * @param book 图书实体对象
     */
    @LogAction("生成全部AI数据")
    public void generateAllAiData(Book book) {
        Long bookId = book.getId(); // 获取图书ID
        try {
            // 获取图书内容：DB fullText > 同请求缓存 parsedContent > 文件提取
            String content = book.getFullText();
            if (content == null || content.isBlank()) {
                content = book.getParsedContent();
            }
            if (content == null || content.isBlank()) {
                content = extractContentForTags(book); // 从文件中提取内容
            }
            if (content == null || content.isBlank()) {
                log.debug("图书无内容可供生成AI数据: bookId={}", bookId);
                return;
            }

            // 合并调用 AI，一次性生成标签、评分、相关度和简介
            CombinedAiResult result = callAiCombined(content);

            // 记录AI调用结果日志
            log.info("========== AI合并调用结果 start ==========");
            log.info("AI合并调用结果: bookId={}", bookId);
            log.info("AI合并调用结果: tags: {}", result != null ? result.tags : "null");
            log.info("AI合并调用结果: concept: {}", result != null ? result.concept : "null");
            log.info("AI合并调用结果: readerNeed: {}", result != null ? result.readerNeed : "null");
            log.info("AI合并调用结果: targetReader: {}", result != null ? result.targetReader : "null");
            log.info("AI合并调用结果: rating: {}", result != null ? result.rating : "null");
            log.info("AI合并调用结果: relevanceScoresJson: {}", result != null ? result.relevanceScoresJson : "null");
            log.info("AI合并调用结果: description: {}", result != null ? result.description : "null");
            log.info("AI合并调用结果: description长度: {}", result != null && result.description != null ? result.description.length() : 0);
            log.info("========== AI合并调用结果 end ==========");

            if (result == null) {
                log.warn("合并AI调用返回空结果: bookId={}", bookId);
                return;
            }

            // 填充标签（将标签列表转换为JSON数组字符串）
            if (result.tags != null && !result.tags.isEmpty()) {
                String tagsJson = result.tags.stream()
                        .map(t -> "\"" + t + "\"") // 为每个标签添加引号
                        .collect(Collectors.joining(",", "[", "]")); // 拼接为JSON数组
                book.setFormatTags(tagsJson); // 设置标签
            }

            // 填充核心概念标签
            if (result.concept != null && !result.concept.isEmpty()) {
                String conceptJson = result.concept.stream()
                        .map(t -> "\"" + t + "\"")
                        .collect(Collectors.joining(",", "[", "]"));
                book.setConceptTags(conceptJson);
            }

            // 填充读者需求标签
            if (result.readerNeed != null && !result.readerNeed.isEmpty()) {
                String readerNeedJson = result.readerNeed.stream()
                        .map(t -> "\"" + t + "\"")
                        .collect(Collectors.joining(",", "[", "]"));
                book.setReaderNeedTags(readerNeedJson);
            }

            // 填充目标读者标签
            if (result.targetReader != null && !result.targetReader.isEmpty()) {
                String targetReaderJson = result.targetReader.stream()
                        .map(t -> "\"" + t + "\"")
                        .collect(Collectors.joining(",", "[", "]"));
                book.setTargetReaderTags(targetReaderJson);
            }

            // 填充评分
            if (result.rating != null) {
                book.setRating(result.rating); // 设置评分
            }

            // 填充8维度相关度得分（JSON字符串）
            if (result.relevanceScoresJson != null && !result.relevanceScoresJson.isBlank()) {
                book.setRelevanceScores(result.relevanceScoresJson);
            }

            // 填充AI生成的简介
            if (result.description != null && !result.description.isBlank()) {
                book.setDescription(result.description); // 设置简介
            }

        } catch (Exception e) {
            log.warn("合并AI数据生成失败: bookId={} - {}", bookId, e.getMessage());
        }
    }

    /**
     * 为图书生成 RAG 内容向量（书籍内容分块 → Qdrant）
     * 在图书入库后调用，将书籍全文分块生成 embedding 存入 Qdrant content 集合
     *
     * @param bookId 图书ID
     */
    @LogAction("生成RAG内容向量")
    public void generateContentEmbedding(Long bookId) {
        try {
            Book book = bookService.getBookById(bookId);
            // 优先从 DB 的 fullText 读取，避免重新解析文件
            String content = book.getFullText();
            if (content == null || content.isBlank()) {
                content = extractContentForRAG(book);
            }
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
    @LogAction("生成RAG内容向量(预提内容)")
    public void generateContentEmbedding(Long bookId, String content) {
        try {
            if (content == null || content.isBlank()) {
                Book book = bookService.getBookById(bookId);
                // 优先从 DB fullText 读取，避免重新解析文件
                if (book.getFullText() != null && !book.getFullText().isBlank()) {
                    content = book.getFullText();
                    log.debug("使用DB全书内容: bookId={}, contentLen={}", bookId, content.length());
                } else if (book.getRagContent() != null && !book.getRagContent().isBlank()) {
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
            embeddingService.generateContentEmbeddingWithCount(bookId, content);
            log.info("触发RAG内容向量生成: bookId={}, contentLen={}", bookId, content.length());
        } catch (Exception e) {
            log.warn("触发RAG内容向量生成失败: bookId={} - {}", bookId, e.getMessage());
        }
    }

    /**
     * 确保图书内容向量已生成（分布式锁）
     * <p>
     * 多个线程同时请求同一本无向量的书时，只有一个线程执行向量化，
     * 其他线程获取锁失败，通过 {@code null} 返回值告知调用方等待重试。
     *
     * @param bookId 图书ID
     * @return {@link Boolean#TRUE} 成功，{@link Boolean#FALSE} 失败，{@code null} 锁被占用
     */
    @RedisLock(key = "'book:content:embed:' + #bookId", leaseTime = 60, timeUnit = TimeUnit.MINUTES)
    @LogAction("确保内容向量已生成")
    public Boolean ensureContentEmbedded(Long bookId) {
        Book book = bookService.getBookById(bookId);
        if (Boolean.TRUE.equals(book.getContentEmbedded())) {
            return true;
        }
        int count = generateContentEmbeddingWithCount(bookId);
        return count > 0;
    }

    /**
     * 强制重建内容向量（当 contentEmbedded 标志为 true 但 Qdrant 实际向量缺失/损坏时使用）
     * <p>
     * 与 {@link #ensureContentEmbedded} 的区别：本方法不信任 contentEmbedded 标志，
     * 直接检测 Qdrant 中的实际向量状态。如果向量缺失或全零，强制重建。
     * 同样使用 @RedisLock 防止并发重建。
     *
     * @param bookId 图书ID
     * @return {@link Boolean#TRUE} 重建成功，{@link Boolean#FALSE} 重建失败，{@code null} 锁被占用
     */
    @RedisLock(key = "'book:content:embed:' + #bookId", leaseTime = 60, timeUnit = TimeUnit.MINUTES)
    @LogAction("检测并重建内容向量")
    public Boolean forceReEmbedIfMissing(Long bookId) {
        if (!embeddingService.detectZeroContentVectors(bookId)) {
            return true;
        }
        log.info("检测到内容向量缺失/损坏，强制重建: bookId={}", bookId);
        int count = generateContentEmbeddingWithCount(bookId);
        return count > 0;
    }

    /**
     * 为图书全文重新向量化（返回 chunk 数量）
     * 用于管理员手动修复或自动风控触发
     */
    @LogAction("重新内容向量化")
    public int generateContentEmbeddingWithCount(Long bookId) {
        try {
            Book book = bookService.getBookById(bookId);
            // 优先从 DB 的 fullText 读取，避免重新解析文件
            String content = book.getFullText();
            if (content == null || content.isBlank()) {
                content = extractContentForRAG(book);
            }
            if (content == null || content.isBlank()) {
                log.debug("图书无内容可供生成RAG向量: bookId={}", bookId);
                return 0;
            }
            int chunkCount = embeddingService.generateContentEmbeddingWithCount(bookId, content);
            book = bookService.getBookById(bookId);
            book.setContentEmbedded(chunkCount > 0);
            // 记录向量层一致性指纹（模型标识 + 维度），用于后续启动校验和重建风控
            if (chunkCount > 0) {
                book.setContentEmbeddingModel(embeddingService.getCurrentEmbeddingModelName());
                book.setContentEmbeddingDim(embeddingService.getCurrentEmbeddingDim());
            } else {
                book.setContentEmbeddingModel(null);
                book.setContentEmbeddingDim(null);
            }
            bookService.updateBook(bookId, book);
            // 内容向量重建后，失效该书的 RAG 答案缓存（基于旧内容的缓存不再有效）
            if (chunkCount > 0) {
                ragAnswerCache.invalidateBook(bookId);
            }
            log.info("图书全文重新向量化完成: bookId={}, chunks={}, model={}",
                    bookId, chunkCount,
                    chunkCount > 0 ? embeddingService.getCurrentEmbeddingModelName() : "N/A");
            return chunkCount;
        } catch (Exception e) {
            log.warn("图书全文重新向量化失败: bookId={} - {}", bookId, e.getMessage());
            return 0;
        }
    }

    /**
     * 为图书生成元数据向量（标题+作者+标签+简介 → 1个 embedding，用于推荐召回）
     *
     * @param book 图书
     */
    @LogAction("生成元数据向量")
    public void generateBookEmbedding(Book book) {
        try {
            embeddingService.generateBookEmbedding(book);
        } catch (Exception e) {
            log.warn("触发元数据向量生成失败: bookId={} - {}", book.getId(), e.getMessage());
        }
    }

    /**
     * 提取图书内容用于 RAG 向量化
     */
    private String extractContentForRAG(Book book) {
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
    private String extractEpubTextViaZip(Path filePath) {
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
        return ocrPdfWithVisionModel(book, storageProps.resolveBookPath(book.getFileUrl(), book.getFormat()), totalPages);
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
        ChatModel chatModel = chatModelFactory.buildVisionChatModel();
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
    private static ImageContent toImageContent(String dataUri) {
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
                contents.add(toImageContent(dataUri));
            }

            UserMessage userMessage =
                    UserMessage.from(contents);

            SystemMessage systemMessage =
                    SystemMessage.from(
                            "你是一个专业的 OCR 文字识别助手。你的唯一任务是准确识别图片中的文字内容并原样输出。" +
                                    "不要添加任何额外的解释、总结或评论。只输出图片中出现的文字。 \n\n /no_think");

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
    public record CombinedAiResult(List<String> tags, List<String> concept, List<String> readerNeed, List<String> targetReader, Double rating, String relevanceScoresJson, String description) {
    }

    /**
     * 一次 LLM 调用同时生成标签、评分、8维度相关度得分
     */
    private CombinedAiResult callAiCombined(String content) {
        String rawResult = chatModelManager.callAiWithoutThinking(AI_OP_COMBINED,
                String.format("输入: %s", content.replaceAll("\\n", " ").substring(0, Math.min(100, content.length()))),
                Lists.newArrayList(
                        SystemMessage.from(AiPromptConstants.COMBINED_PROMPT_SYSTEM_PROMPT),
                        UserMessage.from(content)
                )
        );
        if (rawResult == null) return null;

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

            return new CombinedAiResult(tags, concept, readerNeed, targetReader, rating, relevanceScoresJson, description);

        } catch (Exception e) {
            log.warn("AI 合并调用结果解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 为新入库的图书重命名封面文件（使用正式的 bookId）
     */
    @LogAction("最终处理封面")
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
            if (Files.exists(newFile)) {
                Files.delete(newFile);
            }
            Files.move(oldFile, newFile);
            book.setCoverUrl("/api/books/cover/" + newFileName);
            log.info("封面重命名: {} -> {}", oldFileName, newFileName);

        } catch (Exception e) {
            log.warn("封面重命名失败: {}", e.getMessage());
        }
    }


    /**
     * 流式生成图书速读摘要（SSE推送）
     * <p>
     * 通过SSE（Server-Sent Events）实时推送速读摘要生成过程，
     * 提升用户体验，避免长时间等待。
     *
     * @param bookId 图书ID
     * @param userId 用户ID（可选，用于个性化摘要）
     * @return SseEmitter对象，用于流式推送生成结果
     */
    @LogAction("流式生成速读摘要")
    public SseEmitter streamSpeedRead(Long bookId, Long userId) {
        SseEmitter emitter = new SseEmitter(360_000L);

        Book book = bookService.getBookById(bookId);
        if (book == null) {
            SseHelper.sendErrorAndComplete(emitter, "图书不存在");
            return emitter;
        }

        com.kbook.entity.User user = null;
        if (userId != null) {
            try {
                user = userService.getUserById(userId);
            } catch (Exception e) {
                log.debug("获取用户失败: {}", e.getMessage());
            }
        }

        com.kbook.entity.User finalUser = user;
        Future<?> aiFuture = sseExecutor.submit(() -> chatModelManager.streamSpeedRead(book, finalUser, emitter));

        emitter.onCompletion(() -> aiFuture.cancel(true));
        emitter.onTimeout(() -> aiFuture.cancel(true));
        emitter.onError(e -> aiFuture.cancel(true));

        return emitter;
    }
}
