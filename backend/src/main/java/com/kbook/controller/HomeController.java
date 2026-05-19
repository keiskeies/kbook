package com.kbook.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.api.Result;
import com.kbook.dto.*;
import com.kbook.entity.Book;
import com.kbook.entity.ReadingProgress;
import com.kbook.service.BookService;
import com.kbook.service.ReadingProgressService;
import com.kbook.service.RecommendService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * 首页数据聚合控制器
 * 一次请求返回首页所需的所有数据，减少前端请求次数
 */
@Slf4j
@RestController
@RequestMapping("/api/home")
@RequiredArgsConstructor
public class HomeController {

    private final BookService bookService;
    private final ReadingProgressService progressService;
    private final RecommendService recommendService;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;


    /**
     * 获取筛选标签列表（独立接口，供搜索页使用）
     */
    @GetMapping("/tags")
    public Result<List<TagStat>> getFilterTags() {
        return Result.ok(getTopTags(100));
    }

    /**
     * 获取阅读统计
     */
    @GetMapping("/stats")
    public Result<ReadingStatsVO> getStats(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        ReadingStats stats = progressService.getReadingStats(userId);
        return Result.ok(ReadingStatsVO.from(stats));
    }

    /**
     * 获取最近阅读（继续阅读）
     */
    @GetMapping("/recent")
    public Result<List<RecentBookVO>> getRecentBooks(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        List<ReadingProgress> recentProgress = progressService.getRecentReading(userId, 4);
        List<RecentBookVO> recentBooks = recentProgress.stream()
                .map(rp -> {
                    Book book = bookService.getBookById(rp.getBookId());
                    return RecentBookVO.builder()
                            .bookId(book.getId())
                            .title(book.getTitle())
                            .author(book.getAuthor())
                            .coverUrl(book.getCoverUrl())
                            .format(book.getFormat())
                            .progress(rp.getProgress())
                            .lastReadAt(rp.getUpdatedAt())
                            .build();
                })
                .toList();
        return Result.ok(recentBooks);
    }

    /**
     * 获取猜你喜欢（个性化推荐）
     */
    @GetMapping("/personalized")
    public Result<List<RecommendedBook>> getPersonalized(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        try {
            List<RecommendedItem> items =
                    recommendService.getPersonalizedRecommendations(userId, 6);
            return Result.ok(items.stream()
                    .map(RecommendedBook::fromRecommendItem)
                    .toList());
        } catch (Exception e) {
            log.error("个性化推荐失败", e);
            return Result.ok(List.of());
        }
    }

    /**
     * 获取高分佳作
     */
    @GetMapping("/top-rated")
    public Result<List<SimpleBookVO>> getTopRated() {
        List<Book> topRated = bookService.getRatingRank(1, 6).getList();
        return Result.ok(topRated.stream().map(SimpleBookVO::from).toList());
    }

    /**
     * 获取新书速递
     */
    @GetMapping("/new-books")
    public Result<List<SimpleBookVO>> getNewBooks() {
        List<Book> newBooks = bookService.getNewBooksRank(1, 12).getList();
        return Result.ok(newBooks.stream().map(SimpleBookVO::from).toList());
    }

    /**
     * 获取热门榜单
     */
    @GetMapping("/popular")
    public Result<List<SimpleBookVO>> getPopular() {
        List<Book> popular = bookService.getReadRank(1, 6).getList();
        return Result.ok(popular.stream().map(SimpleBookVO::from).toList());
    }

    /**
     * 获取热门标签
     */
    @GetMapping("/categories")
    public Result<List<TagStat>> getCategories() {
        return Result.ok(getTopTags(50));
    }

    /**
     * 获取首页全部数据（保留兼容）
     */
    @GetMapping
    public Result<HomeData> getHomeData(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();

        ReadingStats stats = progressService.getReadingStats(userId);

        List<ReadingProgress> recentProgress = progressService.getRecentReading(userId, 4);
        List<RecentBookVO> recentBooks = recentProgress.stream()
                .map(rp -> {
                    Book book = bookService.getBookById(rp.getBookId());
                    return RecentBookVO.builder()
                            .bookId(book.getId())
                            .title(book.getTitle())
                            .author(book.getAuthor())
                            .coverUrl(book.getCoverUrl())
                            .format(book.getFormat())
                            .progress(rp.getProgress())
                            .lastReadAt(rp.getUpdatedAt())
                            .build();
                })
                .toList();

        List<RecommendedBook> personalized;
        try {
            List<RecommendedItem> items =
                    recommendService.getPersonalizedRecommendations(userId, 6);
            personalized = items.stream()
                    .map(RecommendedBook::fromRecommendItem)
                    .toList();
        } catch (Exception e) {
            log.error("个性化推荐失败", e);
            personalized = List.of();
        }

        List<Book> topRated = bookService.getRatingRank(1, 6).getList();
        List<Book> newBooks = bookService.getNewBooksRank(1, 12).getList();
        List<Book> popular = bookService.getReadRank(1, 6).getList();
        List<TagStat> categories = getTopTags(50);

        return Result.ok(HomeData.builder()
                .stats(ReadingStatsVO.from(stats))
                .recentBooks(recentBooks)
                .personalizedBooks(personalized)
                .topRatedBooks(topRated.stream().map(SimpleBookVO::from).toList())
                .newBooks(newBooks.stream().map(SimpleBookVO::from).toList())
                .popularBooks(popular.stream().map(SimpleBookVO::from).toList())
                .categories(categories)
                .build());
    }

    /**
     * 统计热门标签（从所有书籍的 formatTags 中提取）
     * formatTags 格式示例: ["小说","历史","传记"]
     * 使用 Redis 缓存，72小时失效，分布式锁保证线程安全
     */
    public List<TagStat> getTopTags() {
        // 1. 先查缓存（无锁，保证高并发读取性能）
        try {
            String cachedData = redisTemplate.opsForValue().get(BookService.TOP_TAGS_CACHE_KEY);
            if (cachedData != null && !cachedData.isEmpty()) {
                log.debug("从缓存获取热门标签");
                return objectMapper.readValue(cachedData, new TypeReference<>() {
                });
            }
        } catch (Exception e) {
            log.warn("读取热门标签缓存失败: {}", e.getMessage());
        }

        // 2. 缓存未命中，加锁计算（防止缓存击穿）
        return bookService.computeTopTagsWithLock();
    }


    private List<TagStat> getTopTags(int limit) {
        return this.getTopTags().stream()
                .sorted(Comparator.comparingLong(TagStat::getCount).reversed())
                .limit(limit)
                .toList();
    }

}
