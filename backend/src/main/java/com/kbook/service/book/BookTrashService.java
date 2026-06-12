package com.kbook.service.book;
import com.kbook.service.user.UserService;

import com.kbook.service.recommend.RecommendMatchCalculator;

import com.kbook.service.recommend.RecommendService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kbook.common.exception.BusinessException;
import com.kbook.dto.book.BookTrashItem;
import com.kbook.entity.Book;
import com.kbook.entity.BookTrash;
import com.kbook.entity.User;
import com.kbook.repository.BookRepository;
import com.kbook.repository.BookTrashRepository;
import lombok.extern.slf4j.Slf4j;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.config.annotation.RedisLock;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.kbook.common.util.QueryBuilder.*;

/**
 * 图书垃圾桶服务类
 * <p>
 * 负责处理图书的垃圾桶相关操作，包括将图书移入/移出垃圾桶、
 * 计算和更新图书维度评分等功能。
 * </p>
 */
@Slf4j
@Service
@LogModule("回收站")
public class BookTrashService {

    private final BookTrashRepository bookTrashRepository;
    private final BookRepository bookRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final RecommendService recommendService;

    public BookTrashService(
            BookTrashRepository bookTrashRepository,
            BookRepository bookRepository,
            UserService userService,
            ObjectMapper objectMapper,
            @Lazy RecommendService recommendService) {
        this.bookTrashRepository = bookTrashRepository;
        this.bookRepository = bookRepository;
        this.userService = userService;
        this.objectMapper = objectMapper;
        this.recommendService = recommendService;
    }

    private static final int BASE_RATER_COUNT = 1000;
    private static final double TRASH_PENALTY_CONTRIBUTION = -0.5;

    /**
     * 将图书移动到垃圾桶
     *
     * @param userId 用户ID
     * @param bookId 图书ID
     * @throws BusinessException 当图书不存在或已在垃圾桶中时抛出异常
     */
    @Transactional
    @LogAction("移入垃圾桶")
    @RedisLock(key = "'trash:' + #userId + ':' + #bookId", leaseTime = 10)
    public void moveToTrash(Long userId, Long bookId) {
        // 检查图书是否存在
        if (!bookRepository.existsById(bookId)) {
            throw new BusinessException("图书不存在");
        }
        // 检查是否已经在垃圾桶中
        if (bookTrashRepository.query()
                .where(BookTrash::getUserId, eq(userId))
                .and(BookTrash::getBookId, eq(bookId))
                .exists()) {
            throw new BusinessException("已在垃圾桶中");
        }
        // 创建垃圾桶记录并保存
        BookTrash item = BookTrash.builder()
                .userId(userId)
                .bookId(bookId)
                .build();
        bookTrashRepository.save(item);

        // 获取图书信息并更新维度评分计数
        Book book = bookRepository.findById(bookId).orElseThrow();
        int oldCount = book.getDimensionRatingCount() != null ? book.getDimensionRatingCount() : 0;
        int newCount = oldCount + 1;
        book.setDimensionRatingCount(newCount);

        // 获取用户信息并重新计算维度评分
        User user = userService.getUserById(userId);
        recalculateDimensionScores(book, user, TRASH_PENALTY_CONTRIBUTION, oldCount, newCount);

        // 保存更新后的图书信息
        bookRepository.save(book);

        // 从推荐有序集合中移除该图书，避免已丢弃的图书仍出现在推荐中
        recommendService.removeSingleBook(userId, bookId);

        // 记录日志
        log.info("图书丢入垃圾桶: userId={}, bookId={}, dimensionRatingCount={}", userId, bookId, newCount);
    }

    /**
     * 从垃圾桶中移除图书
     *
     * @param userId 用户ID
     * @param bookId 图书ID
     * @throws BusinessException 当图书不在垃圾桶中时抛出异常
     */
    @Transactional
    @LogAction("移出垃圾桶")
    @RedisLock(key = "'trash:' + #userId + ':' + #bookId", leaseTime = 10)
    public void removeFromTrash(Long userId, Long bookId) {
        // 检查是否在垃圾桶中
        BookTrash trash = bookTrashRepository.query()
                .where(BookTrash::getUserId, eq(userId))
                .and(BookTrash::getBookId, eq(bookId))
                .list(1)
                .stream().findFirst().orElse(null);
        if (trash == null) {
            throw new BusinessException("不在垃圾桶中");
        }
        // 删除垃圾桶记录
        bookTrashRepository.delete(trash);

        // 获取图书信息并更新维度评分计数
        Book book = bookRepository.findById(bookId).orElseThrow();
        int oldCount = book.getDimensionRatingCount() != null ? book.getDimensionRatingCount() : 0;
        int newCount = Math.max(0, oldCount - 1);
        book.setDimensionRatingCount(newCount);

        // 获取用户信息并反向计算维度评分
        User user = userService.getUserById(userId);
        reverseCalculateDimensionScores(book, user, oldCount, newCount, TRASH_PENALTY_CONTRIBUTION);

        // 保存更新后的图书信息
        bookRepository.save(book);

        // 单独计算该图书的匹配得分并加入推荐有序集合
        recommendService.computeAndAddSingleBook(userId, bookId);

        // 记录日志
        log.info("图书移出垃圾桶: userId={}, bookId={}, dimensionRatingCount={}", userId, bookId, newCount);
    }

    /**
     * 在加入书架时更新维度评分
     *
     * @param userId 用户ID
     * @param bookId 图书ID
     */
    @Transactional
    @LogAction("加入书架更新维度")
    @RedisLock(key = "'trash:' + #userId + ':' + #bookId", leaseTime = 10)
    public void updateDimensionScoresOnBookshelf(Long userId, Long bookId) {
        // 获取图书信息
        Book book = bookRepository.findById(bookId).orElseThrow();
        int oldCount = book.getDimensionRatingCount() != null ? book.getDimensionRatingCount() : 0;
        int newCount = oldCount + 1;
        book.setDimensionRatingCount(newCount);

        // 获取用户信息并重新计算维度评分
        User user = userService.getUserById(userId);
        recalculateDimensionScores(book, user, 1.0, oldCount, newCount);

        // 保存更新后的图书信息
        bookRepository.save(book);
        // 记录日志
        log.info("加入书架更新维度得分: userId={}, bookId={}, dimensionRatingCount={}", userId, bookId, newCount);
    }

    /**
     * 在移出书架时反向更新维度评分
     *
     * @param userId 用户ID
     * @param bookId 图书ID
     */
    @Transactional
    @LogAction("移出书架更新维度")
    @RedisLock(key = "'trash:' + #userId + ':' + #bookId", leaseTime = 10)
    public void reverseDimensionScoresOnBookshelf(Long userId, Long bookId) {
        // 获取图书信息，如果不存在则直接返回
        Book book = bookRepository.findById(bookId).orElse(null);
        if (book == null) return;
        int oldCount = book.getDimensionRatingCount() != null ? book.getDimensionRatingCount() : 0;
        // 如果当前计数为0，则无需处理
        if (oldCount == 0) return;
        int newCount = oldCount - 1;
        book.setDimensionRatingCount(newCount);

        // 获取用户信息并反向计算维度评分
        User user = userService.getUserById(userId);
        reverseCalculateDimensionScores(book, user, oldCount, newCount, 1.0);

        // 保存更新后的图书信息
        bookRepository.save(book);
        // 记录日志
        log.info("移出书架更新维度得分: userId={}, bookId={}, dimensionRatingCount={}", userId, bookId, newCount);
    }

    /**
     * 检查指定用户的图书是否在垃圾桶中
     *
     * @param userId 用户ID
     * @param bookId 图书ID
     * @return 如果在垃圾桶中返回true，否则返回false
     */
    @LogAction("检查是否在垃圾桶")
    public boolean isInTrash(Long userId, Long bookId) {
        return bookTrashRepository.query()
                .where(BookTrash::getUserId, eq(userId))
                .and(BookTrash::getBookId, eq(bookId))
                .exists();
    }

    /**
     * 获取用户的垃圾桶列表
     *
     * @param userId 用户ID
     * @return 垃圾桶中的图书项列表
     */
    @LogAction("获取垃圾桶列表")
    public List<BookTrashItem> getTrashList(Long userId) {
        // 查询用户的垃圾桶记录，按创建时间降序排列
        List<BookTrash> trashItems = bookTrashRepository.query()
                .where(BookTrash::getUserId, eq(userId))
                .orderByDesc(BookTrash::getCreatedAt)
                .list();
        if (trashItems.isEmpty()) return new ArrayList<>();

        // 提取所有图书ID并批量查询图书信息
        List<Long> bookIds = trashItems.stream().map(BookTrash::getBookId).collect(Collectors.toList());
        Map<Long, Book> bookMap = bookRepository.findAllById(bookIds)
                .stream().collect(Collectors.toMap(Book::getId, b -> b));

        // 构建垃圾桶项列表
        return trashItems.stream().map(item -> {
            Book book = bookMap.get(item.getBookId());
            return BookTrashItem.builder()
                    .trashId(item.getId())
                    .bookId(item.getBookId())
                    .title(book != null ? book.getTitle() : "未知")
                    .author(book != null ? book.getAuthor() : null)
                    .coverUrl(book != null ? book.getCoverUrl() : null)
                    .format(book != null ? book.getFormat() : null)
                    .formatTags(book != null ? book.getFormatTags() : null)
                    .fileSize(book != null ? book.getFileSize() : null)
                    .rating(book != null ? book.getRating() : 0.0)
                    .trashedAt(item.getCreatedAt())
                    .build();
        }).collect(Collectors.toList());
    }

    /**
     * 获取用户垃圾桶中的图书数量
     *
     * @param userId 用户ID
     * @return 垃圾桶中的图书数量
     */
    @LogAction("获取垃圾桶数量")
    public long getTrashCount(Long userId) {
        return bookTrashRepository.query()
                .where(BookTrash::getUserId, eq(userId))
                .count();
    }

    /**
     * 获取用户垃圾桶中的所有图书ID
     *
     * @param userId 用户ID
     * @return 垃圾桶中的图书ID列表
     */
    @LogAction("获取垃圾桶中的图书ID")
    public List<Long> getTrashedBookIds(Long userId) {
        return bookTrashRepository.query()
                .where(BookTrash::getUserId, eq(userId))
                .orderByDesc(BookTrash::getCreatedAt)
                .list()
                .stream().map(BookTrash::getBookId).collect(Collectors.toList());
    }

    /**
     * 重新计算图书的维度评分
     * <p>
     * 根据用户贡献值和旧的评分计数，重新计算图书各维度的相关性评分。
     * 使用加权平均算法，考虑基础评分者数量和实际评分者数量。
     * </p>
     *
     * @param book              图书对象
     * @param user              用户对象
     * @param userContribution  用户对该维度的贡献值
     * @param oldCount          旧的评分计数
     * @param newCount          新的评分计数
     */
    void recalculateDimensionScores(Book book, User user, double userContribution, int oldCount, int newCount) {
        // 如果图书没有相关性评分数据，则直接返回
        if (book.getRelevanceScores() == null || book.getRelevanceScores().isBlank()) return;

        try {
            // 解析现有的评分JSON数据
            JsonNode scores = objectMapper.readTree(book.getRelevanceScores());
            ObjectNode newScores = objectMapper.createObjectNode();

            // 提取用户维度映射
            Map<String, Double> userDimensions = extractUserDimensionMap(user);

            // 遍历所有评分维度
            var iter = scores.fields();
            while (iter.hasNext()) {
                var entry = iter.next();
                String key = entry.getKey();
                double oldScore = entry.getValue().asDouble();

                // 获取用户在该维度的贡献值
                double contribution = userDimensions.getOrDefault(key, userContribution);

                // 根据旧计数计算新评分
                double newScore;
                if (oldCount == 0) {
                    // 如果是第一个评分，使用基础评分者数量计算
                    newScore = (oldScore * BASE_RATER_COUNT + contribution) / (BASE_RATER_COUNT + newCount);
                } else {
                    // 否则基于之前的总分计算
                    double previousTotal = oldScore * (BASE_RATER_COUNT + oldCount);
                    newScore = (previousTotal + contribution) / (BASE_RATER_COUNT + newCount);
                }

                // 限制评分范围在0-1之间，并保留6位小数精度
                newScore = Math.max(0.0, Math.min(1.0, newScore));
                newScores.put(key, Math.round(newScore * 1000000.0) / 1000000.0);
            }

            // 更新图书的相关性评分
            book.setRelevanceScores(objectMapper.writeValueAsString(newScores));
        } catch (Exception e) {
            // 记录重算失败的警告日志
            log.warn("重算维度得分失败: bookId={} - {}", book.getId(), e.getMessage());
        }
    }


    /**
     * 反向计算图书的维度评分（带默认贡献值）
     * <p>
     * 用于从评分中移除某个用户的贡献，与recalculateDimensionScores相反。
     * </p>
     *
     * @param book                图书对象
     * @param user                用户对象
     * @param oldCount            旧的评分计数
     * @param newCount            新的评分计数
     * @param defaultContribution 默认贡献值
     */
    private void reverseCalculateDimensionScores(Book book, User user, int oldCount, int newCount, double defaultContribution) {
        // 如果没有评分数据或旧计数为0，则直接返回
        if (book.getRelevanceScores() == null || book.getRelevanceScores().isBlank()) return;
        if (oldCount == 0) return;

        try {
            // 解析现有的评分JSON数据
            JsonNode scores = objectMapper.readTree(book.getRelevanceScores());
            ObjectNode newScores = objectMapper.createObjectNode();

            // 提取用户维度映射
            Map<String, Double> userDimensions = extractUserDimensionMap(user);

            // 遍历所有评分维度
            var iter = scores.fields();
            while (iter.hasNext()) {
                var entry = iter.next();
                String key = entry.getKey();
                double currentScore = entry.getValue().asDouble();

                // 获取用户在该维度的贡献值
                double contribution = userDimensions.getOrDefault(key, defaultContribution);

                // 计算当前总分并减去用户贡献得到之前的总分
                double currentTotal = currentScore * (BASE_RATER_COUNT + oldCount);
                double previousTotal = currentTotal - contribution;

                // 根据新计数计算新评分
                double newScore;
                if (newCount == 0) {
                    // 如果没有剩余评分，使用基础评分者数量
                    newScore = previousTotal / BASE_RATER_COUNT;
                } else {
                    // 否则基于剩余评分者数量计算
                    newScore = previousTotal / (BASE_RATER_COUNT + newCount);
                }

                // 限制评分范围在0-1之间，并保留6位小数精度
                newScore = Math.max(0.0, Math.min(1.0, newScore));
                newScores.put(key, Math.round(newScore * 1000000.0) / 1000000.0);
            }

            // 更新图书的相关性评分
            book.setRelevanceScores(objectMapper.writeValueAsString(newScores));
        } catch (Exception e) {
            // 记录反向重算失败的警告日志
            log.warn("反向重算维度得分失败: bookId={} - {}", book.getId(), e.getMessage());
        }
    }

    /**
     * 从用户信息中提取维度映射
     * <p>
     * 根据用户的各种属性（年龄、性别、婚姻状况等）构建维度键值对映射，
     * 用于计算用户对不同图书维度的贡献值。
     * </p>
     *
     * @param user 用户对象
     * @return 维度名称到贡献值的映射
     */
    Map<String, Double> extractUserDimensionMap(User user) {
        // 创建有序的维度映射
        Map<String, Double> dims = new java.util.LinkedHashMap<>();

        // 处理生日维度：计算年龄组
        if (user.getBirthday() != null) {
            int age = Period.between(user.getBirthday(), java.time.LocalDate.now()).getYears();
            String ageGroup = RecommendMatchCalculator.getAgeGroup(age);
            dims.put(ageGroup, 1.0);
        }

        // 处理性别维度
        if (user.getGender() != null) {
            String genderKey = "MALE".equals(user.getGender()) ? "male" : "female";
            dims.put(genderKey, 1.0);
        }

        // 处理婚姻状况维度
        if (user.getMarried() != null) {
            dims.put(user.getMarried() ? "married" : "unmarried", 1.0);
        }

        // 处理孩子年龄区间维度（优先新字段，兜底旧字段）
        if (user.getChildrenAgeRanges() != null && !user.getChildrenAgeRanges().isBlank()) {
            String[] ranges = user.getChildrenAgeRanges().split(",");
            for (String range : ranges) {
                String key = range.trim().toLowerCase();
                if (!key.isEmpty()) {
                    dims.put(key, 1.0);
                }
            }
        } else if (user.getHasChildren() != null) {
            dims.put(user.getHasChildren() ? "hasChildren" : "noChildren", 1.0);
        }

        // 处理MBTI性格类型维度
        if (user.getMbti() != null) {
            dims.put(user.getMbti().toUpperCase(), 1.0);
        }

        // 处理职业维度：支持多个职业，用逗号分隔
        if (user.getOccupation() != null && !user.getOccupation().isBlank()) {
            String[] occList = user.getOccupation().split(",");
            for (String occ : occList) {
                String key = occ.trim().toLowerCase();
                if (!key.isEmpty()) {
                    dims.put(key, 1.0);
                }
            }
        }

        // 处理教育程度维度
        if (user.getAspirationEducation() != null) {
            dims.put(user.getAspirationEducation().toLowerCase(), 1.0);
        }

        // 处理创业意向维度
        if (user.getEntrepreneurship() != null && !user.getEntrepreneurship().isBlank()) {
            String key = user.getEntrepreneurship().toLowerCase();
            if (key.contains("entrepreneur") || key.contains("want")) {
                dims.put("entrepreneur", 1.0);
            } else {
                dims.put("notInterested", 1.0);
            }
        }

        // 处理年收入维度：排除不愿透露的情况
        if (user.getAspirationIncome() != null && !user.getAspirationIncome().isBlank()
                && !"PREFER_NOT_TO_SAY".equalsIgnoreCase(user.getAspirationIncome())) {
            dims.put(user.getAspirationIncome().toLowerCase(), 1.0);
        }

        // 处理心情维度
        if (user.getMood() != null) {
            dims.put(user.getMood().toLowerCase(), 1.0);
        }

        return dims;
    }
}
