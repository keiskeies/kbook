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
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 推荐服务 — 多路召回 + 质量调制 + 新鲜度 + MMR 多样性 + Redis 缓存
 * <p>
 * 核心公式：
 *   finalScore = matchScore × qualityFactor × freshnessFactor + preferenceBonus
 * <p>
 * 推荐流程：
 * 1. 路径A - 规则召回：8维度画像匹配（年龄段/性别/婚姻/子女/MBTI）
 * 2. 路径B - 向量召回：Qdrant 语义相似度（书籍元数据 embedding → 用户兴趣 embedding）
 * 3. 路径C - 协同召回：相似用户的阅读行为（UserCF 简化版）
 * 4. 路径D - 探索召回：随机采样 + 热门补充，防止信息茧房
 * 5. 融合排序：加权融合四路得分
 * 6. 质量调制：分段函数，低分书强压制、高分书温和加成
 * 7. 新鲜度调制：新书获得曝光窗口
 * 8. 用户偏好：排除/加权
 * 9. MMR 去重 + 同作者限制
 * 10. 缓存：Redis 30分钟 TTL
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
    private final RecommendCoefficientService coefficientService;

    public RecommendService(
            BookRepository bookRepository,
            ReadingProgressRepository progressRepository,
            UserReadHistoryRepository readHistoryRepository,
            UserService userService,
            @Lazy EmbeddingService embeddingService,
            UserBookPreferenceRepository preferenceRepository,
            ObjectMapper objectMapper,
            RedisTemplate<String, Object> redisTemplate,
            @Lazy RecommendCoefficientService coefficientService
    ) {
        this.bookRepository = bookRepository;
        this.progressRepository = progressRepository;
        this.readHistoryRepository = readHistoryRepository;
        this.userService = userService;
        this.embeddingService = embeddingService;
        this.preferenceRepository = preferenceRepository;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.coefficientService = coefficientService;
    }

    // ==================== 算法参数（动态系数，由 RecommendCoefficientService 管理） ====================

    /**
     * Redis 缓存 key 前缀
     */
    private static final String CACHE_PREFIX = "kbook:recommend:";

    /**
     * 缓存 TTL（分钟）
     */
    private static final int CACHE_TTL_MINUTES = 30;

    // ==================== 公开接口 ====================

    /**
     * 批量计算规则匹配分（轻量级，仅基于用户画像+书籍relevanceScores）
     * 适用于在任意图书列表中展示匹配度，不涉及向量搜索和协同过滤
     *
     * @param userId   用户ID
     * @param bookIds  书籍ID列表
     * @return Map<bookId, matchScore>，匹配分范围 0~1，无画像时返回 null
     */
    public Map<Long, Double> batchCalculateMatchScores(Long userId, List<Long> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) return Map.of();

        User user = userService.getUserById(userId);
        Map<Long, Double> result = new LinkedHashMap<>();

        for (Long bookId : bookIds) {
            Book book = bookRepository.findById(bookId).orElse(null);
            if (book == null) continue;

            double score = calculateMatchScore(user, book);
            // 只有用户至少填了1个画像维度时才返回分数（否则都是默认0.5，无意义）
            if (user.getBirthday() != null || user.getGender() != null
                    || user.getMarried() != null || user.getHasChildren() != null
                    || user.getMbti() != null || user.getOccupation() != null
                    || user.getEducation() != null || user.getEntrepreneurship() != null
                    || user.getAnnualIncome() != null || user.getMood() != null) {
                result.put(bookId, Math.round(score * 100.0) / 100.0);
            }
        }
        return result;
    }

    /**
     * 获取个性化推荐（带缓存）
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

        // ======= 多路召回（4路） =======
        Map<Long, Double> ruleScores = ruleRecall(user, readBookIds);
        Map<Long, Double> vectorScores = vectorRecall(user, readBookIds);
        Map<Long, Double> collabScores = collaborativeRecall(userId, readBookIds);
        Map<Long, Double> exploreScores = exploreRecall(user, readBookIds);

        log.debug("多路召回完成: rule={}, vector={}, collab={}, explore={}",
                ruleScores.size(), vectorScores.size(), collabScores.size(), exploreScores.size());

        // ======= 融合排序 =======
        Map<Long, Double> fusedScores = fuseScores(ruleScores, vectorScores, collabScores, exploreScores);

        // ======= 加载图书信息 + 质量调制 + 新鲜度调制 + 偏好过滤/加权 =======
        List<String> excludedTags = getExcludedTags(userId);
        List<String> excludedAuthors = getExcludedAuthors(userId);
        List<String> excludedFormats = getExcludedFormats(userId);
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

            double matchScore = entry.getValue();

            // 质量因子：分段函数，低分书强压制，高分书温和加成
            double qualityFactor = calculateQualityFactor(book.getRating());

            // 新鲜度因子：新书获得曝光窗口
            double freshnessFactor = calculateFreshnessFactor(book.getCreatedAt());

            // 用户喜欢偏好加权
            double preferenceBonus = calculateIncludeBonus(book, includedTags, includedAuthors, includedFormats);

            // 最终得分 = 匹配度 × 质量 × 新鲜度 + 偏好加成
            double finalScore = matchScore * qualityFactor * freshnessFactor + preferenceBonus;

            // 构建召回路径信息（用于反馈追踪）
            String recallPaths = buildRecallPaths(book.getId(), ruleScores, vectorScores, collabScores, exploreScores);

            scoredBooks.add(new ScoredBook(book, finalScore, matchScore, qualityFactor,
                    ruleScores.getOrDefault(book.getId(), 0.0),
                    vectorScores.getOrDefault(book.getId(), 0.0),
                    collabScores.getOrDefault(book.getId(), 0.0),
                    recallPaths));
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
        if (user.getOccupation() != null && !user.getOccupation().isBlank()) {
            String[] occList = user.getOccupation().split(",");
            for (String occ : occList) {
                sb.append(getOccupationLabel(occ.trim())).append(" ");
            }
        }
        if (user.getEducation() != null) {
            sb.append(getEducationLabel(user.getEducation())).append(" ");
        }
        if (user.getEntrepreneurship() != null && !user.getEntrepreneurship().isBlank()) {
            sb.append(getEntrepreneurshipLabel(user.getEntrepreneurship())).append(" ");
        }
        if (user.getAnnualIncome() != null && !user.getAnnualIncome().isBlank()) {
            sb.append(getAnnualIncomeLabel(user.getAnnualIncome())).append(" ");
        }
        if (user.getMood() != null) {
            sb.append(getMoodLabel(user.getMood())).append(" ");
        }
        if (user.getBio() != null && !user.getBio().isBlank()) {
            sb.append("兴趣：").append(user.getBio());
        }

        return sb.toString().trim();
    }

    /**
     * 获取职业的中文标签
     */
    private String getOccupationLabel(String occupation) {
        if (occupation == null) return "";
        return switch (occupation.toUpperCase()) {
            case "STUDENT" -> "学生";
            case "TECH" -> "技术/IT";
            case "FINANCE" -> "金融/商业";
            case "EDUCATION" -> "教育/科研";
            case "MEDICAL" -> "医疗/健康";
            case "ARTS" -> "文艺/传媒";
            case "MANAGEMENT" -> "管理/行政";
            case "FREELANCE" -> "自由职业";
            case "RETIRED" -> "退休";
            case "OTHER" -> "其他";
            default -> occupation;
        };
    }

    /**
     * 获取学历的中文标签
     */
    private String getEducationLabel(String education) {
        if (education == null) return "";
        return switch (education.toUpperCase()) {
            case "HIGH_SCHOOL" -> "高中及以下";
            case "COLLEGE" -> "大专";
            case "BACHELOR" -> "本科";
            case "MASTER" -> "硕士";
            case "DOCTORATE" -> "博士";
            case "OTHER" -> "其他";
            default -> education;
        };
    }

    /**
     * 获取心情的中文标签
     */
    private String getMoodLabel(String mood) {
        if (mood == null) return "";
        return switch (mood.toUpperCase()) {
            case "HAPPY" -> "开心";
            case "CALM" -> "平静";
            case "ANXIOUS" -> "焦虑";
            case "SAD" -> "低落";
            case "MOTIVATED" -> "充满动力";
            case "TIRED" -> "疲惫";
            case "CURIOUS" -> "好奇";
            default -> mood;
        };
    }

    /**
     * 获取创业意向的中文标签
     */
    private String getEntrepreneurshipLabel(String entrepreneurship) {
        if (entrepreneurship == null) return "";
        return switch (entrepreneurship.toUpperCase()) {
            case "ENTREPRENEUR_OR_WANT" -> "正在创业/想创业";
            case "NOT_INTERESTED" -> "暂不考虑";
            default -> entrepreneurship;
        };
    }

    /**
     * 获取创业意向的相关键（已合并为单一维度，无需关联）
     */
    private List<String> getRelatedEntrepreneurship(String entrepreneurship) {
        return List.of();
    }

    /**
     * 获取年收入的中文标签
     */
    private String getAnnualIncomeLabel(String annualIncome) {
        if (annualIncome == null) return "";
        return switch (annualIncome.toUpperCase()) {
            case "UNDER_50K" -> "年收入5万以内";
            case "50K_150K" -> "年收入5~15万";
            case "150K_300K" -> "年收入15~30万";
            case "300K_500K" -> "年收入30~50万";
            case "500K_1M" -> "年收入50~100万";
            case "OVER_1M" -> "年收入100万+";
            case "PREFER_NOT_TO_SAY" -> "";
            default -> annualIncome;
        };
    }



    /**
     * 获取相邻年收入（用于模糊匹配，收入区间相邻的互为相关）
     */
    private List<String> getAdjacentIncomes(String income) {
        return switch (income.toLowerCase()) {
            case "under_50k" -> List.of("50k_150k");
            case "50k_150k" -> List.of("under_50k", "150k_300k");
            case "150k_300k" -> List.of("50k_150k", "300k_500k");
            case "300k_500k" -> List.of("150k_300k", "500k_1m");
            case "500k_1m" -> List.of("300k_500k", "over_1m");
            case "over_1m" -> List.of("500k_1m");
            default -> List.of();
        };
    }

    /**
     * 获取相邻职业（用于模糊匹配，同一大类的职业互为相邻）
     */
    private List<String> getAdjacentOccupations(String occupation) {
        return switch (occupation.toLowerCase()) {
            case "student" -> List.of("education");         // 学生 ↔ 教育/科研
            case "tech" -> List.of("education", "freelance"); // 技术 ↔ 教育、自由职业
            case "finance" -> List.of("management");        // 金融 ↔ 管理
            case "education" -> List.of("student", "tech");  // 教育 ↔ 学生、技术
            case "medical" -> List.of("education");          // 医疗 ↔ 教育
            case "arts" -> List.of("freelance", "education");// 文艺 ↔ 自由职业、教育
            case "management" -> List.of("finance");        // 管理 ↔ 金融
            case "freelance" -> List.of("arts", "tech");    // 自由职业 ↔ 文艺、技术
            case "retired" -> List.of();                     // 退休无相邻
            case "other" -> List.of();                       // 其他无相邻
            default -> List.of();
        };
    }

    /**
     * 获取相邻学历（用于模糊匹配，学历等级相邻的互为相邻）
     */
    private List<String> getAdjacentEducations(String education) {
        return switch (education.toLowerCase()) {
            case "high_school" -> List.of("college");           // 高中 ↔ 大专
            case "college" -> List.of("high_school", "bachelor"); // 大专 ↔ 高中、本科
            case "bachelor" -> List.of("college", "master");    // 本科 ↔ 大专、硕士
            case "master" -> List.of("bachelor", "doctorate");  // 硕士 ↔ 本科、博士
            case "doctorate" -> List.of("master");              // 博士 ↔ 硕士
            case "other" -> List.of();                           // 其他无相邻
            default -> List.of();
        };
    }

    /**
     * 获取相关心情（用于模糊匹配，情绪相近的心情互为相关）
     */
    private List<String> getRelatedMoods(String mood) {
        return switch (mood.toLowerCase()) {
            case "happy" -> List.of("motivated", "calm");        // 开心 ↔ 充满动力、平静
            case "calm" -> List.of("happy", "curious");          // 平静 ↔ 开心、好奇
            case "anxious" -> List.of("sad", "tired");           // 焦虑 ↔ 低落、疲惫
            case "sad" -> List.of("anxious", "tired");           // 低落 ↔ 焦虑、疲惫
            case "motivated" -> List.of("happy", "curious");     // 充满动力 ↔ 开心、好奇
            case "tired" -> List.of("sad", "anxious");           // 疲惫 ↔ 低落、焦虑
            case "curious" -> List.of("calm", "motivated");      // 好奇 ↔ 平静、充满动力
            default -> List.of();
        };
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

    // ==================== 质量因子 & 新鲜度因子 ====================

    /**
     * 质量因子（分段线性函数）
     * <p>
     * 设计原则：推荐烂书比错过好书更伤用户体验 → 低分惩罚 > 高分加成
     * <p>
     * 分段映射（系数由 RecommendCoefficientService 动态管理）：
     * - 1.0 → very_low    （强压制，劣质书即使高匹配也不推）
     * - 2.0 → low         （明显降权）
     * - 3.0 → below_avg   （近中性，中等质量不影响推荐）
     * - 4.0 → good        （温和加成）
     * - 5.0 → excellent   （天花板加成，杰作但不霸榜）
     * - 无评分 → unknown   （略压制，避免未评分书排太前）
     */
    private double calculateQualityFactor(Double rating) {
        if (rating == null || rating <= 0) return coefficientService.getCoefficient("QUALITY", "unknown", 0.85);

        double veryLow = coefficientService.getCoefficient("QUALITY", "very_low", 0.40);
        double low = coefficientService.getCoefficient("QUALITY", "low", 0.70);
        double belowAvg = coefficientService.getCoefficient("QUALITY", "below_avg", 0.95);
        double good = coefficientService.getCoefficient("QUALITY", "good", 1.15);
        double excellent = coefficientService.getCoefficient("QUALITY", "excellent", 1.30);

        if (rating < 2.0) {
            return veryLow + (low - veryLow) * (rating - 1.0);
        } else if (rating < 3.0) {
            return low + (belowAvg - low) * (rating - 2.0);
        } else if (rating < 4.0) {
            return belowAvg + (good - belowAvg) * (rating - 3.0);
        } else {
            return good + (excellent - good) * (rating - 4.0);
        }
    }

    /**
     * 新鲜度因子
     * <p>
     * 新入库的书获得短暂曝光窗口，帮助冷启动：
     * - 7天内：1.03-1.12（线性衰减，越新越高）
     * - 7-30天：1.00-1.03（线性衰减至中性）
     * - 30天以上：1.00（不影响）
     */
    private double calculateFreshnessFactor(LocalDateTime createdAt) {
        if (createdAt == null) return 1.0;

        long daysAgo = ChronoUnit.DAYS.between(createdAt, LocalDateTime.now());
        if (daysAgo < 0) daysAgo = 0;

        int daysMax = (int) coefficientService.getCoefficient("FRESHNESS", "days_max", 7);
        int daysDecay = (int) coefficientService.getCoefficient("FRESHNESS", "days_decay", 30);
        double bonusMax = coefficientService.getCoefficient("FRESHNESS", "bonus_max", 1.12);
        double bonusMin = coefficientService.getCoefficient("FRESHNESS", "bonus_min", 1.03);

        if (daysAgo <= daysMax) {
            double ratio = (double) daysAgo / daysMax;
            return bonusMax - (bonusMax - bonusMin) * ratio;
        } else if (daysAgo <= daysDecay) {
            double ratio = (double) (daysAgo - daysMax) / (daysDecay - daysMax);
            return bonusMin - (bonusMin - 1.0) * ratio;
        } else {
            return 1.0;
        }
    }

    // ==================== 路径A: 规则召回（8维度画像匹配） ====================

    /**
     * 规则召回：遍历候选集，按8维度 relevanceScores 计算匹配度
     * 候选集 = 高分书籍 + 热门书籍 + 新书 + 随机采样
     * 扩大候选集以覆盖更多中等评分但高匹配度的书
     */
    private Map<Long, Double> ruleRecall(User user, List<Long> excludeBookIds) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        Set<Long> excludeSet = new HashSet<>(excludeBookIds);

        // 候选集：评分前200 + 阅读前150 + 新书前100
        List<Book> candidates = new ArrayList<>();
        candidates.addAll(bookRepository.findAllByOrderByRatingDesc(PageRequest.of(0, 200)).getContent());
        candidates.addAll(bookRepository.findAllByOrderByReadCountDesc(PageRequest.of(0, 150)).getContent());
        candidates.addAll(bookRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 100)).getContent());

        for (Book book : candidates) {
            if (excludeSet.contains(book.getId())) continue;

            double score = calculateMatchScore(user, book);
            double ruleMinScore = coefficientService.getCoefficient("OTHER", "rule_min_score", 0.3);
            if (score > ruleMinScore) { // 低于阈值的太不匹配，直接跳过
                scores.merge(book.getId(), score, Math::max); // 去重取最高分
            }
        }

        return scores;
    }

    /**
     * 计算8维度匹配度得分（正面 + 反面信号 + 相邻模糊匹配 + 覆盖度衰减）
     * <p>
     * 优化点：
     * 1. 反面维度：用户是男性时，female 高分是负面信号 → 正面分 - 反面惩罚
     * 2. 相邻年龄段模糊匹配：30岁用户看 20-29 也有弱匹配（衰减0.4）
     * 3. 维度覆盖度衰减：维度越少，匹配分越不可靠，做置信度衰减
     */
    private double calculateMatchScore(User user, Book book) {
        if (book.getRelevanceScores() == null || book.getRelevanceScores().isBlank()) {
            return 0.5;
        }

        // 读取动态系数
        double ageWeight = coefficientService.getCoefficient("MATCH", "age_weight", 1.5);
        double mbtiWeight = coefficientService.getCoefficient("MATCH", "mbti_weight", 1.3);
        double adjacentDecay = coefficientService.getCoefficient("MATCH", "adjacent_decay", 0.40);
        double oppositeThreshold = coefficientService.getCoefficient("MATCH", "opposite_threshold", 0.7);
        double oppositeMaxPenalty = coefficientService.getCoefficient("MATCH", "opposite_penalty", 0.3);

        // 覆盖度衰减系数
        double covDim10 = coefficientService.getCoefficient("COVERAGE", "dim10", 1.0);
        double covDim9 = coefficientService.getCoefficient("COVERAGE", "dim9", 0.98);
        double covDim8 = coefficientService.getCoefficient("COVERAGE", "dim8", 0.96);
        double covDim7 = coefficientService.getCoefficient("COVERAGE", "dim7", 0.93);
        double covDim6 = coefficientService.getCoefficient("COVERAGE", "dim6", 0.89);
        double covDim5 = coefficientService.getCoefficient("COVERAGE", "dim5", 0.84);
        double covDim4 = coefficientService.getCoefficient("COVERAGE", "dim4", 0.78);
        double covDim3 = coefficientService.getCoefficient("COVERAGE", "dim3", 0.70);
        double covDim2 = coefficientService.getCoefficient("COVERAGE", "dim2", 0.58);
        double covDim1 = coefficientService.getCoefficient("COVERAGE", "dim1", 0.42);

        try {
            JsonNode scores = objectMapper.readTree(book.getRelevanceScores());
            double totalScore = 0;
            double totalWeight = 0;
            int matchedDimensions = 0;
            int totalDimensions = 10; // 年龄/性别/婚姻/子女/MBTI/职业/学历/创业/收入/心情 共10维

            // ========== 年龄段匹配（权重最高） ==========
            if (user.getBirthday() != null) {
                int age = java.time.Period.between(user.getBirthday(), java.time.LocalDate.now()).getYears();
                String ageGroup = getAgeGroup(age);

                // 正面：精确年龄段
                if (scores.has(ageGroup)) {
                    totalScore += scores.get(ageGroup).asDouble() * ageWeight;
                    totalWeight += ageWeight;
                }

                // 模糊匹配：相邻年龄段（衰减）
                String prevGroup = getAdjacentAgeGroup(age, -1);
                String nextGroup = getAdjacentAgeGroup(age, 1);
                if (prevGroup != null && !prevGroup.equals(ageGroup) && scores.has(prevGroup)) {
                    totalScore += scores.get(prevGroup).asDouble() * ageWeight * adjacentDecay;
                    totalWeight += ageWeight * adjacentDecay;
                }
                if (nextGroup != null && !nextGroup.equals(ageGroup) && scores.has(nextGroup)) {
                    totalScore += scores.get(nextGroup).asDouble() * ageWeight * adjacentDecay;
                    totalWeight += ageWeight * adjacentDecay;
                }

                matchedDimensions++;
            }

            // ========== 性别匹配（反面惩罚） ==========
            if (user.getGender() != null) {
                String genderKey = "MALE".equals(user.getGender()) ? "male" : "female";
                String oppositeKey = "MALE".equals(user.getGender()) ? "female" : "male";

                // 正面
                if (scores.has(genderKey)) {
                    totalScore += scores.get(genderKey).asDouble();
                    totalWeight += 1.0;
                }
                // 反面：异性高分 = 这本书不太适合我
                if (scores.has(oppositeKey)) {
                    double oppositeScore = scores.get(oppositeKey).asDouble();
                    double penalty = Math.max(0, oppositeScore - oppositeThreshold) / (1.0 - oppositeThreshold) * oppositeMaxPenalty;
                    penalty = Math.min(penalty, oppositeMaxPenalty);
                    totalScore -= penalty;
                }

                matchedDimensions++;
            }

            // ========== 婚姻匹配（反面惩罚） ==========
            if (user.getMarried() != null) {
                String marryKey = user.getMarried() ? "married" : "unmarried";
                String oppositeMarryKey = user.getMarried() ? "unmarried" : "married";

                // 正面
                if (scores.has(marryKey)) {
                    totalScore += scores.get(marryKey).asDouble();
                    totalWeight += 1.0;
                }
                // 反面
                if (scores.has(oppositeMarryKey)) {
                    double oppositeScore = scores.get(oppositeMarryKey).asDouble();
                    double penalty = Math.min(Math.max(0, oppositeScore - oppositeThreshold) / (1.0 - oppositeThreshold) * oppositeMaxPenalty, oppositeMaxPenalty);
                    totalScore -= penalty;
                }

                matchedDimensions++;
            }

            // ========== 子女匹配（反面惩罚） ==========
            if (user.getHasChildren() != null) {
                String childKey = user.getHasChildren() ? "hasChildren" : "noChildren";
                String oppositeChildKey = user.getHasChildren() ? "noChildren" : "hasChildren";

                // 正面
                if (scores.has(childKey)) {
                    totalScore += scores.get(childKey).asDouble();
                    totalWeight += 1.0;
                }
                // 反面
                if (scores.has(oppositeChildKey)) {
                    double oppositeScore = scores.get(oppositeChildKey).asDouble();
                    double penalty = Math.min(Math.max(0, oppositeScore - oppositeThreshold) / (1.0 - oppositeThreshold) * oppositeMaxPenalty, oppositeMaxPenalty);
                    totalScore -= penalty;
                }

                matchedDimensions++;
            }

            // ========== MBTI 匹配（同组模糊匹配） ==========
            if (user.getMbti() != null) {
                String mbtiKey = user.getMbti().toUpperCase();
                // 正面
                if (scores.has(mbtiKey)) {
                    totalScore += scores.get(mbtiKey).asDouble() * mbtiWeight;
                    totalWeight += mbtiWeight;
                }

                // MBTI 同组模糊匹配
                List<String> adjacentMbti = getAdjacentMbti(mbtiKey);
                for (String adj : adjacentMbti) {
                    if (scores.has(adj)) {
                        totalScore += scores.get(adj).asDouble() * mbtiWeight * adjacentDecay;
                        totalWeight += mbtiWeight * adjacentDecay;
                    }
                }

                matchedDimensions++;
            }

            // ========== 职业匹配（多选，权重1.0，同职业正面+相邻职业衰减） ==========
            if (user.getOccupation() != null && !user.getOccupation().isBlank()) {
                String[] userOccList = user.getOccupation().split(",");
                double occWeight = coefficientService.getCoefficient("MATCH", "occupation_weight", 1.0);
                double occDecay = coefficientService.getCoefficient("MATCH", "occupation_decay", 0.40);

                for (String userOcc : userOccList) {
                    String occKey = userOcc.trim().toLowerCase();
                    if (occKey.isEmpty()) continue;

                    // 正面：精确职业
                    if (scores.has(occKey)) {
                        totalScore += scores.get(occKey).asDouble() * occWeight;
                        totalWeight += occWeight;
                    }

                    // 模糊匹配：相邻职业（衰减）
                    List<String> adjacentOcc = getAdjacentOccupations(occKey);
                    for (String adj : adjacentOcc) {
                        if (scores.has(adj)) {
                            totalScore += scores.get(adj).asDouble() * occWeight * occDecay;
                            totalWeight += occWeight * occDecay;
                        }
                    }
                }

                matchedDimensions++;
            }

            // ========== 学历匹配（权重0.8，同级别正面+相邻级别衰减） ==========
            if (user.getEducation() != null) {
                String eduKey = user.getEducation().toLowerCase();
                double eduWeight = coefficientService.getCoefficient("MATCH", "education_weight", 0.8);
                double eduDecay = coefficientService.getCoefficient("MATCH", "education_decay", 0.40);

                // 正面：精确学历
                if (scores.has(eduKey)) {
                    totalScore += scores.get(eduKey).asDouble() * eduWeight;
                    totalWeight += eduWeight;
                }

                // 模糊匹配：相邻学历（衰减）
                List<String> adjacentEdu = getAdjacentEducations(eduKey);
                for (String adj : adjacentEdu) {
                    if (scores.has(adj)) {
                        totalScore += scores.get(adj).asDouble() * eduWeight * eduDecay;
                        totalWeight += eduWeight * eduDecay;
                    }
                }

                matchedDimensions++;
            }

            // ========== 创业意向匹配（权重0.6，单一维度精确匹配） ==========
            if (user.getEntrepreneurship() != null && !user.getEntrepreneurship().isBlank()) {
                String entreKey = user.getEntrepreneurship().toLowerCase();
                double entreWeight = coefficientService.getCoefficient("MATCH", "entrepreneurship_weight", 0.6);

                // 精确匹配创业意向
                if (scores.has(entreKey)) {
                    totalScore += scores.get(entreKey).asDouble() * entreWeight;
                    totalWeight += entreWeight;
                }

                matchedDimensions++;
            }

            // ========== 年收入匹配（权重0.5，正面+相邻衰减） ==========
            if (user.getAnnualIncome() != null && !user.getAnnualIncome().isBlank()
                    && !"PREFER_NOT_TO_SAY".equalsIgnoreCase(user.getAnnualIncome())) {
                String incomeKey = user.getAnnualIncome().toLowerCase();
                double incomeWeight = coefficientService.getCoefficient("MATCH", "income_weight", 0.5);
                double incomeDecay = coefficientService.getCoefficient("MATCH", "income_decay", 0.40);

                // 正面：精确收入区间
                if (scores.has(incomeKey)) {
                    totalScore += scores.get(incomeKey).asDouble() * incomeWeight;
                    totalWeight += incomeWeight;
                }

                // 模糊匹配：相邻收入区间（衰减）
                List<String> adjacentIncome = getAdjacentIncomes(incomeKey);
                for (String adj : adjacentIncome) {
                    if (scores.has(adj)) {
                        totalScore += scores.get(adj).asDouble() * incomeWeight * incomeDecay;
                        totalWeight += incomeWeight * incomeDecay;
                    }
                }

                matchedDimensions++;
            }

            // ========== 心情状态匹配（权重0.7） ==========
            if (user.getMood() != null) {
                String moodKey = user.getMood().toLowerCase();
                double moodWeight = coefficientService.getCoefficient("MATCH", "mood_weight", 0.7);
                double moodDecay = coefficientService.getCoefficient("MATCH", "mood_decay", 0.40);

                // 正面：精确心情
                if (scores.has(moodKey)) {
                    totalScore += scores.get(moodKey).asDouble() * moodWeight;
                    totalWeight += moodWeight;
                }

                // 模糊匹配：相关心情（衰减）
                List<String> relatedMoods = getRelatedMoods(moodKey);
                for (String adj : relatedMoods) {
                    if (scores.has(adj)) {
                        totalScore += scores.get(adj).asDouble() * moodWeight * moodDecay;
                        totalWeight += moodWeight * moodDecay;
                    }
                }

                matchedDimensions++;
            }

            // 无匹配维度
            if (totalWeight == 0) return 0.5;

            double matchScore = totalScore / totalWeight;

            // ========== 维度覆盖度衰减 ==========
            double coverageFactor = switch (matchedDimensions) {
                case 10 -> covDim10;
                case 9 -> covDim9;
                case 8 -> covDim8;
                case 7 -> covDim7;
                case 6 -> covDim6;
                case 5 -> covDim5;
                case 4 -> covDim4;
                case 3 -> covDim3;
                case 2 -> covDim2;
                case 1 -> covDim1;
                default -> 0.35;
            };

            return matchScore * coverageFactor;
        } catch (Exception e) {
            log.debug("解析相关度得分失败: bookId={} - {}", book.getId(), e.getMessage());
            return 0.5;
        }
    }

    /**
     * 获取相邻年龄段
     * @param direction -1=前一个年龄段，1=后一个年龄段
     */
    private String getAdjacentAgeGroup(int age, int direction) {
        // 先算出当前年龄段的起始年龄
        int[] boundaries = {0, 10, 20, 30, 40, 50, 60, Integer.MAX_VALUE};
        int currentIdx = -1;
        for (int i = 0; i < boundaries.length - 1; i++) {
            if (age >= boundaries[i] && age < boundaries[i + 1]) {
                currentIdx = i;
                break;
            }
        }
        if (currentIdx < 0) return null;

        int adjacentIdx = currentIdx + direction;
        if (adjacentIdx < 0 || adjacentIdx >= boundaries.length - 1) return null;

        return getAgeGroup(boundaries[adjacentIdx]);
    }

    /**
     * 获取 MBTI 相邻类型（每个字母翻转一个，共4个相邻型）
     * 例：INTJ → ENTJ, ISTJ, INFJ, INTP
     */
    private List<String> getAdjacentMbti(String mbti) {
        if (mbti == null || mbti.length() != 4) return List.of();
        List<String> adjacent = new ArrayList<>();
        char[] chars = mbti.toCharArray();
        char[][] flips = {
                {chars[0], chars[0] == 'I' ? 'E' : 'I'},
                {chars[1], chars[1] == 'N' ? 'S' : 'N'},
                {chars[2], chars[2] == 'T' ? 'F' : 'T'},
                {chars[3], chars[3] == 'J' ? 'P' : 'J'}
        };
        for (int i = 0; i < 4; i++) {
            char[] copy = chars.clone();
            copy[i] = flips[i][1];
            adjacent.add(new String(copy));
        }
        return adjacent;
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

    // ==================== 路径D: 探索召回（随机 + 热门补充） ====================

    /**
     * 探索召回：从全量书籍中随机采样 + 热门书补充
     * 目的：打破信息茧房，给用户偶尔的惊喜（serendipity）
     * <p>
     * 采样策略：
     * - 60% 随机采样（均匀分布，任何书都有机会）
     * - 40% 热门书补充（基于阅读量，保证基本质量）
     * <p>
     * 探索召回的书获得较低的初始分数（WEIGHT_EXPLORE 系数由动态配置管理），
     * 但如果恰好匹配用户画像，质量因子和偏好加成会让它浮上来。
     */
    private Map<Long, Double> exploreRecall(User user, List<Long> excludeBookIds) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        Set<Long> excludeSet = new HashSet<>(excludeBookIds);

        int exploreRandomCount = (int) coefficientService.getCoefficient("OTHER", "explore_random_count", 30);

        try {
            long totalBooks = bookRepository.count();
            if (totalBooks == 0) return scores;

            // 1. 随机采样
            int randomCount = (int) (exploreRandomCount * 0.6);
            Set<Long> randomIds = new HashSet<>();
            int attempts = 0;
            while (randomIds.size() < randomCount && attempts < randomCount * 3) {
                long randomId = ThreadLocalRandom.current().nextLong(1, totalBooks + 1);
                if (!excludeSet.contains(randomId)) {
                    randomIds.add(randomId);
                }
                attempts++;
            }
            for (Long bookId : randomIds) {
                Book book = bookRepository.findById(bookId).orElse(null);
                if (book == null) continue;
                // 随机书给一个基础分，基于画像匹配度适度调整
                double baseScore = 0.3 + calculateMatchScore(user, book) * 0.3;
                scores.put(bookId, baseScore);
            }

            // 2. 热门书补充
            int hotCount = (int) (exploreRandomCount * 0.4);
            List<Book> hotBooks = bookRepository.findAllByOrderByReadCountDesc(PageRequest.of(0, hotCount * 3)).getContent();
            int added = 0;
            for (Book book : hotBooks) {
                if (excludeSet.contains(book.getId()) || scores.containsKey(book.getId())) continue;
                double baseScore = 0.3 + calculateMatchScore(user, book) * 0.3;
                scores.put(book.getId(), baseScore);
                added++;
                if (added >= hotCount) break;
            }
        } catch (Exception e) {
            log.debug("探索召回失败: {}", e.getMessage());
        }

        return scores;
    }

    // ==================== 评分融合 + MMR ====================

    /**
     * 融合四路得分：加权平均（缺失路径的权重分配给其他路径）
     */
    private Map<Long, Double> fuseScores(Map<Long, Double> rule, Map<Long, Double> vector,
                                          Map<Long, Double> collab, Map<Long, Double> explore) {
        Set<Long> allBookIds = new HashSet<>();
        allBookIds.addAll(rule.keySet());
        allBookIds.addAll(vector.keySet());
        allBookIds.addAll(collab.keySet());
        allBookIds.addAll(explore.keySet());

        Map<Long, Double> fused = new LinkedHashMap<>();
        for (Long bookId : allBookIds) {
            double r = rule.getOrDefault(bookId, 0.0);
            double v = vector.getOrDefault(bookId, 0.0);
            double c = collab.getOrDefault(bookId, 0.0);
            double e = explore.getOrDefault(bookId, 0.0);

            // 计算有效路径数
            int activePaths = (r > 0 ? 1 : 0) + (v > 0 ? 1 : 0) + (c > 0 ? 1 : 0) + (e > 0 ? 1 : 0);
            if (activePaths == 0) continue;

            // 加权融合（缺失路径的权重分配给其他路径）
            double weightRule = coefficientService.getCoefficient("FUSION", "weight_rule", 0.30);
            double weightVector = coefficientService.getCoefficient("FUSION", "weight_vector", 0.40);
            double weightCollab = coefficientService.getCoefficient("FUSION", "weight_collab", 0.20);
            double weightExplore = coefficientService.getCoefficient("FUSION", "weight_explore", 0.10);

            double totalWeight = 0;
            double totalScore = 0;

            if (r > 0) { totalScore += r * weightRule; totalWeight += weightRule; }
            if (v > 0) { totalScore += v * weightVector; totalWeight += weightVector; }
            if (c > 0) { totalScore += c * weightCollab; totalWeight += weightCollab; }
            if (e > 0) { totalScore += e * weightExplore; totalWeight += weightExplore; }

            // 多路径命中加成（多路命中意味着更可靠的推荐）
            double pathBonus = activePaths >= 4 ? 0.20 : (activePaths >= 3 ? 0.12 : (activePaths >= 2 ? 0.05 : 0.0));

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
                double mmrLambda = coefficientService.getCoefficient("OTHER", "mmr_lambda", 0.7);
                double mmr = mmrLambda * candidate.finalScore - (1 - mmrLambda) * maxSim;

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
        int maxSameAuthor = (int) coefficientService.getCoefficient("OTHER", "max_same_author", 2);

        for (ScoredBook sb : books) {
            String author = sb.book.getAuthor() != null ? sb.book.getAuthor() : "未知";
            int currentCount = authorCount.getOrDefault(author, 0);

            if (currentCount >= maxSameAuthor) continue; // 同作者超过限制，跳过

            authorCount.put(author, currentCount + 1);
            result.add(RecommendedItem.builder()
                    .bookId(sb.book.getId())
                    .title(sb.book.getTitle())
                    .author(sb.book.getAuthor())
                    .coverUrl(sb.book.getCoverUrl())
                    .format(sb.book.getFormat())
                    .rating(sb.book.getRating())
                    .readCount(sb.book.getReadCount())
                    .description(sb.book.getDescription() != null && sb.book.getDescription().length() > 80
                            ? sb.book.getDescription().substring(0, 80) + "..." : sb.book.getDescription())
                    .matchScore(Math.round(sb.finalScore * 100.0) / 100.0)
                    .ruleScore(Math.round(sb.ruleScore * 100.0) / 100.0)
                    .vectorScore(Math.round(sb.vectorScore * 100.0) / 100.0)
                    .collabScore(Math.round(sb.collabScore * 100.0) / 100.0)
                    .recallPaths(sb.recallPaths)
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

    /**
     * 构建召回路径信息（用于反馈追踪和自动调参）
     */
    private String buildRecallPaths(Long bookId, Map<Long, Double> ruleScores,
                                     Map<Long, Double> vectorScores, Map<Long, Double> collabScores,
                                     Map<Long, Double> exploreScores) {
        List<String> paths = new ArrayList<>();
        if (ruleScores.containsKey(bookId) && ruleScores.get(bookId) > 0) paths.add("RULE");
        if (vectorScores.containsKey(bookId) && vectorScores.get(bookId) > 0) paths.add("VECTOR");
        if (collabScores.containsKey(bookId) && collabScores.get(bookId) > 0) paths.add("COLLAB");
        if (exploreScores.containsKey(bookId) && exploreScores.get(bookId) > 0) paths.add("EXPLORE");
        return String.join(",", paths);
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

    /**
     * 计算用户喜欢偏好对推荐分数的加成
     */
    private double calculateIncludeBonus(Book book, List<String> includedTags,
                                          List<String> includedAuthors, List<String> includedFormats) {
        double tagBonus = coefficientService.getCoefficient("PREFERENCE", "tag_bonus", 0.12);
        double authorBonus = coefficientService.getCoefficient("PREFERENCE", "author_bonus", 0.15);
        double formatBonus = coefficientService.getCoefficient("PREFERENCE", "format_bonus", 0.05);

        double bonus = 0.0;
        // 标签匹配加分
        if (!includedTags.isEmpty() && book.getFormatTags() != null) {
            Set<String> bookTags = parseTags(book.getFormatTags());
            for (String tag : includedTags) {
                if (bookTags.stream().anyMatch(t -> t.equalsIgnoreCase(tag))) {
                    bonus += tagBonus;
                }
            }
        }
        // 作者匹配加分
        if (!includedAuthors.isEmpty() && book.getAuthor() != null) {
            for (String author : includedAuthors) {
                if (author.equalsIgnoreCase(book.getAuthor())) {
                    bonus += authorBonus;
                    break;
                }
            }
        }
        // 格式匹配加分
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

    private record ScoredBook(Book book, double finalScore, double matchScore, double qualityFactor, double ruleScore,
                              double vectorScore, double collabScore, String recallPaths) {
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
        private Long readCount;
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
        /**
         * 命中的召回路径（逗号分隔，如 "RULE,VECTOR"），用于反馈追踪
         */
        private String recallPaths;
        private LocalDateTime recommendedAt;
    }
}
