package com.kbook.service.rank;
import com.kbook.service.book.BookService;

import com.kbook.common.api.PageResult;
import com.kbook.dto.book.BookProjection;
import com.kbook.repository.BookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;


/**
 * 榜单聚合服务 — Redis 缓存 + 定时刷新
 * <p>
 * 负责管理平台各类排行榜数据，包括：
 * - 阅读榜：按阅读量降序排列
 * - 评分榜：按评分降序排列
 * - 新书榜：按创建时间降序排列
 * <p>
 * 缓存策略：
 * - 所有榜单仅缓存图书 ID 列表（逗号分隔），取用时通过 BookRepository 查询最新数据
 * - 定时刷新周期为2小时（@Scheduled）
 * - 缓存未命中时降级到数据库查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogModule("排行榜")
public class RankService {

    /** 书籍数据访问层，用于查询书籍投影数据 */
    private final BookRepository bookRepository;
    /** 书籍服务，用于获取书籍投影和热门标签计算 */
    private final BookService bookService;
    /** Redis 操作模板，用于榜单缓存的读写 */
    private final StringRedisTemplate redisTemplate;

    /** Redis Key：阅读榜缓存，存储按阅读量降序的前50本书籍ID */
    private static final String READ_RANK_KEY = "kbook:rank:read";
    /** Redis Key：评分榜缓存，存储按评分降序的前50本书籍ID */
    private static final String RATING_RANK_KEY = "kbook:rank:rating";
    /** Redis Key：新书榜缓存，存储按创建时间降序的前50本书籍ID */
    private static final String NEW_BOOKS_RANK_KEY = "kbook:rank:new";
    /** 榜单缓存过期时间（小时），2小时后自动失效等待下次刷新 */
    private static final long CACHE_TTL_HOURS = 2;

    /**
     * 解析 ID 列表字符串（逗号分隔）
     */
    private List<Long> parseIdList(String idsStr) {
        List<Long> ids = new ArrayList<>();
        for (String s : idsStr.split(",")) {
            s = s.trim();
            if (!s.isEmpty()) {
                ids.add(Long.parseLong(s));
            }
        }
        return ids;
    }

    /**
     * 缓存图书 ID 列表（逗号分隔，如 "1,5,8,12"）
     */
    private void cacheBookIds(String key, List<BookProjection> books) {
        try {
            String idsStr = books.stream()
                    .map(b -> String.valueOf(b.getId()))
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            redisTemplate.opsForValue().set(key, idsStr, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("缓存写入失败: key={}", key, e);
        }
    }

    /**
     * 刷新阅读榜缓存
     */
    private void refreshReadRankCache() {
        Pageable top50 = PageRequest.of(0, 50);
        Page<BookProjection> readRank = bookRepository.findAllProjectedByOrderByReadCountDesc(top50);
        cacheBookIds(READ_RANK_KEY, readRank.getContent());
        log.debug("阅读榜缓存刷新完成: {}本", readRank.getContent().size());
    }

    /**
     * 刷新评分榜缓存
     */
    private void refreshRatingRankCache() {
        Pageable top50 = PageRequest.of(0, 50);
        Page<BookProjection> ratingRank = bookRepository.findAllProjectedByOrderByRatingDesc(top50);
        cacheBookIds(RATING_RANK_KEY, ratingRank.getContent());
        log.debug("评分榜缓存刷新完成: {}本", ratingRank.getContent().size());
    }

    /**
     * 刷新新书榜缓存
     */
    private void refreshNewBooksRankCache() {
        Pageable top50 = PageRequest.of(0, 50);
        Page<BookProjection> newBooks = bookRepository.findAllProjectedByOrderByCreatedAtDesc(top50);
        cacheBookIds(NEW_BOOKS_RANK_KEY, newBooks.getContent());
        log.debug("新书榜缓存刷新完成: {}本", newBooks.getContent().size());
    }

    /**
     * 从缓存读取指定页码的阅读榜
     */
    @LogAction("获取阅读榜")
    public PageResult<BookProjection> getReadRank(int page, int size) {
        return getCachedPage(READ_RANK_KEY, page, size);
    }

    /**
     * 从缓存读取指定页码的评分榜
     */
    @LogAction("获取评分榜")
    public PageResult<BookProjection> getRatingRank(int page, int size) {
        return getCachedPage(RATING_RANK_KEY, page, size);
    }

    /**
     * 从缓存读取指定页码的新书榜
     */
    @LogAction("获取新书榜")
    public PageResult<BookProjection> getNewBooksRank(int page, int size) {
        return getCachedPage(NEW_BOOKS_RANK_KEY, page, size);
    }

    /**
     * 从缓存的 top 50 ID 列表中截取分页。
     * 缓存不存在时降级到数据库查询；缓存存在但超出范围则返回空列表，不再查数据库。
     */
    private PageResult<BookProjection> getCachedPage(String cacheKey, int page, int size) {
        String cachedIds = redisTemplate.opsForValue().get(cacheKey);
        if (cachedIds == null || cachedIds.isEmpty()) {
            return loadFromDb(cacheKey, page, size);
        }
        try {
            List<Long> ids = parseIdList(cachedIds);
            int totalCached = ids.size();
            int fromIndex = (page - 1) * size;
            if (fromIndex >= totalCached) {
                return PageResult.of(List.of(), totalCached, page, size);
            }
            int toIndex = Math.min(fromIndex + size, totalCached);
            List<Long> pageIds = ids.subList(fromIndex, toIndex);
            List<BookProjection> books = bookRepository.findProjectedByIdIn(pageIds);
            return PageResult.of(books, totalCached, page, size);
        } catch (Exception e) {
            log.warn("缓存读取失败，降级到数据库: key={}", cacheKey, e);
            return loadFromDb(cacheKey, page, size);
        }
    }

    private PageResult<BookProjection> loadFromDb(String cacheKey, int page, int size) {
        Pageable pb = PageRequest.of(page - 1, size);
        if (READ_RANK_KEY.equals(cacheKey)) {
            Page<BookProjection> pg = bookRepository.findAllProjectedByOrderByReadCountDesc(pb);
            return PageResult.of(pg.getContent(), pg.getTotalElements(), page, size);
        }
        if (RATING_RANK_KEY.equals(cacheKey)) {
            Page<BookProjection> pg = bookRepository.findAllProjectedByOrderByRatingDesc(pb);
            return PageResult.of(pg.getContent(), pg.getTotalElements(), page, size);
        }
        // NEW_BOOKS_RANK_KEY
        Page<BookProjection> pg = bookRepository.findAllProjectedByOrderByCreatedAtDesc(pb);
        return PageResult.of(pg.getContent(), pg.getTotalElements(), page, size);
    }

    /**
     * 刷新热门标签缓存 — 委托 BookService 加锁计算并写入 Redis
     */
    private void refreshHotTagsCache() {
        try {
            bookService.computeTopTagsWithLock();
            log.debug("热门标签缓存刷新完成");
        } catch (Exception e) {
            log.warn("刷新热门标签缓存失败: {}", e.getMessage());
        }
    }

    /**
     * 定时刷新热门榜单缓存 — 每2小时
     */
    @Scheduled(fixedRate = 2 * 60 * 60 * 1000)
    public void refreshRankCache() {
        log.info("开始刷新榜单缓存...");
        try {
            refreshReadRankCache();
            refreshRatingRankCache();
            refreshNewBooksRankCache();
            refreshHotTagsCache();

            log.info("榜单缓存刷新完成");
        } catch (Exception e) {
            log.error("刷新榜单缓存失败", e);
        }
    }
}
