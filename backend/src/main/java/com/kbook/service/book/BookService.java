package com.kbook.service.book;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.api.PageResult;
import com.kbook.common.exception.BusinessException;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.TransactionUtils;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.config.annotation.RedisLock;
import com.kbook.config.properties.BookStorageProperties;
import com.kbook.constants.AiPromptConstants;
import com.kbook.document.BookDocument;
import com.kbook.dto.book.BookProjection;
import com.kbook.dto.stats.TagStat;
import com.kbook.entity.AiScene;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.repository.UserReadHistoryRepository;
import com.kbook.service.ai.ChatModelManager;
import com.kbook.service.embedding.EmbeddingService;
import com.kbook.service.recommend.BookDimensionScoreService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
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
@LogModule("图书")
public class BookService {

    private final BookRepository bookRepository;
    private final BookSearchService bookSearchService;
    private final EmbeddingService embeddingService;
    private final ChatModelManager chatModelManager;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final UserReadHistoryRepository userReadHistoryRepository;
    private final BookDimensionScoreService bookDimensionScoreService;

    private final BookStorageProperties storageProps;

    /**
     * 热门标签缓存 Key
     */
    public static final String TOP_TAGS_CACHE_KEY = "kbook:home:top_tags";

    /**
     * 缓存失效时间：72小时
     */
    private static final long CACHE_TTL_HOURS = 72;

    private static final String BOOK_PROJ_CACHE_KEY_PREFIX = "book:proj:";
    private static final long CACHE_BASE_TTL_MINUTES = 30;
    private static final long CACHE_RANDOM_TTL_MINUTES = 10; // 防雪崩随机范围

    public BookService(BookRepository bookRepository,
                       BookSearchService bookSearchService,
                       @Lazy EmbeddingService embeddingService,
                       @Lazy ChatModelManager chatModelManager,
                       StringRedisTemplate redisTemplate,
                       BookStorageProperties storageProps,
                       ObjectMapper objectMapper,
                       UserReadHistoryRepository userReadHistoryRepository,
                       BookDimensionScoreService bookDimensionScoreService) {
        this.bookRepository = bookRepository;
        this.bookSearchService = bookSearchService;
        this.embeddingService = embeddingService;
        this.chatModelManager = chatModelManager;
        this.redisTemplate = redisTemplate;
        this.storageProps = storageProps;
        this.objectMapper = objectMapper;
        this.userReadHistoryRepository = userReadHistoryRepository;
        this.bookDimensionScoreService = bookDimensionScoreService;
    }


    /**
     * 计算热门标签（带分布式锁，防止并发重复计算）
     * <p>
     * 从评分排行前5000本书中统计标签出现频率，取前120个热门标签。
     * 使用 Redis 分布式锁 + 双重检查确保高并发下只计算一次。
     * 结果缓存72小时，过期后自动重新计算。
     *
     * @return 热门标签列表（按出现次数降序排列）
     */
    @LogAction("计算热门标签")
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
     * 获取图书详情（直接查库，完整实体包含摘要/目录等重字段）
     */
    @LogAction("获取图书详情")
    public Book getBookById(Long bookId) {
        return bookRepository.findById(bookId)
                .orElseThrow(() -> new BusinessException("图书不存在"));
    }

    /**
     * 获取图书投影（仅常用字段，带独立缓存，key更小）
     */
    @LogAction("获取图书投影")
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
            redisTemplate.delete(BOOK_PROJ_CACHE_KEY_PREFIX + bookId);
        } catch (Exception e) {
            log.warn("清除图书缓存失败: bookId={}", bookId, e);
        }
    }

    /**
     * 图书入库（JPA + ES 双写）
     */
    @Transactional
    @LogAction("创建图书")
    public Book createBook(Book book) {
        log.info("图书入库: title={}, author={}, format={}", book.getTitle(), book.getAuthor(), book.getFormat());
        List<String> validFormats = Arrays.asList("TXT", "EPUB", "PDF");
        if (!validFormats.contains(book.getFormat())) {
            log.warn("不支持的图书格式: {}", book.getFormat());
            throw new BusinessException("不支持的图书格式: " + book.getFormat());
        }
        Book saved = bookRepository.save(book);
        log.info("图书入库成功: id={}, title={}", saved.getId(), saved.getTitle());
        return saved;
    }

    /**
     * 更新图书信息（JPA + ES 双写，不含统计字段）
     * <p>
     * 统计字段（rating/ratingCount/dimensionRatingCount/readCount）有专用业务流程，
     * 不在此处更新，只交由 updateBookAll 全量覆盖。
     */
    @Transactional
    @LogAction("更新图书")
    public void updateBook(Long id, Book updates) {
        log.debug("更新图书: id={}", id);
        Book book = getBookById(id);
        applyBookUpdates(book, updates);
        Book saved = bookRepository.save(book);
        if (updates.getRelevanceScores() != null) {
            bookDimensionScoreService.syncFromBook(saved);
        }
        TransactionUtils.afterCommit(() -> evictBookCache(id));
        log.info("图书更新成功: id={}, title={}", saved.getId(), saved.getTitle());
    }

    /**
     * 全量更新图书（含所有统计字段）
     * <p>
     * 与 updateBook 的区别：允许直接覆盖以下统计字段
     * rating / ratingCount / dimensionRatingCount / readCount
     * 这些字段在普通业务流程中有专用更新方法，全量更新时可通过此方法整体覆盖。
     */
    @Transactional
    @LogAction("全量更新图书")
    public void updateBookAll(Long id, Book updates) {
        log.debug("全量更新图书: id={}", id);
        Book book = getBookById(id);
        applyBookUpdates(book, updates);
        // 统计字段：只在全量更新时允许覆盖
        if (updates.getRating() != null) book.setRating(updates.getRating());
        if (updates.getRatingCount() != null) book.setRatingCount(updates.getRatingCount());
        if (updates.getDimensionRatingCount() != null) book.setDimensionRatingCount(updates.getDimensionRatingCount());
        if (updates.getReadCount() != null) book.setReadCount(updates.getReadCount());
        Book saved = bookRepository.save(book);
        if (updates.getRelevanceScores() != null) {
            bookDimensionScoreService.syncFromBook(saved);
        }
        TransactionUtils.afterCommit(() -> evictBookCache(id));
        log.info("图书ALL更新成功: id={}, title={}", saved.getId(), saved.getTitle());
    }

    /**
     * 公共字段赋值（忽略 null），新增 Book 字段只改此处
     * <p>
     * ==== 不在此赋值，有专用更新方法的字段 ====
     * rating              → setAiRating() / rateBook()     — BookService
     * ratingCount         → rateBook()                     — BookService
     * dimensionRatingCount → BookTrashService              — 维度打分维护
     * readCount           → incrementReadCount()           — BookService
     * 以上字段只在 updateBookAll 中允许直接覆盖。
     * <p>
     * ==== 创建后不改的字段 ====
     * format / fileUrl / fileSize
     * <p>
     * ==== 两边都保留的字段 ====
     * relevanceScores — 有专用 updateRelevanceScores()，但管理后台需手动覆盖，所以保留
     */
    private void applyBookUpdates(Book book, Book updates) {
        if (updates.getTitle() != null) book.setTitle(updates.getTitle());
        if (updates.getAuthor() != null) book.setAuthor(updates.getAuthor());
        if (updates.getCoverUrl() != null) book.setCoverUrl(updates.getCoverUrl());
        if (updates.getDescription() != null) book.setDescription(updates.getDescription());
        if (updates.getFormatTags() != null) book.setFormatTags(updates.getFormatTags());
        if (updates.getConceptTags() != null) book.setConceptTags(updates.getConceptTags());
        if (updates.getReaderNeedTags() != null) book.setReaderNeedTags(updates.getReaderNeedTags());
        if (updates.getTargetReaderTags() != null) book.setTargetReaderTags(updates.getTargetReaderTags());
        if (updates.getTotalUnits() != null) book.setTotalUnits(updates.getTotalUnits());
        if (updates.getRelevanceScores() != null) book.setRelevanceScores(updates.getRelevanceScores());
        if (updates.getToc() != null) book.setToc(updates.getToc());
        if (updates.getChapterSummary() != null) book.setChapterSummary(updates.getChapterSummary());
        if (updates.getContentEmbedded() != null) book.setContentEmbedded(updates.getContentEmbedded());
        if (updates.getCompressedSummary() != null) book.setCompressedSummary(updates.getCompressedSummary());
    }

    /**
     * 更新图书封面（管理员）
     * 上传新封面图片，自动压缩至最大宽度 300px
     */
    @Transactional
    @LogAction("更新图书封面")
    public Book updateBookCover(Long bookId, MultipartFile coverFile) {
        Book book = bookRepository.findOneById(bookId);
        if (book == null) {
            throw new BusinessException("图书不存在: " + bookId);
        }

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
            TransactionUtils.afterCommit(() -> evictBookCache(bookId));

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
    @LogAction("关键词搜索图书")
    public PageResult<BookDocument> searchBooksByKeyword(String keyword, String tag, int page, int size) {
        log.debug("关键词搜索: keyword={}, tag={}, page={}, size={}", keyword, tag, page, size);
        return bookSearchService.keywordSearch(keyword, tag, page, size);
    }

    /**
     * 混合搜索（供 AI Tool 使用）
     * 结合向量语义和关键词，适合模糊意图理解
     */
    @LogAction("混合搜索图书")
    public PageResult<BookDocument> hybridSearch(String keyword, String tag, int page, int size) {
        log.debug("混合搜索: keyword={}, tag={}, page={}, size={}", keyword, tag, page, size);
        return bookSearchService.hybridSearch(keyword, tag, page, size);
    }

    /**
     * 混合搜索（带排除列表，供 AI Tool 追问去重使用）
     *
     * @param excludeBookIds 需排除的书籍ID（如已推荐的），可为 null
     */
    public PageResult<BookDocument> hybridSearch(String keyword, String tag,
                                                  java.util.List<Long> excludeBookIds, int page, int size) {
        log.debug("混合搜索(带排除): keyword={}, tag={}, exclude={}, page={}, size={}",
                keyword, tag, excludeBookIds != null ? excludeBookIds.size() : 0, page, size);
        return bookSearchService.hybridSearch(keyword, tag, excludeBookIds, page, size);
    }

    /**
     * 搜索图书（JPA 原始方法，保留兼容 — 供内部/AI工具使用）
     * <p>
     * 注意：此方法仅走 MySQL LIKE，不经过 Qdrant/ES。
     * 需要混合搜索请使用 {@link #hybridSearch}。
     */
    @LogAction("搜索图书")
    public PageResult<BookProjection> searchBooks(String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "readCount"));
        Page<BookProjection> pageData = bookRepository.searchProjectedBooks(keyword, pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 搜索建议
     */
    @LogAction("搜索建议")
    public List<String> suggestBooks(String keyword) {
        return bookSearchService.suggest(keyword);
    }

    /**
     * 阅读排行
     */
    @LogAction("获取阅读排行")
    public PageResult<BookProjection> getReadRank(int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<BookProjection> pageData = bookRepository.findAllProjectedByOrderByReadCountDesc(pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 评分排行
     */
    @LogAction("获取评分排行")
    public PageResult<BookProjection> getRatingRank(int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<BookProjection> pageData = bookRepository.findAllProjectedByOrderByRatingDesc(pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 新书榜
     */
    @LogAction("获取新书榜")
    public PageResult<BookProjection> getNewBooksRank(int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<BookProjection> pageData = bookRepository.findAllProjectedByOrderByCreatedAtDesc(pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 按格式筛选
     */
    @LogAction("按格式筛选图书")
    public PageResult<BookProjection> getBooksByFormat(String format, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "readCount"));
        Page<BookProjection> pageData = bookRepository.findProjectedByFormat(format, pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 按标签筛选
     */
    @LogAction("按标签筛选图书")
    public PageResult<BookProjection> getBooksByTag(String tag, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "readCount"));
        Page<BookProjection> pageData = bookRepository.findProjectedByTag(tag, pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 增加阅读计数（JPA + ES 双写）
     */
    @Transactional
    @LogAction("增加阅读计数")
    @RedisLock(key = "'book:readcount:' + #bookId", leaseTime = 5)
    public void incrementReadCount(Long bookId) {
        Book book = getBookById(bookId);
        book.setReadCount(book.getReadCount() + 1);
        Book saved = bookRepository.save(book);
        TransactionUtils.afterCommit(() -> evictBookCache(bookId));
        log.debug("阅读计数增加: bookId={}, readCount={}", bookId, saved.getReadCount());
    }

    /**
     * 管理格式标签（JPA + ES 双写）
     */
    @Transactional
    @LogAction("更新格式标签")
    public Book updateFormatTags(Long bookId, List<String> tags) {
        Book book = getBookById(bookId);
        String tagsJson = tags.stream()
                .map(t -> "\"" + t + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        book.setFormatTags(tagsJson);
        Book saved = bookRepository.save(book);
        TransactionUtils.afterCommit(() -> evictBookCache(bookId));
        return saved;
    }

    /**
     * 更新8维度相关度得分
     */
    @Transactional
    @LogAction("更新相关度得分")
    public void updateRelevanceScores(Long bookId, String scoresJson) {
        Book book = getBookById(bookId);
        book.setRelevanceScores(scoresJson);
        bookRepository.save(book);
        // 同步维度得分到 book_dimension_scores 表（供 SQL 批量评分使用）
        bookDimensionScoreService.syncFromBook(book);
        TransactionUtils.afterCommit(() -> evictBookCache(bookId));
    }

    /**
     * AI 评分基数常量
     */
    private static final long AI_RATING_BASE = 1000L;

    /**
     * AI 初始评分（不更新实际评分人数）
     */
    @Transactional
    @LogAction("设置AI评分")
    public void setAiRating(Long bookId, Double rating) {
        Book book = getBookById(bookId);
        book.setRating(rating);
        bookRepository.save(book);
        TransactionUtils.afterCommit(() -> evictBookCache(bookId));
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
    @LogAction("设置图书封面URL")
    public void setCoverUrl(Long bookId, String coverUrl) {
        // 获取图书实体并更新封面URL
        Book book = getBookById(bookId);
        book.setCoverUrl(coverUrl);

        bookRepository.save(book);
        TransactionUtils.afterCommit(() -> evictBookCache(bookId));

        log.info("图书封面图片: bookId={}, coverUrl={}", bookId, coverUrl);
    }

    /**
     * 用户评分（增量平均计算，更新实际评分人数）
     * 计算公式: new_avg = (old_avg * (AI基数 + 用户数) + new_score) / (AI基数 + 用户数 + 1)
     */
    @Transactional
    @LogAction("用户评分")
    @RedisLock(key = "'book:rate:' + #bookId + ':' + #userId", leaseTime = 10)
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
        Book saved = bookRepository.save(book);
        TransactionUtils.afterCommit(() -> evictBookCache(bookId));
        log.info("用户评分: bookId={}, newRating={}, userCount={}", bookId, saved.getRating(), saved.getRatingCount());
        return saved;
    }


    /**
     * 更新图书简介
     */
    @Transactional
    @LogAction("更新图书简介")
    public void updateDescription(Long bookId, String description) {
        Book book = getBookById(bookId);
        book.setDescription(description);
        bookRepository.save(book);
        TransactionUtils.afterCommit(() -> evictBookCache(bookId));
    }

    /**
     * 删除图书（全链路：JPA + ES + Qdrant向量 + Redis缓存 + 封面图片）
     */
    @Transactional
    @LogAction("删除图书")
    @RedisLock(key = "'book:delete:' + #id", leaseTime = 30)
    public void deleteBook(Long id) {
        Book book = bookRepository.findOneById(id);
        if (null != book) {
            deleteCoverFile(book.getCoverUrl());
        }

        // 删除 JPA 数据库记录
        bookRepository.deleteById(id);

        // 删除 ES 索引
        bookSearchService.deleteIndex(id);

        // 事务提交后再删除 Qdrant 向量数据（避免长事务锁表）+ 清除 Redis 缓存
        TransactionUtils.afterCommit(() -> {
            embeddingService.removeBookEmbedding(id);
            embeddingService.removeContentEmbedding(id);
            clearBookRelatedCache();
            evictBookCache(id);
        });

        log.info("图书删除成功: id={}, title={}", id, null == book ? "" : book.getTitle());
    }

    /**
     * 按作者删除所有书籍（全链路：JPA + ES + Qdrant + Redis + 封面）
     *
     * @param author 作者名
     * @return 删除的书籍数量
     */
    @Transactional
    @LogAction("按作者删除图书")
    @RedisLock(key = "'book:delete-author:' + #author", leaseTime = 120)
    public int deleteBooksByAuthor(String author) {
        List<Book> books = bookRepository.findByAuthor(author);
        if (books.isEmpty()) {
            return 0;
        }

        int count = 0;
        List<Long> deletedIds = new ArrayList<>();
        for (Book book : books) {
            try {
                deleteCoverFile(book.getCoverUrl());
                bookRepository.deleteById(book.getId());
                bookSearchService.deleteIndex(book.getId());
                deletedIds.add(book.getId());
                count++;
            } catch (Exception e) {
                log.error("删除书籍失败: id={}, title={} - {}", book.getId(), book.getTitle(), e.getMessage());
            }
        }

        // 事务提交后再删除 Qdrant 向量数据 + 清除缓存
        List<Long> ids = deletedIds;
        TransactionUtils.afterCommit(() -> {
            for (Long bookId : ids) {
                embeddingService.removeBookEmbedding(bookId);
                embeddingService.removeContentEmbedding(bookId);
            }
            clearBookRelatedCache();
            ids.forEach(this::evictBookCache);
        });
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
    @LogAction("合并同名图书")
    @RedisLock(key = "'book:merge:' + #title", leaseTime = 300, timeUnit = TimeUnit.SECONDS)
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
            if (mainBook.getConceptTags() == null && other.getConceptTags() != null) {
                mainBook.setConceptTags(other.getConceptTags());
            }
            if (mainBook.getReaderNeedTags() == null && other.getReaderNeedTags() != null) {
                mainBook.setReaderNeedTags(other.getReaderNeedTags());
            }
            if (mainBook.getTargetReaderTags() == null && other.getTargetReaderTags() != null) {
                mainBook.setTargetReaderTags(other.getTargetReaderTags());
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

        // 删除被合并的其他格式书籍
        StringBuilder mergedInfo = new StringBuilder();
        for (Book other : toMerge) {
            deleteCoverFile(other.getCoverUrl());
            bookRepository.deleteById(other.getId());
            bookSearchService.deleteIndex(other.getId());
            mergedInfo.append(String.format("  合并: [id=%d] %s (%s) → [id=%d] %s (%s)\n",
                    other.getId(), other.getTitle(), other.getFormat(),
                    savedMain.getId(), savedMain.getTitle(), savedMain.getFormat()));
        }

        // 事务提交后再操作 Qdrant 向量 + 重新生成主书籍向量 + 清除缓存
        List<Long> mergedIds = toMerge.stream().map(Book::getId).toList();
        TransactionUtils.afterCommit(() -> {
            for (Long mergedId : mergedIds) {
                embeddingService.removeBookEmbedding(mergedId);
                embeddingService.removeContentEmbedding(mergedId);
            }
            embeddingService.removeBookEmbedding(savedMain.getId());
            embeddingService.generateBookEmbedding(savedMain);
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
            // 封面URL格式：/api/admin/books/cover/filename.jpg 或 /api/books/cover/filename.jpg
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
            // 使用 SCAN 代替 KEYS 避免阻塞 Redis
            var cursor = redisTemplate.scan(
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match("kbook:recommend:*")
                            .count(100)
                            .build());
            List<String> recommendKeys = new ArrayList<>();
            cursor.forEachRemaining(recommendKeys::add);
            cursor.close();
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

    // ==================== 钩子方法：统一处理 ES/Redis/Qdrant ====================

    /**
     * 保存成功后处理（事务提交后）：更新 ES 索引
     */
    private void dealSaveResult(Book saved) {
        try {
            bookSearchService.indexBook(saved);
        } catch (Exception e) {
            log.error("保存后更新 ES 索引失败: bookId={}", saved.getId(), e);
        }
    }

    /**
     * 更新成功后处理（事务提交后）：更新 ES 索引、清除 Redis 缓存
     */
    private void dealUpdateResult(Book updated) {
        try {
            bookSearchService.indexBook(updated);
            evictBookCache(updated.getId());
        } catch (Exception e) {
            log.error("更新后同步数据失败: bookId={}", updated.getId(), e);
        }
    }

    /**
     * 生成图书精炼摘要：将 chapterSummary + 标签 + 目录 压缩为高信息密度的结构化摘要。
     *
     * <p>一次 LLM 调用，生成后存入 Book.compressedSummary，后续问答直接复用。
     * 不设长度上限，以精炼为目标，保留所有关键信息。</p>
     *
     * @param book 书籍实体（需含 chapterSummary, description, toc, 各类标签）
     * @return 精炼后的摘要文本，失败时返回 null
     */
    public String generateCompressedSummary(Book book) {
        try {
            StringBuilder input = new StringBuilder();

            if (book.getDescription() != null && !book.getDescription().isBlank()) {
                input.append("【图书简介】\n").append(book.getDescription()).append("\n\n");
            }
            if (book.getFormatTags() != null && !book.getFormatTags().isBlank()) {
                input.append("【格式标签】").append(book.getFormatTags()).append("\n");
            }
            if (book.getConceptTags() != null && !book.getConceptTags().isBlank()) {
                input.append("【核心概念标签】").append(book.getConceptTags()).append("\n");
            }
            if (book.getReaderNeedTags() != null && !book.getReaderNeedTags().isBlank()) {
                input.append("【读者需求标签】").append(book.getReaderNeedTags()).append("\n");
            }
            if (book.getTargetReaderTags() != null && !book.getTargetReaderTags().isBlank()) {
                input.append("【目标读者标签】").append(book.getTargetReaderTags()).append("\n");
            }
            if (book.getToc() != null && !book.getToc().isBlank()) {
                input.append("\n【图书目录】\n").append(book.getToc()).append("\n");
            }
            if (book.getChapterSummary() != null && !book.getChapterSummary().isBlank()) {
                input.append("\n【章节原文摘录】\n").append(book.getChapterSummary()).append("\n");
            }

            String systemPrompt = AiPromptConstants.COMPRESSED_SUMMARY_SYSTEM_PROMPT;

            // 动态内容（图书信息）作为 UserMessage
            List<ChatMessage> chatMessages = List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(input.toString()));

            String result = chatModelManager.callAiForScene(AiScene.BOOK_SUMMARY_REFINE,
                    "图书摘要精炼",
                    String.format("book=%s, inputLen=%d", book.getTitle(), input.length()),
                    chatMessages);

            if (result != null && !result.isBlank()) {
                log.info("图书摘要精炼成功: bookId={}, title={}, resultLen={}",
                        book.getId(), book.getTitle(), result.length());
                return result;
            }

        } catch (Exception e) {
            log.warn("图书摘要精炼失败: bookId={}, title={} - {}", book.getId(), book.getTitle(), e.getMessage());
        }
        return null;
    }

    /**
     * 获取书籍的有效摘要，优先返回 compressedSummary（LLM 精炼），
     * 若为空则触发懒生成并持久化，失败时回退到 chapterSummary。
     * <p>
     * 使用分布式锁防止并发重复生成：锁被占用时返回 null，调用方跳过摘要展示。
     *
     * @param book 书籍实体
     * @return 摘要文本；锁被占用时为 null；两者都为空时为 null
     */
    @Transactional
    @RedisLock(key = "'book:summary:compress:' + #book.id", leaseTime = 300, timeUnit = TimeUnit.SECONDS)
    public String resolveBookSummary(Book book) {
        // 1. 压缩摘要已存在，直接返回
        if (book.getCompressedSummary() != null && !book.getCompressedSummary().isBlank()) {
            return book.getCompressedSummary();
        }
        // 2. 压缩摘要为空，尝试懒生成
        if (book.getChapterSummary() != null && !book.getChapterSummary().isBlank()) {
            try {
                String compressed = generateCompressedSummary(book);
                if (compressed != null && !compressed.isBlank()) {
                    book.setCompressedSummary(compressed);
                    bookRepository.save(book);
                    log.info("resolveBookSummary 懒生成成功: bookId={}, len={}", book.getId(), compressed.length());
                    return compressed;
                }
            } catch (Exception e) {
                log.warn("resolveBookSummary 懒生成失败: bookId={}, error={}", book.getId(), e.getMessage());
            }
        }
        // 3. 回退到原始章节摘要
        return book.getChapterSummary();
    }
}
