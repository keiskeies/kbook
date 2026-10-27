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

    /** AI 内容预算字符上限（提取器侧同名常量独立） */
    private static final int MAX_CONTENT_FOR_AI = 15000;

    private final ChatModelFactory chatModelFactory;
    private final ChatModelManager chatModelManager;
    private final UserProfileBuilder userProfileBuilder;
    private final BookMetadataInferrer bookMetadataInferrer;
    private final BookService bookService; // 书籍服务
    private final UserService userService; // 用户服务
    private final ObjectMapper objectMapper; // JSON对象映射器
    private final BookStorageProperties storageProps; // 书籍存储配置属性
    private final ExecutorService sseExecutor;
    private final BookFullTextExtractor extractor;
    private final BookAiDataService aiDataService;
    private final com.kbook.service.ai.SpeedReadCache speedReadCache;

    public BookParserService(
            ChatModelFactory chatModelFactory,
            ChatModelManager chatModelManager,
            UserProfileBuilder userProfileBuilder,
            BookMetadataInferrer bookMetadataInferrer,
            BookService bookService,
            UserService userService,
            ObjectMapper objectMapper,
            BookStorageProperties storageProps,
            com.kbook.service.ai.SpeedReadCache speedReadCache,
            BookFullTextExtractor extractor,
            BookAiDataService aiDataService,
            @Qualifier("sseExecutor") ExecutorService sseExecutor) {
        this.chatModelFactory = chatModelFactory;
        this.chatModelManager = chatModelManager;
        this.userProfileBuilder = userProfileBuilder;
        this.bookMetadataInferrer = bookMetadataInferrer;
        this.bookService = bookService;
        this.userService = userService;
        this.objectMapper = objectMapper;
        this.storageProps = storageProps;
        this.speedReadCache = speedReadCache;
        this.extractor = extractor;
        this.aiDataService = aiDataService;
        this.sseExecutor = sseExecutor;
    }

    /**
     * 封面最大宽度（像素）
     * 用于等比例压缩封面图片，确保统一尺寸
     */
    private static final int COVER_MAX_WIDTH = 300;


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
            String fullText = extractor.extractEpubTextViaZip(filePath); // 通过ZIP方式提取文本
            if (fullText != null && !fullText.isBlank()) {
                book.setRagContent(fullText); // 设置RAG全文内容
                book.setFullText(fullText); // 持久化全文，用于向量重建
                // 构建用于AI标签生成的内容（分层采样全文）
                book.setParsedContent(extractor.buildContentForTags(book, extractor.stratifiedSample(fullText)));
            }
        }
    }

    /**
     * ZIP 降级提取全文
     */
    private void fallbackToZipExtraction(Book book, Path filePath) {
        log.info("EPUB 降级模式: 尝试 ZIP 提取全文: bookId={}", book.getId());
        String fullText = extractor.extractEpubTextViaZip(filePath);
        if (fullText != null && !fullText.isBlank()) {
            book.setRagContent(fullText);
            book.setFullText(fullText); // 持久化全文，用于向量重建
            book.setParsedContent(extractor.buildContentForTags(book, fullText.substring(0, Math.min(fullText.length(), 15000))));
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
        String toc = extractor.buildEpubTocInternal(epubBook);
        if (!toc.isEmpty()) {
            book.setToc(toc);
        }

        // 提取核心章节摘要（目录 + 按章均匀分配）
        book.setChapterSummary(extractEpubChapterSummary(epubBook));

        // 提取用于AI标签生成的正文内容（目录 + 按章均匀分配）
        book.setParsedContent(extractor.buildContentForTags(book, extractor.buildChapterBasedContent(epubBook)));

        // 提取用于RAG的完整全文内容
        book.setRagContent(extractor.extractEpubFullTextFromEpubBook(epubBook));
        book.setFullText(book.getRagContent()); // 持久化全文，用于向量重建
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
            String toc = extractor.buildEpubTocInternal(epubBook);
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
                bookMetadataInferrer.infer(book, firstPagesText); // 从内容推断元数据
            }

            // 7. 构建 AI 标签生成的内容
            book.setParsedContent(extractor.buildContentForTags(book, firstPagesText));

            // 8. 提取全文用于RAG（在PDDocument已打开时提取，避免后续二次加载文件）
            try {
                String fullText = extractor.extractPdfFullTextFromDocument(book, document, isScanned); // 提取全文
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
     * <p>
     * 走 {@link AiScene#PDF_OCR} 场景路由：用户可在后台显式绑定视觉模型，
     * 未绑定时优先用 YML {@code kbook.ai.vision} 配置，再回退到 QA 配置。
     */
    private void extractPdfMetadataWithOcr(Book book, PDDocument document) {
        try {
            // OCR 前3页获取封面/版权页/目录页的文字
            int pagesToOcr = Math.min(3, document.getNumberOfPages());
            PDFRenderer renderer = new PDFRenderer(document);
            List<String> imageDataUris = new ArrayList<>();

            for (int i = 0; i < pagesToOcr; i++) {
                BufferedImage image = null;
                try {
                    image = renderer.renderImageWithDPI(i, 100);
                    String base64 = extractor.imageToJpegBase64(image);
                    imageDataUris.add("data:image/jpeg;base64," + base64);
                } finally {
                    if (image != null) image.flush();
                }
            }

            if (imageDataUris.isEmpty()) return;

            // 构建多模态消息：要求模型识别书名/作者/简介/目录
            String ocrPrompt = BookMetaPrompts.OCR_METADATA_USER_PROMPT;

            List<Content> contents = new ArrayList<>();
            contents.add(TextContent.from(ocrPrompt));
            for (String dataUri : imageDataUris) {
                contents.add(extractor.toImageContent(dataUri));
            }

            UserMessage userMessage =
                    UserMessage.from(contents);
            SystemMessage systemMessage =
                    SystemMessage.from(BookMetaPrompts.OCR_METADATA_SYSTEM_PROMPT);
            // /no_think 由 callAiForScene 自动追加（PDF_OCR thinking=false）

            String result = chatModelManager.callAiForScene(AiScene.PDF_OCR, "PDF元数据OCR",
                    String.format("bookId=%s, pages=%d", book.getId(), pagesToOcr),
                    List.of(systemMessage, userMessage));

            if (result == null || result.isBlank()) return;

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
     * 解析 OCR 元数据 Markdown 结果并填充到 Book 对象。
     * <p>期望格式（来自 OCR_METADATA_USER_PROMPT）：
     * <pre>
     * ## 书名
     * xxx
     * ## 作者
     * xxx
     * ## 简介
     * xxx
     * ## 目录
     * xxx
     * </pre>
     */
    private void parseOcrMetadataResult(Book book, String markdown) {
        try {
            // 书名（仅当 PDF 元数据也没提取到时才用 OCR 结果）
            if (book.getTitle() == null || book.getTitle().isBlank() || book.getTitle().matches(".*\\.(pdf|PDF)$")) {
                String title = CommonUtils.extractMarkdownSection(markdown, "书名");
                if (title != null) {
                    book.setTitle(title);
                }
            }

            // 作者
            if (book.getAuthor() == null || book.getAuthor().isBlank()) {
                String author = CommonUtils.extractMarkdownSection(markdown, "作者");
                if (author != null) {
                    book.setAuthor(author);
                }
            }

            // 简介（始终覆盖，OCR提取的更准确）
            String desc = CommonUtils.extractMarkdownSection(markdown, "简介");
            if (desc != null) {
                book.setDescription(desc);
            }

            // 目录
            if (book.getToc() == null || book.getToc().isBlank()) {
                String toc = CommonUtils.extractMarkdownSection(markdown, "目录");
                if (toc != null) {
                    book.setToc(toc);
                }
            }

            log.info("PDF OCR 元数据提取成功: bookId={}, title={}, author={}, hasDesc={}, hasToc={}",
                    book.getId(), book.getTitle(), book.getAuthor(),
                    book.getDescription() != null && !book.getDescription().isBlank(),
                    book.getToc() != null && !book.getToc().isBlank());

        } catch (Exception e) {
            log.warn("解析 OCR 元数据 Markdown 失败: {} - {}",
                    markdown.substring(0, Math.min(200, markdown.length())), e.getMessage());
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
     * 解析 TXT 格式图书
     * 提取开头文本、章节目录，并使用AI推断作者和简介
     *
     * @param book     图书实体对象
     * @param filePath TXT文件路径
     */
    private void parseTxt(Book book, Path filePath) {
        try {
            String content = TextNormalizer.readText(filePath); // 读取TXT并按BOM/UTF-8/GB18030探测编码
            book.setTotalUnits((long) content.length()); // 设置总字符数

            // 缓存全文用于RAG（避免后续 generateContentEmbedding 二次读取文件）
            book.setRagContent(content);
            book.setFullText(content); // 持久化全文，用于向量重建

            // 取前15000字符用于 AI 评分和标签生成
            String preview = content.length() > 15000 ? content.substring(0, 15000) : content;
            book.setParsedContent(extractor.buildContentForTags(book, preview)); // 构建AI标签生成内容

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
            bookMetadataInferrer.infer(book, preview); // 从内容推断元数据
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







    // ======================== 合并 AI 请求（标签 + 评分 + 相关度，一次调用） ========================














    /**
     * OCR 每批次处理的页数（减少批次大小以降低内存峰值）
     */
    private static final int OCR_BATCH_SIZE = 1;













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
        Future<?> aiFuture = sseExecutor.submit(() -> streamSpeedRead(book, finalUser, emitter));

        emitter.onCompletion(() -> aiFuture.cancel(true));
        emitter.onTimeout(() -> aiFuture.cancel(true));
        emitter.onError(e -> aiFuture.cancel(true));

        return emitter;
    }

    /**
     * 流式生成 3 分钟速读摘要，通过 SSE 实时推送内容。
     * <p>
     * 提升用户体验。输出格式为 Markdown 标题 + 内容行，便于前端渲染。</p>
     *
     * @param book    书籍实体
     * @param user    用户实体（可为 null）
     * @param emitter SSE 发送器，用于推送流式数据
     */
    public void streamSpeedRead(Book book, User user, SseEmitter emitter) {
        Long bookId = book.getId();
        Long userId = user != null ? user.getId() : null;

        // 书籍内容 + 稳定画像指纹：任一变化都视为未命中（画像更新还会整体 DEL 用户 key）
        String bookContent = BookContentUtils.buildSpeedReadContent(book);
        String bookFp = speedReadCache.fingerprint(bookContent);
        String profileFp = speedReadCache.fingerprint(userProfileBuilder.buildStableSignature(user));

        // ① 命中缓存：按行分块流式回放，前端协议与真实生成一致
        String cached = speedReadCache.get(bookId, userId, bookFp, profileFp);
        if (cached != null) {
            speedReadCache.replay(emitter, cached);
            return;
        }

        // ② 未命中：抢生成锁，避免同用户同书并发重复调用 LLM
        boolean lockAcquired = speedReadCache.tryLock(bookId, userId);
        if (!lockAcquired) {
            String concurrentResult = speedReadCache.awaitCached(bookId, userId, bookFp, profileFp);
            if (concurrentResult != null) {
                speedReadCache.replay(emitter, concurrentResult);
                return;
            }
            log.info("[SPEED-READ-CACHE] 另一请求生成中，本次降级为不写缓存: bookId={}, userId={}",
                    bookId, userId);
        }

        try {
            // 获取流式模型实例（按场景路由）
            StreamingChatModel model = chatModelFactory.buildStreamingForScene(AiScene.SPEED_READ);
            if (model == null) {
                SseHelper.sendErrorAndComplete(emitter, "AI 模型未配置，无法生成速读摘要");
                return;
            }
            var logContext = chatModelFactory.buildLogContext(AiScene.SPEED_READ);

            String userProfileDesc = userProfileBuilder.build(user);

            // 固定角色 + 格式指令作为 SystemMessage（与动态内容分离，复用 KV Cache 前缀）
            String systemPrompt = BookDigestPrompts.SPEED_READ_SYSTEM_PROMPT;

            // 消息顺序优化 KV Cache：SystemMessage(固定指令) → UserMessage(图书信息) → UserMessage(用户画像)
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(systemPrompt + "\n\n /no_think"));
            messages.add(UserMessage.from("【书籍信息】\n" + bookContent + "\n\n /no_think"));
            if (!userProfileDesc.isBlank()) {
                messages.add(UserMessage.from("【读者画像】\n" + userProfileDesc + "\n\n /no_think"));
            }

            StreamingSseHandler.stream(model, messages, emitter, new StreamingSseHandler.Callback() {
                @Override
                public String getOperationName() {
                    return "3分钟速读";
                }

                @Override
                public void onComplete(String fullResponse, ChatResponse completeResponse) {
                    // 统一摘要日志已由 StreamingSseHandler.onCompleteResponse 打印
                    if (!lockAcquired) return;
                    FinishReason finishReason = completeResponse != null ? completeResponse.finishReason() : null;
                    if (finishReason == FinishReason.LENGTH) {
                        log.warn("[SPEED-READ-CACHE] 输出被 token 上限截断，跳过缓存: bookId={}, userId={}",
                                bookId, userId);
                        return;
                    }
                    if (!isCacheableSpeedRead(fullResponse)) {
                        log.warn("[SPEED-READ-CACHE] 输出结构不完整，跳过缓存: bookId={}, userId={}",
                                bookId, userId);
                        return;
                    }
                    speedReadCache.put(bookId, userId, bookFp, profileFp, fullResponse);
                }
            }, 2, logContext);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) return;
            log.warn("流式速读摘要异常: bookId={} - {}", bookId, e.getMessage());
            SseHelper.sendErrorAndComplete(emitter, SseHelper.extractFriendlyError(e));
        } finally {
            if (lockAcquired) {
                speedReadCache.unlock(bookId, userId);
            }
        }
    }

    /**
     * 只有结构完整的输出才写入缓存，避免把半截内容或异常文本长期留在缓存里。
     */
    private static boolean isCacheableSpeedRead(String text) {
        return text != null && text.length() >= 100 && text.contains("### ");
    }
    // ==================== 委托（公开 API 保持原签名，调用方零改动） ====================

    @LogAction("生成全部AI数据")
    public void generateAllAiData(Book book) {
        aiDataService.generateAllAiData(book);
    }

    @LogAction("生成RAG内容向量")
    public void generateContentEmbedding(Long bookId) {
        aiDataService.generateContentEmbedding(bookId);
    }

    public Boolean ensureContentEmbedded(Long bookId) {
        return aiDataService.ensureContentEmbedded(bookId);
    }

    public Boolean forceReEmbedIfMissing(Long bookId) {
        return aiDataService.forceReEmbedIfMissing(bookId);
    }

    public void generateBookEmbedding(Book book) {
        aiDataService.generateBookEmbedding(book);
    }

    public int generateContentEmbeddingWithCount(Long bookId) {
        return aiDataService.generateContentEmbeddingWithCount(bookId);
    }

    public void finalizeCover(Book book) {
        aiDataService.finalizeCover(book);
    }

}
