package com.kbook.service;

import com.kbook.common.api.PageResult;
import com.kbook.document.BookDocument;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.repository.BookSearchRepository;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 图书搜索服务 — Qdrant 向量 + ES 全文检索 + JPA 降级，加权融合排序
 * <p>
 * 搜索流程：
 * 1. 向量召回 — Qdrant kbook_books 语义相似度（理解"适合失恋看的治愈系书籍"）
 * 2. 关键词召回 — ES 全文检索 title/author/description（精确匹配"三体""金庸"）
 * 3. 融合排序 — 0.6×向量分 + 0.4×关键词分，两路互补
 * 4. 降级 — ES 不可用 → MySQL LIKE；Qdrant 不可用 → 仅关键词
 * <p>
 * 双写策略：图书写入时同步更新 ES 索引
 */
@Slf4j
@Service
public class BookSearchService {

    private final BookSearchRepository searchRepository;
    private final BookRepository bookRepository;
    private final EmbeddingService embeddingService;

    /** ES 是否可用标志 */
    private volatile boolean esAvailable = true;

    /** 各路召回上限 */
    private static final int RECALL_SIZE = 100;
    /** 向量召回最低相似度 */
    private static final double MIN_VECTOR_SCORE = 0.3;
    /** 融合权重：向量 */
    private static final double VECTOR_WEIGHT = 0.6;
    /** 融合权重：关键词 */
    private static final double KEYWORD_WEIGHT = 0.4;

    public BookSearchService(BookSearchRepository searchRepository,
                             BookRepository bookRepository,
                             @Lazy EmbeddingService embeddingService) {
        this.searchRepository = searchRepository;
        this.bookRepository = bookRepository;
        this.embeddingService = embeddingService;
    }

    // ==================== 混合搜索（对外主入口） ====================

    /**
     * 关键词优先搜索（用于前端 /api/books/search）
     * 优先返回 ES 书名/作者匹配的书籍，适合用户精确查找
     */
    public PageResult<BookDocument> keywordSearch(String keyword, String format, String tag, int page, int size) {
        if (keyword == null || keyword.isBlank()) {
            if (format != null && !format.isBlank()) {
                return searchByFormat(format, page, size);
            }
            if (tag != null && !tag.isBlank()) {
                return searchByTag(tag, page, size);
            }
            return PageResult.of(List.of(), 0L, page, size);
        }

        // 1. 尝试 ES 搜索
        if (esAvailable) {
            try {
                return esKeywordSearch(keyword, format, tag, page, size);
            } catch (Exception e) {
                log.warn("ES 关键词搜索异常，降级到 MySQL: {}", e.getMessage());
                esAvailable = false;
            }
        }

        // 2. 降级 MySQL
        return mysqlKeywordSearch(keyword, format, tag, page, size);
    }

    /**
     * ES 关键词搜索实现
     */
    private PageResult<BookDocument> esKeywordSearch(String keyword, String format, String tag, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<BookDocument> result;

        if (format != null && !format.isBlank()) {
            result = searchRepository.searchWithFormat(keyword, format, pageable);
        } else {
            result = searchRepository.searchWithHighlight(keyword, pageable);
        }

        List<BookDocument> docs = result.getContent();

        // 标签过滤
        if (tag != null && !tag.isBlank()) {
            docs = docs.stream()
                    .filter(doc -> doc.getFormatTags() != null && doc.getFormatTags().contains(tag))
                    .toList();
        }

        esAvailable = true;
        return PageResult.of(docs, result.getTotalElements(), page, size);
    }

    /**
     * MySQL 关键词搜索实现
     */
    private PageResult<BookDocument> mysqlKeywordSearch(String keyword, String format, String tag, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "readCount"));
        Page<Book> jpaResult = bookRepository.searchBooks(keyword, format, pageable);
        List<Book> books = jpaResult.getContent();

        // 转换为 BookDocument
        List<BookDocument> docs = books.stream().map(this::toDocument).toList();

        // 标签过滤
        if (tag != null && !tag.isBlank()) {
            docs = docs.stream()
                    .filter(doc -> doc.getFormatTags() != null && doc.getFormatTags().contains(tag))
                    .toList();
        }

        return PageResult.of(docs, jpaResult.getTotalElements(), page, size);
    }

    /**
     * 混合搜索：Qdrant 向量语义召回 + ES/MySQL 关键词召回，加权融合排序
     * <p>
     * 仅格式筛选（无关键词）时走纯 ES/MySQL，不做向量搜索
     * 适用于 AI Tool 等需要语义理解场景
     */
    public PageResult<BookDocument> hybridSearch(String keyword, String format, String tag, int page, int size) {
        // 无关键词 → 纯格式/标签筛选，不做向量搜索（向量搜索需要文本 query）
        if (keyword == null || keyword.isBlank()) {
            if (format != null && !format.isBlank()) {
                return searchByFormat(format, page, size);
            }
            if (tag != null && !tag.isBlank()) {
                return searchByTag(tag, page, size);
            }
            return PageResult.of(List.of(), 0L, page, size);
        }

        long startTime = System.currentTimeMillis();

        // 1. 并行召回
        Map<Long, Double> vectorScores = vectorRecall(keyword);
        Map<Long, Integer> keywordRanks = keywordRecall(keyword, format);

        // 2. 收集所有候选 bookId
        Set<Long> allIds = new LinkedHashSet<>();
        allIds.addAll(vectorScores.keySet());
        allIds.addAll(keywordRanks.keySet());

        if (allIds.isEmpty()) {
            return PageResult.of(List.of(), 0L, page, size);
        }

        // 3. 加权融合
        List<ScoredBook> scored = new ArrayList<>();
        for (Long bookId : allIds) {
            boolean hasVector = vectorScores.containsKey(bookId);
            boolean hasKeyword = keywordRanks.containsKey(bookId);

            double vs = vectorScores.getOrDefault(bookId, 0.0);
            double ks = rankToScore(keywordRanks.get(bookId));

            double fusionScore;
            if (hasVector && hasKeyword) {
                fusionScore = VECTOR_WEIGHT * vs + KEYWORD_WEIGHT * ks;
            } else if (hasVector) {
                fusionScore = vs * VECTOR_WEIGHT;
            } else {
                fusionScore = ks * KEYWORD_WEIGHT;
            }

            scored.add(new ScoredBook(bookId, fusionScore, vs, ks));
        }

        // 4. 排序 + 分页
        scored.sort((a, b) -> Double.compare(b.fusionScore, a.fusionScore));

        int start = Math.min((page - 1) * size, scored.size());
        int end = Math.min(page * size, scored.size());
        List<ScoredBook> paged = scored.subList(start, end);

        // 5. MySQL 批量补全字段 → BookDocument
        List<Long> pagedIds = paged.stream().map(s -> s.bookId).toList();
        Map<Long, Book> bookMap = bookRepository.findAllById(pagedIds).stream()
                .collect(Collectors.toMap(Book::getId, b -> b, (a, b) -> a));

        // 按融合分排序输出
        List<BookDocument> docs = paged.stream()
                .map(sb -> {
                    Book book = bookMap.get(sb.bookId);
                    if (book == null) return null;
                    return toDocument(book);
                })
                .filter(Objects::nonNull)
                .toList();

        // 6. 标签过滤（有标签时，在融合结果后过滤）
        if (tag != null && !tag.isBlank()) {
            docs = docs.stream()
                    .filter(doc -> doc.getFormatTags() != null && doc.getFormatTags().contains(tag))
                    .toList();
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("混合搜索完成: keyword='{}', tag='{}', vectorHits={}, keywordHits={}, fused={}, returned={}, elapsed={}ms",
                keyword.length() > 20 ? keyword.substring(0, 20) + "..." : keyword,
                tag,
                vectorScores.size(), keywordRanks.size(), scored.size(), docs.size(), elapsed);

        return PageResult.of(docs, (long) scored.size(), page, size);
    }

    // ==================== 向量召回：Qdrant kbook_books ====================

    /**
     * 通过 Qdrant kbook_books 做语义向量召回
     */
    private Map<Long, Double> vectorRecall(String queryText) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        if (!embeddingService.isAvailable()) {
            log.debug("向量召回跳过: EmbeddingService 不可用");
            return scores;
        }
        try {
            List<EmbeddingMatch<TextSegment>> matches =
                    embeddingService.searchSimilarBooks(queryText, RECALL_SIZE, MIN_VECTOR_SCORE, List.of());
            for (EmbeddingMatch<TextSegment> match : matches) {
                if (match.embedded() != null && match.embedded().metadata() != null) {
                    Long bookId = match.embedded().metadata().getLong("bookId");
                    if (bookId != null) {
                        scores.putIfAbsent(bookId, match.score());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("向量召回异常: {}", e.getMessage());
        }
        return scores;
    }

    // ==================== 关键词召回：ES / MySQL ====================

    /**
     * 关键词召回：ES 优先，降级 MySQL，返回 bookId → rank（1-based，越小越相关）
     */
    private Map<Long, Integer> keywordRecall(String keyword, String format) {
        if (esAvailable) {
            try {
                return esKeywordRecall(keyword, format);
            } catch (Exception e) {
                log.warn("ES 关键词召回异常，降级到 MySQL: {}", e.getMessage());
                esAvailable = false;
            }
        }
        return mysqlKeywordRecall(keyword, format);
    }

    /**
     * ES 关键词召回，返回 bookId → rank
     */
    private Map<Long, Integer> esKeywordRecall(String keyword, String format) {
        Map<Long, Integer> ranks = new LinkedHashMap<>();
        Pageable pageable = PageRequest.of(0, RECALL_SIZE);
        Page<BookDocument> result;

        if (format != null && !format.isBlank()) {
            result = searchRepository.searchWithFormat(keyword, format, pageable);
        } else {
            result = searchRepository.searchWithHighlight(keyword, pageable);
        }

        List<BookDocument> docs = result.getContent();
        for (int i = 0; i < docs.size(); i++) {
            ranks.putIfAbsent(docs.get(i).getId(), i + 1);
        }
        esAvailable = true;
        return ranks;
    }

    /**
     * MySQL LIKE 关键词召回（ES 降级方案），返回 bookId → rank
     */
    private Map<Long, Integer> mysqlKeywordRecall(String keyword, String format) {
        Map<Long, Integer> ranks = new LinkedHashMap<>();
        Pageable pageable = PageRequest.of(0, RECALL_SIZE, Sort.by(Sort.Direction.DESC, "readCount"));
        Page<Book> jpaResult = bookRepository.searchBooks(keyword, format, pageable);
        List<Book> books = jpaResult.getContent();
        for (int i = 0; i < books.size(); i++) {
            ranks.putIfAbsent(books.get(i).getId(), i + 1);
        }
        return ranks;
    }

    /**
     * 排名 → 得分：倒数排名归一化，+5 避免 rank=1 得 1.0 过于极端
     */
    private double rankToScore(Integer rank) {
        if (rank == null) return 0.0;
        return 1.0 / (rank + 5);
    }

    // ==================== 纯格式筛选（无关键词，不走向量） ====================

    /**
     * 按格式筛选（无关键词时使用）
     */
    private PageResult<BookDocument> searchByFormat(String format, int page, int size) {
        if (esAvailable) {
            try {
                Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "readCount"));
                Page<BookDocument> result = searchRepository.findByFormat(format, pageable);
                esAvailable = true;
                return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
            } catch (Exception e) {
                log.warn("ES 格式筛选异常: {}", e.getMessage());
                esAvailable = false;
            }
        }
        // MySQL 降级
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "readCount"));
        Page<Book> jpaResult = bookRepository.findByFormat(format, pageable);
        List<BookDocument> docs = jpaResult.getContent().stream()
                .map(this::toDocument)
                .collect(Collectors.toList());
        return PageResult.of(docs, jpaResult.getTotalElements(), page, size);
    }

    /**
     * 按标签筛选（无关键词时使用）
     */
    private PageResult<BookDocument> searchByTag(String tag, int page, int size) {
        // MySQL 标签筛选（formatTags 存储的是 JSON 字符串，用 LIKE 匹配）
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "readCount"));
        Page<Book> jpaResult = bookRepository.findByTag(tag, pageable);
        List<BookDocument> docs = jpaResult.getContent().stream()
                .map(this::toDocument)
                .collect(Collectors.toList());
        return PageResult.of(docs, jpaResult.getTotalElements(), page, size);
    }

    // ==================== 保留：旧版 search 方法（向后兼容） ====================

    /**
     * ES 全文搜索（带高亮），降级到 JPA
     * @deprecated 请使用 {@link #hybridSearch(String, String, int, int)}，混合搜索结果更优
     */
    @Deprecated
    public PageResult<BookDocument> search(String keyword, String format, int page, int size) {
        if (!esAvailable) {
            return fallbackSearch(keyword, format, page, size);
        }

        try {
            Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "readCount"));
            Page<BookDocument> result;

            if (keyword != null && !keyword.isBlank()) {
                if (format != null && !format.isBlank()) {
                    result = searchRepository.searchWithFormat(keyword, format, pageable);
                } else {
                    result = searchRepository.searchWithHighlight(keyword, pageable);
                }
            } else if (format != null && !format.isBlank()) {
                result = searchRepository.findByFormat(format, pageable);
            } else {
                return fallbackSearch(keyword, format, page, size);
            }

            List<BookDocument> docs = result.getContent();

            esAvailable = true;
            return PageResult.of(docs, result.getTotalElements(), page, size);
        } catch (Exception e) {
            log.warn("ES 搜索异常，降级到 JPA: {}", e.getMessage());
            esAvailable = false;
            return fallbackSearch(keyword, format, page, size);
        }
    }

    /**
     * 搜索建议（前缀匹配）
     */
    public List<String> suggest(String keyword) {
        if (!esAvailable || keyword == null || keyword.isBlank()) {
            return List.of();
        }
        try {
            Pageable limit = PageRequest.of(0, 8);
            List<BookDocument> docs = searchRepository.suggestByTitle(keyword, limit);
            return docs.stream().map(BookDocument::getTitle).distinct().collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("ES 搜索建议异常: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * JPA 降级搜索
     */
    private PageResult<BookDocument> fallbackSearch(String keyword, String format, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "readCount"));
        Page<Book> jpaResult = bookRepository.searchBooks(keyword, format, pageable);
        List<BookDocument> docs = jpaResult.getContent().stream()
                .map(this::toDocument)
                .collect(Collectors.toList());
        return PageResult.of(docs, jpaResult.getTotalElements(), page, size);
    }

    /**
     * 同步单本图书到 ES
     */
    public void indexBook(Book book) {
        if (!esAvailable) return;
        try {
            searchRepository.save(toDocument(book));
        } catch (Exception e) {
            log.warn("ES 索引失败 bookId={}: {}", book.getId(), e.getMessage());
            esAvailable = false;
        }
    }

    /**
     * 从 ES 删除图书
     */
    public void deleteIndex(Long bookId) {
        if (!esAvailable) return;
        try {
            searchRepository.deleteById(bookId);
        } catch (Exception e) {
            log.warn("ES 删除索引失败 bookId={}: {}", bookId, e.getMessage());
        }
    }

    /**
     * 全量重建索引
     */
    @Transactional(readOnly = true)
    public long rebuildIndex() {
        int pageSize = 500;
        int page = 0;
        long total = 0;
        esAvailable = true;

        while (true) {
            Pageable pageable = PageRequest.of(page++, pageSize);
            List<Book> books = bookRepository.findAll(pageable).getContent();
            if (books.isEmpty()) break;

            List<BookDocument> docs = books.stream()
                    .map(this::toDocument)
                    .collect(Collectors.toList());
            searchRepository.saveAll(docs);
            total += docs.size();
        }

        log.info("ES 索引重建完成，共 {} 条", total);
        return total;
    }

    /**
     * 全量重建索引 — SSE 流式推送进度
     */
    @Transactional(readOnly = true)
    public long rebuildIndexWithProgress(java.util.function.BiConsumer<Integer, Integer> onProgress) {
        long totalBooks = bookRepository.count();
        int pageSize = 500;
        int page = 0;
        int processed = 0;

        esAvailable = true;
        log.info("ES 索引重建开始，共 {} 条", totalBooks);

        while (true) {
            Pageable pageable = PageRequest.of(page++, pageSize);
            List<Book> books = bookRepository.findAll(pageable).getContent();
            if (books.isEmpty()) break;

            List<BookDocument> docs = books.stream()
                    .map(this::toDocument)
                    .collect(Collectors.toList());
            searchRepository.saveAll(docs);
            processed += books.size();
            if (onProgress != null) {
                onProgress.accept(processed, (int) totalBooks);
            }
        }

        log.info("ES 索引重建完成，共 {} 条", processed);
        return processed;
    }

    // ==================== 内部类 ====================

    /**
     * 融合打分中间结果
     */
    private record ScoredBook(Long bookId, double fusionScore, double vectorScore, double keywordScore) {
    }

    /**
     * Entity → Document
     */
    private BookDocument toDocument(Book book) {
        return BookDocument.builder()
                .id(book.getId())
                .title(book.getTitle())
                .author(book.getAuthor())
                .description(book.getDescription())
                .format(book.getFormat())
                .formatTags(book.getFormatTags())
                .coverUrl(book.getCoverUrl())
                .fileSize(book.getFileSize())
                .readCount(book.getReadCount())
                .rating(book.getRating())
                .totalUnits(book.getTotalUnits())
                .fileUrl(book.getFileUrl())
                .createdAt(book.getCreatedAt() != null ? book.getCreatedAt().toEpochSecond(java.time.ZoneOffset.of("+8")) : 0L)
                .build();
    }
}
