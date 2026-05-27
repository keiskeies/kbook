package com.kbook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.util.SseHelper;
import com.kbook.dto.RecommendedItem;
import com.kbook.entity.Book;
import com.kbook.entity.User;
import com.kbook.entity.UserBookPreference;
import com.kbook.entity.UserReadHistory;
import com.kbook.repository.BookRepository;
import com.kbook.repository.ReadingProgressRepository;
import com.kbook.repository.UserBookPreferenceRepository;
import com.kbook.repository.UserReadHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 推荐服务
 * <p>
 * 核心推荐入口，协调规则匹配、向量召回、协同过滤和探索发现四路召回策略。
 * 推荐结果存储在 Redis Sorted Set 中，支持分页查询和 SSE 进度推送。
 * 用户画像变更时异步重算推荐。
 */
@Slf4j
@Service
public class RecommendService {

    private final BookRepository bookRepository;
    private final ReadingProgressRepository progressRepository;
    private final UserReadHistoryRepository readHistoryRepository;
    private final UserService userService;
    private final UserBookPreferenceRepository preferenceRepository;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RecommendCoefficientService coefficientService;
    private final MatchScoreCacheService matchScoreCacheService;
    private final RecommendComputeService computeService;
    private final DimensionStatsService dimensionStatsService;
    private final BookTrashService bookTrashService;

    public RecommendService(
            BookRepository bookRepository,
            ReadingProgressRepository progressRepository,
            UserReadHistoryRepository readHistoryRepository,
            UserService userService,
            @Lazy UserBookPreferenceRepository preferenceRepository,
            ObjectMapper objectMapper,
            RedisTemplate<String, Object> redisTemplate,
            @Lazy RecommendCoefficientService coefficientService,
            MatchScoreCacheService matchScoreCacheService,
            RecommendComputeService computeService,
            DimensionStatsService dimensionStatsService,
            BookTrashService bookTrashService
    ) {
        this.bookRepository = bookRepository;
        this.progressRepository = progressRepository;
        this.readHistoryRepository = readHistoryRepository;
        this.userService = userService;
        this.preferenceRepository = preferenceRepository;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.coefficientService = coefficientService;
        this.matchScoreCacheService = matchScoreCacheService;
        this.computeService = computeService;
        this.dimensionStatsService = dimensionStatsService;
        this.bookTrashService = bookTrashService;
    }

    /** 推荐缓存键前缀 */
    private static final String CACHE_PREFIX = "kbook:recommend:";
    /** 推荐排序集合键前缀 */
    private static final String SORTED_KEY_PREFIX = "kbook:recommend:sorted:";
    /** 临时排序集合键后缀 */
    private static final String SORTED_TEMP_SUFFIX = ":temp";

    /**
     * 批量计算用户与多本书的匹配度得分（带缓存）
     * @param userId 用户ID
     * @param bookIds 书籍ID列表
     * @return bookId → 匹配度得分 的映射
     */
    public Map<Long, Double> batchCalculateMatchScores(Long userId, List<Long> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) return Map.of();

        User user = userService.getUserById(userId);
        boolean hasProfile = user.getBirthday() != null || user.getGender() != null
                || user.getMarried() != null || user.getHasChildren() != null
                || user.getMbti() != null || user.getOccupation() != null
                || user.getEducation() != null || user.getEntrepreneurship() != null
                || user.getAnnualIncome() != null || user.getMood() != null;

        if (!hasProfile) return Map.of();

        List<String> strIds = bookIds.stream().map(String::valueOf).collect(Collectors.toList());

        Map<String, Double> cachedScores = matchScoreCacheService.getScores(userId, strIds);
        Map<Long, Double> result = new LinkedHashMap<>();
        cachedScores.forEach((k, v) -> result.put(Long.parseLong(k), v));

        List<Long> missingIds = bookIds.stream()
                .filter(id -> !result.containsKey(id))
                .toList();

        if (missingIds.isEmpty()) return result;

        Map<String, Double> newScores = new HashMap<>();
        for (Long bookId : missingIds) {
            Book book = bookRepository.findById(bookId).orElse(null);
            if (book == null) continue;
            double score = RecommendMatchCalculator.calculateMatchScore(user, book, coefficientService, objectMapper, dimensionStatsService);
            double roundedScore = Math.round(score * 100.0) / 100.0;
            newScores.put(String.valueOf(bookId), roundedScore);
            result.put(bookId, roundedScore);
        }

        if (!newScores.isEmpty()) {
            matchScoreCacheService.putScores(userId, newScores);
        }

        return result;
    }

    /**
     * 获取个性化推荐列表（优先从 Redis Sorted Set 读取）
     * @param userId 用户ID
     * @param count 返回数量
     * @return 推荐项列表
     */
    public List<RecommendedItem> getPersonalizedRecommendations(Long userId, int count) {
        String sortedKey = SORTED_KEY_PREFIX + userId;
        try {
            Long size = redisTemplate.opsForZSet().size(sortedKey);
            if (size != null && size > 0) {
                Set<Object> bookIds = redisTemplate.opsForZSet().reverseRange(sortedKey, 0, count - 1);
                if (bookIds != null && !bookIds.isEmpty()) {
                    return buildItemsFromSortedSet(sortedKey, bookIds);
                }
            }
        } catch (Exception e) {
            log.debug("读取推荐Sorted Set失败: {}", e.getMessage());
        }

        List<RecommendComputeService.ScoredBook> scoredBooks = computeService.computeAndSave(userId);
        if (scoredBooks == null) scoredBooks = List.of();
        return buildTopItems(scoredBooks, count);
    }

    /**
     * 分页获取推荐结果
     * @param userId 用户ID
     * @param page 页码
     * @param size 每页大小
     * @return 分页结果（含 list, total, page, size）
     */
    public Map<String, Object> getRecommendationsPage(Long userId, int page, int size) {
        String sortedKey = SORTED_KEY_PREFIX + userId;
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Long total = redisTemplate.opsForZSet().size(sortedKey);
            if (total == null || total == 0) {
                result.put("list", List.of());
                result.put("total", 0);
                result.put("page", page);
                result.put("size", size);
                return result;
            }

            long start = (long) (page - 1) * size;
            long end = start + size - 1;
            Set<Object> bookIds = redisTemplate.opsForZSet().reverseRange(sortedKey, start, end);

            List<RecommendedItem> items = List.of();
            if (bookIds != null && !bookIds.isEmpty()) {
                items = buildItemsFromSortedSet(sortedKey, bookIds);
            }

            result.put("list", items);
            result.put("total", total);
            result.put("page", page);
            result.put("size", size);
        } catch (Exception e) {
            log.debug("分页查询推荐失败: {}", e.getMessage());
            result.put("list", List.of());
            result.put("total", 0);
            result.put("page", page);
            result.put("size", size);
        }

        return result;
    }

    /**
     * 清除用户的推荐缓存
     * @param userId 用户ID
     */
    public void clearUserCache(Long userId) {
        try {
            Set<String> keys = redisTemplate.keys(CACHE_PREFIX + userId + ":*");
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
            redisTemplate.delete(SORTED_KEY_PREFIX + userId);
            redisTemplate.delete(SORTED_KEY_PREFIX + userId + SORTED_TEMP_SUFFIX);
        } catch (Exception e) {
            log.debug("清除推荐缓存失败: {}", e.getMessage());
        }
    }

    /**
     * 异步重新计算用户推荐
     * @param userId 用户ID
     */
    @Async
    public void asyncRecompute(Long userId) {
        try {
            log.info("异步重新计算推荐: userId={}", userId);
            List<RecommendComputeService.ScoredBook> scoredBooks = computeService.computeAndSave(userId);
            if (scoredBooks != null) {
                log.info("异步重新计算完成: userId={}, count={}", userId, scoredBooks.size());
            } else {
                log.info("异步重新计算跳过(锁被占用): userId={}", userId);
            }
        } catch (Exception e) {
            log.error("异步重新计算失败: userId={}", userId, e);
        }
    }

    /**
     * SSE 推荐生成（带进度推送）：规则匹配 + 探索发现，实时推送进度
     * @param userId 用户ID
     * @param emitter SSE 发射器
     */
    public void generateWithProgress(Long userId, SseEmitter emitter) {
        try {
            sendProgress(emitter, "loading", "正在加载用户数据...", 0, 0, 0);

            User user = userService.getUserById(userId);
            List<Long> readBookIds = getReadBookIds(userId);
            Set<Long> excludeSet = new HashSet<>(readBookIds);
            excludeSet.addAll(bookTrashService.getTrashedBookIds(userId));

            sendProgress(emitter, "loading", "正在加载书籍数据...", 5, 0, 0);
            List<Book> allBooks = bookRepository.findAll();
            int totalBooks = allBooks.size();

            sendProgress(emitter, "matching", "正在计算匹配度...", 10, 0, totalBooks);

            List<String> excludedTags = getExcludedTags(userId);
            List<String> excludedAuthors = getExcludedAuthors(userId);
            List<String> excludedFormats = getExcludedFormats(userId);
            List<String> includedTags = getIncludedTags(userId);
            List<String> includedAuthors = getIncludedAuthors(userId);
            List<String> includedFormats = getIncludedFormats(userId);

            double ruleMinScore = coefficientService.getCoefficient("OTHER", "rule_min_score", -0.5);

            List<RecommendComputeService.ScoredBook> scoredBooks = new ArrayList<>();
            int batchSize = 500;
            int processed = 0;

            for (int i = 0; i < totalBooks; i += batchSize) {
                int end = Math.min(i + batchSize, totalBooks);
                for (int j = i; j < end; j++) {
                    Book book = allBooks.get(j);
                    if (excludeSet.contains(book.getId())) continue;
                    if (isExcludedByPreference(book, excludedTags, excludedAuthors, excludedFormats)) continue;

                    double matchScore = RecommendMatchCalculator.calculateMatchScore(user, book, coefficientService, objectMapper, dimensionStatsService);
                    if (matchScore <= ruleMinScore) continue;

                    double qualityBonus = calculateQualityBonus(book.getRating());
                    double freshnessBonus = calculateFreshnessBonus(book.getCreatedAt());
                    double preferenceBonus = calculateIncludeBonus(book, includedTags, includedAuthors, includedFormats);
                    double rawFinalScore = matchScore + qualityBonus + freshnessBonus + preferenceBonus;
                    double finalScore = RecommendMatchCalculator.normalizeScore(rawFinalScore);

                    scoredBooks.add(new RecommendComputeService.ScoredBook(book, finalScore, matchScore, qualityBonus, "RULE"));
                }
                processed = end;
                int progressPercent = 10 + (int) (60.0 * processed / totalBooks);
                sendProgress(emitter, "matching", "正在计算匹配度...", progressPercent, processed, totalBooks);
            }

            sendProgress(emitter, "exploring", "正在探索更多书籍...", 72, 0, 0);
            addExploreBooks(user, excludeSet, scoredBooks);

            sendProgress(emitter, "sorting", "正在排序推荐结果...", 80, 0, 0);
            scoredBooks.sort((a, b) -> Double.compare(b.finalScore(), a.finalScore()));

            sendProgress(emitter, "saving", "正在保存推荐结果...", 90, 0, 0);
            computeService.saveToSortedSetDirect(userId, scoredBooks);

            sendProgress(emitter, "done", "推荐生成完成", 100, scoredBooks.size(), totalBooks);

            List<RecommendedItem> topItems = buildTopItems(scoredBooks, 60);
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(objectMapper.writeValueAsString(topItems)));
            emitter.complete();

            log.info("SSE推荐生成完成: userId={}, total={}", userId, scoredBooks.size());
        } catch (Exception e) {
            log.error("SSE推荐生成失败: userId={}", userId, e);
            SseHelper.sendErrorAndComplete(emitter, "推荐生成失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
    }

    /** 添加探索书籍：随机书籍 + 热门书籍，避免信息茧房 */
    private void addExploreBooks(User user, Set<Long> excludeSet, List<RecommendComputeService.ScoredBook> scoredBooks) {
        int exploreRandomCount = (int) coefficientService.getCoefficient("OTHER", "explore_random_count", 30);
        Set<Long> existingIds = scoredBooks.stream()
                .map(RecommendComputeService.ScoredBook::book)
                .map(Book::getId)
                .collect(Collectors.toSet());

        int randomCount = (int) (exploreRandomCount * 0.6);
        List<Book> randomBooks = bookRepository.findRandomBooks(randomCount * 2);
        int added = 0;
        for (Book book : randomBooks) {
            if (excludeSet.contains(book.getId()) || existingIds.contains(book.getId())) continue;
            double baseScore = 0.3 + RecommendMatchCalculator.calculateMatchScore(user, book, coefficientService, objectMapper, dimensionStatsService) * 0.3;
            scoredBooks.add(new RecommendComputeService.ScoredBook(book, baseScore, baseScore, 0.0, "EXPLORE"));
            existingIds.add(book.getId());
            added++;
            if (added >= randomCount) break;
        }

        int hotCount = (int) (exploreRandomCount * 0.4);
        List<Book> hotBooks = bookRepository.findAllByOrderByReadCountDesc(org.springframework.data.domain.PageRequest.of(0, hotCount * 3)).getContent();
        added = 0;
        for (Book book : hotBooks) {
            if (excludeSet.contains(book.getId()) || existingIds.contains(book.getId())) continue;
            double baseScore = 0.3 + RecommendMatchCalculator.calculateMatchScore(user, book, coefficientService, objectMapper, dimensionStatsService) * 0.3;
            scoredBooks.add(new RecommendComputeService.ScoredBook(book, baseScore, baseScore, 0.0, "EXPLORE"));
            existingIds.add(book.getId());
            added++;
            if (added >= hotCount) break;
        }
    }

    // ==================== Redis 读取 ====================

    /** 从 Redis Sorted Set 构建 RecommendedItem 列表 */
    private List<RecommendedItem> buildItemsFromSortedSet(String sortedKey, Set<Object> bookIds) {
        List<RecommendedItem> items = new ArrayList<>();
        for (Object idObj : bookIds) {
            long bookId;
            if (idObj instanceof Integer) bookId = ((Integer) idObj).longValue();
            else if (idObj instanceof Long) bookId = (Long) idObj;
            else {
                try { bookId = Long.parseLong(String.valueOf(idObj)); }
                catch (NumberFormatException e) { continue; }
            }

            Book book = bookRepository.findById(bookId).orElse(null);
            if (book == null) continue;

            Double score = redisTemplate.opsForZSet().score(sortedKey, bookId);
            double matchScore = score != null ? score : 0.0;

            items.add(RecommendedItem.builder()
                    .bookId(book.getId())
                    .title(book.getTitle())
                    .author(book.getAuthor())
                    .coverUrl(book.getCoverUrl())
                    .format(book.getFormat())
                    .rating(book.getRating())
                    .readCount(book.getReadCount())
                    .formatTags(book.getFormatTags())
                    .fileSize(book.getFileSize())
                    .description(book.getDescription() != null && book.getDescription().length() > 80
                            ? book.getDescription().substring(0, 80) + "..." : book.getDescription())
                    .matchScore(Math.round(matchScore * 100.0) / 100.0)
                    .recommendedAt(LocalDateTime.now())
                    .build());
        }
        return items;
    }

    /** 从评分书籍列表构建推荐项（限制同一作者最多出现次数） */
    private List<RecommendedItem> buildTopItems(List<RecommendComputeService.ScoredBook> scoredBooks, int count) {
        List<RecommendedItem> result = new ArrayList<>();
        int maxSameAuthor = (int) coefficientService.getCoefficient("OTHER", "max_same_author", 2);
        Map<String, Integer> authorCount = new HashMap<>();

        for (RecommendComputeService.ScoredBook sb : scoredBooks) {
            String author = sb.book().getAuthor() != null ? sb.book().getAuthor() : "未知";
            int currentCount = authorCount.getOrDefault(author, 0);
            if (currentCount >= maxSameAuthor) continue;

            authorCount.put(author, currentCount + 1);
            result.add(RecommendedItem.builder()
                    .bookId(sb.book().getId())
                    .title(sb.book().getTitle())
                    .author(sb.book().getAuthor())
                    .coverUrl(sb.book().getCoverUrl())
                    .format(sb.book().getFormat())
                    .rating(sb.book().getRating())
                    .readCount(sb.book().getReadCount())
                    .formatTags(sb.book().getFormatTags())
                    .fileSize(sb.book().getFileSize())
                    .description(sb.book().getDescription() != null && sb.book().getDescription().length() > 80
                            ? sb.book().getDescription().substring(0, 80) + "..." : sb.book().getDescription())
                    .matchScore(Math.round(sb.finalScore() * 100.0) / 100.0)
                    .recommendedAt(LocalDateTime.now())
                    .build());

            if (result.size() >= count) break;
        }
        return result;
    }

    // ==================== SSE 进度 ====================

    /** 通过 SSE 发送推荐生成进度 */
    private void sendProgress(SseEmitter emitter, String stage, String message,
                               int progress, int current, int total) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("stage", stage);
            data.put("message", message);
            data.put("progress", progress);
            if (current > 0) data.put("current", current);
            if (total > 0) data.put("total", total);
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(objectMapper.writeValueAsString(data)));
        } catch (IOException e) {
            log.debug("SSE进度发送失败: {}", e.getMessage());
        }
    }

    // ==================== 辅助方法 ====================

    /** 计算书籍质量加分（基于评分） */
    private double calculateQualityBonus(Double rating) {
        if (rating == null || rating <= 0) return -0.05;
        if (rating < 2.0) return -0.15 + (rating - 1.0) * 0.07;
        else if (rating < 3.0) return -0.08 + (rating - 2.0) * 0.06;
        else if (rating < 4.0) return -0.02 + (rating - 3.0) * 0.06;
        else return 0.04 + (rating - 4.0) * 0.06;
    }

    /** 计算新鲜度加分（基于入库时间，7天内最高0.05，30天内递减） */
    private double calculateFreshnessBonus(LocalDateTime createdAt) {
        if (createdAt == null) return 0;
        long daysAgo = java.time.temporal.ChronoUnit.DAYS.between(createdAt, LocalDateTime.now());
        if (daysAgo < 0) daysAgo = 0;
        if (daysAgo <= 7) return 0.05 * (1.0 - (double) daysAgo / 7);
        else if (daysAgo <= 30) return 0.02 * (1.0 - (double) (daysAgo - 7) / 23);
        return 0;
    }

    /** 计算用户偏好加分（标签/作者/格式 INCLUDE 偏好） */
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

    /** 计算用户偏好减分（标签/作者/格式 EXCLUDE 偏好） */
    private double calculateExcludePenalty(Book book, List<String> excludedTags,
                                           List<String> excludedAuthors, List<String> excludedFormats) {
        double tagPenalty = coefficientService.getCoefficient("PREFERENCE", "tag_penalty", 0.12);
        double authorPenalty = coefficientService.getCoefficient("PREFERENCE", "author_penalty", 0.15);
        double formatPenalty = coefficientService.getCoefficient("PREFERENCE", "format_penalty", 0.05);

        double penalty = 0.0;
        if (!excludedTags.isEmpty() && book.getFormatTags() != null) {
            Set<String> bookTags = parseTags(book.getFormatTags());
            for (String tag : excludedTags) {
                if (bookTags.stream().anyMatch(t -> t.equalsIgnoreCase(tag))) penalty -= tagPenalty;
            }
        }
        if (!excludedAuthors.isEmpty() && book.getAuthor() != null) {
            for (String author : excludedAuthors) {
                if (author.equalsIgnoreCase(book.getAuthor())) { penalty -= authorPenalty; break; }
            }
        }
        if (!excludedFormats.isEmpty() && book.getFormat() != null) {
            for (String format : excludedFormats) {
                if (format.equalsIgnoreCase(book.getFormat())) { penalty -= formatPenalty; break; }
            }
        }
        return penalty;
    }

    /** 判断书籍是否被用户偏好排除 */
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


    /**
     * 记录用户阅读行为（用于协同过滤）
     * @param userId 用户ID
     * @param bookId 书籍ID
     * @param action 行为类型
     * @param weight 权重
     * @param detail 行为详情
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordReadAction(Long userId, Long bookId, String action, Integer weight, String detail) {
        try {
            Optional<UserReadHistory> existing = readHistoryRepository.findByUserIdAndBookIdAndAction(userId, bookId, action);
            if (existing.isPresent()) {
                UserReadHistory history = existing.get();
                history.setWeight(weight != null ? weight : 1);
                history.setActionDetail(detail);
                readHistoryRepository.save(history);
            } else {
                UserReadHistory history = UserReadHistory.builder()
                        .userId(userId)
                        .bookId(bookId)
                        .action(action)
                        .weight(weight != null ? weight : 1)
                        .actionDetail(detail)
                        .build();
                readHistoryRepository.save(history);
            }
        } catch (Exception e) {
            log.warn("记录阅读行为失败: userId={}, bookId={}, action={} - {}", userId, bookId, action, e.getMessage());
        }
    }

    /** 获取用户已读/已交互的书籍ID列表 */
    private List<Long> getReadBookIds(Long userId) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.addAll(readHistoryRepository.findAllInteractedBookIdsByUserId(userId));
        ids.addAll(progressRepository.findAllBookIdsByUserId(userId));
        return new ArrayList<>(ids);
    }

    /** 获取用户排除的标签偏好列表 */
    private List<String> getExcludedTags(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "TAG", "EXCLUDE")
                .stream().map(UserBookPreference::getValue).toList();
    }

    /** 获取用户排除的作者偏好列表 */
    private List<String> getExcludedAuthors(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "AUTHOR", "EXCLUDE")
                .stream().map(UserBookPreference::getValue).toList();
    }

    /** 获取用户排除的格式偏好列表 */
    private List<String> getExcludedFormats(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "FORMAT", "EXCLUDE")
                .stream().map(UserBookPreference::getValue).toList();
    }

    /** 获取用户偏好的标签列表 */
    private List<String> getIncludedTags(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "TAG", "INCLUDE")
                .stream().map(UserBookPreference::getValue).toList();
    }

    /** 获取用户偏好的作者列表 */
    private List<String> getIncludedAuthors(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "AUTHOR", "INCLUDE")
                .stream().map(UserBookPreference::getValue).toList();
    }

    /** 获取用户偏好的格式列表 */
    private List<String> getIncludedFormats(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "FORMAT", "INCLUDE")
                .stream().map(UserBookPreference::getValue).toList();
    }

    /** 解析书籍标签字符串为 Set（兼容 JSON 数组和逗号分隔格式） */
    private Set<String> parseTags(String formatTags) {
        if (formatTags == null || formatTags.isBlank()) return Set.of();
        return Arrays.stream(formatTags.replaceAll("[\\[\\]\"]", "").split("[,，]"))
                .map(String::trim)
                .filter(t -> !t.isBlank())
                .collect(Collectors.toSet());
    }
}
