package com.kbook.service;

import com.kbook.config.annotation.RedisLock;
import com.kbook.entity.Book;
import com.kbook.entity.User;
import com.kbook.repository.BookRepository;
import com.kbook.repository.ReadingProgressRepository;
import com.kbook.repository.UserBookPreferenceRepository;
import com.kbook.repository.UserReadHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendComputeService {

    private final BookRepository bookRepository;
    private final ReadingProgressRepository progressRepository;
    private final UserReadHistoryRepository readHistoryRepository;
    private final UserService userService;
    private final UserBookPreferenceRepository preferenceRepository;
    private final RecommendCoefficientService coefficientService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final DimensionStatsService dimensionStatsService;

    private static final String SORTED_KEY_PREFIX = "kbook:recommend:sorted:";
    private static final String SORTED_TEMP_SUFFIX = ":temp";
    private static final int CACHE_TTL_MINUTES = 30;

    @RedisLock(key = "'kbook:lock:recommend:' + #userId", leaseTime = 600)
    public List<ScoredBook> computeAndSave(Long userId) {
        log.info("获取锁成功，开始计算推荐: userId={}", userId);
        long startTime = System.currentTimeMillis();

        List<ScoredBook> scoredBooks = computeScoredBooks(userId);
        saveToSortedSetWithTemp(userId, scoredBooks);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("推荐计算完成: userId={}, count={}, elapsed={}ms", userId, scoredBooks.size(), elapsed);
        return scoredBooks;
    }

    private List<ScoredBook> computeScoredBooks(Long userId) {
        User user = userService.getUserById(userId);
        List<Long> readBookIds = getReadBookIds(userId);
        Set<Long> excludeSet = new HashSet<>(readBookIds);

        List<String> excludedTags = getExcludedTags(userId);
        List<String> excludedAuthors = getExcludedAuthors(userId);
        List<String> excludedFormats = getExcludedFormats(userId);
        List<String> includedTags = getIncludedTags(userId);
        List<String> includedAuthors = getIncludedAuthors(userId);
        List<String> includedFormats = getIncludedFormats(userId);

        double ruleMinScore = coefficientService.getCoefficient("OTHER", "rule_min_score", -0.5);

        List<ScoredBook> scoredBooks = new ArrayList<>();
        List<Book> allBooks = bookRepository.findAll();

        for (Book book : allBooks) {
            if (excludeSet.contains(book.getId())) continue;
            if (isExcludedByPreference(book, excludedTags, excludedAuthors, excludedFormats)) continue;

            double matchScore = RecommendMatchCalculator.calculateMatchScore(user, book, coefficientService, null, dimensionStatsService);
            if (matchScore <= ruleMinScore) continue;

            double qualityBonus = calculateQualityBonus(book.getRating());
            double freshnessBonus = calculateFreshnessBonus(book.getCreatedAt());
            double preferenceBonus = calculateIncludeBonus(book, includedTags, includedAuthors, includedFormats);
            double finalScore = matchScore + qualityBonus + freshnessBonus + preferenceBonus;

            scoredBooks.add(new ScoredBook(book, finalScore, matchScore, qualityBonus, "RULE"));
        }

        addExploreBooks(user, excludeSet, scoredBooks);

        scoredBooks.sort((a, b) -> Double.compare(b.finalScore, a.finalScore));
        return scoredBooks;
    }

    private void addExploreBooks(User user, Set<Long> excludeSet, List<ScoredBook> scoredBooks) {
        int exploreRandomCount = (int) coefficientService.getCoefficient("OTHER", "explore_random_count", 30);
        Set<Long> existingIds = scoredBooks.stream()
                .map(sb -> sb.book.getId())
                .collect(Collectors.toSet());

        int randomCount = (int) (exploreRandomCount * 0.6);
        List<Book> randomBooks = bookRepository.findRandomBooks(randomCount * 2);
        int added = 0;
        for (Book book : randomBooks) {
            if (excludeSet.contains(book.getId()) || existingIds.contains(book.getId())) continue;
            double baseScore = 0.3 + RecommendMatchCalculator.calculateMatchScore(user, book, coefficientService, null, dimensionStatsService) * 0.3;
            scoredBooks.add(new ScoredBook(book, baseScore, baseScore, 0.0, "EXPLORE"));
            existingIds.add(book.getId());
            added++;
            if (added >= randomCount) break;
        }

        int hotCount = (int) (exploreRandomCount * 0.4);
        List<Book> hotBooks = bookRepository.findAllByOrderByReadCountDesc(PageRequest.of(0, hotCount * 3)).getContent();
        added = 0;
        for (Book book : hotBooks) {
            if (excludeSet.contains(book.getId()) || existingIds.contains(book.getId())) continue;
            double baseScore = 0.3 + RecommendMatchCalculator.calculateMatchScore(user, book, coefficientService, null, dimensionStatsService) * 0.3;
            scoredBooks.add(new ScoredBook(book, baseScore, baseScore, 0.0, "EXPLORE"));
            existingIds.add(book.getId());
            added++;
            if (added >= hotCount) break;
        }
    }

    private void saveToSortedSetWithTemp(Long userId, List<ScoredBook> scoredBooks) {
        try {
            String tempKey = SORTED_KEY_PREFIX + userId + SORTED_TEMP_SUFFIX;
            String realKey = SORTED_KEY_PREFIX + userId;

            redisTemplate.delete(tempKey);
            for (ScoredBook sb : scoredBooks) {
                redisTemplate.opsForZSet().add(tempKey, sb.book.getId(), sb.finalScore);
            }
            redisTemplate.expire(tempKey, CACHE_TTL_MINUTES, TimeUnit.MINUTES);

            redisTemplate.delete(realKey);
            redisTemplate.rename(tempKey, realKey);
        } catch (Exception e) {
            log.debug("写入推荐Sorted Set(temp)失败: {}", e.getMessage());
        }
    }

    void saveToSortedSetDirect(Long userId, List<ScoredBook> scoredBooks) {
        try {
            String sortedKey = SORTED_KEY_PREFIX + userId;
            redisTemplate.delete(sortedKey);
            for (ScoredBook sb : scoredBooks) {
                redisTemplate.opsForZSet().add(sortedKey, sb.book.getId(), sb.finalScore);
            }
            redisTemplate.expire(sortedKey, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.debug("写入推荐Sorted Set失败: {}", e.getMessage());
        }
    }

    private double calculateQualityBonus(Double rating) {
        if (rating == null || rating <= 0) return -0.05;
        if (rating < 2.0) return -0.15 + (rating - 1.0) * 0.07;
        else if (rating < 3.0) return -0.08 + (rating - 2.0) * 0.06;
        else if (rating < 4.0) return -0.02 + (rating - 3.0) * 0.06;
        else return 0.04 + (rating - 4.0) * 0.06;
    }

    private double calculateFreshnessBonus(LocalDateTime createdAt) {
        if (createdAt == null) return 0;
        long daysAgo = ChronoUnit.DAYS.between(createdAt, LocalDateTime.now());
        if (daysAgo < 0) daysAgo = 0;
        if (daysAgo <= 7) return 0.05 * (1.0 - (double) daysAgo / 7);
        else if (daysAgo <= 30) return 0.02 * (1.0 - (double) (daysAgo - 7) / 23);
        return 0;
    }

    private double calculateIncludeBonus(Book book, List<String> includedTags,
                                          List<String> includedAuthors, List<String> includedFormats) {
        double tagBonus = coefficientService.getCoefficient("PREFERENCE", "tag_bonus", 0.12);
        double authorBonus = coefficientService.getCoefficient("PREFERENCE", "author_bonus", 0.15);
        double formatBonus = coefficientService.getCoefficient("PREFERENCE", "format_bonus", 0.05);

        double bonus = 0.0;
        if (!includedTags.isEmpty() && book.getFormatTags() != null) {
            Set<String> bookTags = parseTags(book.getFormatTags());
            for (String tag : includedTags) {
                if (bookTags.stream().anyMatch(t -> t.equalsIgnoreCase(tag))) bonus += tagBonus;
            }
        }
        if (!includedAuthors.isEmpty() && book.getAuthor() != null) {
            for (String author : includedAuthors) {
                if (author.equalsIgnoreCase(book.getAuthor())) { bonus += authorBonus; break; }
            }
        }
        if (!includedFormats.isEmpty() && book.getFormat() != null) {
            for (String format : includedFormats) {
                if (format.equalsIgnoreCase(book.getFormat())) { bonus += formatBonus; break; }
            }
        }
        return bonus;
    }

    private boolean isExcludedByPreference(Book book, List<String> excludedTags,
                                            List<String> excludedAuthors, List<String> excludedFormats) {
        if (!excludedFormats.isEmpty() && book.getFormat() != null
                && excludedFormats.contains(book.getFormat().toUpperCase())) return true;
        if (!excludedAuthors.isEmpty() && book.getAuthor() != null
                && excludedAuthors.stream().anyMatch(a -> a.equalsIgnoreCase(book.getAuthor()))) return true;
        if (!excludedTags.isEmpty() && book.getFormatTags() != null) {
            Set<String> bookTags = parseTags(book.getFormatTags());
            for (String excludedTag : excludedTags) {
                if (bookTags.stream().anyMatch(t -> t.equalsIgnoreCase(excludedTag))) return true;
            }
        }
        return false;
    }

    private List<Long> getReadBookIds(Long userId) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.addAll(readHistoryRepository.findAllInteractedBookIdsByUserId(userId));
        ids.addAll(progressRepository.findAllBookIdsByUserId(userId));
        return new ArrayList<>(ids);
    }

    private List<String> getExcludedTags(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "TAG", "EXCLUDE")
                .stream().map(com.kbook.entity.UserBookPreference::getValue).toList();
    }

    private List<String> getExcludedAuthors(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "AUTHOR", "EXCLUDE")
                .stream().map(com.kbook.entity.UserBookPreference::getValue).toList();
    }

    private List<String> getExcludedFormats(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "FORMAT", "EXCLUDE")
                .stream().map(com.kbook.entity.UserBookPreference::getValue).toList();
    }

    private List<String> getIncludedTags(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "TAG", "INCLUDE")
                .stream().map(com.kbook.entity.UserBookPreference::getValue).toList();
    }

    private List<String> getIncludedAuthors(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "AUTHOR", "INCLUDE")
                .stream().map(com.kbook.entity.UserBookPreference::getValue).toList();
    }

    private List<String> getIncludedFormats(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "FORMAT", "INCLUDE")
                .stream().map(com.kbook.entity.UserBookPreference::getValue).toList();
    }

    private Set<String> parseTags(String formatTags) {
        if (formatTags == null || formatTags.isBlank()) return Set.of();
        return Arrays.stream(formatTags.replaceAll("[\\[\\]\"]", "").split("[,，]"))
                .map(String::trim)
                .filter(t -> !t.isBlank())
                .collect(Collectors.toSet());
    }

    public record ScoredBook(Book book, double finalScore, double matchScore, double qualityBonus, String recallPath) {
    }
}
