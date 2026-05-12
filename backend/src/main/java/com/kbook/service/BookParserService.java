package com.kbook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.util.CommonUtils;
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
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${kbook.cover-path}")
    private String coverPath;

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
     * AI 标签生成的系统提示词（单独调用时使用）
     */
    private static final String TAG_SYSTEM_PROMPT = """
            你是一个专业的图书标签生成助手。根据提供的图书信息（书名、作者、简介、正文片段），生成3-8个精准的标签。

            规则：
            - 标签应涵盖：类型/题材、风格、主题、读者群体等维度
            - 每个标签2-4个字，简洁准确
            - 基于你读到的正文内容判断，不要仅凭书名推测
            - 只返回标签，用逗号分隔，不要编号和解释
            - 示例：科幻,太空歌剧,经典,冒险

            /no_think

            图书信息如下：
            """;

    /**
     * 合并AI请求的系统提示词 — 一次调用同时生成标签、评分、12维度相关度得分、简介
     */
    private static final String COMBINED_PROMPT = """
            你是一位严格的图书评论家，擅长基于文本内容深度评估图书质量。根据提供的图书信息（书名、作者、简介、正文片段），同时完成以下四项任务：

            任务1：生成3-8个精准的标签
            - 标签应涵盖：类型/题材、风格、主题、读者群体等维度
            - 每个标签2-4个字，简洁准确
            - 用逗号分隔，不要编号和解释
            - 示例：科幻,太空歌剧,经典,冒险

            任务2：给出1-5之间的评分（一位小数）

            【评分原则】你必须像一个严苛的评论家，评分应当符合正态分布：
            - 绝大多数书应在 2.5-3.5 之间（中等水平）
            - 4.0 及以上是稀缺的，只给真正思想深刻、文笔精湛或具有里程碑意义的书
            - 4.5 以上极其罕见，仅限传世经典或开创性著作
            - 1.5-2.5 给内容浅薄、逻辑混乱或纯粹无营养的书

            【评分尺度】（严格遵守，不要宽大处理）：
            - 1.0-1.9：质量很差，逻辑混乱或毫无价值
            - 2.0-2.5：平庸之作，浅尝辄止，缺乏深度
            - 2.6-3.0：中等偏下，有一定可读性但缺乏亮点
            - 3.1-3.5：中等水平，有合理内容但无突出价值
            - 3.6-4.0：良好，在某个维度有明显价值（思想深度/文学性/专业性）
            - 4.1-4.5：优秀，多维度出色，远超同类书籍
            - 4.6-5.0：传世经典，人类文明级别的著作

            【类型参考区间】（在该区间内根据实际质量区分高低）：
            - 深度思想类（哲学/政治/经济/历史/社会学/心理学/逻辑学/军事战略）：3.0-4.5，极少数经典可到4.6+
            - 专业学术类（科学/技术/数学/医学）：3.0-4.2
            - 经典文学名著：3.5-4.8
            - 当代文学/传记/纪实：2.8-4.0
            - 生活/健康/职场/自助类：2.0-3.5
            - 网络小说/言情/都市/玄幻/仙侠/穿越/修仙/轻小说：1.5-3.0
            - 类型小说（悬疑/推理/冒险/恐怖）：2.0-3.5

            【重要提醒】：
            - 不要因为书名看起来严肃就给高分，要基于你读到的正文内容来判断
            - 如果正文内容浅薄、空洞、套路化，即使题材严肃也必须低分
            - 一本普通的通俗读物就该在2.5-3.0，不要因为"还行"就给3.5+
            - 同一类型内也要拉开差距：写得好3.5，写得普通2.8，写得差2.0

            任务3：为以下维度打分（0-1之间的小数），返回JSON格式
            年龄段："0-9","10-19","20-29","30-39","40-49","50-59","60+"
            性别："male","female"
            婚姻："married","unmarried"
            子女："hasChildren","noChildren"
            MBTI："INTJ","INTP","ENTJ","ENTP","INFJ","INFP","ENFJ","ENFP","ISTJ","ISFJ","ESTJ","ESFJ","ISTP","ISFP","ESTP","ESFP"
            职业："student","tech","finance","education","medical","arts","management","freelance","retired","other"
            学历："high_school","college","bachelor","master","doctorate","other_edu"
            创业意向："entrepreneur","wantEntrepreneur","notInterested"
            年收入："under_50k","50k_150k","150k_300k","300k_500k","500k_1m","over_1m","prefer_not_to_say"
            心情："happy","calm","anxious","sad","motivated","tired","curious"

            任务4：生成图书简介
            - 基于你读到的正文内容，写一段100-300字的内容简介
            - 如果图书信息中已有简介，在原有简介基础上补充完善（不重复、不遗漏关键信息）
            - 简介应包含：书籍的核心主题、主要内容或故事梗概、适合的读者群体
            - 语言简洁客观，不要使用夸张的宣传语
            - 如果是小说，简述故事背景和主角，不要剧透结局
            - 如果是非虚构类，概括核心观点和论述框架

            只返回以下JSON格式，不要其他文字：
            {
              "tags": "标签1,标签2,标签3",
              "rating": 3.0,
              "relevance": {"0-9":0.1,"10-19":0.3,"20-29":0.8,"30-39":0.7,"40-49":0.5,"50-59":0.3,"60+":0.2,"male":0.6,"female":0.7,"married":0.5,"unmarried":0.8,"hasChildren":0.4,"noChildren":0.8,"INTJ":0.7,"INTP":0.6,"ENTJ":0.5,"ENTP":0.6,"INFJ":0.8,"INFP":0.9,"ENFJ":0.7,"ENFP":0.7,"ISTJ":0.4,"ISFJ":0.5,"ESTJ":0.3,"ESFJ":0.4,"ISTP":0.4,"ISFP":0.5,"ESTP":0.3,"ESFP":0.4,"student":0.3,"tech":0.5,"finance":0.4,"education":0.6,"medical":0.3,"arts":0.5,"management":0.4,"freelance":0.4,"retired":0.3,"other":0.4,"high_school":0.2,"college":0.4,"bachelor":0.6,"master":0.7,"doctorate":0.5,"other_edu":0.3,"entrepreneur":0.6,"wantEntrepreneur":0.5,"notInterested":0.4,"under_50k":0.3,"50k_150k":0.4,"150k_300k":0.6,"300k_500k":0.7,"500k_1m":0.6,"over_1m":0.5,"prefer_not_to_say":0.5,"happy":0.6,"calm":0.7,"anxious":0.3,"sad":0.4,"motivated":0.7,"tired":0.3,"curious":0.8},
              "description": "基于正文内容生成的100-300字图书简介"
            }

            /no_think

            图书信息如下：
            """;

    /**
     * 初始化时打印封面目录的绝对路径
     */
    @PostConstruct
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
                } catch (Exception ignored) {}
                if (epubBodyForTags.length() >= 15000) break;
            }
            // 合并目录+正文，让 AI 评分基于实际内容
            String combinedForTags = (tocBuilder.length() > 0 ? "【目录】\n" + tocBuilder + "\n\n【正文】\n" : "")
                    + epubBodyForTags.toString();
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
                Path coverDir = Paths.get(coverPath);
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
            if (!isScanned && (book.getAuthor() == null || book.getAuthor().isBlank()
                    || true /* 始终用AI生成更完整的简介 */)) {
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
                    String base64 = imageToJpegBase64(image, 0.85f);
                    imageDataUris.add("data:image/jpeg;base64," + base64);
                } finally {
                    if (image != null) image.flush();
                }
            }

            if (imageDataUris.isEmpty()) return;

            // 构建多模态消息：要求模型识别书名/作者/简介/目录
            String thinkingSuffix = aiProviderConfigService.getThinkingPromptSuffix();
            String ocrPrompt = "请识别这些PDF页面图片中的文字内容，并提取以下信息，以JSON格式返回：\n" +
                    "- title: 书名（如果能看到的话）\n" +
                    "- author: 作者（如果能看到的话）\n" +
                    "- description: 简介/内容简介（如果能看到的话，尽量完整提取）\n" +
                    "- toc: 目录（如果能看到目录页，列出所有章节标题，每行一个）\n" +
                    "如果某项信息在图片中找不到，对应字段填 null。\n" +
                    "只返回JSON，不要其他文字。" + thinkingSuffix;

            List<Content> contents = new ArrayList<>();
            contents.add(TextContent.from(ocrPrompt));
            for (String dataUri : imageDataUris) {
                contents.add(ImageContent.from(dataUri));
            }

            UserMessage userMessage =
                    UserMessage.from(contents);
            SystemMessage systemMessage =
                    SystemMessage.from(
                            "你是一个专业的 OCR 文字识别助手，擅长从书籍封面、版权页、目录页中提取结构化信息。");

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
            if (book.getAuthor() == null || book.getAuthor().isBlank() || true) {
                inferMetadataFromContent(book, preview);
            }
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
            String thinkingSuffix = aiProviderConfigService.getThinkingPromptSuffix();
            String prompt = "根据以下书籍内容，推断并提取以下信息，以JSON格式返回：\n" +
                    "- author: 作者名（如果内容中能看出来，否则填 null）\n" +
                    "- description: 简短的内容简介（50-200字，概括书籍主题和内容，如果内容中自带简介则提取原简介）\n" +
                    "只返回JSON，不要其他文字。\n\n" +
                    "书籍内容：\n" + CommonUtils.truncateText(content, 2000) + thinkingSuffix;

            ChatResponse response = chatModel.chat(List.of(
                    SystemMessage.from(
                            "你是一个专业的图书信息提取助手，擅长从文本内容中推断书籍的作者和简介。"),
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
     * 为图书生成 AI 标签（入库后调用）- 非强制版本
     */
    public void generateTags(Long bookId) {
        generateTags(bookId, false);
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
                } catch (Exception ignored) {}
                if (bodyBuilder.length() >= 15000) break;
            }

            // 提取目录
            StringBuilder tocBuilder = new StringBuilder();
            if (epubBook.getTableOfContents() != null) {
                extractEpubTocChildren(epubBook.getTableOfContents().getTocReferences(), tocBuilder, 0);
            }

            String combined = (tocBuilder.length() > 0 ? "【目录】\n" + tocBuilder + "\n\n【正文】\n" : "")
                    + bodyBuilder.toString();
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
            String thinkingSuffix = aiProviderConfigService.getThinkingPromptSuffix();
            ChatResponse response = chatModel.chat(List.of(
                    SystemMessage.from(TAG_SYSTEM_PROMPT),
                    UserMessage.from(content + thinkingSuffix)
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
            你是一位严格的图书评论家。根据提供的图书信息（书名、作者、简介、正文片段），给出一个1.0-5.0之间的评分（5星制，一位小数）。

            【评分原则】评分必须符合正态分布，绝大多数书应在2.5-3.5之间：
            - 1.0-1.9：质量很差，逻辑混乱或毫无价值
            - 2.0-2.5：平庸之作，浅尝辄止，缺乏深度
            - 2.6-3.0：中等偏下，有一定可读性但缺乏亮点
            - 3.1-3.5：中等水平，有合理内容但无突出价值
            - 3.6-4.0：良好，在某个维度有明显价值
            - 4.1-4.5：优秀，多维度出色，远超同类
            - 4.6-5.0：传世经典，极其罕见

            【类型参考区间】（在区间内根据实际质量区分高低）：
            - 深度思想类（哲学/政治/经济/历史/社会学/心理学/逻辑学/军事战略）：3.0-4.5
            - 专业学术类（科学/技术/数学/医学）：3.0-4.2
            - 经典文学名著：3.5-4.8
            - 当代文学/传记/纪实：2.8-4.0
            - 生活/健康/职场/自助类：2.0-3.5
            - 网络小说/言情/都市/玄幻/仙侠/穿越/修仙/轻小说：1.5-3.0
            - 类型小说（悬疑/推理/冒险/恐怖）：2.0-3.5

            【重要提醒】不要因为书名看起来严肃就给高分，要基于正文内容判断。

            规则：
            - 只返回一个数字（1.0-5.0之间的一位小数），不要其他文字
            - 示例：3.0

            /no_think

            图书信息如下：
            """;

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
            String thinkingSuffix = aiProviderConfigService.getThinkingPromptSuffix();
            ChatResponse response = chatModel.chat(List.of(
                    UserMessage.from(RATING_PROMPT + content + thinkingSuffix)
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
     * 12维度相关度得分 AI 提示词
     */
    private static final String RELEVANCE_PROMPT = """
            你是一个专业的图书读者匹配分析助手。根据提供的图书信息（书名、作者、简介或目录），分析这本书对不同读者群体的适合程度。
            
            请为以下12个维度打分（0-1之间的小数，0表示完全不适合，1表示非常适合），并以JSON格式返回：
            
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
            
            职业维度（10种职业大类）：
            - "student": 在校学生适合度
            - "tech": 技术/IT从业者适合度
            - "finance": 金融/商业从业者适合度
            - "education": 教育/科研工作者适合度
            - "medical": 医疗/健康从业者适合度
            - "arts": 文艺/传媒工作者适合度
            - "management": 管理/行政人员适合度
            - "freelance": 自由职业者适合度
            - "retired": 退休人员适合度
            - "other": 其他职业适合度
            
            学历维度（6种学历类别）：
            - "high_school": 高中及以下学历适合度
            - "college": 大专学历适合度
            - "bachelor": 本科学历适合度
            - "master": 硕士学历适合度
            - "doctorate": 博士学历适合度
            - "other_edu": 其他学历适合度
            
            创业意向维度（3种意向）：
            - "entrepreneur": 正在创业的人适合度
            - "wantEntrepreneur": 想创业的人适合度
            - "notInterested": 暂不考虑创业的人适合度
            
            年收入维度（7种收入区间）：
            - "under_50k": 年收入5万以下适合度
            - "50k_150k": 年收入5-15万适合度
            - "150k_300k": 年收入15-30万适合度
            - "300k_500k": 年收入30-50万适合度
            - "500k_1m": 年收入50-100万适合度
            - "over_1m": 年收入100万以上适合度
            - "prefer_not_to_say": 不愿透露收入的人适合度
            
            心情维度（7种心情状态）：
            - "happy": 开心状态适合度
            - "calm": 平静状态适合度
            - "anxious": 焦虑状态适合度
            - "sad": 低落状态适合度
            - "motivated": 充满动力状态适合度
            - "tired": 疲惫状态适合度
            - "curious": 好奇状态适合度
            
            只返回JSON，不要其他文字。格式示例：
            {"0-9":0.1,"10-19":0.3,"20-29":0.8,"30-39":0.7,"40-49":0.5,"50-59":0.3,"60+":0.2,"male":0.6,"female":0.7,"married":0.5,"unmarried":0.8,"hasChildren":0.4,"noChildren":0.8,"INTJ":0.7,"INTP":0.6,"ENTJ":0.5,"ENTP":0.6,"INFJ":0.8,"INFP":0.9,"ENFJ":0.7,"ENFP":0.7,"ISTJ":0.4,"ISFJ":0.5,"ESTJ":0.3,"ESFJ":0.4,"ISTP":0.4,"ISFP":0.5,"ESTP":0.3,"ESFP":0.4,"student":0.3,"tech":0.5,"finance":0.4,"education":0.6,"medical":0.3,"arts":0.5,"management":0.4,"freelance":0.4,"retired":0.3,"other":0.4,"high_school":0.2,"college":0.4,"bachelor":0.6,"master":0.7,"doctorate":0.5,"other_edu":0.3,"entrepreneur":0.6,"wantEntrepreneur":0.5,"notInterested":0.4,"under_50k":0.3,"50k_150k":0.4,"150k_300k":0.6,"300k_500k":0.7,"500k_1m":0.6,"over_1m":0.5,"prefer_not_to_say":0.5,"happy":0.6,"calm":0.7,"anxious":0.3,"sad":0.4,"motivated":0.7,"tired":0.3,"curious":0.8}
            
            /no_think
            
            图书信息如下：
            """;

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
                            String base64 = imageToJpegBase64(image, 0.85f);
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
    private String imageToJpegBase64(BufferedImage image, float quality) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
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
            String thinkingSuffix = aiProviderConfigService.getThinkingPromptSuffix();

            // 构建用户消息文本（包含 OCR 指令 + thinking 后缀）
            String ocrPrompt = String.format(
                    "请仔细识别以下PDF页面图片中的所有文字内容，原样输出，不要遗漏任何文字。" +
                            "保持原文的段落结构，如果图片中有表格，用文本形式还原表格内容。" +
                            "不要添加任何解释、总结或评论，只输出识别到的原文。" +
                            "（当前处理的是%s）%s", batchDesc, thinkingSuffix);

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
            log.info("callAiCombined 输入内容: {}", content);

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
