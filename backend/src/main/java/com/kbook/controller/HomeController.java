package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.entity.Book;
import com.kbook.entity.ReadingProgress;
import com.kbook.service.BookService;
import com.kbook.service.BookshelfService;
import com.kbook.service.ReadingProgressService;
import com.kbook.service.RecommendService;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final BookshelfService bookshelfService;
    private final ReadingProgressService progressService;
    private final RecommendService recommendService;

    /**
     * 获取筛选标签列表（独立接口，供搜索页使用）
     */
    @GetMapping("/tags")
    public Result<List<TagStat>> getFilterTags() {
        return Result.ok(getTopTags());
    }

    /**
     * 获取阅读统计
     */
    @GetMapping("/stats")
    public Result<ReadingStatsVO> getStats(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        ReadingProgressService.ReadingStats stats = progressService.getReadingStats(userId);
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
            List<RecommendService.RecommendedItem> items =
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
        return Result.ok(getTopTags());
    }

    /**
     * 获取首页全部数据（保留兼容）
     */
    @GetMapping
    public Result<HomeData> getHomeData(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();

        ReadingProgressService.ReadingStats stats = progressService.getReadingStats(userId);

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
            List<RecommendService.RecommendedItem> items =
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
        List<TagStat> categories = getTopTags();

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
     */
    private List<TagStat> getTopTags() {
        List<Book> all = bookService.getReadRank(1, 500).getList();
        Map<String, Long> tagCount = new HashMap<>();

        for (Book book : all) {
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

        return tagCount.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(50)
                .map(e -> TagStat.builder()
                        .name(e.getKey())
                        .count(e.getValue())
                        .build())
                .toList();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TagStat {
        private String name;
        private Long count;
    }

    // ===== VO classes =====

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HomeData {
        private ReadingStatsVO stats;
        private List<RecentBookVO> recentBooks;
        private List<RecommendedBook> personalizedBooks;
        private List<SimpleBookVO> topRatedBooks;
        private List<SimpleBookVO> newBooks;
        private List<SimpleBookVO> popularBooks;
        private List<TagStat> categories;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReadingStatsVO {
        private long totalBooks;
        private long completedBooks;
        private long readingBooks;

        public static ReadingStatsVO from(ReadingProgressService.ReadingStats stats) {
            return ReadingStatsVO.builder()
                    .totalBooks(stats.getTotalBooks())
                    .completedBooks(stats.getCompletedBooks())
                    .readingBooks(stats.getReadingBooks())
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentBookVO {
        private Long bookId;
        private String title;
        private String author;
        private String coverUrl;
        private String format;
        private Double progress;
        private java.time.LocalDateTime lastReadAt;

        public static RecentBookVO from(BookshelfService.BookshelfItem item) {
            return RecentBookVO.builder()
                    .bookId(item.getBookId())
                    .title(item.getTitle())
                    .author(item.getAuthor())
                    .coverUrl(item.getCoverUrl())
                    .format(item.getFormat())
                    .progress(item.getProgress())
                    .lastReadAt(item.getLastReadAt())
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecommendedBook {
        private Long id;
        private String title;
        private String author;
        private String coverUrl;
        private String format;
        private Double rating;
        private String description;
        private Double matchScore;
        private Long readCount;

        public static RecommendedBook from(Book book, double matchScore) {
            return RecommendedBook.builder()
                    .id(book.getId())
                    .title(book.getTitle())
                    .author(book.getAuthor())
                    .coverUrl(book.getCoverUrl())
                    .format(book.getFormat())
                    .rating(book.getRating())
                    .readCount(book.getReadCount())
                    .description(book.getDescription() != null && book.getDescription().length() > 80
                            ? book.getDescription().substring(0, 80) + "..." : book.getDescription())
                    .matchScore(Math.round(matchScore * 100.0) / 100.0)
                    .build();
        }

        /**
         * 从 RecommendService.RecommendedItem 转换
         */
        public static RecommendedBook fromRecommendItem(RecommendService.RecommendedItem item) {
            return RecommendedBook.builder()
                    .id(item.getBookId())
                    .title(item.getTitle())
                    .author(item.getAuthor())
                    .coverUrl(item.getCoverUrl())
                    .format(item.getFormat())
                    .rating(item.getRating())
                    .description(item.getDescription())
                    .matchScore(item.getMatchScore())
                    .readCount(item.getReadCount())
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SimpleBookVO {
        private Long id;
        private String title;
        private String author;
        private String coverUrl;
        private String format;
        private Double rating;
        private Long readCount;

        public static SimpleBookVO from(Book book) {
            return SimpleBookVO.builder()
                    .id(book.getId())
                    .title(book.getTitle())
                    .author(book.getAuthor())
                    .coverUrl(book.getCoverUrl())
                    .format(book.getFormat())
                    .rating(book.getRating())
                    .readCount(book.getReadCount())
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FormatCategory {
        private String format;
        private String label;
        private String icon;
        private Long count;
    }
}
