package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.entity.RecommendCoefficient;
import com.kbook.service.RecommendCoefficientService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 推荐系数管理控制器（管理员）
 */
@RestController
@RequestMapping("/api/admin/recommend/coefficients")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class RecommendCoefficientAdminController {

    private final RecommendCoefficientService coefficientService;

    /**
     * 获取所有系数
     */
    @GetMapping
    public Result<List<RecommendCoefficient>> getAllCoefficients() {
        return Result.ok(coefficientService.getAllCoefficients());
    }

    /**
     * 更新系数值
     */
    @PutMapping
    public Result<RecommendCoefficient> updateCoefficient(@RequestBody UpdateCoefficientRequest req) {
        RecommendCoefficient rc = coefficientService.setCoefficient(
                req.getCategory(), req.getKey(), req.getValue(), req.getLocked());
        return Result.ok(rc);
    }

    /**
     * 重置单个系数为默认值
     */
    @PostMapping("/reset")
    public Result<RecommendCoefficient> resetCoefficient(@RequestBody ResetCoefficientRequest req) {
        RecommendCoefficient rc = coefficientService.resetCoefficient(req.getCategory(), req.getKey());
        return Result.ok(rc);
    }

    /**
     * 重置所有未锁定的系数为默认值
     */
    @PostMapping("/reset-all")
    public Result<Integer> resetAllToDefaults() {
        return Result.ok(coefficientService.resetToDefaults());
    }

    /**
     * 手动触发调参（调试用）
     */
    @PostMapping("/tune")
    public Result<Void> triggerAutoTune() {
        coefficientService.autoTuneCoefficients();
        return Result.ok(null);
    }

    // ==================== 请求 DTO ====================

    @Data
    public static class UpdateCoefficientRequest {
        private String category;
        private String key;
        private Double value;
        private Boolean locked;
    }

    @Data
    public static class ResetCoefficientRequest {
        private String category;
        private String key;
    }
}
