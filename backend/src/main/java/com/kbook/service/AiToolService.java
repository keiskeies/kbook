package com.kbook.service;

import com.kbook.common.api.PageResult;
import com.kbook.entity.Book;
import com.kbook.entity.User;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 工具服务 — LangChain4j @Tool 注解方法，供大模型自主调用
 * <p>
 * 工具能力：图书搜索、图书详情、排行榜、格式筛选
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiToolService {

    private final BookService bookService;
    private final BookshelfService bookshelfService;
    private final UserService userService;
    private final RecommendService recommendService;
    private final EmbeddingService embeddingService;

    @Tool("搜索图书，支持按关键词和格式筛选。返回图书列表，包含书名、作者、格式、评分、简介等。")
    public String searchBooks(
            @P("搜索关键词，如书名或作者名") String keyword,
            @P("图书格式，可选值：TXT、EPUB、PDF，为空则不限格式") String format
    ) {
        log.debug("[AI Tool] searchBooks: keyword={}, format={}", keyword, format);
        try {
            String fmt = (format == null || format.isBlank()) ? null : format.toUpperCase();
            PageResult<Book> result = bookService.searchBooks(keyword, fmt, 1, 5);
            if (result.getList().isEmpty()) {
                return "没有找到相关图书。";
            }
            return result.getList().stream()
                    .map(b -> String.format("[BOOK:id=%d]《%s》作者:%s 格式:%s 评分:%.1f 阅读:%d次 简介:%s",
                            b.getId(),
                            b.getTitle(),
                            b.getAuthor() != null ? b.getAuthor() : "未知",
                            b.getFormat(),
                            b.getRating(),
                            b.getReadCount(),
                            b.getDescription() != null ? truncate(b.getDescription(), 80) : "暂无"))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.error("[AI Tool] searchBooks error", e);
            return "搜索图书时发生错误，请稍后重试。";
        }
    }

    @Tool("获取图书详细信息，包括完整的简介、格式、文件大小、评分等。需要提供图书ID。")
    public String getBookDetail(
            @P("图书ID") Long bookId
    ) {
        log.debug("[AI Tool] getBookDetail: bookId={}", bookId);
        try {
            Book book = bookService.getBookById(bookId);
            Map<String, Object> detail = new HashMap<>();
            detail.put("id", book.getId());
            detail.put("title", book.getTitle());
            detail.put("author", book.getAuthor());
            detail.put("format", book.getFormat());
            detail.put("rating", book.getRating());
            detail.put("readCount", book.getReadCount());
            detail.put("description", book.getDescription());
            detail.put("fileSize", book.getFileSize());
            detail.put("formatTags", book.getFormatTags());
            detail.put("coverUrl", book.getCoverUrl());
            return detail.toString();
        } catch (Exception e) {
            log.error("[AI Tool] getBookDetail error", e);
            return "获取图书详情时发生错误，图书可能不存在。";
        }
    }

    @Tool("获取阅读排行榜，返回阅读次数最多的图书列表。")
    public String getReadRank() {
        log.debug("[AI Tool] getReadRank");
        try {
            PageResult<Book> result = bookService.getReadRank(1, 10);
            if (result.getList().isEmpty()) {
                return "暂无排行数据。";
            }
            StringBuilder sb = new StringBuilder("阅读排行榜 TOP 10:\n");
            for (int i = 0; i < result.getList().size(); i++) {
                Book b = result.getList().get(i);
                sb.append(String.format("%d. [BOOK:id=%d]《%s》 作者:%s 阅读:%d次 评分:%.1f\n",
                        i + 1, b.getId(), b.getTitle(), b.getAuthor() != null ? b.getAuthor() : "未知",
                        b.getReadCount(), b.getRating()));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("[AI Tool] getReadRank error", e);
            return "获取排行榜时发生错误。";
        }
    }

    @Tool("获取评分排行榜，返回评分最高的图书列表。")
    public String getRatingRank() {
        log.debug("[AI Tool] getRatingRank");
        try {
            PageResult<Book> result = bookService.getRatingRank(1, 10);
            if (result.getList().isEmpty()) {
                return "暂无排行数据。";
            }
            StringBuilder sb = new StringBuilder("评分排行榜 TOP 10:\n");
            for (int i = 0; i < result.getList().size(); i++) {
                Book b = result.getList().get(i);
                sb.append(String.format("%d. [BOOK:id=%d]《%s》 作者:%s 评分:%.1f 阅读:%d次\n",
                        i + 1, b.getId(), b.getTitle(), b.getAuthor() != null ? b.getAuthor() : "未知",
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

            List<EmbeddingMatch<TextSegment>> matches = embeddingService.searchContent(query, 5, bookId);
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

            Book book = bookService.getBookById(bookId);
            String bookTitle = book != null ? book.getTitle() : "未知";
            log.debug("[AI Tool] searchBookContent hit: book={}, hits={}", bookTitle, matches.size());
            return sb.toString();
        } catch (Exception e) {
            log.error("[AI Tool] searchBookContent error", e);
            return "搜索书籍内容时发生错误。";
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    /**
     * 个性化推荐图书 — 委托给 RecommendService 进行多路召回+评分融合
     */
}
