package com.kbook.service;

import com.kbook.common.api.PageResult;
import com.kbook.common.exception.BusinessException;
import com.kbook.config.properties.BookStorageProperties;
import com.kbook.document.BookDocument;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 图书服务
 */
@Slf4j
@Service
public class BookService {

    private final BookRepository bookRepository;
    private final BookSearchService bookSearchService;
    private final EmbeddingService embeddingService;
    private final StringRedisTemplate redisTemplate;

    private final BookStorageProperties storageProps;

    public BookService(BookRepository bookRepository,
                       BookSearchService bookSearchService,
                       @Lazy EmbeddingService embeddingService,
                       StringRedisTemplate redisTemplate,
                       BookStorageProperties storageProps) {
        this.bookRepository = bookRepository;
        this.bookSearchService = bookSearchService;
        this.embeddingService = embeddingService;
        this.redisTemplate = redisTemplate;
        this.storageProps = storageProps;
    }

    /**
     * 图书入库（JPA + ES 双写）
     */
    @Transactional
    public Book createBook(Book book) {
        log.info("图书入库: title={}, author={}, format={}", book.getTitle(), book.getAuthor(), book.getFormat());
        List<String> validFormats = Arrays.asList("TXT", "EPUB", "PDF");
        if (!validFormats.contains(book.getFormat())) {
            log.warn("不支持的图书格式: {}", book.getFormat());
            throw new BusinessException("不支持的图书格式: " + book.getFormat());
        }
        Book saved = bookRepository.save(book);
        bookSearchService.indexBook(saved);
        log.info("图书入库成功: id={}, title={}", saved.getId(), saved.getTitle());
        return saved;
    }

    /**
     * 更新图书信息（JPA + ES 双写）
     */
    @Transactional
    public Book updateBook(Long id, Book updates) {
        log.debug("更新图书: id={}", id);
        Book book = getBookById(id);
        if (updates.getTitle() != null) book.setTitle(updates.getTitle());
        if (updates.getAuthor() != null) book.setAuthor(updates.getAuthor());
        if (updates.getCoverUrl() != null) book.setCoverUrl(updates.getCoverUrl());
        if (updates.getDescription() != null) book.setDescription(updates.getDescription());
        if (updates.getFormatTags() != null) book.setFormatTags(updates.getFormatTags());
        if (updates.getTotalUnits() != null) book.setTotalUnits(updates.getTotalUnits());
        if (updates.getRelevanceScores() != null) book.setRelevanceScores(updates.getRelevanceScores());
        // 注意：rating 和 ratingCount 不通过 updateBook 更新，必须使用 setAiRating 或 rateBook 方法
        if (updates.getReadCount() != null) book.setReadCount(updates.getReadCount());
        if (updates.getToc() != null) book.setToc(updates.getToc());
        if (updates.getChapterSummary() != null) book.setChapterSummary(updates.getChapterSummary());
        if (updates.getContentEmbedded() != null) book.setContentEmbedded(updates.getContentEmbedded());
        Book saved = bookRepository.save(book);
        bookSearchService.indexBook(saved);
        log.info("图书更新成功: id={}, title={}", saved.getId(), saved.getTitle());
        return saved;
    }

    /**
     * 获取图书详情
     */
    public Book getBookById(Long id) {
        return bookRepository.findById(id)
                .orElseThrow(() -> new BusinessException("图书不存在"));
    }

    /**
     * 混合搜索（Qdrant 向量 + ES/MySQL 关键词，加权融合排序）
     * <p>
     * 替代原有的纯 ES 搜索，融合 Qdrant kbook_books 语义向量召回，
     * 同时覆盖"精确匹配书名"和"理解语义意图"两种搜索需求。
     */
    public PageResult<BookDocument> searchBooksEs(String keyword, String format, int page, int size) {
        log.debug("混合搜索: keyword={}, format={}, page={}, size={}", keyword, format, page, size);
        return bookSearchService.hybridSearch(keyword, format, page, size);
    }

    /**
     * 搜索图书（JPA 原始方法，保留兼容 — 供内部/AI工具使用）
     * <p>
     * 注意：此方法仅走 MySQL LIKE，不经过 Qdrant/ES。
     * 需要混合搜索请使用 {@link #searchBooksEs}。
     */
    public PageResult<Book> searchBooks(String keyword, String format, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "readCount"));
        Page<Book> pageData = bookRepository.searchBooks(keyword, format, pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 搜索建议
     */
    public List<String> suggestBooks(String keyword) {
        return bookSearchService.suggest(keyword);
    }

    /**
     * 阅读排行
     */
    public PageResult<Book> getReadRank(int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Book> pageData = bookRepository.findAllByOrderByReadCountDesc(pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 评分排行
     */
    public PageResult<Book> getRatingRank(int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Book> pageData = bookRepository.findAllByOrderByRatingDesc(pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 新书榜
     */
    public PageResult<Book> getNewBooksRank(int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Book> pageData = bookRepository.findAllByOrderByCreatedAtDesc(pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 按格式筛选
     */
    public PageResult<Book> getBooksByFormat(String format, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "readCount"));
        Page<Book> pageData = bookRepository.findByFormat(format, pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 按标签筛选
     */
    public PageResult<Book> getBooksByTag(String tag, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "readCount"));
        Page<Book> pageData = bookRepository.findByTag(tag, pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 增加阅读计数（JPA + ES 双写）
     */
    @Transactional
    public void incrementReadCount(Long bookId) {
        Book book = getBookById(bookId);
        book.setReadCount(book.getReadCount() + 1);
        Book saved = bookRepository.saveAndFlush(book);
        bookSearchService.indexBook(saved);
        log.debug("阅读计数增加: bookId={}, readCount={}", bookId, saved.getReadCount());
    }

    /**
     * 管理格式标签（JPA + ES 双写）
     */
    @Transactional
    public Book updateFormatTags(Long bookId, List<String> tags) {
        Book book = getBookById(bookId);
        String tagsJson = tags.stream()
                .map(t -> "\"" + t + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        book.setFormatTags(tagsJson);
        Book saved = bookRepository.saveAndFlush(book);
        bookSearchService.indexBook(saved);
        return saved;
    }

    /**
     * 更新8维度相关度得分
     */
    @Transactional
    public void updateRelevanceScores(Long bookId, String scoresJson) {
        Book book = getBookById(bookId);
        book.setRelevanceScores(scoresJson);
        Book saved = bookRepository.saveAndFlush(book);
        bookSearchService.indexBook(saved);
    }

    /** AI 评分基数常量 */
    private static final long AI_RATING_BASE = 1000L;

    /**
     * AI 初始评分（不更新实际评分人数）
     */
    @Transactional
    public Book setAiRating(Long bookId, Double rating) {
        Book book = getBookById(bookId);
        book.setRating(rating);
        Book saved = bookRepository.saveAndFlush(book);
        bookSearchService.indexBook(saved);
        log.info("AI 初始评分: bookId={}, rating={}", bookId, rating);
        return saved;
    }

    /**
     * 用户评分（增量平均计算，更新实际评分人数）
     * 计算公式: new_avg = (old_avg * (AI基数 + 用户数) + new_score) / (AI基数 + 用户数 + 1)
     */
    @Transactional
    public Book rateBook(Long bookId, Double rating) {
        Book book = getBookById(bookId);
        Long userCount = book.getRatingCount() != null ? book.getRatingCount() : 0L;
        Double currentRating = book.getRating() != null ? book.getRating() : 0.0;
        long totalCount = AI_RATING_BASE + userCount;

        if (currentRating <= 0) {
            // AI 未评分，用户首次评分
            book.setRating(rating);
        } else {
            // 增量平均计算
            double newRating = (currentRating * totalCount + rating) / (totalCount + 1);
            book.setRating(newRating);
        }
        book.setRatingCount(userCount + 1);

        Book saved = bookRepository.saveAndFlush(book);
        bookSearchService.indexBook(saved);
        log.info("用户评分: bookId={}, newRating={}, userCount={}", bookId, saved.getRating(), saved.getRatingCount());
        return saved;
    }

    /**
     * 更新图书评分（通用方法，保留兼容）
     */
    @Transactional
    public Book updateRating(Long bookId, Double rating) {
        return rateBook(bookId, rating);
    }

    /**
     * 更新图书简介
     */
    public void updateDescription(Long bookId, String description) {
        Book book = getBookById(bookId);
        book.setDescription(description);
        Book saved = bookRepository.saveAndFlush(book);
        bookSearchService.indexBook(saved);
        log.info("图书简介更新: bookId={}, 字数={}", bookId, description != null ? description.length() : 0);
    }

    /**
     * 删除图书（全链路：JPA + ES + Qdrant向量 + Redis缓存 + 封面图片）
     */
    @Transactional
    public void deleteBook(Long id) {
        Book book = bookRepository.findById(id)
                .orElseThrow(() -> new BusinessException("图书不存在"));

        // 1. 删除封面图片文件
        deleteCoverFile(book.getCoverUrl());

        // 2. 删除 Qdrant 向量数据（元数据向量 + 内容向量）
        embeddingService.removeBookEmbedding(id);
        embeddingService.removeContentEmbedding(id);

        // 3. 删除 JPA 数据库记录
        bookRepository.deleteById(id);

        // 4. 删除 ES 索引
        bookSearchService.deleteIndex(id);

        // 5. 清除相关 Redis 缓存（推荐缓存 + 榜单缓存）
        clearBookRelatedCache();

        log.info("图书删除成功(全链路): id={}, title={}", id, book.getTitle());
    }

    /**
     * 按作者删除所有书籍（全链路：JPA + ES + Qdrant + Redis + 封面）
     *
     * @param author 作者名
     * @return 删除的书籍数量
     */
    @Transactional
    public int deleteBooksByAuthor(String author) {
        List<Book> books = bookRepository.findByAuthor(author);
        if (books.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (Book book : books) {
            try {
                // 删除封面
                deleteCoverFile(book.getCoverUrl());
                // 删除向量
                embeddingService.removeBookEmbedding(book.getId());
                embeddingService.removeContentEmbedding(book.getId());
                // 删除 JPA + ES
                bookRepository.deleteById(book.getId());
                bookSearchService.deleteIndex(book.getId());
                count++;
            } catch (Exception e) {
                log.error("删除书籍失败: id={}, title={} - {}", book.getId(), book.getTitle(), e.getMessage());
            }
        }

        // 清除缓存
        clearBookRelatedCache();
        log.info("按作者删除书籍完成: author={}, deleted={}", author, count);
        return count;
    }

    /**
     * 合并同名书籍：以 EPUB 为主，其他格式的关联数据迁移到 EPUB 书籍ID上，其他格式书籍删除
     *
     * @param title 书名
     * @return 合并结果描述
     */
    @Transactional
    public String mergeBooksByTitle(String title) {
        List<Book> books = bookRepository.findByTitle(title);
        if (books.size() <= 1) {
            return "未找到同名书籍或只有一本，无需合并";
        }

        // 找到 EPUB 格式作为主书籍
        Book mainBook = books.stream()
                .filter(b -> "EPUB".equalsIgnoreCase(b.getFormat()))
                .findFirst()
                .orElse(books.get(0)); // 没有 EPUB 则取第一本

        List<Book> toMerge = books.stream()
                .filter(b -> !b.getId().equals(mainBook.getId()))
                .toList();

        // 合并数据：将其他格式的数据补到主书籍上（主书籍缺失的字段从其他格式中取）
        for (Book other : toMerge) {
            if (mainBook.getAuthor() == null && other.getAuthor() != null) {
                mainBook.setAuthor(other.getAuthor());
            }
            if (mainBook.getDescription() == null && other.getDescription() != null) {
                mainBook.setDescription(other.getDescription());
            }
            if (mainBook.getCoverUrl() == null && other.getCoverUrl() != null) {
                mainBook.setCoverUrl(other.getCoverUrl());
            }
            if (mainBook.getFormatTags() == null && other.getFormatTags() != null) {
                mainBook.setFormatTags(other.getFormatTags());
            }
            if (mainBook.getRelevanceScores() == null && other.getRelevanceScores() != null) {
                mainBook.setRelevanceScores(other.getRelevanceScores());
            }
            if (mainBook.getRating() == null || mainBook.getRating() <= 0) {
                if (other.getRating() != null && other.getRating() > 0) {
                    mainBook.setRating(other.getRating());
                }
            }
            if (mainBook.getToc() == null && other.getToc() != null) {
                mainBook.setToc(other.getToc());
            }
            if (mainBook.getChapterSummary() == null && other.getChapterSummary() != null) {
                mainBook.setChapterSummary(other.getChapterSummary());
            }
            // 合并阅读次数（取总和）
            if (other.getReadCount() != null && other.getReadCount() > 0) {
                mainBook.setReadCount(mainBook.getReadCount() + other.getReadCount());
            }
        }

        // 更新主书籍（JPA + ES）
        Book savedMain = bookRepository.save(mainBook);
        bookSearchService.indexBook(savedMain);

        // 删除被合并的其他格式书籍（全链路）
        StringBuilder mergedInfo = new StringBuilder();
        for (Book other : toMerge) {
            // 不删除封面（因为主书籍可能已引用）
            embeddingService.removeBookEmbedding(other.getId());
            embeddingService.removeContentEmbedding(other.getId());
            bookRepository.deleteById(other.getId());
            bookSearchService.deleteIndex(other.getId());
            mergedInfo.append(String.format("  合并: [id=%d] %s (%s) → [id=%d] %s (%s)\n",
                    other.getId(), other.getTitle(), other.getFormat(),
                    savedMain.getId(), savedMain.getTitle(), savedMain.getFormat()));
        }

        // 重新生成主书籍的向量（合并后数据已变）
        embeddingService.removeBookEmbedding(savedMain.getId());
        embeddingService.generateBookEmbedding(savedMain.getId());

        // 清除缓存
        clearBookRelatedCache();

        String result = String.format("合并完成：主书籍 [id=%d]《%s》(%s)，合并了 %d 本其他格式书籍\n%s",
                savedMain.getId(), savedMain.getTitle(), savedMain.getFormat(), toMerge.size(), mergedInfo);
        log.info("合并同名书籍: title={}, mainId={}, merged={}", title, savedMain.getId(), toMerge.size());
        return result;
    }

    /**
     * 删除封面图片文件
     */
    private void deleteCoverFile(String coverUrl) {
        if (coverUrl == null || coverUrl.isBlank()) return;
        try {
            // 封面URL格式：/api/books/admin/cover/filename.jpg 或 /api/books/cover/filename.jpg
            String filename = coverUrl.substring(coverUrl.lastIndexOf('/') + 1);
            Path imagePath = Paths.get(storageProps.getCoverPath()).resolve(filename);
            if (Files.exists(imagePath)) {
                Files.delete(imagePath);
                log.debug("删除封面图片: {}", imagePath);
            }
        } catch (Exception e) {
            log.warn("删除封面图片失败: coverUrl={} - {}", coverUrl, e.getMessage());
        }
    }

    /**
     * 清除与书籍相关的 Redis 缓存（推荐缓存 + 榜单缓存）
     */
    private void clearBookRelatedCache() {
        try {
            // 清除所有推荐缓存
            var recommendKeys = redisTemplate.keys("kbook:recommend:*");
            if (!recommendKeys.isEmpty()) {
                redisTemplate.delete(recommendKeys);
            }
            // 清除榜单缓存
            redisTemplate.delete("kbook:rank:read");
            redisTemplate.delete("kbook:rank:rating");
            redisTemplate.delete("kbook:rank:new");
        } catch (Exception e) {
            log.debug("清除缓存失败: {}", e.getMessage());
        }
    }
}
