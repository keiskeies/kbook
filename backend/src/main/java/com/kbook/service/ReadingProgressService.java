package com.kbook.service;

import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.dto.ProgressBatchItem;
import com.kbook.dto.ReadingHistoryVO;
import com.kbook.dto.ReadingStats;
import com.kbook.entity.Book;
import com.kbook.entity.ReadingProgress;
import com.kbook.repository.BookRepository;
import com.kbook.repository.ReadingProgressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 阅读进度服务类
 * <p>
 * 负责管理用户的阅读进度，包括上报、查询、批量同步等功能
 * 支持多种书籍格式（EPUB/TXT/PDF）的进度计算
 * <p>
 * 进度计算规则：
 * - EPUB/TXT: progress = 已读字符数 / 总字符数 (0.0~1.0)
 * - PDF: progress = 当前页码 / 总页数 (0.0~1.0)
 * <p>
 * 冲突解决策略：时间戳覆盖（最新写入胜出）
 * 断网降级方案：前端本地存储，联网后上报
 */
@Slf4j
@Service
@LogModule("进度")
@RequiredArgsConstructor
public class ReadingProgressService {

    private final ReadingProgressRepository progressRepository; // 阅读进度数据访问层
    private final BookRepository bookRepository; // 书籍数据访问层


    /**
     * 上报阅读进度
     * 上报时机：翻页/切后台时触发
     * 冲突解决：时间戳覆盖策略（始终接受最新上报）
     *
     * @param userId 用户ID
     * @param bookId 书籍ID
     * @param progress 阅读进度（0.0~1.0）
     * @param currentPosition 当前位置标识（页码或字符位置）
     * @return ProgressResult 包含进度记录和是否是新创建的标志
     */
    @Transactional // 开启事务保证数据一致性
    @LogAction("上报阅读进度")
    public ProgressResult reportProgress(Long userId, Long bookId, Double progress, String currentPosition) {
        log.debug("上报阅读进度: userId={}, bookId={}, progress={}", userId, bookId, progress); // 记录调试日志

        // 查询是否已存在该用户的阅读进度记录
        ReadingProgress existing = progressRepository.findByUserIdAndBookId(userId, bookId).orElse(null);
        boolean isNew = (existing == null);

        ReadingProgress rp = isNew ? ReadingProgress.builder()
                                     .userId(userId)
                                     .bookId(bookId)
                                     .build() : existing;

        rp.setProgress(clampProgress(progress));
        rp.setCurrentPosition(currentPosition);

        try {
            ReadingProgress saved = progressRepository.save(rp);
            log.debug("阅读进度保存成功: userId={}, bookId={}, progress={}, isNew={}", userId, bookId, saved.getProgress(), isNew);
            return new ProgressResult(saved, isNew);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 并发竞态：两次请求同时 INSERT 同一条记录，第二次触发唯一约束冲突
            // 此时重试：直接查出现有记录并更新
            if (isNew) {
                log.debug("并发冲突，重试更新已有进度: userId={}, bookId={}", userId, bookId);
                existing = progressRepository.findByUserIdAndBookId(userId, bookId).orElse(null);
                if (existing != null) {
                    existing.setProgress(clampProgress(progress));
                    existing.setCurrentPosition(currentPosition);
                    ReadingProgress saved = progressRepository.save(existing);
                    return new ProgressResult(saved, false);
                }
            }
            throw e; // 不是并发竞态，原样抛出
        }
    }

    /**
     * 进度上报结果记录类
     * 封装进度保存操作的结果信息
     * @param progress 保存后的进度记录
     * @param isNew 是否为新创建的记录
     */
    public record ProgressResult(ReadingProgress progress, boolean isNew) {
    }

    /**
     * 批量上报进度（断网恢复后使用）
     * 用于处理离线阅读后批量同步进度的场景
     * 冲突解决：比较客户端时间戳与服务器时间戳，较新者胜出
     *
     * @param userId 用户ID
     * @param items 批量进度项列表
     * @return BatchProgressResult 包含统计信息和新创建的bookId列表
     */
    @Transactional // 开启事务保证数据一致性
    @LogAction("批量上报阅读进度")
    public BatchProgressResult batchReportProgress(Long userId, List<ProgressBatchItem> items) {
        log.info("开始批量上报进度: userId={}, count={}", userId, items.size()); // 记录开始日志
        int updated = 0, created = 0, skipped = 0; // 初始化计数器：更新数、创建数、跳过数
        List<Long> newBookIds = new java.util.ArrayList<>(); // 存储新创建的书籍ID列表

        // 遍历每个进度项进行处理
        for (ProgressBatchItem item : items) {
            // 查询是否已存在该书籍的进度记录
            ReadingProgress existing = progressRepository.findByUserIdAndBookId(userId, item.getBookId())
                    .orElse(null);

            if (existing == null) {
                // 如果不存在则创建新的进度记录
                ReadingProgress rp = ReadingProgress.builder()
                        .userId(userId) // 设置用户ID
                        .bookId(item.getBookId()) // 设置书籍ID
                        .progress(clampProgress(item.getProgress())) // 设置进度值（确保在0.0~1.0范围内）
                        .currentPosition(item.getCurrentPosition()) // 设置当前位置
                        .build();
                progressRepository.save(rp); // 保存新记录
                created++; // 创建计数器加1
                newBookIds.add(item.getBookId()); // 添加到新书籍ID列表
            } else {
                // 如果已存在则根据时间戳判断是否需要更新
                // 时间戳覆盖：客户端时间戳比服务器更新才覆盖
                if (item.getClientTimestamp() != null &&
                        existing.getUpdatedAt() != null &&
                        item.getClientTimestamp().isAfter(existing.getUpdatedAt())) {
                    // 客户端时间戳更新，执行更新操作
                    existing.setProgress(clampProgress(item.getProgress())); // 更新进度值
                    existing.setCurrentPosition(item.getCurrentPosition()); // 更新当前位置
                    progressRepository.save(existing); // 保存更新
                    updated++; // 更新计数器加1
                } else if (item.getClientTimestamp() == null) {
                    // 无时间戳则默认覆盖（兼容旧版本客户端）
                    existing.setProgress(clampProgress(item.getProgress())); // 更新进度值
                    existing.setCurrentPosition(item.getCurrentPosition()); // 更新当前位置
                    progressRepository.save(existing); // 保存更新
                    updated++; // 更新计数器加1
                } else {
                    // 服务器数据更新，跳过本次更新
                    skipped++; // 跳过计数器加1
                }
            }
        }
        log.info("批量上报进度完成: userId={}, created={}, updated={}, skipped={}", userId, created, updated, skipped); // 记录完成日志
        // 如果有创建或更新操作，则清除用户缓存
        if (created > 0 || updated > 0) {
        }
        return new BatchProgressResult(created, updated, skipped, newBookIds); // 返回批量处理结果
    }

    /**
     * 批量上报结果记录类
     * 封装批量进度上报操作的统计信息
     * @param created 新创建的记录数
     * @param updated 更新的记录数
     * @param skipped 跳过的记录数
     * @param newBookIds 新创建的书籍ID列表
     */
    public record BatchProgressResult(int created, int updated, int skipped, List<Long> newBookIds) {
    }

    /**
     * 获取单个书籍的阅读进度
     * @param userId 用户ID
     * @param bookId 书籍ID
     * @return 阅读进度对象，不存在则返回null
     */
    @LogAction("获取阅读进度")
    public ReadingProgress getProgress(Long userId, Long bookId) {
        // 查询并返回用户的阅读进度，不存在则返回null
        return progressRepository.findByUserIdAndBookId(userId, bookId)
                .orElse(null);
    }

    /**
     * 批量获取多个书籍的阅读进度
     * 用于一次性查询用户对多本书的阅读进度，提高查询效率
     * @param userId 用户ID
     * @param bookIds 书籍ID列表
     * @return 书籍ID到阅读进度对象的映射Map
     */
    @LogAction("批量获取阅读进度")
    public Map<Long, ReadingProgress> getProgressBatch(Long userId, List<Long> bookIds) {
        // 批量查询用户在这些书籍上的阅读进度
        List<ReadingProgress> list = progressRepository.findByUserIdAndBookIdIn(userId, bookIds);
        // 将列表转换为以书籍ID为键的Map，方便快速查找
        return list.stream().collect(Collectors.toMap(ReadingProgress::getBookId, p -> p));
    }

    /**
     * 获取用户所有阅读进度
     * 按更新时间降序排列，最近阅读的排在前面
     * @param userId 用户ID
     * @return 阅读进度列表
     */
    @LogAction("获取用户所有阅读进度")
    public List<ReadingProgress> getUserProgresses(Long userId) {
        // 查询用户的所有阅读进度，按更新时间降序排列
        return progressRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    /**
     * 分页获取用户阅读历史（含图书信息）
     * 返回带有完整书籍信息的阅读历史记录，用于展示阅读历史页面
     * @param userId 用户ID
     * @param page 页码（从0开始）
     * @param size 每页大小
     * @return 分页的阅读历史视图对象列表
     */
    @LogAction("获取阅读历史")
    public com.kbook.common.api.PageResult<ReadingHistoryVO> getUserReadingHistory(Long userId, int page, int size) {
        // 分页查询用户的阅读进度，按更新时间降序排列
        org.springframework.data.domain.Page<ReadingProgress> pageData = progressRepository
                .findByUserIdOrderByUpdatedAtDesc(userId, org.springframework.data.domain.PageRequest.of(page, size));

        // 将阅读进度转换为包含书籍信息的视图对象
        List<ReadingHistoryVO> list = pageData.getContent().stream().map(rp -> {
            Book book = bookRepository.findById(rp.getBookId()).orElse(null); // 查询书籍信息
            return ReadingHistoryVO.from(rp, book); // 构建阅读历史视图对象
        }).collect(Collectors.toList());

        return com.kbook.common.api.PageResult.of(list, pageData.getTotalElements(), page, size); // 构建分页结果返回
    }

    /**
     * 获取最近阅读的书籍（未读完的）
     * 用于展示"继续阅读"功能，返回最近阅读且未完成的书箱
     * @param userId 用户ID
     * @param limit 返回数量限制
     * @return 最近阅读的进度列表
     */
    @LogAction("获取最近阅读")
    public List<ReadingProgress> getRecentReading(Long userId, int limit) {
        // 查询最近阅读的书籍，限制返回数量
        return progressRepository.findRecentReading(userId, PageRequest.of(0, limit));
    }

    /**
     * 获取用户阅读统计信息
     * 统计用户的总阅读书籍数、已完成数和阅读中数
     * @param userId 用户ID
     * @return 阅读统计对象
     */
    @LogAction("获取阅读统计")
    public ReadingStats getReadingStats(Long userId) {
        // 获取用户的所有阅读进度
        List<ReadingProgress> all = progressRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        long totalBooks = all.size(); // 计算总书籍数
        long completedBooks = all.stream().filter(p -> p.getProgress() >= 1.0).count(); // 统计已完成书籍数（进度>=1.0）
        long readingBooks = totalBooks - completedBooks; // 计算阅读中书籍数

        // 构建并返回阅读统计对象
        return ReadingStats.builder()
                .totalBooks(totalBooks) // 设置总书籍数
                .completedBooks(completedBooks) // 设置已完成书籍数
                .readingBooks(readingBooks) // 设置阅读中书籍数
                .build();
    }

    /**
     * 限制进度值在有效范围内
     * 确保进度值在0.0到1.0之间，防止异常数据
     * @param progress 原始进度值
     * @return 限制后的进度值（0.0~1.0）
     */
    private Double clampProgress(Double progress) {
        if (progress == null) return 0.0; // 空值处理，返回0.0
        // 使用Math.max和Math.min确保进度值在0.0~1.0范围内
        return Math.max(0.0, Math.min(1.0, progress));
    }

}
