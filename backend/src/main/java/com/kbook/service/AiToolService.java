package com.kbook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.api.PageResult;
import com.kbook.common.util.CommonUtils;
import com.kbook.config.properties.QdrantProperties;
import com.kbook.dto.BookProjection;
import com.kbook.dto.RecommendedItem;
import com.kbook.document.BookDocument;
import com.kbook.entity.Book;
import com.kbook.entity.UserBookPreference;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 工具服务 — LangChain4j @Tool 注解方法，供大模型自主调用
 * <p>
 * 工具能力：图书搜索、图书详情、排行榜、格式筛选、图书管理操作、智能推荐、用户偏好
 * <p>
 * 注意：此类不能使用 @RequiredArgsConstructor，必须手动注入 @Lazy 依赖，
 * 否则 Spring 会创建 CGLIB 代理，导致 @Tool 注解对 LangChain4j 不可见。
 */
@Slf4j
@Service
public class AiToolService {

    private final BookService bookService;
    private final BookshelfService bookshelfService;
    private final ChatModelManager chatModelManager;
    private final EmbeddingService embeddingService;
    private final UserBookPreferenceService preferenceService;
    private final RecommendService recommendService;
    private final ObjectMapper objectMapper;
    private final QdrantProperties qdrantProperties;

    public AiToolService(
            BookService bookService,
            BookshelfService bookshelfService,
            ChatModelManager chatModelManager,
            @Lazy EmbeddingService embeddingService,
            UserBookPreferenceService preferenceService,
            @Lazy RecommendService recommendService,
            ObjectMapper objectMapper,
            QdrantProperties qdrantProperties
    ) {
        this.bookService = bookService;
        this.bookshelfService = bookshelfService;
        this.chatModelManager = chatModelManager;
        this.embeddingService = embeddingService;
        this.preferenceService = preferenceService;
        this.recommendService = recommendService;
        this.objectMapper = objectMapper;
        this.qdrantProperties = qdrantProperties;
    }

    /** 获取当前请求的工具结果上下文（可能为 null，非 AI 工具调用场景） */
    private ToolResultContext ctx() {
        return ToolResultContext.current();
    }

    /** 安全记录一本书到上下文 */
    private void recordBook(String title, Long bookId) {
        ToolResultContext c = ctx();
        if (c != null) {
            c.addBook(title, bookId);
            c.markToolCalled();
        }
    }

    // ==================== 图书查询工具 ====================

    @Tool("当用户想找书、搜书、查询书籍时调用。按关键词搜索图书，返回包含图书ID、书名、作者、评分、标签、简介的列表。支持书名、作者名、主题描述等自然语言搜索。")
    public String searchBooks(
            @P("搜索关键词。精确找书用书名或作者名；主题搜索用简短描述如'科幻'或'治愈'，不要用完整句子") String keyword
    ) {
        log.debug("[AI Tool] searchBooks: keyword={}", keyword);
        try {
            PageResult<BookDocument> result =
                    bookService.hybridSearch(keyword, null, 1, 10);
            if (result.getList().isEmpty()) {
                return "没有找到相关图书。";
            }
            List<BookDocument> sorted = new ArrayList<>(result.getList());
            sorted.sort((a, b) -> {
                double ra = a.getRating() != null ? a.getRating() : 0.0;
                double rb = b.getRating() != null ? b.getRating() : 0.0;
                return Double.compare(rb, ra);
            });
            return sorted.stream()
                    .peek(b -> recordBook(b.getTitle(), b.getId()))
                    .map(b -> {
                        StringBuilder entry = new StringBuilder();
                        entry.append(String.format("[BOOK:id=%s]《%s》 作者:%s 评分:%.1f",
                                b.getId(),
                                b.getTitle(),
                                b.getAuthor() != null ? b.getAuthor() : "未知",
                                b.getRating() != null ? b.getRating() : 0.0));
                        if (b.getFormatTags() != null && !b.getFormatTags().isBlank()) {
                            String tags = b.getFormatTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
                            entry.append(" 标签:").append(tags);
                        }
                        if (b.getDescription() != null && !b.getDescription().isBlank()) {
                            entry.append(" 简介:").append(truncate(b.getDescription(), 120));
                        }
                        return entry.toString();
                    })
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.error("[AI Tool] searchBooks error", e);
            return "搜索图书时发生错误，请稍后重试。";
        }
    }

    @Tool("当用户想了解某本书的详细信息时调用。需要提供图书ID（可从searchBooks结果中获取[BOOK:id=数字]）。返回书名、作者、格式、评分、阅读次数、完整简介、目录等。")
    public String getBookDetail(
            @P("图书ID，从searchBooks结果中的[BOOK:id=数字]获取") Long bookId
    ) {
        log.debug("[AI Tool] getBookDetail: bookId={}", bookId);
        try {
            Book book = bookService.getBookById(bookId);
            StringBuilder sb = new StringBuilder();
            sb.append("[BOOK:id=").append(book.getId()).append("]《").append(book.getTitle()).append("》\n");
            sb.append("作者: ").append(book.getAuthor() != null ? book.getAuthor() : "未知").append("\n");
            sb.append("格式: ").append(book.getFormat()).append("\n");
            sb.append("评分: ").append(book.getRating()).append("\n");
            sb.append("阅读次数: ").append(book.getReadCount()).append("\n");
            if (book.getFileSize() != null) sb.append("文件大小: ").append(book.getFileSize()).append("\n");
            if (book.getFormatTags() != null && !book.getFormatTags().isBlank()) {
                sb.append("标签: ").append(book.getFormatTags().replaceAll("[\\[\\]\"]", "").replace(",", "、")).append("\n");
            }
            if (book.getDescription() != null && !book.getDescription().isBlank()) {
                sb.append("简介: ").append(book.getDescription()).append("\n");
            }
            if (book.getToc() != null && !book.getToc().isBlank()) {
                sb.append("目录: ").append(truncate(book.getToc(), 500)).append("\n");
            }
            if (book.getChapterSummary() != null && !book.getChapterSummary().isBlank()) {
                sb.append("章节摘要: ").append(truncate(book.getChapterSummary(), 300)).append("\n");
            }
            recordBook(book.getTitle(), book.getId());
            return sb.toString();
        } catch (Exception e) {
            log.error("[AI Tool] getBookDetail error", e);
            return "获取图书详情时发生错误，图书可能不存在。";
        }
    }

    @Tool("当用户想看热门图书排行时调用。返回阅读次数最多的TOP10图书列表。")
    public String getReadRank() {
        log.debug("[AI Tool] getReadRank");
        try {
            PageResult<BookProjection> result = bookService.getReadRank(1, 10);
            if (result.getList().isEmpty()) {
                return "暂无排行数据。";
            }
            StringBuilder sb = new StringBuilder("阅读排行榜 TOP 10:\n");
            for (int i = 0; i < result.getList().size(); i++) {
                BookProjection b = result.getList().get(i);
                recordBook(b.getTitle(), b.getId());
                sb.append(String.format("%d. [BOOK:id=%d]《%s》 作者:%s 阅读:%d次 评分:%.1f\n",
                        i + 1, b.getId(), b.getTitle(),
                        b.getAuthor() != null ? b.getAuthor() : "未知",
                        b.getReadCount(), b.getRating()));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("[AI Tool] getReadRank error", e);
            return "获取排行榜时发生错误。";
        }
    }

    @Tool("当用户想看高分图书排行时调用。返回评分最高的TOP10图书列表。")
    public String getRatingRank() {
        log.debug("[AI Tool] getRatingRank");
        try {
            PageResult<BookProjection> result = bookService.getRatingRank(1, 10);
            if (result.getList().isEmpty()) {
                return "暂无排行数据。";
            }
            StringBuilder sb = new StringBuilder("评分排行榜 TOP 10:\n");
            for (int i = 0; i < result.getList().size(); i++) {
                BookProjection b = result.getList().get(i);
                recordBook(b.getTitle(), b.getId());
                sb.append(String.format("%d. [BOOK:id=%d]《%s》 作者:%s 评分:%.1f 阅读:%d次\n",
                        i + 1, b.getId(), b.getTitle(),
                        b.getAuthor() != null ? b.getAuthor() : "未知",
                        b.getRating(), b.getReadCount()));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("[AI Tool] getRatingRank error", e);
            return "获取排行榜时发生错误。";
        }
    }

    @Tool("获取某个用户的书架图书列表。")
    public String getUserBookshelf(
            @P("用户ID") Long userId
    ) {
        log.debug("[AI Tool] getUserBookshelf: userId={}", userId);
        try {
            var items = bookshelfService.getBookshelf(userId);
            if (items.isEmpty()) {
                return "书架为空。";
            }
            return items.stream()
                    .map(item -> String.format("[BOOK:id=%d]《%s》 作者:%s 格式:%s 进度:%.0f%%",
                            item.getBookId(),
                            item.getTitle(),
                            item.getAuthor() != null ? item.getAuthor() : "未知",
                            item.getFormat(),
                            item.getProgress() * 100))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.error("[AI Tool] getUserBookshelf error", e);
            return "获取书架信息时发生错误。";
        }
    }

    @Tool("在指定书籍的内容中搜索相关片段。当用户询问某本书的具体内容、人物关系、情节细节时使用此工具。返回与查询最相关的原文片段。")
    public String searchBookContent(
            @P("图书ID") Long bookId,
            @P("搜索关键词或问题，如'主角的成长经历'、'关于自由的讨论'") String query
    ) {
        log.debug("[AI Tool] searchBookContent: bookId={}, query={}", bookId, query);
        try {
            if (!embeddingService.isAvailable()) {
                return "向量检索功能暂不可用。";
            }

            Book book = bookService.getBookById(bookId);
            List<EmbeddingMatch<TextSegment>> matches = embeddingService.searchContent(query, qdrantProperties.getRagTopK(), book);
            if (matches.isEmpty()) {
                return "未在该书中找到相关内容。";
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < matches.size(); i++) {
                EmbeddingMatch<TextSegment> match = matches.get(i);
                String text = match.embedded() != null ? match.embedded().text() : "";
                if (!text.isBlank()) {
                    sb.append("片段").append(i + 1).append("：").append(text).append("\n\n");
                }
            }

            String bookTitle = book != null ? book.getTitle() : "未知";
            log.debug("[AI Tool] searchBookContent hit: book={}, hits={}", bookTitle, matches.size());
            return sb.toString();
        } catch (Exception e) {
            log.error("[AI Tool] searchBookContent error", e);
            return "搜索书籍内容时发生错误。";
        }
    }

    // ==================== 图书管理操作工具（可直接操作数据库） ====================

    @Tool("删除指定作者的所有书籍。此操作会从数据库、Elasticsearch索引、Qdrant向量库、Redis缓存和封面图片文件中彻底删除，不可恢复。请谨慎使用，操作前需与用户确认。")
    public String deleteBooksByAuthor(
            @P("作者名，如'金庸'、'余华'") String author
    ) {
        log.info("[AI Tool] deleteBooksByAuthor: author={}", author);
        try {
            // 先查询确认有多少本
            var books = bookService.searchBooks(author, 1, 50);
            long authorBookCount = books.getList().stream()
                    .filter(b -> author.equals(b.getAuthor()))
                    .count();

            if (authorBookCount == 0) {
                return "未找到作者 \"" + author + "\" 的书籍。";
            }

            int deleted = bookService.deleteBooksByAuthor(author);
            return String.format("已删除作者 \"%s\" 的 %d 本书籍（数据库/ES索引/Qdrant向量/Redis缓存/封面图片已同步清理）。", author, deleted);
        } catch (Exception e) {
            log.error("[AI Tool] deleteBooksByAuthor error", e);
            return "删除书籍时发生错误：" + e.getMessage();
        }
    }

    @Tool("合并同名的不同格式书籍。以EPUB格式为主书籍，将其他格式（PDF/TXT）的数据合并到EPUB上，然后删除其他格式的书籍。合并后EPUB书籍将拥有最完整的数据，其他格式的数据库记录、ES索引、向量数据将被删除。")
    public String mergeBooksByTitle(
            @P("书名，如'三体'、'活着'") String title
    ) {
        log.info("[AI Tool] mergeBooksByTitle: title={}", title);
        try {
            return bookService.mergeBooksByTitle(title);
        } catch (Exception e) {
            log.error("[AI Tool] mergeBooksByTitle error", e);
            return "合并书籍时发生错误：" + e.getMessage();
        }
    }

    // ==================== AI智能推荐工具 ====================

    @Tool("根据指定书籍推荐相关书籍。通过分析该书的标签、评分维度、作者等，查询书中引用的其他书籍，并找出评分维度相似度高的书籍。")
    public String recommendRelatedBooks(
            @P("源图书ID") Long bookId,
            @P("推荐数量，默认5") Integer count
    ) {
        log.debug("[AI Tool] recommendRelatedBooks: bookId={}, count={}", bookId, count);
        try {
            Book sourceBook = bookService.getBookById(bookId);
            if (sourceBook == null) {
                return "未找到该书籍。";
            }

            int limit = (count != null && count > 0 && count <= 20) ? count : 5;
            List<Map<String, Object>> recommendations = new ArrayList<>();

            // 1. 向量语义相似度推荐
            if (embeddingService.isAvailable()) {
                String queryText = buildBookSearchQuery(sourceBook);
                List<Long> excludeIds = List.of(bookId);
                List<EmbeddingMatch<TextSegment>> matches =
                        embeddingService.searchSimilarBooks(queryText, limit * 2, 0.4, excludeIds);

                for (EmbeddingMatch<TextSegment> match : matches) {
                    if (match.embedded() == null || match.embedded().metadata() == null) continue;
                    Long relatedBookId = match.embedded().metadata().getLong("bookId");
                    if (relatedBookId == null) continue;

                    Book relatedBook = bookService.getBookById(relatedBookId);
                    recommendations.add(Map.of(
                            "bookId", relatedBookId,
                            "title", relatedBook.getTitle(),
                            "author", relatedBook.getAuthor() != null ? relatedBook.getAuthor() : "未知",
                            "format", relatedBook.getFormat(),
                            "rating", relatedBook.getRating(),
                            "vectorScore", String.format("%.2f", match.score()),
                            "reason", "语义相似"
                    ));
                }
            }

            // 2. 8维度评分相似度推荐
            List<Map<String, Object>> scoreSimilar = findBooksByScoreSimilarity(sourceBook, limit);
            for (Map<String, Object> item : scoreSimilar) {
                Long id = (Long) item.get("bookId");
                // 避免重复
                if (recommendations.stream().anyMatch(r -> r.get("bookId").equals(id))) continue;
                recommendations.add(item);
            }

            // 3. 同作者推荐
            if (sourceBook.getAuthor() != null && !sourceBook.getAuthor().isBlank()) {
                var sameAuthorBooks = bookService.searchBooks(sourceBook.getAuthor(), 1, 5);
                for (BookProjection b : sameAuthorBooks.getList()) {
                    if (b.getId().equals(bookId)) continue;
                    if (recommendations.stream().anyMatch(r -> r.get("bookId").equals(b.getId()))) continue;
                    recommendations.add(Map.of(
                            "bookId", b.getId(),
                            "title", b.getTitle(),
                            "author", b.getAuthor() != null ? b.getAuthor() : "未知",
                            "format", b.getFormat(),
                            "rating", b.getRating(),
                            "reason", "同作者"
                    ));
                }
            }

            // 截取指定数量
            recommendations = recommendations.stream().limit(limit).toList();

            if (recommendations.isEmpty()) {
                return "未找到与《" + sourceBook.getTitle() + "》相关的书籍。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("基于《").append(sourceBook.getTitle()).append("》的推荐：\n\n");
            for (int i = 0; i < recommendations.size(); i++) {
                Map<String, Object> r = recommendations.get(i);
                String title = (String) r.get("title");
                Object bookIdObj = r.get("bookId");
                if (title != null && bookIdObj != null) {
                    recordBook(title, Long.valueOf(bookIdObj.toString()));
                }
                sb.append(String.format("%d. [BOOK:id=%s]《%s》 作者:%s 评分:%s\n",
                        i + 1,
                        bookIdObj,
                        r.get("title"),
                        r.get("author"),
                        r.get("rating")));
                sb.append("> 推荐理由：").append(r.get("reason")).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("[AI Tool] recommendRelatedBooks error", e);
            return "推荐相关书籍时发生错误。";
        }
    }

    @Tool("记录用户不想看的书籍类型偏好。之后的推荐中将不再推荐该类书籍，除非用户再次恢复。支持标签(TAG)、作者(AUTHOR)、格式(FORMAT)三种类别。")
    public String addExcludePreference(
            @P("用户ID") Long userId,
            @P("偏好类别：TAG(标签)、AUTHOR(作者)、FORMAT(格式)") String category,
            @P("偏好值，如'科幻'、'金庸'、'TXT'") String value
    ) {
        log.info("[AI Tool] addExcludePreference: userId={}, category={}, value={}", userId, category, value);
        try {
            String cat = category.toUpperCase();
            if (!cat.equals("TAG") && !cat.equals("AUTHOR") && !cat.equals("FORMAT")) {
                return "无效的类别，请使用 TAG、AUTHOR 或 FORMAT。";
            }
            preferenceService.addExcludePreference(userId, cat, value);
            return String.format("已记录：用户 %d 不想看 %s 类型的 \"%s\"，后续推荐将排除该类型。如需恢复，请说\"我想看%s的%s了\"。",
                    userId, cat.equals("TAG") ? "标签" : (cat.equals("AUTHOR") ? "作者" : "格式"),
                    value, cat.equals("TAG") ? "标签" : (cat.equals("AUTHOR") ? "作者" : "格式"), value);
        } catch (Exception e) {
            log.error("[AI Tool] addExcludePreference error", e);
            return "记录偏好时发生错误。";
        }
    }

    @Tool("恢复用户之前排除的书籍类型偏好。恢复后，该类书籍将重新出现在推荐中。")
    public String removeExcludePreference(
            @P("用户ID") Long userId,
            @P("偏好类别：TAG(标签)、AUTHOR(作者)、FORMAT(格式)") String category,
            @P("偏好值，如'科幻'、'金庸'、'TXT'") String value
    ) {
        log.info("[AI Tool] removeExcludePreference: userId={}, category={}, value={}", userId, category, value);
        try {
            String cat = category.toUpperCase();
            boolean removed = preferenceService.removeExcludePreference(userId, cat, value);
            if (removed) {
                return String.format("已恢复：用户 %d 可以再次看到 %s \"%s\" 类型的书籍推荐。", userId,
                        cat.equals("TAG") ? "标签" : (cat.equals("AUTHOR") ? "作者" : "格式"), value);
            } else {
                return "未找到该排除记录，可能已经恢复过了。";
            }
        } catch (Exception e) {
            log.error("[AI Tool] removeExcludePreference error", e);
            return "恢复偏好时发生错误。";
        }
    }

    @Tool("查询用户的所有书籍偏好（包括不想看的标签、作者、格式）。")
    public String getUserPreferences(
            @P("用户ID") Long userId
    ) {
        log.debug("[AI Tool] getUserPreferences: userId={}", userId);
        try {
            List<UserBookPreference> prefs = preferenceService.getAllPreferences(userId);
            if (prefs.isEmpty()) {
                return "该用户暂无书籍偏好记录。";
            }
            StringBuilder sb = new StringBuilder("用户书籍偏好：\n");
            for (UserBookPreference p : prefs) {
                String catName = switch (p.getCategory()) {
                    case "TAG" -> "标签";
                    case "AUTHOR" -> "作者";
                    case "FORMAT" -> "格式";
                    default -> p.getCategory();
                };
                String typeName = "EXCLUDE".equals(p.getType()) ? "不想看" : "想看";
                sb.append(String.format("- %s: %s (%s)\n", catName, p.getValue(), typeName));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("[AI Tool] getUserPreferences error", e);
            return "查询偏好时发生错误。";
        }
    }

    @Tool("记录用户喜欢/想看的书籍类型偏好。之后的推荐中会优先推荐该类书籍。支持标签(TAG)、作者(AUTHOR)、格式(FORMAT)三种类别。")
    public String addIncludePreference(
            @P("用户ID") Long userId,
            @P("偏好类别：TAG(标签)、AUTHOR(作者)、FORMAT(格式)") String category,
            @P("偏好值，如'科幻'、'金庸'、'EPUB'") String value
    ) {
        log.info("[AI Tool] addIncludePreference: userId={}, category={}, value={}", userId, category, value);
        try {
            String cat = category.toUpperCase();
            if (!cat.equals("TAG") && !cat.equals("AUTHOR") && !cat.equals("FORMAT")) {
                return "无效的类别，请使用 TAG、AUTHOR 或 FORMAT。";
            }
            preferenceService.addIncludePreference(userId, cat, value);
            return String.format("已记录：用户 %d 喜欢看 %s 类型的 \"%s\"，后续推荐会优先推荐该类型。",
                    userId, cat.equals("TAG") ? "标签" : (cat.equals("AUTHOR") ? "作者" : "格式"), value);
        } catch (Exception e) {
            log.error("[AI Tool] addIncludePreference error", e);
            return "记录偏好时发生错误。";
        }
    }

    @Tool("取消用户之前标记为喜欢/想看的书籍类型偏好。取消后，该类书籍不再获得优先推荐。")
    public String removeIncludePreference(
            @P("用户ID") Long userId,
            @P("偏好类别：TAG(标签)、AUTHOR(作者)、FORMAT(格式)") String category,
            @P("偏好值，如'科幻'、'金庸'、'EPUB'") String value
    ) {
        log.info("[AI Tool] removeIncludePreference: userId={}, category={}, value={}", userId, category, value);
        try {
            String cat = category.toUpperCase();
            boolean removed = preferenceService.removeIncludePreference(userId, cat, value);
            if (removed) {
                return String.format("已取消：用户 %d 不再偏好 %s \"%s\" 类型的书籍。", userId,
                        cat.equals("TAG") ? "标签" : (cat.equals("AUTHOR") ? "作者" : "格式"), value);
            } else {
                return "未找到该偏好记录，可能已经取消过了。";
            }
        } catch (Exception e) {
            log.error("[AI Tool] removeIncludePreference error", e);
            return "取消偏好时发生错误。";
        }
    }

    // ==================== 个性化推荐工具 ====================

    @Tool("根据用户画像（年龄、性别、MBTI、阅读偏好等）进行个性化推荐。当用户说\"推荐适合我的书\"、\"猜我喜欢\"、\"我适合看什么\"时使用。")
    public String personalizeRecommend(
            @P("用户ID") Long userId,
            @P("推荐数量，默认5") Integer count
    ) {
        log.debug("[AI Tool] personalizeRecommend: userId={}, count={}", userId, count);
        try {
            int limit = (count != null && count > 0 && count <= 20) ? count : 5;
            List<RecommendedItem> items = recommendService.getPersonalizedRecommendations(userId, limit);
            if (items.isEmpty()) {
                return "暂无个性化推荐数据，可以尝试搜索或查看排行榜。";
            }
            StringBuilder sb = new StringBuilder("为你个性化推荐：\n\n");
            for (int i = 0; i < items.size(); i++) {
                RecommendedItem item = items.get(i);
                recordBook(item.getTitle(), item.getBookId());
                sb.append(String.format("%d. [BOOK:id=%d]《%s》 作者:%s 评分:%.1f 匹配度:%.0f%%\n",
                        i + 1,
                        item.getBookId(),
                        item.getTitle(),
                        item.getAuthor() != null ? item.getAuthor() : "未知",
                        item.getRating() != null ? item.getRating() : 0,
                        item.getMatchScore() * 100));
                sb.append("> 推荐理由：");
                if (item.getDescription() != null) {
                    sb.append(truncate(item.getDescription(), 80));
                } else {
                    sb.append("与您的阅读偏好高度匹配");
                }
                sb.append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("[AI Tool] personalizeRecommend error", e);
            return "获取个性化推荐时发生错误。";
        }
    }

    // ==================== 辅助方法 ====================

    private String truncate(String text, int maxLen) {
        return CommonUtils.truncateText(text, maxLen);
    }

    /**
     * 构建书籍搜索查询文本
     */
    private String buildBookSearchQuery(Book book) {
        StringBuilder sb = new StringBuilder();
        if (book.getTitle() != null) sb.append(book.getTitle()).append(" ");
        if (book.getAuthor() != null) sb.append(book.getAuthor()).append(" ");
        if (book.getFormatTags() != null) {
            sb.append(book.getFormatTags().replaceAll("[\\[\\]\"]", "").replace(",", " "));
        }
        return sb.toString().trim();
    }

    /**
     * 基于8维度评分相似度查找书籍
     */
    private List<Map<String, Object>> findBooksByScoreSimilarity(Book sourceBook, int limit) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (sourceBook.getRelevanceScores() == null || sourceBook.getRelevanceScores().isBlank()) {
            return results;
        }

        try {
            JsonNode sourceScores = objectMapper.readTree(sourceBook.getRelevanceScores());

            // 获取所有书籍（限制候选集大小）
            PageResult<BookProjection> candidates = bookService.searchBooks("", 1, 200);
            List<ScoredBook> scoredBooks = new ArrayList<>();

            for (BookProjection candidate : candidates.getList()) {
                if (candidate.getId().equals(sourceBook.getId())) continue;
                if (candidate.getRelevanceScores() == null || candidate.getRelevanceScores().isBlank()) continue;

                try {
                    JsonNode candidateScores = objectMapper.readTree(candidate.getRelevanceScores());
                    double similarity = calculateScoreSimilarity(sourceScores, candidateScores);
                    if (similarity > 0.5) {
                        scoredBooks.add(new ScoredBook(candidate, similarity));
                    }
                } catch (Exception ignored) {}
            }

            scoredBooks.sort((a, b) -> Double.compare(b.score, a.score));

            for (ScoredBook sb : scoredBooks.stream().limit(limit).toList()) {
                results.add(Map.of(
                        "bookId", sb.book.getId(),
                        "title", sb.book.getTitle(),
                        "author", sb.book.getAuthor() != null ? sb.book.getAuthor() : "未知",
                        "format", sb.book.getFormat(),
                        "rating", sb.book.getRating(),
                        "scoreSimilarity", String.format("%.2f", sb.score),
                        "reason", "评分维度相似"
                ));
            }
        } catch (Exception e) {
            log.debug("评分相似度计算失败: {}", e.getMessage());
        }
        return results;
    }

    /**
     * 计算两个8维度评分的余弦相似度
     */
    private double calculateScoreSimilarity(JsonNode scoresA, JsonNode scoresB) {
        List<Double> vecA = new ArrayList<>();
        List<Double> vecB = new ArrayList<>();

        Iterator<String> fields = scoresA.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (scoresB.has(field)) {
                vecA.add(scoresA.get(field).asDouble());
                vecB.add(scoresB.get(field).asDouble());
            }
        }

        if (vecA.isEmpty()) return 0.0;

        double dotProduct = 0, normA = 0, normB = 0;
        for (int i = 0; i < vecA.size(); i++) {
            dotProduct += vecA.get(i) * vecB.get(i);
            normA += vecA.get(i) * vecA.get(i);
            normB += vecB.get(i) * vecB.get(i);
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator > 0 ? dotProduct / denominator : 0.0;
    }

    private record ScoredBook(BookProjection book, double score) {
    }
}
