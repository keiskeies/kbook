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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 推荐计算服务类
 * 负责根据用户行为、偏好和书籍特征计算个性化推荐分数，并将结果保存到Redis中
 * 采用规则-based的推荐算法，结合匹配度、质量、新鲜度和偏好等多个维度进行综合评分
 */
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
    private final BookTrashService bookTrashService;

    private static final String SORTED_KEY_PREFIX = "kbook:recommend:sorted:"; // Redis有序集合键前缀
    private static final String SORTED_TEMP_SUFFIX = ":temp"; // 临时键后缀，用于原子性更新

    /**
     * 计算并保存用户推荐列表（带分布式锁）
     * 使用Redis分布式锁确保同一用户同时只有一个推荐计算任务在执行
     * @param userId 用户ID
     * @return 计算后的评分书籍列表
     */
    @RedisLock(key = "'kbook:lock:recommend:' + #userId", leaseTime = 600) // 分布式锁，锁定600秒
    public List<ScoredBook> computeAndSave(Long userId) {
        log.info("获取锁成功，开始计算推荐: userId={}", userId); // 记录开始计算日志
        long startTime = System.currentTimeMillis(); // 记录开始时间用于性能监控

        List<ScoredBook> scoredBooks = computeScoredBooks(userId); // 执行核心推荐计算逻辑
        saveToSortedSetWithTemp(userId, scoredBooks); // 将计算结果保存到Redis有序集合

        long elapsed = System.currentTimeMillis() - startTime; // 计算耗时
        log.info("推荐计算完成: userId={}, count={}, elapsed={}ms", userId, scoredBooks.size(), elapsed); // 记录完成日志
        return scoredBooks; // 返回计算结果
    }

    /**
     * 核心推荐计算逻辑：计算所有候选书籍的推荐分数
     * @param userId 用户ID
     * @return 按最终分数降序排列的评分书籍列表
     */
    private List<ScoredBook> computeScoredBooks(Long userId) {
        User user = userService.getUserById(userId);
        List<Long> readBookIds = getReadBookIds(userId);
        Set<Long> excludeSet = new HashSet<>(readBookIds);
        excludeSet.addAll(bookTrashService.getTrashedBookIds(userId));

        // 获取用户的排除偏好设置
        List<String> excludedTags = getExcludedTags(userId); // 排除的标签
        List<String> excludedAuthors = getExcludedAuthors(userId); // 排除的作者
        List<String> excludedFormats = getExcludedFormats(userId); // 排除的格式
        // 获取用户的包含偏好设置
        List<String> includedTags = getIncludedTags(userId); // 包含的标签
        List<String> includedAuthors = getIncludedAuthors(userId); // 包含的作者
        List<String> includedFormats = getIncludedFormats(userId); // 包含的格式

        // 获取规则最小分数阈值，低于此分数的书籍将被过滤
        double ruleMinScore = coefficientService.getCoefficient("OTHER", "rule_min_score", -0.5);

        List<ScoredBook> scoredBooks = new ArrayList<>(); // 存储评分结果的列表

        // 分页查询书籍，避免 findAll() 全量加载导致内存和 CPU 压力
        int pageSize = 500;
        int pageNumber = 0;
        Page<Book> bookPage;
        do {
            Pageable pageable = PageRequest.of(pageNumber, pageSize);
            bookPage = bookRepository.findAllByOrderByIdAsc(pageable);

            for (Book book : bookPage.getContent()) {
                if (excludeSet.contains(book.getId())) continue; // 跳过已读/交互过的书籍
                if (isExcludedByPreference(book, excludedTags, excludedAuthors, excludedFormats)) continue; // 跳过被用户排除偏好的书籍

                // 计算基础匹配分数（基于用户画像与书籍特征的相似度）
                double matchScore = RecommendMatchCalculator.calculateMatchScore(user, book, coefficientService, null, dimensionStatsService);
                if (matchScore <= ruleMinScore) continue; // 如果匹配分数低于阈值则跳过

                // 计算各项加分项
                double qualityBonus = calculateQualityBonus(book.getRating());
                double freshnessBonus = calculateFreshnessBonus(book.getCreatedAt());
                double preferenceBonus = calculateIncludeBonus(book, includedTags, includedAuthors, includedFormats);
                double rawFinalScore = matchScore + qualityBonus + freshnessBonus + preferenceBonus;
                double finalScore = RecommendMatchCalculator.normalizeScore(rawFinalScore);

                scoredBooks.add(new ScoredBook(book, finalScore, matchScore, qualityBonus, "RULE"));
            }
            pageNumber++;
        } while (bookPage.hasNext());

        addExploreBooks(user, excludeSet, scoredBooks); // 添加探索性书籍（随机+热门）

        scoredBooks.sort((a, b) -> Double.compare(b.finalScore, a.finalScore)); // 按最终分数降序排序
        return scoredBooks; // 返回排序后的结果
    }

    /**
     * 添加探索性书籍到推荐列表
     * 探索性书籍包括随机书籍和热门书籍，用于增加推荐的多样性和发现新内容
     * @param user 用户对象
     * @param excludeSet 需要排除的书籍ID集合
     * @param scoredBooks 当前评分书籍列表（会被修改）
     */
    private void addExploreBooks(User user, Set<Long> excludeSet, List<ScoredBook> scoredBooks) {
        // 获取探索性书籍总数配置，默认30本
        int exploreRandomCount = (int) coefficientService.getCoefficient("OTHER", "explore_random_count", 30);
        // 构建已存在书籍ID集合，避免重复添加
        Set<Long> existingIds = scoredBooks.stream()
                .map(sb -> sb.book.getId())
                .collect(Collectors.toSet());

        // 添加随机探索书籍（占探索总数的60%）
        int randomCount = (int) (exploreRandomCount * 0.6); // 计算随机书籍数量
        List<Book> randomBooks = bookRepository.findRandomBooks(randomCount * 2); // 获取随机书籍（多取一些以防重复）
        int added = 0; // 已添加计数器
        for (Book book : randomBooks) {
            if (excludeSet.contains(book.getId()) || existingIds.contains(book.getId())) continue; // 跳过已存在或需排除的书籍
            // 计算基础分数：固定基数0.3 + 匹配分数*0.3的权重
            double baseScore = 0.3 + RecommendMatchCalculator.calculateMatchScore(user, book, coefficientService, null, dimensionStatsService) * 0.3;
            scoredBooks.add(new ScoredBook(book, baseScore, baseScore, 0.0, "EXPLORE")); // 添加探索书籍，标记为EXPLORE类型
            existingIds.add(book.getId()); // 添加到已存在集合
            added++; // 计数器递增
            if (added >= randomCount) break; // 达到目标数量后退出
        }

        // 添加热门探索书籍（占探索总数的40%）
        int hotCount = (int) (exploreRandomCount * 0.4); // 计算热门书籍数量
        List<Book> hotBooks = bookRepository.findAllByOrderByReadCountDesc(PageRequest.of(0, hotCount * 3)).getContent(); // 按阅读量降序获取热门书籍
        added = 0; // 重置计数器
        for (Book book : hotBooks) {
            if (excludeSet.contains(book.getId()) || existingIds.contains(book.getId())) continue; // 跳过已存在或需排除的书籍
            // 计算基础分数：固定基数0.3 + 匹配分数*0.3的权重
            double baseScore = 0.3 + RecommendMatchCalculator.calculateMatchScore(user, book, coefficientService, null, dimensionStatsService) * 0.3;
            scoredBooks.add(new ScoredBook(book, baseScore, baseScore, 0.0, "EXPLORE")); // 添加探索书籍，标记为EXPLORE类型
            existingIds.add(book.getId()); // 添加到已存在集合
            added++; // 计数器递增
            if (added >= hotCount) break; // 达到目标数量后退出
        }
    }

    /**
     * 使用临时键策略将推荐结果保存到Redis有序集合
     * 采用先写入临时键再重命名的方式实现原子性更新，避免读取到不完整的数据
     * @param userId 用户ID
     * @param scoredBooks 评分书籍列表
     */
    private void saveToSortedSetWithTemp(Long userId, List<ScoredBook> scoredBooks) {
        try {
            String tempKey = SORTED_KEY_PREFIX + userId + SORTED_TEMP_SUFFIX; // 构建临时键名
            String realKey = SORTED_KEY_PREFIX + userId; // 构建正式键名

            redisTemplate.delete(tempKey); // 删除可能存在的旧临时键
            // 将所有评分书籍添加到临时有序集合中
            for (ScoredBook sb : scoredBooks) {
                redisTemplate.opsForZSet().add(tempKey, sb.book.getId(), sb.finalScore); // 以书籍ID为成员，最终分数为分数值
            }
            redisTemplate.delete(realKey); // 删除旧的正式键
            redisTemplate.rename(tempKey, realKey); // 原子性重命名临时键为正式键
        } catch (Exception e) {
            log.debug("写入推荐Sorted Set(temp)失败: {}", e.getMessage()); // 记录失败日志但不中断流程
        }
    }

    /**
     * 直接将推荐结果保存到Redis有序集合（不使用临时键策略）
     * 适用于不需要原子性保证的场景
     * @param userId 用户ID
     * @param scoredBooks 评分书籍列表
     */
    void saveToSortedSetDirect(Long userId, List<ScoredBook> scoredBooks) {
        try {
            String sortedKey = SORTED_KEY_PREFIX + userId; // 构建有序集合键名
            redisTemplate.delete(sortedKey); // 删除旧的有序集合
            // 将所有评分书籍添加到有序集合中
            for (ScoredBook sb : scoredBooks) {
                redisTemplate.opsForZSet().add(sortedKey, sb.book.getId(), sb.finalScore); // 以书籍ID为成员，最终分数为分数值
            }
        } catch (Exception e) {
            log.debug("写入推荐Sorted Set失败: {}", e.getMessage()); // 记录失败日志但不中断流程
        }
    }

    /**
     * 计算书籍质量加分
     * 根据书籍评分给予不同的质量加分，高分书籍获得更多加分，低分书籍可能被扣分
     * @param rating 书籍评分（1-5分）
     * @return 质量加分值
     */
    private double calculateQualityBonus(Double rating) {
        if (rating == null || rating <= 0) return -0.05; // 无评分或无效评分时扣0.05分
        if (rating < 2.0) return -0.15 + (rating - 1.0) * 0.07; // 1-2分区间：从-0.15线性增长到-0.08
        else if (rating < 3.0) return -0.08 + (rating - 2.0) * 0.06; // 2-3分区间：从-0.08线性增长到-0.02
        else if (rating < 4.0) return -0.02 + (rating - 3.0) * 0.06; // 3-4分区间：从-0.02线性增长到0.04
        else return 0.04 + (rating - 4.0) * 0.06; // 4-5分区间：从0.04线性增长到0.10
    }

    /**
     * 计算书籍新鲜度加分
     * 新书籍获得更高的新鲜度加分，随着时间推移加分逐渐递减
     * @param createdAt 书籍创建时间
     * @return 新鲜度加分值
     */
    private double calculateFreshnessBonus(LocalDateTime createdAt) {
        if (createdAt == null) return 0; // 无创建时间时不加不减
        long daysAgo = ChronoUnit.DAYS.between(createdAt, LocalDateTime.now()); // 计算距今天数
        if (daysAgo < 0) daysAgo = 0; // 处理未来时间的异常情况
        if (daysAgo <= 7) return 0.05 * (1.0 - (double) daysAgo / 7); // 7天内：从0.05线性递减到0
        else if (daysAgo <= 30) return 0.02 * (1.0 - (double) (daysAgo - 7) / 23); // 8-30天：从0.02线性递减到0
        return 0; // 超过30天无新鲜度加分
    }

    /**
     * 计算用户包含偏好加分
     * 当书籍符合用户的包含偏好（标签、作者、格式）时给予额外加分
     * @param book 书籍对象
     * @param includedTags 用户包含的标签列表
     * @param includedAuthors 用户包含的作者列表
     * @param includedFormats 用户包含的格式列表
     * @return 偏好加分值
     */
    private double calculateIncludeBonus(Book book, List<String> includedTags,
                                          List<String> includedAuthors, List<String> includedFormats) {
        // 从配置服务获取各类偏好的加分系数
        double tagBonus = coefficientService.getCoefficient("PREFERENCE", "tag_bonus", 0.12); // 标签加分系数，默认0.12
        double authorBonus = coefficientService.getCoefficient("PREFERENCE", "author_bonus", 0.15); // 作者加分系数，默认0.15
        double formatBonus = coefficientService.getCoefficient("PREFERENCE", "format_bonus", 0.05); // 格式加分系数，默认0.05

        double bonus = 0.0; // 初始化总加分
        // 检查标签匹配情况
        if (!includedTags.isEmpty() && book.getFormatTags() != null) {
            Set<String> bookTags = parseTags(book.getFormatTags()); // 解析书籍标签
            for (String tag : includedTags) {
                // 如果书籍包含用户喜欢的标签，则累加标签加分
                if (bookTags.stream().anyMatch(t -> t.equalsIgnoreCase(tag))) bonus += tagBonus;
            }
        }
        // 检查作者匹配情况
        if (!includedAuthors.isEmpty() && book.getAuthor() != null) {
            for (String author : includedAuthors) {
                // 如果书籍作者是用户喜欢的作者，则累加作者加分（只加一次）
                if (author.equalsIgnoreCase(book.getAuthor())) { bonus += authorBonus; break; }
            }
        }
        // 检查格式匹配情况
        if (!includedFormats.isEmpty() && book.getFormat() != null) {
            for (String format : includedFormats) {
                // 如果书籍格式是用户喜欢的格式，则累加格式加分（只加一次）
                if (format.equalsIgnoreCase(book.getFormat())) { bonus += formatBonus; break; }
            }
        }
        return bonus; // 返回总偏好加分
    }

    /**
     * 检查书籍是否被用户排除偏好所排除
     * @param book 待检查的书籍
     * @param excludedTags 用户排除的标签列表
     * @param excludedAuthors 用户排除的作者列表
     * @param excludedFormats 用户排除的格式列表
     * @return true表示应被排除，false表示不应被排除
     */
    private boolean isExcludedByPreference(Book book, List<String> excludedTags,
                                            List<String> excludedAuthors, List<String> excludedFormats) {
        // 检查格式是否在排除列表中
        if (!excludedFormats.isEmpty() && book.getFormat() != null
                && excludedFormats.contains(book.getFormat().toUpperCase())) return true;
        // 检查作者是否在排除列表中（忽略大小写）
        if (!excludedAuthors.isEmpty() && book.getAuthor() != null
                && excludedAuthors.stream().anyMatch(a -> a.equalsIgnoreCase(book.getAuthor()))) return true;
        // 检查标签是否在排除列表中
        if (!excludedTags.isEmpty() && book.getFormatTags() != null) {
            Set<String> bookTags = parseTags(book.getFormatTags()); // 解析书籍标签
            for (String excludedTag : excludedTags) {
                // 如果书籍包含任一被排除的标签，则返回true
                if (bookTags.stream().anyMatch(t -> t.equalsIgnoreCase(excludedTag))) return true;
            }
        }
        return false; // 没有任何排除条件匹配，返回false
    }

    /**
     * 获取用户已读或交互过的书籍ID列表
     * 合并阅读历史和阅读进度中的书籍ID，去重后返回
     * @param userId 用户ID
     * @return 已读/交互过的书籍ID列表
     */
    private List<Long> getReadBookIds(Long userId) {
        Set<Long> ids = new LinkedHashSet<>(); // 使用LinkedHashSet保持插入顺序并去重
        ids.addAll(readHistoryRepository.findAllInteractedBookIdsByUserId(userId)); // 添加阅读历史中的书籍ID
        ids.addAll(progressRepository.findAllBookIdsByUserId(userId)); // 添加阅读进度中的书籍ID
        return new ArrayList<>(ids); // 转换为List返回
    }

    /**
     * 获取用户排除的标签列表
     * @param userId 用户ID
     * @return 排除的标签列表
     */
    private List<String> getExcludedTags(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "TAG", "EXCLUDE")
                .stream().map(com.kbook.entity.UserBookPreference::getValue).toList(); // 提取偏好值并转为列表
    }

    /**
     * 获取用户排除的作者列表
     * @param userId 用户ID
     * @return 排除的作者列表
     */
    private List<String> getExcludedAuthors(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "AUTHOR", "EXCLUDE")
                .stream().map(com.kbook.entity.UserBookPreference::getValue).toList(); // 提取偏好值并转为列表
    }

    /**
     * 获取用户排除的格式列表
     * @param userId 用户ID
     * @return 排除的格式列表
     */
    private List<String> getExcludedFormats(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "FORMAT", "EXCLUDE")
                .stream().map(com.kbook.entity.UserBookPreference::getValue).toList(); // 提取偏好值并转为列表
    }

    /**
     * 获取用户包含的标签列表
     * @param userId 用户ID
     * @return 包含的标签列表
     */
    private List<String> getIncludedTags(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "TAG", "INCLUDE")
                .stream().map(com.kbook.entity.UserBookPreference::getValue).toList(); // 提取偏好值并转为列表
    }

    /**
     * 获取用户包含的作者列表
     * @param userId 用户ID
     * @return 包含的作者列表
     */
    private List<String> getIncludedAuthors(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "AUTHOR", "INCLUDE")
                .stream().map(com.kbook.entity.UserBookPreference::getValue).toList(); // 提取偏好值并转为列表
    }

    /**
     * 获取用户包含的格式列表
     * @param userId 用户ID
     * @return 包含的格式列表
     */
    private List<String> getIncludedFormats(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "FORMAT", "INCLUDE")
                .stream().map(com.kbook.entity.UserBookPreference::getValue).toList(); // 提取偏好值并转为列表
    }

    /**
     * 解析书籍标签字符串为标签集合
     * 支持多种分隔符和格式，如JSON数组格式或逗号分隔格式
     * @param formatTags 标签字符串，可能包含方括号、引号等字符
     * @return 解析后的标签集合
     */
    private Set<String> parseTags(String formatTags) {
        if (formatTags == null || formatTags.isBlank()) return Set.of(); // 空值处理，返回空集合
        // 去除方括号和引号，按中英文逗号分割，修剪空白，过滤空字符串
        return Arrays.stream(formatTags.replaceAll("[\\[\\]\"]", "").split("[,，]"))
                .map(String::trim)
                .filter(t -> !t.isBlank())
                .collect(Collectors.toSet());
    }

    /**
     * 评分书籍记录类
     * 封装书籍及其各项评分信息，用于推荐结果传输
     * @param book 书籍对象
     * @param finalScore 最终综合分数
     * @param matchScore 基础匹配分数
     * @param qualityBonus 质量加分
     * @param recallPath 召回路径标识（RULE=规则推荐，EXPLORE=探索推荐）
     */
    public record ScoredBook(Book book, double finalScore, double matchScore, double qualityBonus, String recallPath) {
    }
}
