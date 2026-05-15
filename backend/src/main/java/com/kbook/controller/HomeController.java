package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.entity.Book;
import com.kbook.service.BookService;
import com.kbook.service.BookshelfService;
import com.kbook.service.ReadingProgressService;
import com.kbook.service.RecommendService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

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
     * 获取首页全部数据
     */
    @GetMapping
    public Result<HomeData> getHomeData(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();

        // 1. 阅读统计
        ReadingProgressService.ReadingStats stats = progressService.getReadingStats(userId);

        // 2. 最近阅读（从书架中取最近阅读未完成的4本）
        List<BookshelfService.BookshelfItem> shelf = bookshelfService.getBookshelf(userId);
        List<BookshelfService.BookshelfItem> recentBooks = shelf.stream()
                .filter(s -> s.getLastReadAt() != null && s.getProgress() < 1.0)
                .sorted((a, b) -> b.getLastReadAt().compareTo(a.getLastReadAt()))
                .limit(4)
                .toList();

        // 3. 猜你喜欢（个性化推荐 — 委托给 RecommendService）
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

        // 4. 高分佳作
        List<Book> topRated = bookService.getRatingRank(1, 6).getList();

        // 5. 新书速递
        List<Book> newBooks = bookService.getNewBooksRank(1, 6).getList();

        // 6. 热门榜单
        List<Book> popular = bookService.getReadRank(1, 6).getList();

        // 7. 分类发现（格式统计）
        List<FormatCategory> categories = getFormatCategories();

        return Result.ok(HomeData.builder()
                .stats(ReadingStatsVO.from(stats))
                .recentBooks(recentBooks.stream().map(RecentBookVO::from).toList())
                .personalizedBooks(personalized)
                .topRatedBooks(topRated.stream().map(SimpleBookVO::from).toList())
                .newBooks(newBooks.stream().map(SimpleBookVO::from).toList())
                .popularBooks(popular.stream().map(SimpleBookVO::from).toList())
                .categories(categories)
                .build());
    }

    private List<FormatCategory> getFormatCategories() {
        List<Book> all = bookService.getReadRank(1, 200).getList();
        Map<String, Long> countByFormat = all.stream()
                .collect(Collectors.groupingBy(Book::getFormat, Collectors.counting()));

        Map<String, String> formatLabels = Map.of("TXT", "文本", "EPUB", "电子书", "PDF", "文档");
        Map<String, String> formatIcons = Map.of("TXT", "📖", "EPUB", "📕", "PDF", "📄");

        return countByFormat.entrySet().stream()
                .map(e -> FormatCategory.builder()
                        .format(e.getKey())
                        .label(formatLabels.getOrDefault(e.getKey(), e.getKey()))
                        .icon(formatIcons.getOrDefault(e.getKey(), "📚"))
                        .count(e.getValue())
                        .build())
                .sorted((a, b) -> Long.compare(b.count, a.count))
                .toList();
    }

    // ===== VO classes =====

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class HomeData {
        private ReadingStatsVO stats;
        private List<RecentBookVO> recentBooks;
        private List<RecommendedBook> personalizedBooks;
        private List<SimpleBookVO> topRatedBooks;
        private List<SimpleBookVO> newBooks;
        private List<SimpleBookVO> popularBooks;
        private List<FormatCategory> categories;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
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

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
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

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
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

        /** 从 RecommendService.RecommendedItem 转换 */
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

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
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

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FormatCategory {
        private String format;
        private String label;
        private String icon;
        private Long count;
    }
}
