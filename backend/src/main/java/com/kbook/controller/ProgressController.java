package com.kbook.controller;

import com.kbook.common.api.PageResult;
import com.kbook.common.api.Result;
import com.kbook.dto.ProgressBatchItem;
import com.kbook.dto.ReadingHistoryVO;
import com.kbook.dto.ReadingStats;
import com.kbook.entity.ReadingProgress;
import com.kbook.service.BookService;
import com.kbook.service.ReadingProgressService;
import com.kbook.service.RecommendCoefficientService;
import com.kbook.service.RecommendService;
import com.kbook.dto.ProgressBatchGetRequest;
import com.kbook.dto.ProgressReportRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 阅读进度控制器
 */
@RestController
@RequestMapping("/api/progress")
@RequiredArgsConstructor
@Tag(name = "进度")
public class ProgressController {

    private final ReadingProgressService progressService;
    private final RecommendService recommendService;
    private final RecommendCoefficientService coefficientService;
    private final BookService bookService;

    /**
     * 上报阅读进度
     */
    @Operation(summary = "上报进度")
    @PostMapping
    public Result<ReadingProgress> reportProgress(Authentication authentication,
                                                   @RequestBody ProgressReportRequest req) {
        Long userId = (Long) authentication.getPrincipal();
        ReadingProgressService.ProgressResult result = progressService.reportProgress(userId, req.getBookId(), req.getProgress(), req.getCurrentPosition());
        ReadingProgress progress = result.progress();

        // 如果是首次阅读（新创建的记录），增加阅读计数
        if (result.isNew()) {
            bookService.incrementReadCount(req.getBookId());
        }

        // 记录阅读行为
        recommendService.recordReadAction(userId, req.getBookId(), "READ", 1,
                String.valueOf(req.getProgress()));

        // 记录推荐反馈
        coefficientService.recordFeedback(userId, req.getBookId(), "READ", 0.2,
                null, null, null, String.valueOf(req.getProgress()));

        // 如果读完（progress >= 1.0），记录完成行为（权重最高）
        if (req.getProgress() != null && req.getProgress() >= 1.0) {
            recommendService.recordReadAction(userId, req.getBookId(), "COMPLETE", 5, null);
            coefficientService.recordFeedback(userId, req.getBookId(), "COMPLETE", 0.5,
                    null, null, null, null);
        }

        return Result.ok(progress);
    }

    /**
     * 批量上报进度（断网恢复后）
     */
    @Operation(summary = "批量上报进度")
    @PostMapping("/batch")
    public Result<Void> batchReportProgress(Authentication authentication,
                                              @RequestBody List<ProgressBatchItem> items) {
        Long userId = (Long) authentication.getPrincipal();
        ReadingProgressService.BatchProgressResult result = progressService.batchReportProgress(userId, items);
        
        // 为新创建的阅读记录增加阅读计数
        for (Long bookId : result.newBookIds()) {
            bookService.incrementReadCount(bookId);
        }
        
        return Result.ok(null);
    }

    /**
     * 获取某本书的阅读进度
     */
    @Operation(summary = "获取进度")
    @GetMapping("/{bookId}")
    public Result<ReadingProgress> getProgress(Authentication authentication,
                                                @PathVariable Long bookId) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(progressService.getProgress(userId, bookId));
    }

    /**
     * 批量获取进度
     */
    @Operation(summary = "批量获取进度")
    @PostMapping("/batch-get")
    public Result<Map<Long, ReadingProgress>> getProgressBatch(Authentication authentication,
                                                                 @RequestBody ProgressBatchGetRequest req) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(progressService.getProgressBatch(userId, req.getBookIds()));
    }

    /**
     * 分页获取用户阅读历史（含图书信息）
     */
    @Operation(summary = "获取阅读历史")
    @GetMapping("/history")
    public Result<PageResult<ReadingHistoryVO>> getUserReadingHistory(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(progressService.getUserReadingHistory(userId, page, size));
    }

    /**
     * 获取最近阅读
     */
    @Operation(summary = "获取最近阅读")
    @GetMapping("/recent")
    public Result<List<ReadingProgress>> getRecentReading(Authentication authentication,
                                                           @RequestParam(defaultValue = "10") int limit) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(progressService.getRecentReading(userId, limit));
    }

    /**
     * 获取阅读统计
     */
    @Operation(summary = "获取阅读统计")
    @GetMapping("/stats")
    public Result<ReadingStats> getReadingStats(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(progressService.getReadingStats(userId));
    }


}
