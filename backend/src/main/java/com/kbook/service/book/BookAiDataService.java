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
 * 图书 AI 数据服务 — 标签/评分/相关度/简介合并生成、内容向量嵌入、封面定稿。
 */
@Slf4j
@Service
public class BookAiDataService {

    /** AI 调用操作类型常量 */
    private static final String AI_OP_COMBINED = "合并请求（标签+评分+相关度）";

    private final ChatModelManager chatModelManager;
    private final EmbeddingService embeddingService;
    private final BookService bookService;
    private final BookStorageProperties storageProps;
    private final com.kbook.service.ai.rag.RagAnswerCache ragAnswerCache;
    private final ObjectMapper objectMapper;
    private final BookFullTextExtractor extractor;

    public BookAiDataService(
            ChatModelManager chatModelManager,
            EmbeddingService embeddingService,
            BookService bookService,
            BookStorageProperties storageProps,
            com.kbook.service.ai.rag.RagAnswerCache ragAnswerCache,
            ObjectMapper objectMapper,
            BookFullTextExtractor extractor) {
        this.chatModelManager = chatModelManager;
        this.embeddingService = embeddingService;
        this.bookService = bookService;
        this.storageProps = storageProps;
        this.ragAnswerCache = ragAnswerCache;
        this.objectMapper = objectMapper;
        this.extractor = extractor;
    }

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
                content = extractor.extractContentForTags(book); // 从文件中提取内容
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
                content = extractor.extractContentForRAG(book);
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
                    content = extractor.extractContentForRAG(book);
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
                content = extractor.extractContentForRAG(book);
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
     * 合并AI调用结果
     */
    public record CombinedAiResult(List<String> tags, List<String> concept, List<String> readerNeed, List<String> targetReader, Double rating, String relevanceScoresJson, String description) {
    }

    /**
     * 一次 LLM 调用同时生成标签、评分、8维度相关度得分
     */
    private CombinedAiResult callAiCombined(String content) {
        String rawResult = chatModelManager.callAiForScene(AiScene.BOOK_PARSE_COMBINED, AI_OP_COMBINED,
                String.format("输入: %s", content.replaceAll("\\n", " ").substring(0, Math.min(100, content.length()))),
                Lists.newArrayList(
                        SystemMessage.from(BookMetaPrompts.COMBINED_PROMPT_SYSTEM_PROMPT),
                        UserMessage.from(content)
                )
        );
        if (rawResult == null) return null;

        rawResult = CommonUtils.stripCodeFence(rawResult).trim();

        // 优先 Markdown 解析（行式 KV + ## 区段，比 JSON 更稳定，description 含中文引号/换行不易破坏结构）
        try {
            CombinedAiResult md = parseCombinedFromMarkdown(rawResult);
            if (md != null) return md;
        } catch (Exception e) {
            log.warn("Markdown 解析失败,尝试 JSON 兜底: {}", e.getMessage());
        }

        // JSON 兜底（LLM 偶发仍输出 JSON 时容错）
        return parseCombinedFromJson(rawResult);
    }

    /**
     * 从 Markdown 输出解析综合结果。
     * 期望格式：
     * ## TAGS / ## CONCEPT / ## READER_NEED / ## TARGET_READER / ## STYLE  → 「、」或「,」分隔的标签
     * ## DESCRIPTION  → 多行简介
     * ## RATING  → 单行数字
     * ## RELEVANCE  → 每行「KEY: SCORE」
     */
    private CombinedAiResult parseCombinedFromMarkdown(String text) {
        if (text == null || text.isBlank()) return null;

        List<String> tags = parseTagSection(text, "TAGS");
        List<String> concept = parseTagSection(text, "CONCEPT");
        List<String> readerNeed = parseTagSection(text, "READER_NEED");
        List<String> targetReader = parseTagSection(text, "TARGET_READER");
        // 注：prompt 含 ## STYLE 字段,但 CombinedAiResult 不存储,不解析

        String description = CommonUtils.extractMarkdownSection(text, "DESCRIPTION");
        if (description != null && ("无".equals(description) || "null".equalsIgnoreCase(description))) {
            description = null;
        }

        Double rating = null;
        String ratingSection = CommonUtils.extractMarkdownSection(text, "RATING");
        if (ratingSection != null && !ratingSection.isBlank()) {
            // 提取第一个数字（兼容「3.5」「评分：3.5」「3.5分」「rating: 3.5」）
            Pattern rp = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)");
            Matcher rm = rp.matcher(ratingSection);
            if (rm.find()) {
                try {
                    double r = Double.parseDouble(rm.group(1));
                    rating = Math.max(1.0, Math.min(5.0, Math.round(r * 10.0) / 10.0));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // RELEVANCE：行式 KV → Map → 序列化为 JSON 字符串（保持下游兼容）
        String relevanceScoresJson = null;
        String relSection = CommonUtils.extractMarkdownSection(text, "RELEVANCE");
        if (relSection != null && !relSection.isBlank()) {
            Map<String, Double> relMap = new LinkedHashMap<>();
            Pattern kvPat = Pattern.compile("^\\s*([A-Za-z0-9_+-]+)\\s*[:：]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*$");
            for (String line : relSection.split("\\r?\\n")) {
                Matcher m = kvPat.matcher(line);
                if (m.find()) {
                    try {
                        relMap.put(m.group(1), Double.parseDouble(m.group(2)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (!relMap.isEmpty()) {
                try {
                    relevanceScoresJson = objectMapper.writeValueAsString(relMap);
                } catch (Exception e) {
                    log.warn("relevance 序列化失败: {}", e.getMessage());
                }
            }
        }

        // 至少有一项结果才算成功
        if (tags == null && concept == null && readerNeed == null && targetReader == null
                && rating == null && relevanceScoresJson == null && description == null) {
            return null;
        }

        return new CombinedAiResult(tags, concept, readerNeed, targetReader, rating, relevanceScoresJson, description);
    }

    /**
     * 解析标签区段：## HEADER 下的内容按「、」「,」「，」或换行分隔成列表。
     */
    private List<String> parseTagSection(String text, String header) {
        String section = CommonUtils.extractMarkdownSection(text, header);
        if (section == null || section.isBlank()) return null;
        List<String> result = Stream.of(section.split("[\\n,，、]"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .filter(s -> !"无".equals(s) && !"null".equalsIgnoreCase(s))
                .collect(Collectors.toList());
        return result.isEmpty() ? null : result;
    }

    /**
     * JSON 兜底解析：LLM 偶发仍输出 JSON 时容错。保留原解析逻辑。
     */
    private CombinedAiResult parseCombinedFromJson(String rawResult) {
        if (rawResult == null) return null;
        try {
            int jsonStart = rawResult.indexOf('{');
            int jsonEnd = rawResult.lastIndexOf('}');
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                log.warn("AI 合并调用返回内容无有效JSON: {}", rawResult);
                return null;
            }
            String jsonStr = rawResult.substring(jsonStart, jsonEnd + 1);
            JsonNode root = objectMapper.readTree(jsonStr);

            List<String> tags = parseJsonStringArray(root, "tags");
            List<String> concept = parseJsonStringArray(root, "concept");
            List<String> readerNeed = parseJsonStringArray(root, "reader_need");
            List<String> targetReader = parseJsonStringArray(root, "target_reader");

            Double rating = null;
            if (root.has("rating") && !root.get("rating").isNull()) {
                try {
                    double r = root.get("rating").asDouble();
                    rating = Math.max(1.0, Math.min(5.0, Math.round(r * 10.0) / 10.0));
                } catch (Exception e) {
                    log.warn("评分解析失败: {}", e.getMessage());
                }
            }

            String relevanceScoresJson = null;
            if (root.has("relevance") && !root.get("relevance").isNull()) {
                JsonNode relevanceNode = root.get("relevance");
                if (relevanceNode.isObject()) {
                    relevanceScoresJson = objectMapper.writeValueAsString(relevanceNode);
                }
            }

            String description = null;
            if (root.has("description") && !root.get("description").isNull()) {
                String desc = root.get("description").asText().trim();
                if (!desc.isBlank() && !"null".equalsIgnoreCase(desc)) {
                    description = desc;
                }
            }

            if (tags == null && concept == null && readerNeed == null && targetReader == null
                    && rating == null && relevanceScoresJson == null && description == null) {
                return null;
            }
            return new CombinedAiResult(tags, concept, readerNeed, targetReader, rating, relevanceScoresJson, description);
        } catch (Exception e) {
            log.warn("AI 合并调用结果 JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 从 JsonNode 提取字段，兼容数组和逗号分隔字符串 */
    private List<String> parseJsonStringArray(JsonNode root, String field) {
        if (!root.has(field) || root.get(field).isNull()) return null;
        JsonNode node = root.get(field);
        if (node.isArray()) {
            List<String> list = StreamSupport.stream(node.spliterator(), false)
                    .map(JsonNode::asText)
                    .map(String::trim)
                    .filter(t -> !t.isBlank())
                    .toList();
            return list.isEmpty() ? null : list;
        }
        String s = node.asText();
        if (s == null || s.isBlank()) return null;
        List<String> list = Stream.of(s.split("[,，、]"))
                .map(String::trim)
                .filter(t -> !t.isBlank())
                .toList();
        return list.isEmpty() ? null : list;
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
}
