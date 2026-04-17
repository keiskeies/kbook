package com.kbook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.entity.Book;
import com.kbook.entity.User;
import com.kbook.entity.UserBookPreference;
import com.kbook.entity.UserReadHistory;
import com.kbook.repository.BookRepository;
import com.kbook.repository.ReadingProgressRepository;
import com.kbook.repository.UserBookPreferenceRepository;
import com.kbook.repository.UserReadHistoryRepository;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 推荐服务 — 多路召回 + 评分融合 + MMR 多样性 + Redis 缓存
 * <p>
 * 推荐流程：
 * 1. 路径A - 规则召回：8维度画像匹配（年龄段/性别/婚姻/子女/MBTI）
 * 2. 路径B - 向量召回：Qdrant 语义相似度（书籍元数据 embedding → 用户兴趣 embedding）
 * 3. 路径C - 协同召回：相似用户的阅读行为（UserCF 简化版）
 * 4. 融合排序：加权融合三路得分 + 评分权重 + MMR 去重
 * 5. 过滤：排除已读完 + 同作者最多2本
 * 6. 缓存：Redis 30分钟 TTL
 * <p>
 * 注意：不使用 @RequiredArgsConstructor，需手动注入 @Lazy EmbeddingService 以打破循环依赖。
 */
@Slf4j
@Service
public class RecommendService {

    private final BookRepository bookRepository;
    private final ReadingProgressRepository progressRepository;
    private final UserReadHistoryRepository readHistoryRepository;
    private final UserService userService;
    private final EmbeddingService embeddingService;
    private final UserBookPreferenceRepository preferenceRepository;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    public RecommendService(
            BookRepository bookRepository,
            ReadingProgressRepository progressRepository,
            UserReadHistoryRepository readHistoryRepository,
            UserService userService,
            @Lazy EmbeddingService embeddingService,
            UserBookPreferenceRepository preferenceRepository,
            ObjectMapper objectMapper,
            RedisTemplate<String, Object> redisTemplate
    ) {
        this.bookRepository = bookRepository;
        this.progressRepository = progressRepository;
        this.readHistoryRepository = readHistoryRepository;
        this.userService = userService;
        this.embeddingService = embeddingService;
        this.preferenceRepository = preferenceRepository;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Redis 缓存 key 前缀
     */
    private static final String CACHE_PREFIX = "kbook:recommend:";

    /**
     * 缓存 TTL（分钟）
     */
    private static final int CACHE_TTL_MINUTES = 30;

    /**
     * 三路召回权重
     */
    private static final double WEIGHT_RULE = 0.35;
    private static final double WEIGHT_VECTOR = 0.40;
    private static final double WEIGHT_COLLAB = 0.25;

    /**
     * 评分权重系数
     */
    private static final double RATING_WEIGHT = 0.2;

    /**
     * 同作者最大推荐数
     */
    private static final int MAX_SAME_AUTHOR = 2;

    /**
     * MMR lambda 参数（0=最大多样性，1=最大相关性）
     */
    private static final double MMR_LAMBDA = 0.7;

    // ==================== 公开接口 ====================

    /**
     * 获取个性化推荐（带缓存）
     *
     * @param userId 用户ID
     * @param count  推荐数量
     * @return 推荐结果列表
     */
    public List<RecommendedItem> getPersonalizedRecommendations(Long userId, int count) {
        // 1. 查缓存
        String cacheKey = CACHE_PREFIX + userId + ":" + count;
        try {
            @SuppressWarnings("unchecked")
            List<RecommendedItem> cached = (List<RecommendedItem>) redisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isEmpty()) {
                log.debug("推荐结果命中缓存: userId={}, count={}", userId, cached.size());
                return cached;
            }
        } catch (Exception e) {
            log.debug("读取推荐缓存失败: {}", e.getMessage());
        }

        // 2. 计算推荐
        List<RecommendedItem> result = calculateRecommendations(userId, count);

        // 3. 写缓存
        try {
            redisTemplate.opsForValue().set(cacheKey, result, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.debug("写入推荐缓存失败: {}", e.getMessage());
        }

        return result;
    }

    /**
     * 计算个性化推荐（核心算法）
     */
    private List<RecommendedItem> calculateRecommendations(Long userId, int count) {
        long startTime = System.currentTimeMillis();

        User user = userService.getUserById(userId);

        // 获取用户已交互的图书ID（已读+书架）
        List<Long> readBookIds = getReadBookIds(userId);
        log.debug("用户已交互图书: userId={}, count={}", userId, readBookIds.size());

        // ======= 多路召回 =======
        Map<Long, Double> ruleScores = ruleRecall(user, readBookIds);
        Map<Long, Double> vectorScores = vectorRecall(user, readBookIds);
        Map<Long, Double> collabScores = collaborativeRecall(userId, readBookIds);

        log.debug("多路召回完成: rule={}, vector={}, collab={}",
                ruleScores.size(), vectorScores.size(), collabScores.size());

        // ======= 融合排序 =======
        Map<Long, Double> fusedScores = fuseScores(ruleScores, vectorScores, collabScores);

        // ======= 加载图书信息 + 加上评分权重 + 用户偏好过滤/加权 =======
        // 获取用户排除偏好
        List<String> excludedTags = getExcludedTags(userId);
        List<String> excludedAuthors = getExcludedAuthors(userId);
        List<String> excludedFormats = getExcludedFormats(userId);
        // 获取用户喜欢偏好
        List<String> includedTags = getIncludedTags(userId);
        List<String> includedAuthors = getIncludedAuthors(userId);
        List<String> includedFormats = getIncludedFormats(userId);

        List<ScoredBook> scoredBooks = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : fusedScores.entrySet()) {
            Book book = bookRepository.findById(entry.getKey()).orElse(null);
            if (book == null) continue;

            // 用户偏好过滤：排除用户不想看的书籍
            if (isExcludedByPreference(book, excludedTags, excludedAuthors, excludedFormats)) {
                log.debug("推荐过滤(用户偏好): bookId={}, title={}", book.getId(), book.getTitle());
                continue;
            }

            double fusedScore = entry.getValue();
            double ratingBonus = (book.getRating() != null && book.getRating() > 0)
                    ? (book.getRating() / 5.0) * RATING_WEIGHT : 0;

            // 用户喜欢偏好加权：匹配用户喜欢的标签/作者/格式则加分
            double includeBonus = calculateIncludeBonus(book, includedTags, includedAuthors, includedFormats);

            double finalScore = fusedScore + ratingBonus + includeBonus;

            scoredBooks.add(new ScoredBook(book, finalScore, ruleScores.getOrDefault(book.getId(), 0.0),
                    vectorScores.getOrDefault(book.getId(), 0.0),
                    collabScores.getOrDefault(book.getId(), 0.0)));
        }

        // ======= 排序 =======
        scoredBooks.sort((a, b) -> Double.compare(b.finalScore, a.finalScore));

        // ======= MMR 多样性打散 =======
        List<ScoredBook> diverseBooks = mmrDiversify(scoredBooks, count * 2);

        // ======= 同作者限制 + 取最终结果 =======
        List<RecommendedItem> result = applyAuthorLimit(diverseBooks, count);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("推荐计算完成: userId={}, resultCount={}, elapsed={}ms", userId, result.size(), elapsed);

        return result;
    }

    /**
     * 清除用户推荐缓存
     */
    public void clearUserCache(Long userId) {
        try {
            Set<String> keys = redisTemplate.keys(CACHE_PREFIX + userId + ":*");
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.debug("清除推荐缓存: userId={}, keys={}", userId, keys.size());
            }
        } catch (Exception e) {
            log.debug("清除推荐缓存失败: {}", e.getMessage());
        }
    }

    /**
     * 构建用户画像描述文本（用于向量召回的 query）
     */
    public String buildUserProfileText(User user) {
        StringBuilder sb = new StringBuilder();

        if (user.getBirthday() != null) {
            int age = java.time.Period.between(user.getBirthday(), java.time.LocalDate.now()).getYears();
            sb.append(age).append("岁 ");
        }
        if (user.getGender() != null) {
            sb.append(switch (user.getGender()) {
                case "MALE" -> "男性 ";
                case "FEMALE" -> "女性 ";
                default -> "";
            });
        }
        if (user.getMarried() != null) {
            sb.append(user.getMarried() ? "已婚 " : "未婚 ");
        }
        if (user.getHasChildren() != null) {
            sb.append(user.getHasChildren() ? "有孩子 " : "无孩子 ");
        }
        if (user.getMbti() != null) {
            sb.append(user.getMbti()).append("型人格 ");
        }
        if (user.getBio() != null && !user.getBio().isBlank()) {
            sb.append("兴趣：").append(user.getBio());
        }

        return sb.toString().trim();
    }

    /**
     * 记录用户阅读行为（供其他 Service 调用）
     */
    public void recordReadAction(Long userId, Long bookId, String action, Integer weight, String detail) {
        try {
            // 先删除同类型旧记录（避免重复）
            readHistoryRepository.deleteByUserIdAndBookIdAndAction(userId, bookId, action);

            UserReadHistory history = UserReadHistory.builder()
                    .userId(userId)
                    .bookId(bookId)
                    .action(action)
                    .weight(weight != null ? weight : 1)
                    .actionDetail(detail)
                    .build();
            readHistoryRepository.save(history);

            // 清除推荐缓存
            clearUserCache(userId);
        } catch (Exception e) {
            log.warn("记录阅读行为失败: userId={}, bookId={}, action={} - {}", userId, bookId, action, e.getMessage());
        }
    }

    // ==================== 路径A: 规则召回（8维度画像匹配） ====================

    /**
     * 规则召回：遍历候选集，按8维度 relevanceScores 计算匹配度
     * 候选集 = 高分书籍 + 热门书籍（扩大到各100本）
     */
    private Map<Long, Double> ruleRecall(User user, List<Long> excludeBookIds) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        Set<Long> excludeSet = new HashSet<>(excludeBookIds);

        // 候选集：评分前100 + 阅读前100 + 新书前50
        List<Book> candidates = new ArrayList<>();
        candidates.addAll(bookRepository.findAllByOrderByRatingDesc(PageRequest.of(0, 100)).getContent());
        candidates.addAll(bookRepository.findAllByOrderByReadCountDesc(PageRequest.of(0, 100)).getContent());
        candidates.addAll(bookRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 50)).getContent());

        for (Book book : candidates) {
            if (excludeSet.contains(book.getId())) continue;

            double score = calculateMatchScore(user, book);
            if (score > 0.3) { // 低于0.3的太不匹配，直接跳过
                scores.merge(book.getId(), score, Math::max); // 去重取最高分
            }
        }

        return scores;
    }

    /**
     * 计算8维度匹配度得分
     */
    private double calculateMatchScore(User user, Book book) {
        if (book.getRelevanceScores() == null || book.getRelevanceScores().isBlank()) {
            return 0.5;
        }

        try {
            JsonNode scores = objectMapper.readTree(book.getRelevanceScores());
            double totalScore = 0;
            double dimensionCount = 0;

            // 年龄段匹配（权重最高）
            if (user.getBirthday() != null) {
                int age = java.time.Period.between(user.getBirthday(), java.time.LocalDate.now()).getYears();
                String ageGroup = getAgeGroup(age);
                if (scores.has(ageGroup)) {
                    totalScore += scores.get(ageGroup).asDouble() * 1.5; // 年龄权重1.5x
                    dimensionCount += 1.5;
                }
            }

            // 性别匹配
            if (user.getGender() != null) {
                String genderKey = "MALE".equals(user.getGender()) ? "male" : "female";
                if (scores.has(genderKey)) {
                    totalScore += scores.get(genderKey).asDouble();
                    dimensionCount++;
                }
            }

            // 婚姻匹配
            if (user.getMarried() != null) {
                String marryKey = user.getMarried() ? "married" : "unmarried";
                if (scores.has(marryKey)) {
                    totalScore += scores.get(marryKey).asDouble();
                    dimensionCount++;
                }
            }

            // 子女匹配
            if (user.getHasChildren() != null) {
                String childKey = user.getHasChildren() ? "hasChildren" : "noChildren";
                if (scores.has(childKey)) {
                    totalScore += scores.get(childKey).asDouble();
                    dimensionCount++;
                }
            }

            // MBTI匹配（权重次高）
            if (user.getMbti() != null) {
                String mbtiKey = user.getMbti().toUpperCase();
                if (scores.has(mbtiKey)) {
                    totalScore += scores.get(mbtiKey).asDouble() * 1.3; // MBTI权重1.3x
                    dimensionCount += (int) 1.3;
                }
            }

            return dimensionCount == 0 ? 0.5 : totalScore / dimensionCount;
        } catch (Exception e) {
            log.debug("解析相关度得分失败: bookId={} - {}", book.getId(), e.getMessage());
            return 0.5;
        }
    }

    private String getAgeGroup(int age) {
        if (age < 10) return "0-9";
        if (age < 20) return "10-19";
        if (age < 30) return "20-29";
        if (age < 40) return "30-39";
        if (age < 50) return "40-49";
        if (age < 60) return "50-59";
        return "60+";
    }

    // ==================== 路径B: 向量召回（Qdrant 语义相似度） ====================

    /**
     * 向量召回：根据用户画像描述搜索语义相似的书籍
     * 如果用户有已读书籍，还会基于已读书籍的元数据做相似推荐
     */
    private Map<Long, Double> vectorRecall(User user, List<Long> excludeBookIds) {
        Map<Long, Double> scores = new LinkedHashMap<>();

        if (!embeddingService.isAvailable()) {
            log.debug("向量召回跳过: Embedding 不可用");
            return scores;
        }

        try {
            // 1. 基于用户画像搜索
            String profileText = buildUserProfileText(user);
            if (!profileText.isBlank()) {
                List<EmbeddingMatch<TextSegment>> matches =
                        embeddingService.searchSimilarBooks(profileText, 50, 0.4, excludeBookIds);
                for (EmbeddingMatch<TextSegment> match : matches) {
                    if (match.embedded() != null && match.embedded().metadata() != null) {
                        Long bookId = match.embedded().metadata().getLong("bookId");
                        if (bookId != null && !excludeBookIds.contains(bookId)) {
                            scores.merge(bookId, match.score(), Math::max);
                        }
                    }
                }
            }

            // 2. 基于用户最近读完的书籍搜索相似书
            List<Long> completedIds = progressRepository.findCompletedBookIdsByUserId(user.getId());
            List<Long> recentCompleted = completedIds.stream().limit(5).toList();
            for (Long bookId : recentCompleted) {
                Book book = bookRepository.findById(bookId).orElse(null);
                if (book == null) continue;

                String bookMetadata = buildBookSearchQuery(book);
                List<EmbeddingMatch<TextSegment>> matches =
                        embeddingService.searchSimilarBooks(bookMetadata, 20, 0.5, excludeBookIds);
                for (EmbeddingMatch<TextSegment> match : matches) {
                    if (match.embedded() != null && match.embedded().metadata() != null) {
                        Long id = match.embedded().metadata().getLong("bookId");
                        if (id != null && !excludeBookIds.contains(id)) {
                            // 基于已读的推荐权重降低一些
                            scores.merge(id, match.score() * 0.8, Math::max);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("向量召回失败: {}", e.getMessage());
        }

        return scores;
    }

    /**
     * 构建书籍搜索查询文本
     */
    private String buildBookSearchQuery(Book book) {
        StringBuilder sb = new StringBuilder();
        if (book.getTitle() != null) sb.append(book.getTitle()).append(" ");
        if (book.getAuthor() != null) sb.append(book.getAuthor()).append(" ");
        if (book.getFormatTags() != null) {
            sb.append(book.getFormatTags().replaceAll("[\\[\\]\"]", "").replace(",", " "));
        }
        return sb.toString().trim();
    }

    // ==================== 路径C: 协同召回（UserCF 简化版） ====================

    /**
     * 协同召回：找到与当前用户阅读品味相似的用户，推荐他们读过但当前用户未读的书
     */
    private Map<Long, Double> collaborativeRecall(Long userId, List<Long> excludeBookIds) {
        Map<Long, Double> scores = new LinkedHashMap<>();

        try {
            // 1. 获取当前用户已读的书
            List<Long> myBookIds = readHistoryRepository.findAllInteractedBookIdsByUserId(userId);
            if (myBookIds.isEmpty()) {
                // 没有阅读记录，尝试从进度表获取
                myBookIds = progressRepository.findAllBookIdsByUserId(userId);
            }
            if (myBookIds.isEmpty()) return scores;

            // 取最近交互的20本
            List<Long> recentBooks = myBookIds.stream().limit(20).toList();

            // 2. 找到也读过这些书的用户（相似用户）
            List<Long> similarUserIds = readHistoryRepository.findSimilarUsers(recentBooks, userId);
            if (similarUserIds.isEmpty()) return scores;

            // 限制相似用户数量
            similarUserIds = similarUserIds.stream().limit(50).toList();

            // 3. 获取这些用户读过但当前用户没读过的书
            List<Object[]> bookWeights = readHistoryRepository.findBookIdsByUserIdsExcluding(
                    similarUserIds, excludeBookIds);

            // 4. 归一化权重为得分
            double maxWeight = 1.0;
            for (Object[] row : bookWeights) {
                double w = ((Number) row[1]).doubleValue();
                if (w > maxWeight) maxWeight = w;
            }
            for (Object[] row : bookWeights) {
                Long bookId = ((Number) row[0]).longValue();
                double weight = ((Number) row[1]).doubleValue();
                scores.put(bookId, weight / maxWeight);
            }
        } catch (Exception e) {
            log.warn("协同召回失败: {}", e.getMessage());
        }

        return scores;
    }

    // ==================== 评分融合 + MMR ====================

    /**
     * 融合三路得分：加权平均
     */
    private Map<Long, Double> fuseScores(Map<Long, Double> rule, Map<Long, Double> vector, Map<Long, Double> collab) {
        Set<Long> allBookIds = new HashSet<>();
        allBookIds.addAll(rule.keySet());
        allBookIds.addAll(vector.keySet());
        allBookIds.addAll(collab.keySet());

        Map<Long, Double> fused = new LinkedHashMap<>();
        for (Long bookId : allBookIds) {
            double r = rule.getOrDefault(bookId, 0.0);
            double v = vector.getOrDefault(bookId, 0.0);
            double c = collab.getOrDefault(bookId, 0.0);

            // 计算有效路径数
            int activePaths = (r > 0 ? 1 : 0) + (v > 0 ? 1 : 0) + (c > 0 ? 1 : 0);
            if (activePaths == 0) continue;

            // 加权融合（缺失路径的权重分配给其他路径）
            double totalWeight = 0;
            double totalScore = 0;

            if (r > 0) {
                totalScore += r * WEIGHT_RULE;
                totalWeight += WEIGHT_RULE;
            }
            if (v > 0) {
                totalScore += v * WEIGHT_VECTOR;
                totalWeight += WEIGHT_VECTOR;
            }
            if (c > 0) {
                totalScore += c * WEIGHT_COLLAB;
                totalWeight += WEIGHT_COLLAB;
            }

            // 多路径命中加成
            double pathBonus = activePaths >= 3 ? 0.15 : (activePaths >= 2 ? 0.08 : 0.0);

            fused.put(bookId, totalScore / totalWeight + pathBonus);
        }

        return fused;
    }

    /**
     * MMR（Maximal Marginal Relevance）多样性打散
     * 确保推荐列表中书籍之间的相似度不太高
     */
    private List<ScoredBook> mmrDiversify(List<ScoredBook> candidates, int count) {
        if (candidates.isEmpty()) return candidates;

        List<ScoredBook> selected = new ArrayList<>();
        Set<String> selectedTags = new HashSet<>();

        // 第一本直接选最高分
        ScoredBook first = candidates.get(0);
        selected.add(first);
        addTags(selectedTags, first.book);

        // 后续使用 MMR 选择
        while (selected.size() < count && selected.size() < candidates.size()) {
            ScoredBook best = null;
            double bestMmr = Double.NEGATIVE_INFINITY;

            for (ScoredBook candidate : candidates) {
                if (selected.stream().anyMatch(s -> s.book.getId().equals(candidate.book.getId()))) continue;

                // 计算与已选集的最大相似度（基于标签重叠度）
                double maxSim = calculateMaxSimilarity(candidate.book, selectedTags);

                // MMR = λ * relevance - (1-λ) * maxSimilarity
                double mmr = MMR_LAMBDA * candidate.finalScore - (1 - MMR_LAMBDA) * maxSim;

                if (mmr > bestMmr) {
                    bestMmr = mmr;
                    best = candidate;
                }
            }

            if (best != null) {
                selected.add(best);
                addTags(selectedTags, best.book);
            } else {
                break;
            }
        }

        return selected;
    }

    /**
     * 计算候选书籍与已选集的最大相似度（基于标签重叠）
     */
    private double calculateMaxSimilarity(Book candidate, Set<String> selectedTags) {
        if (selectedTags.isEmpty() || candidate.getFormatTags() == null) return 0.0;

        Set<String> candidateTags = parseTags(candidate.getFormatTags());
        if (candidateTags.isEmpty()) return 0.0;

        // Jaccard 相似度
        long intersection = candidateTags.stream().filter(selectedTags::contains).count();
        long union = candidateTags.size() + selectedTags.size() - intersection;

        return union > 0 ? (double) intersection / union : 0.0;
    }

    /**
     * 同作者限制 + 格式化输出
     */
    private List<RecommendedItem> applyAuthorLimit(List<ScoredBook> books, int count) {
        Map<String, Integer> authorCount = new HashMap<>();
        List<RecommendedItem> result = new ArrayList<>();

        for (ScoredBook sb : books) {
            String author = sb.book.getAuthor() != null ? sb.book.getAuthor() : "未知";
            int currentCount = authorCount.getOrDefault(author, 0);

            if (currentCount >= MAX_SAME_AUTHOR) continue; // 同作者超过限制，跳过

            authorCount.put(author, currentCount + 1);
            result.add(RecommendedItem.builder()
                    .bookId(sb.book.getId())
                    .title(sb.book.getTitle())
                    .author(sb.book.getAuthor())
                    .coverUrl(sb.book.getCoverUrl())
                    .format(sb.book.getFormat())
                    .rating(sb.book.getRating())
                    .description(sb.book.getDescription() != null && sb.book.getDescription().length() > 80
                            ? sb.book.getDescription().substring(0, 80) + "..." : sb.book.getDescription())
                    .matchScore(Math.round(sb.finalScore * 100.0) / 100.0)
                    .ruleScore(Math.round(sb.ruleScore * 100.0) / 100.0)
                    .vectorScore(Math.round(sb.vectorScore * 100.0) / 100.0)
                    .collabScore(Math.round(sb.collabScore * 100.0) / 100.0)
                    .recommendedAt(LocalDateTime.now())
                    .build());

            if (result.size() >= count) break;
        }

        return result;
    }

    // ==================== 辅助方法 ====================

    private List<Long> getReadBookIds(Long userId) {
        // 综合所有来源获取已交互图书ID
        Set<Long> ids = new LinkedHashSet<>();
        ids.addAll(readHistoryRepository.findAllInteractedBookIdsByUserId(userId));
        ids.addAll(progressRepository.findAllBookIdsByUserId(userId));
        return new ArrayList<>(ids);
    }

    private void addTags(Set<String> tagSet, Book book) {
        if (book.getFormatTags() != null) {
            tagSet.addAll(parseTags(book.getFormatTags()));
        }
    }

    private Set<String> parseTags(String formatTags) {
        if (formatTags == null || formatTags.isBlank()) return Set.of();
        return Arrays.stream(formatTags.replaceAll("[\\[\\]\"]", "").split("[,，]"))
                .map(String::trim)
                .filter(t -> !t.isBlank())
                .collect(Collectors.toSet());
    }

    // ==================== 用户偏好过滤 ====================

    private List<String> getExcludedTags(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "TAG", "EXCLUDE")
                .stream().map(UserBookPreference::getValue).toList();
    }

    private List<String> getExcludedAuthors(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "AUTHOR", "EXCLUDE")
                .stream().map(UserBookPreference::getValue).toList();
    }

    private List<String> getExcludedFormats(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "FORMAT", "EXCLUDE")
                .stream().map(UserBookPreference::getValue).toList();
    }

    private List<String> getIncludedTags(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "TAG", "INCLUDE")
                .stream().map(UserBookPreference::getValue).toList();
    }

    private List<String> getIncludedAuthors(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "AUTHOR", "INCLUDE")
                .stream().map(UserBookPreference::getValue).toList();
    }

    private List<String> getIncludedFormats(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "FORMAT", "INCLUDE")
                .stream().map(UserBookPreference::getValue).toList();
    }

    /** 喜好偏好加权系数 */
    private static final double INCLUDE_TAG_BONUS = 0.12;
    private static final double INCLUDE_AUTHOR_BONUS = 0.15;
    private static final double INCLUDE_FORMAT_BONUS = 0.05;

    /**
     * 计算用户喜欢偏好对推荐分数的加成
     */
    private double calculateIncludeBonus(Book book, List<String> includedTags,
                                          List<String> includedAuthors, List<String> includedFormats) {
        double bonus = 0.0;
        // 标签匹配加分
        if (!includedTags.isEmpty() && book.getFormatTags() != null) {
            Set<String> bookTags = parseTags(book.getFormatTags());
            for (String tag : includedTags) {
                if (bookTags.stream().anyMatch(t -> t.equalsIgnoreCase(tag))) {
                    bonus += INCLUDE_TAG_BONUS;
                }
            }
        }
        // 作者匹配加分
        if (!includedAuthors.isEmpty() && book.getAuthor() != null) {
            for (String author : includedAuthors) {
                if (author.equalsIgnoreCase(book.getAuthor())) {
                    bonus += INCLUDE_AUTHOR_BONUS;
                    break;
                }
            }
        }
        // 格式匹配加分
        if (!includedFormats.isEmpty() && book.getFormat() != null) {
            for (String format : includedFormats) {
                if (format.equalsIgnoreCase(book.getFormat())) {
                    bonus += INCLUDE_FORMAT_BONUS;
                    break;
                }
            }
        }
        return bonus;
    }

    /**
     * 判断书籍是否被用户偏好排除
     */
    private boolean isExcludedByPreference(Book book, List<String> excludedTags,
                                            List<String> excludedAuthors, List<String> excludedFormats) {
        // 格式排除
        if (!excludedFormats.isEmpty() && book.getFormat() != null
                && excludedFormats.contains(book.getFormat().toUpperCase())) {
            return true;
        }
        // 作者排除
        if (!excludedAuthors.isEmpty() && book.getAuthor() != null
                && excludedAuthors.stream().anyMatch(a -> a.equalsIgnoreCase(book.getAuthor()))) {
            return true;
        }
        // 标签排除
        if (!excludedTags.isEmpty() && book.getFormatTags() != null) {
            Set<String> bookTags = parseTags(book.getFormatTags());
            for (String excludedTag : excludedTags) {
                if (bookTags.stream().anyMatch(t -> t.equalsIgnoreCase(excludedTag))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== 内部类 ====================

    private static class ScoredBook {
        final Book book;
        final double finalScore;
        final double ruleScore;
        final double vectorScore;
        final double collabScore;

        ScoredBook(Book book, double finalScore, double ruleScore, double vectorScore, double collabScore) {
            this.book = book;
            this.finalScore = finalScore;
            this.ruleScore = ruleScore;
            this.vectorScore = vectorScore;
            this.collabScore = collabScore;
        }
    }

    /**
     * 推荐结果项
     */
    @Data
    @Builder
    public static class RecommendedItem {
        private Long bookId;
        private String title;
        private String author;
        private String coverUrl;
        private String format;
        private Double rating;
        private String description;
        private Double matchScore;
        /**
         * 规则得分
         */
        private Double ruleScore;
        /**
         * 向量得分
         */
        private Double vectorScore;
        /**
         * 协同得分
         */
        private Double collabScore;
        private LocalDateTime recommendedAt;
    }
}
