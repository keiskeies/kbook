package com.kbook.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.api.PageResult;
import com.kbook.common.exception.BusinessException;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.TransactionUtils;
import com.kbook.config.annotation.RedisLock;
import com.kbook.config.properties.BookStorageProperties;
import com.kbook.document.BookDocument;
import com.kbook.dto.BookProjection;
import com.kbook.dto.TagStat;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.repository.UserReadHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
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
    private final ObjectMapper objectMapper;
    private final UserReadHistoryRepository userReadHistoryRepository;

    private final BookStorageProperties storageProps;

    /**
     * 热门标签缓存 Key
     */
    public static final String TOP_TAGS_CACHE_KEY = "kbook:home:top_tags";

    /**
     * 缓存失效时间：72小时
     */
    private static final long CACHE_TTL_HOURS = 72;

    private static final String BOOK_CACHE_KEY_PREFIX = "book:detail:";
    private static final String BOOK_PROJ_CACHE_KEY_PREFIX = "book:proj:";
    private static final long CACHE_BASE_TTL_MINUTES = 30;
    private static final long CACHE_RANDOM_TTL_MINUTES = 10; // 防雪崩随机范围

    public BookService(BookRepository bookRepository,
                       BookSearchService bookSearchService,
                       @Lazy EmbeddingService embeddingService,
                       StringRedisTemplate redisTemplate,
                       BookStorageProperties storageProps,
                        ObjectMapper objectMapper,
                       UserReadHistoryRepository userReadHistoryRepository) {
        this.bookRepository = bookRepository;
        this.bookSearchService = bookSearchService;
        this.embeddingService = embeddingService;
        this.redisTemplate = redisTemplate;
        this.storageProps = storageProps;
        this.objectMapper = objectMapper;
        this.userReadHistoryRepository = userReadHistoryRepository;
    }


    @RedisLock(key = "'kbook:lock:top_tags'", leaseTime = 30)
    public List<TagStat> computeTopTagsWithLock() {
        // 双重检查：加锁后再次确认缓存是否存在（其他线程可能已写入）
        try {
            String cachedData = redisTemplate.opsForValue().get(TOP_TAGS_CACHE_KEY);
            if (cachedData != null && !cachedData.isEmpty()) {
                log.debug("加锁后从缓存获取热门标签");
                return objectMapper.readValue(cachedData, new TypeReference<>() {
                });
            }
        } catch (Exception e) {
            log.warn("读取热门标签缓存失败: {}", e.getMessage());
        }

        // 3. 计算数据
        log.info("缓存未命中，开始计算热门标签");
        List<BookProjection> all = this.getRatingRank(1, 5000).getList();
        Map<String, Long> tagCount = new HashMap<>();

        for (BookProjection book : all) {
            if (book.getFormatTags() == null || book.getFormatTags().isBlank()) continue;
            // 移除 JSON 数组符号和引号: ["a","b"] -> a,b
            String tags = book.getFormatTags().replaceAll("[\\[\\]\"]", "");
            for (String tag : tags.split("[,，]")) {
                String t = tag.trim();
                if (!t.isEmpty()) {
                    tagCount.merge(t, 1L, Long::sum);
                }
            }
        }

        List<TagStat> result = tagCount.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(120)
                .map(e -> TagStat.builder()
                        .name(e.getKey())
                        .count(e.getValue())
                        .build())
                .toList();

        // 4. 写入缓存（72小时失效）
        try {
            String jsonData = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(TOP_TAGS_CACHE_KEY, jsonData, CACHE_TTL_HOURS, TimeUnit.HOURS);
            log.info("热门标签已缓存，共 {} 个标签，TTL={}小时", result.size(), CACHE_TTL_HOURS);
        } catch (Exception e) {
            log.warn("写入热门标签缓存失败: {}", e.getMessage());
        }

        return result;
    }

    /**
     * 获取图书详情（带 Redis 缓存，防穿透/雪崩）
     */
    public Book getBookById(Long bookId) {
        String cacheKey = BOOK_CACHE_KEY_PREFIX + bookId;
        try {
            // 1. 查缓存
            String cachedJson = redisTemplate.opsForValue().get(cacheKey);
            if (cachedJson != null) {
                // 处理空值缓存
                if ("NULL".equals(cachedJson)) {
                    throw new BusinessException("图书不存在");
                }
                return objectMapper.readValue(cachedJson, Book.class);
            }

            // 2. 查数据库
            Book book = bookRepository.findById(bookId)
                    .orElseThrow(() -> new BusinessException("图书不存在"));

            // 3. 写缓存 (基础 TTL + 随机 TTL 防雪崩)
            long ttl = CACHE_BASE_TTL_MINUTES + ThreadLocalRandom.current().nextLong(CACHE_RANDOM_TTL_MINUTES);
            String json = objectMapper.writeValueAsString(book);
            redisTemplate.opsForValue().set(cacheKey, json, ttl, TimeUnit.MINUTES);

            return book;
        } catch (BusinessException e) {
            // 业务异常直接抛出，不缓存
            throw e;
        } catch (Exception e) {
            // 缓存异常降级，直接查库
            log.warn("获取图书缓存失败，降级查库: bookId={}", bookId, e);
            return bookRepository.findById(bookId)
                    .orElseThrow(() -> new BusinessException("图书不存在"));
        }
    }

    /**
     * 获取图书投影（仅常用字段，带独立缓存，key更小）
     */
    public BookProjection getBookProjectionById(Long bookId) {
        String cacheKey = BOOK_PROJ_CACHE_KEY_PREFIX + bookId;
        try {
            String cachedJson = redisTemplate.opsForValue().get(cacheKey);
            if (cachedJson != null) {
                if ("NULL".equals(cachedJson)) throw new BusinessException("图书不存在");
                return objectMapper.readValue(cachedJson, BookProjection.class);
            }
            BookProjection book = bookRepository.findProjectedById(bookId)
                    .orElseThrow(() -> new BusinessException("图书不存在"));
            long ttl = CACHE_BASE_TTL_MINUTES + ThreadLocalRandom.current().nextLong(CACHE_RANDOM_TTL_MINUTES);
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(book), ttl, TimeUnit.MINUTES);
            return book;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("获取图书投影缓存失败，降级查库: bookId={}", bookId, e);
            return bookRepository.findProjectedById(bookId)
                    .orElseThrow(() -> new BusinessException("图书不存在"));
        }
    }

    /**
     * 清除图书详情缓存
     */
    private void evictBookCache(Long bookId) {
        try {
            redisTemplate.delete(BOOK_CACHE_KEY_PREFIX + bookId);
            redisTemplate.delete(BOOK_PROJ_CACHE_KEY_PREFIX + bookId);
        } catch (Exception e) {
            log.warn("清除图书缓存失败: bookId={}", bookId, e);
        }
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
    public void updateBook(Long id, Book updates) {
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
        // 事务提交成功后再清除缓存，防止事务回滚导致缓存与数据库不一致
        TransactionUtils.afterCommit(() -> {
            bookSearchService.indexBook(saved);
            evictBookCache(saved.getId());
        });
        log.info("图书更新成功: id={}, title={}", saved.getId(), saved.getTitle());
    }
    /**
     * 更新图书信息（JPA + ES 双写）
     */
    @Transactional
    public void updateBookAll(Long id, Book updates) {
        log.debug("更新图书: id={}", id);
        Book book = getBookById(id);
        if (updates.getTitle() != null) book.setTitle(updates.getTitle());
        if (updates.getAuthor() != null) book.setAuthor(updates.getAuthor());
        if (updates.getCoverUrl() != null) book.setCoverUrl(updates.getCoverUrl());
        if (updates.getDescription() != null) book.setDescription(updates.getDescription());
        if (updates.getFormatTags() != null) book.setFormatTags(updates.getFormatTags());
        if (updates.getTotalUnits() != null) book.setTotalUnits(updates.getTotalUnits());
        if (updates.getRelevanceScores() != null) book.setRelevanceScores(updates.getRelevanceScores());
        if (updates.getRating() != null) book.setRating(updates.getRating());
        if (updates.getReadCount() != null) book.setReadCount(updates.getReadCount());
        if (updates.getToc() != null) book.setToc(updates.getToc());
        if (updates.getChapterSummary() != null) book.setChapterSummary(updates.getChapterSummary());
        if (updates.getContentEmbedded() != null) book.setContentEmbedded(updates.getContentEmbedded());
        Book saved = bookRepository.save(book);
        // 事务提交成功后再清除缓存，防止事务回滚导致缓存与数据库不一致
        TransactionUtils.afterCommit(() -> {
            bookSearchService.indexBook(saved);
            evictBookCache(saved.getId());
        });
        log.info("图书ALL更新成功: id={}, title={}", saved.getId(), saved.getTitle());
    }

    /**
     * 更新图书封面（管理员）
     * 上传新封面图片，自动压缩至最大宽度 300px
     */
    @Transactional
    public Book updateBookCover(Long bookId, MultipartFile coverFile) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new BusinessException("图书不存在: " + bookId));

        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(coverFile.getBytes()));
            if (original == null) {
                throw new BusinessException("无法读取图片文件");
            }

            String originalFilename = coverFile.getOriginalFilename();
            String format = (originalFilename != null && originalFilename.toLowerCase().endsWith(".png")) ? "png" : "jpg";

            // 压缩至最大宽度 300px
            BufferedImage compressed = CommonUtils.compressImage(original, format, 300);

            // 保存到封面目录
            Path coverDir = Paths.get(storageProps.getCoverPath());
            Files.createDirectories(coverDir);

            String tempFileName = "book_" + bookId + "_cover_" + System.currentTimeMillis() + "." + format;
            Path coverPath = coverDir.resolve(tempFileName);

            try (java.io.OutputStream os = Files.newOutputStream(coverPath)) {
                ImageIO.write(compressed, format, os);
            }

            // 更新封面 URL
            String coverUrl = "/api/books/cover/" + tempFileName;
            book.setCoverUrl(coverUrl);
            Book saved = bookRepository.save(book);

            // 更新 ES 索引
            bookSearchService.indexBook(saved);
            // 事务提交成功后再清除缓存，防止事务回滚导致缓存与数据库不一致
            TransactionUtils.afterCommit(() -> {
                bookSearchService.indexBook(saved);
                evictBookCache(saved.getId());
            });

            log.info("封面更新成功: bookId={}, path={}", bookId, coverPath);
            return saved;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("封面更新失败: bookId={}", bookId, e);
            throw new BusinessException("封面更新失败: " + e.getMessage());
        }
    }

    /**
     * 关键词优先搜索（用于前端 /api/books/search）
     * 优先返回 ES 书名/作者匹配的书籍
     */
    public PageResult<BookDocument> searchBooksByKeyword(String keyword, String format, String tag, int page, int size) {
        log.debug("关键词搜索: keyword={}, format={}, tag={}, page={}, size={}", keyword, format, tag, page, size);
        return bookSearchService.keywordSearch(keyword, format, tag, page, size);
    }

    /**
     * 混合搜索（供 AI Tool 使用）
     * 结合向量语义和关键词，适合模糊意图理解
     */
    public PageResult<BookDocument> searchBooksEs(String keyword, String format, String tag, int page, int size) {
        log.debug("混合搜索: keyword={}, format={}, tag={}, page={}, size={}", keyword, format, tag, page, size);
        return bookSearchService.hybridSearch(keyword, format, tag, page, size);
    }

    /**
     * 搜索图书（JPA 原始方法，保留兼容 — 供内部/AI工具使用）
     * <p>
     * 注意：此方法仅走 MySQL LIKE，不经过 Qdrant/ES。
     * 需要混合搜索请使用 {@link #searchBooksEs}。
     */
    public PageResult<BookProjection> searchBooks(String keyword, String format, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "readCount"));
        Page<BookProjection> pageData = bookRepository.searchProjectedBooks(keyword, format, pageable);
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
    public PageResult<BookProjection> getReadRank(int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<BookProjection> pageData = bookRepository.findAllProjectedByOrderByReadCountDesc(pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 评分排行
     */
    public PageResult<BookProjection> getRatingRank(int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<BookProjection> pageData = bookRepository.findAllProjectedByOrderByRatingDesc(pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 新书榜
     */
    public PageResult<BookProjection> getNewBooksRank(int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<BookProjection> pageData = bookRepository.findAllProjectedByOrderByCreatedAtDesc(pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 按格式筛选
     */
    public PageResult<BookProjection> getBooksByFormat(String format, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "readCount"));
        Page<BookProjection> pageData = bookRepository.findProjectedByFormat(format, pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 按标签筛选
     */
    public PageResult<BookProjection> getBooksByTag(String tag, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "readCount"));
        Page<BookProjection> pageData = bookRepository.findProjectedByTag(tag, pageable);
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
        // 事务提交成功后再清除缓存，防止事务回滚导致缓存与数据库不一致
        TransactionUtils.afterCommit(() -> {
            bookSearchService.indexBook(saved);
            evictBookCache(saved.getId());
        });
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
        // 事务提交成功后再清除缓存，防止事务回滚导致缓存与数据库不一致
        TransactionUtils.afterCommit(() -> {
            bookSearchService.indexBook(saved);
            evictBookCache(saved.getId());
        });
    }

    /**
     * AI 评分基数常量
     */
    private static final long AI_RATING_BASE = 1000L;

    /**
     * AI 初始评分（不更新实际评分人数）
     */
    @Transactional
    public void setAiRating(Long bookId, Double rating) {
        Book book = getBookById(bookId);
        book.setRating(rating);
        Book saved = bookRepository.saveAndFlush(book);
        // 事务提交成功后再清除缓存，防止事务回滚导致缓存与数据库不一致
        TransactionUtils.afterCommit(() -> {
            bookSearchService.indexBook(saved);
            evictBookCache(saved.getId());
        });
        log.info("AI 初始评分: bookId={}, rating={}", bookId, rating);
    }

    /**
     * 设置图书封面图片URL
     * <p>
     * 更新指定图书的封面图片地址，同步更新数据库和搜索引擎索引，
     * 并在事务提交后清除Redis缓存以确保数据一致性。
     *
     * @param bookId   图书ID
     * @param coverUrl 封面图片URL地址
     */
    @Transactional
    public void setCoverUrl(Long bookId, String coverUrl) {
        // 获取图书实体并更新封面URL
        Book book = getBookById(bookId);
        book.setCoverUrl(coverUrl);

        // 持久化到数据库并立即刷新
        Book saved = bookRepository.saveAndFlush(book);

        // 事务提交成功后再清除缓存，防止事务回滚导致缓存与数据库不一致
        TransactionUtils.afterCommit(() -> {
            bookSearchService.indexBook(saved);
            evictBookCache(saved.getId());
        });

        log.info("图书封面图片: bookId={}, coverUrl={}", bookId, coverUrl);
    }

    /**
     * 用户评分（增量平均计算，更新实际评分人数）
     * 计算公式: new_avg = (old_avg * (AI基数 + 用户数) + new_score) / (AI基数 + 用户数 + 1)
     */
    @Transactional
    public Book rateBook(Long bookId, Double rating, Long userId) {
        // 检查用户是否已评分
        boolean hasRated = userReadHistoryRepository.findByUserIdAndBookIdAndAction(userId, bookId, "RATE").isPresent();
        if (hasRated) {
            throw new BusinessException("您已评分过该书籍，不可重复评分");
        }

        Book book = getBookById(bookId);
        long userCount = book.getRatingCount() != null ? book.getRatingCount() : 0L;
        double currentRating = book.getRating() != null ? book.getRating() : 0.0;
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
        // 事务提交后清除缓存，防止事务回滚导致数据不一致
        TransactionUtils.afterCommit(() -> evictBookCache(bookId));
        log.info("用户评分: bookId={}, newRating={}, userCount={}", bookId, saved.getRating(), saved.getRatingCount());
        return saved;
    }


    /**
     * 更新图书简介
     */
    @Transactional
    public void updateDescription(Long bookId, String description) {
        Book book = getBookById(bookId);
        book.setDescription(description);
        Book saved = bookRepository.saveAndFlush(book);
        // 事务提交成功后再清除缓存，防止事务回滚导致缓存与数据库不一致
        TransactionUtils.afterCommit(() -> {
            bookSearchService.indexBook(saved);
            evictBookCache(saved.getId());
        });
    }

    /**
     * 删除图书（全链路：JPA + ES + Qdrant向量 + Redis缓存 + 封面图片）
     */
    @Transactional
    public void deleteBook(Long id) {
        Book book = bookRepository.findById(id).orElse(null);
        if (null != book) {
            // 1. 删除封面图片文件
            deleteCoverFile(book.getCoverUrl());
        }

        // 2. 删除 Qdrant 向量数据（元数据向量 + 内容向量）
        embeddingService.removeBookEmbedding(id);
        embeddingService.removeContentEmbedding(id);

        // 3. 删除 JPA 数据库记录
        bookRepository.deleteById(id);

        // 4. 删除 ES 索引
        bookSearchService.deleteIndex(id);

        // 5. 事务提交后清除相关 Redis 缓存（推荐/榜单/详情/匹配度）
        TransactionUtils.afterCommit(() -> {
            clearBookRelatedCache();
            evictBookCache(id);
        });

        log.info("图书删除成功(全链路): id={}, title={}", id, null == book ? "" : book.getTitle());
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

        // 事务提交后清除缓存
        TransactionUtils.afterCommit(this::clearBookRelatedCache);
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
        embeddingService.generateBookEmbedding(savedMain);

        // 事务提交成功后再清除缓存，防止事务回滚导致缓存与数据库不一致
        TransactionUtils.afterCommit(() -> {
            bookSearchService.indexBook(savedMain);
            evictBookCache(savedMain.getId());
        });
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
