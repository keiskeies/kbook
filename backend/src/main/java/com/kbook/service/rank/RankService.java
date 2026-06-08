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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;


/**
 * 榜单聚合服务 — Redis 缓存 + 定时刷新
 * <p>
 * 负责管理平台各类排行榜数据，包括：
 * - 阅读榜：按阅读量降序排列
 * - 评分榜：按评分降序排列
 * - 新书榜：按创建时间降序排列
 * - 高分佳作：4分以上随机6本（首页展示）
 * - 新书速递：全部书籍随机12本（首页展示）
 * <p>
 * 缓存策略：
 * - 所有榜单仅缓存图书 ID 列表（逗号分隔），取用时通过 BookRepository 查询最新数据
 * - 定时刷新周期为2小时（@Scheduled）
 * - 缓存未命中时降级到数据库查询
 * - 高分佳作和新书速递每次刷新随机打乱顺序
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

    /** 高分佳作缓存 Key — 评分≥4分的书籍中随机选取6本，用于首页推荐 */
    public static final String HIGH_RATED_RANDOM_KEY = "kbook:home:high_rated_random";
    /** 新书速递缓存 Key — 全部书籍中随机选取12本，用于首页展示 */
    public static final String NEW_ARRIVALS_RANDOM_KEY = "kbook:home:new_arrivals_random";
    /** 高分佳作最低评分阈值，只有评分>=此值的书籍才纳入候选 */
    private static final int HIGH_RATED_MIN_SCORE = 4;
    /** 高分佳作每次随机选取的数量 */
    private static final int HIGH_RATED_COUNT = 6;
    /** 新书速递每次随机选取的数量 */
    private static final int NEW_ARRIVALS_COUNT = 12;


    /**
     * 获取高分佳作（4分以上随机6本，优先读缓存）
     * <p>
     * 用于首页推荐模块展示，每次刷新随机打乱顺序增加新鲜感。
     * 缓存命中时直接返回，未命中或解析失败则重新生成。
     *
     * @return 高分书籍投影列表（最多6本）
     */
    @LogAction("获取高分佳作")
    public List<BookProjection> getHighRatedRandom() {
        String cachedIds = redisTemplate.opsForValue().get(HIGH_RATED_RANDOM_KEY);
        if (cachedIds != null && !cachedIds.isEmpty()) {
            try {
                List<Long> ids = parseIdList(cachedIds);
                if (!ids.isEmpty()) {
                    return bookRepository.findProjectedByIdIn(ids);
                }
            } catch (Exception e) {
                log.warn("高分佳作缓存解析失败，重新生成", e);
            }
        }
        return generateHighRatedRandom();
    }

    /**
     * 获取新书速递（全部书籍随机12本，优先读缓存）
     * <p>
     * 用于首页新书推荐模块，随机打乱展示全部书籍。
     * 缓存命中时直接返回，未命中或解析失败则重新生成。
     *
     * @return 随机书籍投影列表（最多12本）
     */
    @LogAction("获取新书速递")
    public List<BookProjection> getNewArrivalsRandom() {
        String cachedIds = redisTemplate.opsForValue().get(NEW_ARRIVALS_RANDOM_KEY);
        if (cachedIds != null && !cachedIds.isEmpty()) {
            try {
                List<Long> ids = parseIdList(cachedIds);
                if (!ids.isEmpty()) {
                    return bookRepository.findProjectedByIdIn(ids);
                }
            } catch (Exception e) {
                log.warn("新书速递缓存解析失败，重新生成", e);
            }
        }
        return generateNewArrivalsRandom();
    }


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
     * 生成高分佳作随机列表（底层方法）
     * <p>
     * 流程：从数据库取前100本评分最高的书 → 筛选评分≥4分 → 随机打乱 → 取前6本 → 缓存ID列表
     *
     * @return 随机高分书籍投影列表
     */
    private List<BookProjection> generateHighRatedRandom() {
        List<BookProjection> candidates = bookRepository.findAllProjectedByOrderByRatingDesc(PageRequest.of(0, 100)).getContent()
                .stream().filter(b -> b.getRating() != null && b.getRating() >= HIGH_RATED_MIN_SCORE).toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<BookProjection> shuffled = new ArrayList<>(candidates);
        Collections.shuffle(shuffled);
        List<BookProjection> result = shuffled.stream().limit(HIGH_RATED_COUNT).toList();
        cacheBookIds(HIGH_RATED_RANDOM_KEY, result);
        return result;
    }

    /**
     * 生成新书速递随机列表（底层方法）
     * <p>
     * 流程：从数据库取前1000本书 → 随机打乱 → 取前12本 → 缓存ID列表
     * 如果书籍总数不足12本则返回全部
     *
     * @return 随机书籍投影列表
     */
    private List<BookProjection> generateNewArrivalsRandom() {
        List<BookProjection> allProjected = bookRepository.findAllProjectedByOrderByIdAsc(PageRequest.of(0, 1000)).getContent();
        if (allProjected.isEmpty()) {
            return List.of();
        }
        List<BookProjection> shuffled = new ArrayList<>(allProjected);
        Collections.shuffle(shuffled);
        List<BookProjection> result = shuffled.stream().limit(NEW_ARRIVALS_COUNT).toList();
        cacheBookIds(NEW_ARRIVALS_RANDOM_KEY, result);
        return result;
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
            generateHighRatedRandom();
            generateNewArrivalsRandom();

            log.info("榜单缓存刷新完成");
        } catch (Exception e) {
            log.error("刷新榜单缓存失败", e);
        }
    }
}
