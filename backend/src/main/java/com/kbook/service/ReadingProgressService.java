package com.kbook.service;

import com.kbook.entity.ReadingProgress;
import com.kbook.repository.BookRepository;
import com.kbook.repository.ReadingProgressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 阅读进度服务
 *
 * 进度计算规则：
 * - EPUB/TXT: progress = 已读字符数 / 总字符数 (0.0~1.0)
 * - PDF: progress = 当前页码 / 总页数 (0.0~1.0)
 *
 * 冲突解决策略：时间戳覆盖（最新写入胜出）
 * 断网降级方案：前端本地存储，联网后上报
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadingProgressService {

    private final ReadingProgressRepository progressRepository;
    private final BookRepository bookRepository;

    /**
     * 上报阅读进度
     * 上报时机：翻页/切后台
     * 冲突解决：时间戳覆盖策略
     */
    @Transactional
    public ReadingProgress reportProgress(Long userId, Long bookId, Double progress, String currentPosition) {
        log.debug("上报阅读进度: userId={}, bookId={}, progress={}", userId, bookId, progress);
        ReadingProgress rp = progressRepository.findByUserIdAndBookId(userId, bookId)
                .orElseGet(() -> {
                    log.debug("创建新的阅读进度记录: userId={}, bookId={}", userId, bookId);
                    return ReadingProgress.builder()
                            .userId(userId)
                            .bookId(bookId)
                            .build();
                });

        // 时间戳覆盖策略：始终接受最新上报
        rp.setProgress(clampProgress(progress));
        rp.setCurrentPosition(currentPosition);

        ReadingProgress saved = progressRepository.save(rp);
        log.debug("阅读进度保存成功: userId={}, bookId={}, progress={}", userId, bookId, saved.getProgress());
        return saved;
    }

    /**
     * 批量上报进度（断网恢复后使用）
     * 冲突解决：比较客户端时间戳与服务器时间戳，较新者胜出
     */
    @Transactional
    public void batchReportProgress(Long userId, List<ProgressBatchItem> items) {
        log.info("开始批量上报进度: userId={}, count={}", userId, items.size());
        int updated = 0, created = 0, skipped = 0;
        
        for (ProgressBatchItem item : items) {
            ReadingProgress existing = progressRepository.findByUserIdAndBookId(userId, item.getBookId())
                    .orElse(null);

            if (existing == null) {
                ReadingProgress rp = ReadingProgress.builder()
                        .userId(userId)
                        .bookId(item.getBookId())
                        .progress(clampProgress(item.getProgress()))
                        .currentPosition(item.getCurrentPosition())
                        .build();
                progressRepository.save(rp);
                created++;
            } else {
                // 时间戳覆盖：客户端时间戳比服务器更新才覆盖
                if (item.getClientTimestamp() != null &&
                    existing.getUpdatedAt() != null &&
                    item.getClientTimestamp().isAfter(existing.getUpdatedAt())) {
                    existing.setProgress(clampProgress(item.getProgress()));
                    existing.setCurrentPosition(item.getCurrentPosition());
                    progressRepository.save(existing);
                    updated++;
                } else if (item.getClientTimestamp() == null) {
                    // 无时间戳则默认覆盖
                    existing.setProgress(clampProgress(item.getProgress()));
                    existing.setCurrentPosition(item.getCurrentPosition());
                    progressRepository.save(existing);
                    updated++;
                } else {
                    skipped++;
                }
            }
        }
        log.info("批量上报进度完成: userId={}, created={}, updated={}, skipped={}", userId, created, updated, skipped);
    }

    /**
     * 获取阅读进度
     */
    public ReadingProgress getProgress(Long userId, Long bookId) {
        return progressRepository.findByUserIdAndBookId(userId, bookId)
                .orElse(null);
    }

    /**
     * 批量获取进度
     */
    public Map<Long, ReadingProgress> getProgressBatch(Long userId, List<Long> bookIds) {
        List<ReadingProgress> list = progressRepository.findByUserIdAndBookIdIn(userId, bookIds);
        return list.stream().collect(Collectors.toMap(ReadingProgress::getBookId, p -> p));
    }

    /**
     * 获取用户所有阅读进度
     */
    public List<ReadingProgress> getUserProgresses(Long userId) {
        return progressRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    /**
     * 获取最近阅读（未读完的）
     */
    public List<ReadingProgress> getRecentReading(Long userId, int limit) {
        return progressRepository.findRecentReading(userId, PageRequest.of(0, limit));
    }

    /**
     * 获取阅读统计
     */
    public ReadingStats getReadingStats(Long userId) {
        List<ReadingProgress> all = progressRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        long totalBooks = all.size();
        long completedBooks = all.stream().filter(p -> p.getProgress() >= 1.0).count();
        long readingBooks = totalBooks - completedBooks;

        return ReadingStats.builder()
                .totalBooks(totalBooks)
                .completedBooks(completedBooks)
                .readingBooks(readingBooks)
                .build();
    }

    /**
     * 删除阅读进度
     */
    @Transactional
    public void deleteProgress(Long userId, Long bookId) {
        progressRepository.deleteByUserIdAndBookId(userId, bookId);
        log.info("删除阅读进度: userId={}, bookId={}", userId, bookId);
    }

    private Double clampProgress(Double progress) {
        if (progress == null) return 0.0;
        return Math.max(0.0, Math.min(1.0, progress));
    }

    /**
     * 批量上报项
     */
    @lombok.Data
    public static class ProgressBatchItem {
        private Long bookId;
        private Double progress;
        private String currentPosition;
        private LocalDateTime clientTimestamp;
    }

    /**
     * 阅读统计
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ReadingStats {
        private long totalBooks;
        private long completedBooks;
        private long readingBooks;
    }
}
