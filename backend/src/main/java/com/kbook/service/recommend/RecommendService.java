package com.kbook.service.recommend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.util.SseHelper;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.config.annotation.RedisLock;
import com.kbook.dto.book.BookProjection;
import com.kbook.dto.recommend.RecommendedItem;
import com.kbook.entity.Book;
import com.kbook.entity.User;
import com.kbook.entity.UserBookPreference;
import com.kbook.entity.UserReadHistory;
import com.kbook.repository.BookRepository;
import com.kbook.repository.ReadingProgressRepository;
import com.kbook.repository.UserBookPreferenceRepository;
import com.kbook.repository.UserReadHistoryRepository;
import com.kbook.service.book.BookTrashService;
import com.kbook.service.tools.DimensionStatsService;
import com.kbook.service.user.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;

import static com.kbook.common.util.QueryBuilder.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.IntConsumer;
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
@LogModule("推荐")
public class RecommendService {

    private final BookRepository bookRepository;
    private final ReadingProgressRepository progressRepository;
    private final UserReadHistoryRepository readHistoryRepository;
    private final UserService userService;
    private final UserBookPreferenceRepository preferenceRepository;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RecommendCoefficientService coefficientService;
    private final DimensionStatsService dimensionStatsService;
    private final BookTrashService bookTrashService;

    public RecommendService(
            BookRepository bookRepository,
            ReadingProgressRepository progressRepository,
            UserReadHistoryRepository readHistoryRepository,
            UserService userService,
            UserBookPreferenceRepository preferenceRepository,
            ObjectMapper objectMapper,
            RedisTemplate<String, Object> redisTemplate,
            @Lazy RecommendCoefficientService coefficientService,
            @Lazy DimensionStatsService dimensionStatsService,
            @Lazy BookTrashService bookTrashService
    ) {
        this.bookRepository = bookRepository;
        this.progressRepository = progressRepository;
        this.readHistoryRepository = readHistoryRepository;
        this.userService = userService;
        this.preferenceRepository = preferenceRepository;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.coefficientService = coefficientService;
        this.dimensionStatsService = dimensionStatsService;
        this.bookTrashService = bookTrashService;
    }

    /** 推荐缓存键前缀 */
    private static final String CACHE_PREFIX = "kbook:recommend:";
    /** 推荐排序集合键前缀 */
    private static final String SORTED_KEY_PREFIX = "kbook:recommend:sorted:";
    /** 临时排序集合键后缀 */
    private static final String SORTED_TEMP_SUFFIX = ":temp";
    /** 重算标识键前缀 */
    private static final String RESTART_KEY_PREFIX = "kbook:recommend:restart:";

    /**
     * 批量计算用户与多本书的匹配度得分（带缓存）
     * @param userId 用户ID
     * @param bookIds 书籍ID列表
     * @return bookId → 匹配度得分 的映射
     */
    @LogAction("批量计算匹配度")
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

        Map<Long, Double> result = new LinkedHashMap<>();
        for (Long bookId : bookIds) {
            BookProjection book = bookRepository.findProjectedById(bookId).orElse(null);
            if (book == null) continue;
            double score = this.computeFullScore(user, book, userId);
            double roundedScore = Math.round(score * 100.0) / 100.0;
            result.put(bookId, roundedScore);
        }

        return result;
    }

    /**
     * 获取个性化推荐列表（优先从 Redis Sorted Set 读取）
     * @param userId 用户ID
     * @param count 返回数量
     * @return 推荐项列表
     */
    @LogAction("获取个性化推荐")
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

        List<ScoredBook> scoredBooks = this.computeAndSave(userId);
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
    @LogAction("分页获取推荐")
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
    @LogAction("清除推荐缓存")
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
     * 从推荐有序集合中移除指定图书
     * @param userId 用户ID
     * @param bookId 图书ID
     */
    @LogAction("移除推荐图书")
    public void removeSingleBook(Long userId, Long bookId) {
        try {
            redisTemplate.opsForZSet().remove(SORTED_KEY_PREFIX + userId, bookId);
        } catch (Exception e) {
            log.debug("从推荐Sorted Set移除图书失败: {}", e.getMessage());
        }
    }

    /**
     * 异步重新计算用户推荐
     * <p>
     * 设置重算标识（中断当前正在进行的计算），然后调用 computeAndSave。
     * computeAndSave 内部会复用图书数据、检测重算标识并自动重新评分。
     * 若锁被占用则等待重试。
     *
     * @param userId 用户ID
     */
    @Async
    @LogAction("异步重算推荐")
    public void asyncRecompute(Long userId) {
        try {
            log.info("异步重新计算推荐: userId={}", userId);
            String restartKey = RESTART_KEY_PREFIX + userId;
            redisTemplate.opsForValue().set(restartKey, "1", 10, java.util.concurrent.TimeUnit.MINUTES);

            int maxRetries = 10;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                List<ScoredBook> result = this.computeAndSave(userId);
                if (result != null) {
                    log.info("异步重新计算完成: userId={}, count={}", userId, result.size());
                    return;
                }
                if (attempt < maxRetries) {
                    log.info("锁被占用，等待后重试: userId={}, attempt={}/{}", userId, attempt, maxRetries);
                    Thread.sleep(2000);
                }
            }
            log.warn("异步重新计算最终放弃(锁被占用超过{}次): userId={}", maxRetries, userId);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("异步重新计算被中断: userId={}", userId);
        } catch (Exception e) {
            log.error("异步重新计算失败: userId={}", userId, e);
        }
    }

    /**
     * SSE 推荐生成（带进度推送）：规则匹配 + 探索发现，实时推送进度
     * <p>
     * 图书数据只查询一次。若计算过程中检测到画像变更，复用已有图书数据重新评分，
     * 并通过 SSE 推送重启进度。
     *
     * @param userId 用户ID
     * @param emitter SSE 发射器
     */
    @LogAction("SSE推荐生成")
    public void generateWithProgress(Long userId, SseEmitter emitter) {
        try {
            sendProgress(emitter, "loading", "正在加载书籍数据...", 0, 0, 0);

            String restartKey = RESTART_KEY_PREFIX + userId;
            redisTemplate.delete(restartKey);

            List<BookProjection> allBooks = bookRepository.findAllProjectedByOrderByIdAsc();
            int intTotalBooks = allBooks.size();

            int maxRestarts = 5;
            for (int restartCount = 0; restartCount <= maxRestarts; restartCount++) {
                if (restartCount > 0) {
                    redisTemplate.delete(restartKey);
                    sendProgress(emitter, "restarting", "检测到画像变更，正在重新计算...", 5, 0, intTotalBooks);
                }

                List<ScoredBook> scoredBooks = scoreAllBooks(userId, allBooks, restartKey, processed -> {
                    int progressPercent = 10 + (int) (60.0 * processed / Math.max(intTotalBooks, 1));
                    sendProgress(emitter, "matching", "正在计算匹配度...", progressPercent, processed, intTotalBooks);
                });

                if (scoredBooks == null) {
                    continue;
                }

                sendProgress(emitter, "saving", "正在保存推荐结果...", 90, 0, 0);
                this.saveToSortedSetDirect(userId, scoredBooks);

                sendProgress(emitter, "done", "推荐生成完成", 100, scoredBooks.size(), intTotalBooks);

                emitter.complete();

                log.info("SSE推荐生成完成: userId={}, total={}, restarts={}", userId, scoredBooks.size(), restartCount);
                return;
            }

            SseHelper.sendErrorAndComplete(emitter, "推荐计算重启次数过多，请稍后重试");
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
    private List<RecommendedItem> buildTopItems(List<ScoredBook> scoredBooks, int count) {
        List<RecommendedItem> result = new ArrayList<>();
        int maxSameAuthor = (int) coefficientService.getCoefficient("OTHER", "max_same_author", 2);
        Map<String, Integer> authorCount = new HashMap<>();

        for (ScoredBook sb : scoredBooks) {
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

    // ==================== 推荐计算核心逻辑 ====================

    /**
     * 评分书籍记录类
     */
    public record ScoredBook(BookProjection book, double finalScore, double matchScore, double qualityBonus,
                              String recallPath) {}

    /**
     * 计算并保存用户推荐列表（带分布式锁）
     * <p>
     * 图书数据只查询一次。若计算过程中检测到画像变更（重算标识），
     * 清空临时评分结果，用最新画像对同一批图书重新评分，不重复查库。
     */
    @RedisLock(key = "'kbook:lock:recommend:' + #userId", leaseTime = 600)
    @LogAction("计算并保存推荐")
    public List<ScoredBook> computeAndSave(Long userId) {
        log.info("获取锁成功，开始计算推荐: userId={}", userId);
        long startTime = System.currentTimeMillis();

        String restartKey = RESTART_KEY_PREFIX + userId;
        redisTemplate.delete(restartKey);

        List<BookProjection> allBooks = bookRepository.findAllProjectedByOrderByIdAsc();
        int total = allBooks.size();

        int maxRestarts = 5;
        for (int restartCount = 0; restartCount <= maxRestarts; restartCount++) {
            if (restartCount > 0) {
                redisTemplate.delete(restartKey);
                log.info("检测到画像变更，复用已有图书数据重新评分: userId={}, restartCount={}", userId, restartCount);
            }

            List<ScoredBook> scoredBooks = scoreAllBooks(userId, allBooks, restartKey, null);

            if (scoredBooks == null) {
                continue;
            }

            saveToSortedSetWithTemp(userId, scoredBooks);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("推荐计算完成: userId={}, count={}, elapsed={}ms, restarts={}",
                    userId, scoredBooks.size(), elapsed, restartCount);
            return scoredBooks;
        }

        log.warn("推荐计算重启次数过多: userId={}", userId);
        return null;
    }

    /**
     * 计算用户推荐评分（内部辅助方法）
     * 从数据库加载全部图书，执行评分逻辑
     *
     * @param userId     用户ID
     * @param restartKey 重算标识key，用于检测画像变更
     * @return 评分结果列表
     */
    private List<ScoredBook> computeScoredBooksWithRestart(Long userId, String restartKey) {
        List<BookProjection> allBooks = bookRepository.findAllProjectedByOrderByIdAsc();
        return scoreAllBooks(userId, allBooks, restartKey, null);
    }

    /**
     * 对给定的图书列表执行评分（可被 restartKey 中断后重新调用，不重复查库）
     *
     * @param userId       用户ID
     * @param allBooks     已加载的图书列表（复用，不重复查库）
     * @param restartKey   重算标识 key，非 null 时每 chunk 检查
     * @param onProgress   进度回调（SSE 用），可为 null
     * @return 评分结果；若被中断返回 null
     */
    private List<ScoredBook> scoreAllBooks(Long userId, List<BookProjection> allBooks,
                                            String restartKey, IntConsumer onProgress) {
        User user = userService.getUserById(userId);
        List<Long> readBookIds = getReadBookIds(userId);
        Set<Long> excludeSet = new HashSet<>(readBookIds);
        excludeSet.addAll(bookTrashService.getTrashedBookIds(userId));

        List<String> excludedTags = getExcludedTags(userId);
        List<String> excludedAuthors = getExcludedAuthors(userId);
        List<String> excludedFormats = getExcludedFormats(userId);
        List<String> includedTags = getIncludedTags(userId);
        List<String> includedAuthors = getIncludedAuthors(userId);
        List<String> includedFormats = getIncludedFormats(userId);

        double ruleMinScore = coefficientService.getCoefficient("OTHER", "rule_min_score", -0.5);

        List<ScoredBook> scoredBooks = Collections.synchronizedList(new ArrayList<>());
        int total = allBooks.size();

        int chunkSize = 2000;
        for (int offset = 0; offset < total; offset += chunkSize) {
            if (restartKey != null) {
                Boolean hasRestart = redisTemplate.hasKey(restartKey);
                if (Boolean.TRUE.equals(hasRestart)) {
                    log.info("检测到重算标识，中断当前评分: userId={}, processed={}/{}", userId, offset, total);
                    return null;
                }
            }

            int end = Math.min(offset + chunkSize, total);
            List<BookProjection> chunk = allBooks.subList(offset, end);

            List<ScoredBook> chunkResults = chunk.parallelStream()
                    .map(book -> scoreBook(user, book, excludeSet,
                            excludedTags, excludedAuthors, excludedFormats,
                            includedTags, includedAuthors, includedFormats, ruleMinScore))
                    .filter(Objects::nonNull)
                    .toList();

            scoredBooks.addAll(chunkResults);

            if (onProgress != null) {
                onProgress.accept(end);
            }
        }

        if (onProgress != null) {
            onProgress.accept(total);
        }

        addExploreBooks(user, excludeSet, scoredBooks);

        scoredBooks.sort((a, b) -> Double.compare(b.finalScore, a.finalScore));
        return scoredBooks;
    }


    /**
     * 添加探索发现书籍到推荐列表
     * 包括随机采样书籍和热门书籍，用于拓展用户视野
     * 探索书籍的得分上限设为正常推荐最低分的40%，确保排在规则匹配之后
     *
     * @param user        用户实体
     * @param excludeSet  需要排除的书籍ID集合（已读/回收站）
     * @param scoredBooks 已评分书籍列表（会被修改，添加探索书籍）
     */
    private void addExploreBooks(User user, Set<Long> excludeSet, List<ScoredBook> scoredBooks) {
        int exploreRandomCount = (int) coefficientService.getCoefficient("OTHER", "explore_random_count", 30);
        Set<Long> existingIds = scoredBooks.stream()
                .map(sb -> sb.book.getId())
                .collect(Collectors.toSet());

        // 探索书籍得分上限设为正常推荐最低分的 40%，确保排在后面
        double minRuleScore = scoredBooks.stream()
                .filter(sb -> "RULE".equals(sb.recallPath()))
                .mapToDouble(ScoredBook::finalScore)
                .min()
                .orElse(0.2);
        double exploreMaxScore = Math.max(0.08, minRuleScore * 0.4);

        int randomCount = (int) (exploreRandomCount * 0.6);
        List<Book> randomBooks = bookRepository.findRandomBooks(randomCount * 2);
        int added = 0;
        for (Book book : randomBooks) {
            if (excludeSet.contains(book.getId()) || existingIds.contains(book.getId())) continue;
            BookProjection bp = BookProjection.from(book);
            double matchScore = RecommendMatchCalculator.calculateMatchScore(user, bp, coefficientService, objectMapper, dimensionStatsService);
            double exploreScore = 0.05 + matchScore * exploreMaxScore * 0.9;
            scoredBooks.add(new ScoredBook(bp, exploreScore, matchScore, 0.0, "EXPLORE"));
            existingIds.add(book.getId());
            added++;
            if (added >= randomCount) break;
        }

        int hotCount = (int) (exploreRandomCount * 0.4);
        List<Book> hotBooks = bookRepository.findAllByOrderByReadCountDesc(PageRequest.of(0, hotCount * 3)).getContent();
        added = 0;
        for (Book book : hotBooks) {
            if (excludeSet.contains(book.getId()) || existingIds.contains(book.getId())) continue;
            BookProjection bp = BookProjection.from(book);
            double matchScore = RecommendMatchCalculator.calculateMatchScore(user, bp, coefficientService, objectMapper, dimensionStatsService);
            double exploreScore = 0.06 + matchScore * exploreMaxScore * 0.94;
            scoredBooks.add(new ScoredBook(bp, exploreScore, matchScore, 0.0, "EXPLORE"));
            existingIds.add(book.getId());
            added++;
            if (added >= hotCount) break;
        }
    }

    /**
     * 通过临时Set写入推荐结果（原子操作）
     * 先写入临时key，再删除旧key并重命名，避免读取时出现空窗口
     *
     * @param userId      用户ID
     * @param scoredBooks 评分书籍列表
     */
    private void saveToSortedSetWithTemp(Long userId, List<ScoredBook> scoredBooks) {
        try {
            String tempKey = SORTED_KEY_PREFIX + userId + SORTED_TEMP_SUFFIX;
            String realKey = SORTED_KEY_PREFIX + userId;

            redisTemplate.delete(tempKey);
            for (ScoredBook sb : scoredBooks) {
                redisTemplate.opsForZSet().add(tempKey, sb.book.getId(), sb.finalScore);
            }
            redisTemplate.delete(realKey);
            redisTemplate.rename(tempKey, realKey);
        } catch (Exception e) {
            log.debug("写入推荐Sorted Set(temp)失败: {}", e.getMessage());
        }
    }

    /**
     * 直接写入推荐结果到Sorted Set（用于SSE推送场景）
     * 删除旧key后重新写入，适用于实时推荐生成
     *
     * @param userId      用户ID
     * @param scoredBooks 评分书籍列表
     */
    void saveToSortedSetDirect(Long userId, List<ScoredBook> scoredBooks) {
        try {
            String sortedKey = SORTED_KEY_PREFIX + userId;
            redisTemplate.delete(sortedKey);
            for (ScoredBook sb : scoredBooks) {
                redisTemplate.opsForZSet().add(sortedKey, sb.book.getId(), sb.finalScore);
            }
        } catch (Exception e) {
            log.debug("写入推荐Sorted Set失败: {}", e.getMessage());
        }
    }

    /**
     * 计算单本图书的推荐得分并加入推荐列表
     * 用于新书入库或手动刷新时的增量计算
     *
     * @param userId 用户ID
     * @param bookId 书籍ID
     */
    @LogAction("计算单本图书推荐得分")
    public void computeAndAddSingleBook(Long userId, Long bookId) {
        try {
            User user = userService.getUserById(userId);
            Book book = bookRepository.findById(bookId).orElse(null);
            if (book == null) return;

            BookProjection bp = BookProjection.from(book);

            Set<Long> excludeSet = new HashSet<>(getReadBookIds(userId));

            List<String> excludedTags = getExcludedTags(userId);
            List<String> excludedAuthors = getExcludedAuthors(userId);
            List<String> excludedFormats = getExcludedFormats(userId);
            List<String> includedTags = getIncludedTags(userId);
            List<String> includedAuthors = getIncludedAuthors(userId);
            List<String> includedFormats = getIncludedFormats(userId);

            double ruleMinScore = coefficientService.getCoefficient("OTHER", "rule_min_score", -0.5);

            ScoredBook sb = scoreBook(user, bp, excludeSet,
                    excludedTags, excludedAuthors, excludedFormats,
                    includedTags, includedAuthors, includedFormats, ruleMinScore);
            if (sb == null) return;

            redisTemplate.opsForZSet().add(SORTED_KEY_PREFIX + userId, bookId, sb.finalScore);

            log.info("单本图书推荐得分计算完成: userId={}, bookId={}, score={}", userId, bookId, sb.finalScore);
        } catch (Exception e) {
            log.debug("单本图书推荐得分计算失败: userId={}, bookId={}, error={}", userId, bookId, e.getMessage());
        }
    }

    /**
     * 计算用户与书籍的完整匹配度得分（含新鲜度和偏好加成）
     * 用于批量计算和单本书计算场景
     *
     * @param user 用户实体
     * @param book 书籍投影
     * @param userId 用户ID（用于获取偏好）
     * @return 最终匹配度得分（0~1）
     */
    @LogAction("计算图书匹配度得分")
    public double computeFullScore(User user, BookProjection book, Long userId) {
        List<String> includedTags = getIncludedTags(userId);
        List<String> includedAuthors = getIncludedAuthors(userId);
        List<String> includedFormats = getIncludedFormats(userId);

        double matchScore = RecommendMatchCalculator.calculateMatchScore(user, book, coefficientService, objectMapper, dimensionStatsService);
        double freshnessBonus = calculateFreshnessBonus(book.getCreatedAt());
        double preferenceBonus = calculateIncludeBonus(book, includedTags, includedAuthors, includedFormats);

        // matchScore 已包含评分维度（权重0.6），新鲜度和偏好作为辅助微调
        double finalScore = (matchScore * 0.90) + (freshnessBonus * 0.05) + (preferenceBonus * 0.05);
        if (finalScore > 1.0) finalScore = 1.0;
        if (finalScore < 0.0) finalScore = 0.0;
        return finalScore;
    }

    /**
     * 对单本书进行评分计算
     * 核心评分逻辑：匹配度×0.9 + 新鲜度×0.05 + 偏好加成×0.05
     *
     * @param user               用户实体
     * @param book               书籍投影
     * @param excludeSet         排除的书籍ID集合
     * @param excludedTags       排除的标签
     * @param excludedAuthors    排除的作者
     * @param excludedFormats    排除的格式
     * @param includedTags       偏好标签
     * @param includedAuthors    偏好作者
     * @param includedFormats    偏好格式
     * @param ruleMinScore       规则召回最低匹配分阈值
     * @return 评分结果，不符合条件时返回null
     */
    private ScoredBook scoreBook(User user, BookProjection book,
                                  Set<Long> excludeSet,
                                  List<String> excludedTags, List<String> excludedAuthors, List<String> excludedFormats,
                                  List<String> includedTags, List<String> includedAuthors, List<String> includedFormats,
                                  double ruleMinScore) {
        if (excludeSet.contains(book.getId())) return null;
        if (isExcludedByPreference(book, excludedTags, excludedAuthors, excludedFormats)) return null;

        double matchScore = RecommendMatchCalculator.calculateMatchScore(user, book, coefficientService, objectMapper, dimensionStatsService);
        if (matchScore <= ruleMinScore) return null;

        double freshnessBonus = calculateFreshnessBonus(book.getCreatedAt());
        double preferenceBonus = calculateIncludeBonus(book, includedTags, includedAuthors, includedFormats);
        double finalScore = (matchScore * 0.90) + (freshnessBonus * 0.05) + (preferenceBonus * 0.05);
        if (finalScore > 1.0) finalScore = 1.0;
        if (finalScore < 0.0) finalScore = 0.0;

        return new ScoredBook(book, finalScore, matchScore, 0.0, "RULE");
    }

    /**
     * 计算图书质量加分
     * 基于评分分段计算：低分压制，高分加成
     *
     * @param rating 图书评分（1~5分）
     * @return 质量加分值
     */
    private double calculateQualityBonus(Double rating) {
        if (rating == null || rating <= 0) return -0.05;
        if (rating < 2.0) return -0.15 + (rating - 1.0) * 0.07;
        else if (rating < 3.0) return -0.08 + (rating - 2.0) * 0.06;
        else if (rating < 4.0) return -0.02 + (rating - 3.0) * 0.06;
        else return 0.04 + (rating - 4.0) * 0.06;
    }

    /**
     * 计算图书新鲜度加分
     * 7天内有加成，30天后衰减为0
     *
     * @param createdAt 图书创建时间
     * @return 新鲜度加分值（0~0.05）
     */
    private double calculateFreshnessBonus(LocalDateTime createdAt) {
        if (createdAt == null) return 0;
        long daysAgo = ChronoUnit.DAYS.between(createdAt, LocalDateTime.now());
        if (daysAgo < 0) daysAgo = 0;
        if (daysAgo <= 7) return 0.05 * (1.0 - (double) daysAgo / 7);
        else if (daysAgo <= 30) return 0.02 * (1.0 - (double) (daysAgo - 7) / 23);
        return 0;
    }

    /**
     * 计算偏好匹配加成
     * 根据用户偏好（标签/作者/格式）与图书属性的匹配度计算加成
     *
     * @param book             书籍投影
     * @param includedTags     偏好标签列表
     * @param includedAuthors  偏好作者列表
     * @param includedFormats  偏好格式列表
     * @return 偏好加成值
     */
    private double calculateIncludeBonus(BookProjection book, List<String> includedTags,
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
                if (author.equalsIgnoreCase(book.getAuthor())) {
                    bonus += authorBonus;
                    break;
                }
            }
        }
        if (!includedFormats.isEmpty() && book.getFormat() != null) {
            for (String format : includedFormats) {
                if (format.equalsIgnoreCase(book.getFormat())) {
                    bonus += formatBonus;
                    break;
                }
            }
        }
        return bonus;
    }

    /**
     * 检查图书是否被偏好排除
     * 匹配排除标签、排除作者、排除格式任一条件即返回true
     *
     * @param book             书籍投影
     * @param excludedTags     排除的标签列表
     * @param excludedAuthors  排除的作者列表
     * @param excludedFormats  排除的格式列表
     * @return true=应排除，false=不排除
     */
    private boolean isExcludedByPreference(BookProjection book, List<String> excludedTags,
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
     * 获取用户已读书籍ID列表
     * 合并阅读历史和阅读进度中的书籍ID（去重）
     *
     * @param userId 用户ID
     * @return 已读书籍ID列表
     */
    private List<Long> getReadBookIds(Long userId) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.addAll(readHistoryRepository.findAllInteractedBookIdsByUserId(userId));
        ids.addAll(progressRepository.findAllBookIdsByUserId(userId));
        return new ArrayList<>(ids);
    }

    /**
     * 获取用户排除的标签偏好
     *
     * @param userId 用户ID
     * @return 排除的标签列表
     */
    private List<String> getExcludedTags(Long userId) {
        return preferenceRepository.query()
                .where(UserBookPreference::getUserId, eq(userId))
                .and(UserBookPreference::getCategory, eq("TAG"))
                .and(UserBookPreference::getType, eq("EXCLUDE"))
                .list().stream().map(UserBookPreference::getValue).toList();
    }

    private List<String> getExcludedAuthors(Long userId) {
        return preferenceRepository.query()
                .where(UserBookPreference::getUserId, eq(userId))
                .and(UserBookPreference::getCategory, eq("AUTHOR"))
                .and(UserBookPreference::getType, eq("EXCLUDE"))
                .list().stream().map(UserBookPreference::getValue).toList();
    }

    private List<String> getExcludedFormats(Long userId) {
        return preferenceRepository.query()
                .where(UserBookPreference::getUserId, eq(userId))
                .and(UserBookPreference::getCategory, eq("FORMAT"))
                .and(UserBookPreference::getType, eq("EXCLUDE"))
                .list().stream().map(UserBookPreference::getValue).toList();
    }

    private List<String> getIncludedTags(Long userId) {
        return preferenceRepository.query()
                .where(UserBookPreference::getUserId, eq(userId))
                .and(UserBookPreference::getCategory, eq("TAG"))
                .and(UserBookPreference::getType, eq("INCLUDE"))
                .list().stream().map(UserBookPreference::getValue).toList();
    }

    private List<String> getIncludedAuthors(Long userId) {
        return preferenceRepository.query()
                .where(UserBookPreference::getUserId, eq(userId))
                .and(UserBookPreference::getCategory, eq("AUTHOR"))
                .and(UserBookPreference::getType, eq("INCLUDE"))
                .list().stream().map(UserBookPreference::getValue).toList();
    }

    private List<String> getIncludedFormats(Long userId) {
        return preferenceRepository.query()
                .where(UserBookPreference::getUserId, eq(userId))
                .and(UserBookPreference::getCategory, eq("FORMAT"))
                .and(UserBookPreference::getType, eq("INCLUDE"))
                .list().stream().map(UserBookPreference::getValue).toList();
    }

    private Set<String> parseTags(String formatTags) {
        if (formatTags == null || formatTags.isBlank()) return Set.of();
        return Arrays.stream(formatTags.replaceAll("[\\[\\]\"]", "").split("[,，]"))
                .map(String::trim)
                .filter(t -> !t.isBlank())
                .collect(Collectors.toSet());
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
    @LogAction("记录阅读行为")
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
