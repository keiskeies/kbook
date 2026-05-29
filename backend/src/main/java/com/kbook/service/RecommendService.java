package com.kbook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.util.SseHelper;
import com.kbook.dto.BookProjection;
import com.kbook.dto.RecommendedItem;
import com.kbook.entity.User;
import com.kbook.entity.UserReadHistory;
import com.kbook.repository.BookRepository;
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
    private final UserReadHistoryRepository readHistoryRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RecommendCoefficientService coefficientService;
    private final MatchScoreCacheService matchScoreCacheService;
    private final RecommendComputeService computeService;
    private final DimensionStatsService dimensionStatsService;

    public RecommendService(
            BookRepository bookRepository,
            UserReadHistoryRepository readHistoryRepository,
            UserService userService,
            ObjectMapper objectMapper,
            RedisTemplate<String, Object> redisTemplate,
            @Lazy RecommendCoefficientService coefficientService,
            MatchScoreCacheService matchScoreCacheService,
            RecommendComputeService computeService,
            DimensionStatsService dimensionStatsService
    ) {
        this.bookRepository = bookRepository;
        this.readHistoryRepository = readHistoryRepository;
        this.userService = userService;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.coefficientService = coefficientService;
        this.matchScoreCacheService = matchScoreCacheService;
        this.computeService = computeService;
        this.dimensionStatsService = dimensionStatsService;
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
                || user.getMarried() != null
                || (user.getChildrenAgeRanges() != null && !user.getChildrenAgeRanges().isBlank())
                || user.getHasChildren() != null
                || user.getMbti() != null || user.getOccupation() != null
                || user.getAspirationEducation() != null || user.getEntrepreneurship() != null
                || user.getAspirationIncome() != null || user.getMood() != null;

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
            BookProjection book = bookRepository.findProjectedById(bookId).orElse(null);
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

    /** 重算标识键前缀 */
    private static final String RESTART_KEY_PREFIX = "kbook:recommend:restart:";

    /**
     * 异步重新计算用户推荐
     * <p>
     * 设置重算标识后调用 computeAndSave。
     * 如果当前有正在进行的计算，它会检测到重算标识并立即中断重来；
     * 如果没有，直接开始计算。
     * 如果锁被占用，等待后重试。
     *
     * @param userId 用户ID
     */
    @Async
    public void asyncRecompute(Long userId) {
        try {
            log.info("异步重新计算推荐: userId={}", userId);
            // 设置重算标识 — 如果正在计算中，会触发其中断并重来
            String restartKey = RESTART_KEY_PREFIX + userId;
            redisTemplate.opsForValue().set(restartKey, "1", 10, java.util.concurrent.TimeUnit.MINUTES);

            int maxRetries = 10;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                List<RecommendComputeService.ScoredBook> scoredBooks = computeService.computeAndSave(userId);
                if (scoredBooks != null) {
                    log.info("异步重新计算完成: userId={}, count={}, attempt={}",
                            userId, scoredBooks.size(), Math.max(attempt, 1));
                    return;
                }
                log.info("锁被占用(已有计算在进行)，等待后重试: userId={}, attempt={}/{}", userId, attempt, maxRetries);
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            log.warn("异步重新计算最终放弃(超过{}次重试): userId={}", maxRetries, userId);
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
            sendProgress(emitter, "loading", "正在加载书籍数据...", 0, 0, 0);

            long totalBooks = bookRepository.count();
            int intTotalBooks = totalBooks > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalBooks;

            List<RecommendComputeService.ScoredBook> scoredBooks = computeService.computeScoredBooks(userId, processed -> {
                int progressPercent = 10 + (int) (60.0 * processed / Math.max(intTotalBooks, 1));
                sendProgress(emitter, "matching", "正在计算匹配度...", progressPercent, processed, intTotalBooks);
            });

            sendProgress(emitter, "saving", "正在保存推荐结果...", 90, 0, 0);
            computeService.saveToSortedSetDirect(userId, scoredBooks);

            sendProgress(emitter, "done", "推荐生成完成", 100, scoredBooks.size(), intTotalBooks);

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

            BookProjection book = bookRepository.findProjectedById(bookId).orElse(null);
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
                    .description(book.getDescription())
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
                    .description(sb.book().getDescription())
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

}
