package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.service.RecommendCoefficientService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 推荐反馈控制器
 * 前端在用户与推荐结果交互时调用，记录反馈事件用于自动调参
 */
@RestController
@RequestMapping("/api/recommend/feedback")
@RequiredArgsConstructor
public class RecommendFeedbackController {

    private final RecommendCoefficientService coefficientService;

    /**
     * 记录推荐点击（用户点击了推荐的书）
     */
    @PostMapping("/click")
    public Result<Void> recordClick(Authentication authentication, @RequestBody FeedbackRequest req) {
        Long userId = (Long) authentication.getPrincipal();
        coefficientService.recordFeedback(userId, req.getBookId(), "CLICK", 0.1,
                req.getRecallPaths(), null, null, null);
        return Result.ok(null);
    }

    /**
     * 记录推荐曝光（用户看到了推荐卡片）
     * 批量接口：一次曝光可能包含多本书
     */
    @PostMapping("/impression")
    public Result<Void> recordImpression(Authentication authentication, @RequestBody ImpressionRequest req) {
        Long userId = (Long) authentication.getPrincipal();
        for (ImpressionItem item : req.getItems()) {
            coefficientService.recordFeedback(userId, item.getBookId(), "IMPRESSION", 0.0,
                    item.getRecallPaths(), null, null, null);
        }
        return Result.ok(null);
    }

    /**
     * 记录用户评分反馈
     */
    @PostMapping("/rate")
    public Result<Void> recordRate(Authentication authentication, @RequestBody RateFeedbackRequest req) {
        Long userId = (Long) authentication.getPrincipal();
        // 评分 1-5，映射到反馈强度 0.1-0.5
        double strength = req.getRating() * 0.1;
        coefficientService.recordFeedback(userId, req.getBookId(), "RATE", strength,
                req.getRecallPaths(), null, null, String.valueOf(req.getRating()));
        return Result.ok(null);
    }

    /**
     * 记录用户关闭/跳过推荐
     */
    @PostMapping("/dismiss")
    public Result<Void> recordDismiss(Authentication authentication, @RequestBody FeedbackRequest req) {
        Long userId = (Long) authentication.getPrincipal();
        coefficientService.recordFeedback(userId, req.getBookId(), "DISMISS", -0.2,
                req.getRecallPaths(), null, null, null);
        return Result.ok(null);
    }

    // ==================== 请求 DTO ====================

    @Data
    public static class FeedbackRequest {
        private Long bookId;
        /** 推荐时的召回路径（来自 RecommendedItem.recallPaths） */
        private String recallPaths;
    }

    @Data
    public static class ImpressionRequest {
        private java.util.List<ImpressionItem> items;
    }

    @Data
    public static class ImpressionItem {
        private Long bookId;
        private String recallPaths;
    }

    @Data
    public static class RateFeedbackRequest {
        private Long bookId;
        private Integer rating;
        private String recallPaths;
    }
}
