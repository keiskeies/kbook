package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.admin.AiBudgetDTO;
import com.kbook.dto.admin.AiBudgetUpdateRequest;
import com.kbook.dto.admin.BookAudienceFitVO;
import com.kbook.dto.admin.BookProfileSnapshotResultVO;
import com.kbook.dto.admin.BookProfileTrendPointVO;
import com.kbook.dto.admin.BookProfileVO;
import com.kbook.dto.admin.DashboardResponse;
import com.kbook.dto.admin.ProfileBookVO;
import com.kbook.service.admin.AdminDashboardService;
import com.kbook.service.admin.AiBudgetService;
import com.kbook.service.admin.BookProfileService;
import com.kbook.service.admin.BookProfileSnapshotService;
import com.kbook.service.admin.ProfileThresholdCalibrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 管理后台仪表盘控制器
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "管理仪表盘")
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;
    private final AiBudgetService budgetService;
    private final BookProfileService bookProfileService;
    private final BookProfileSnapshotService bookProfileSnapshotService;
    private final ProfileThresholdCalibrationService thresholdCalibrationService;

    @Operation(summary = "获取仪表盘数据")
    @GetMapping
    public Result<DashboardResponse> getDashboard(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return Result.ok(dashboardService.getDashboard(days, start, end));
    }

    @Operation(summary = "获取 AI 成本预算")
    @GetMapping("/budget")
    public Result<AiBudgetDTO> getBudget() {
        return Result.ok(budgetService.getStatus());
    }

    @Operation(summary = "获取单书读者画像")
    @GetMapping("/book-profile")
    public Result<BookProfileVO> getBookProfile(
            @RequestParam long bookId,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false, defaultValue = "READ") String basis) {
        return Result.ok(bookProfileService.getBookProfile(bookId, days, start, end, basis));
    }

    @Operation(summary = "单书受众吻合度（书侧声称受众 vs 实际读者分布，P3 单书版）")
    @GetMapping("/book-audience-fit")
    public Result<BookAudienceFitVO> getBookAudienceFit(
            @RequestParam long bookId,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false, defaultValue = "READ") String basis) {
        return Result.ok(bookProfileService.getAudienceFit(bookId, days, start, end, basis));
    }

    @Operation(summary = "手工触发单书画像每日快照（与定时任务同一路径，幂等）")
    @PostMapping("/book-profile-snapshot")
    public Result<List<BookProfileSnapshotResultVO>> snapshotBookProfiles(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.ok(bookProfileSnapshotService.snapshot(date == null ? LocalDate.now() : date));
    }

    @Operation(summary = "画像 cohort 阈值的生效值与自适应状态")
    @GetMapping("/book-profile-thresholds")
    public Result<Map<String, Object>> getBookProfileThresholds() {
        return Result.ok(thresholdCalibrationService.status());
    }

    @Operation(summary = "手工重算一次 cohort 阈值（只收紧，不降破配置下界）")
    @PostMapping("/book-profile-thresholds")
    public Result<Map<String, Integer>> recalibrateBookProfileThresholds() {
        return Result.ok(thresholdCalibrationService.recalibrate());
    }

    @Operation(summary = "单书画像趋势（每日快照，人数与填写数）")
    @GetMapping("/book-profile-trend")
    public Result<List<BookProfileTrendPointVO>> getBookProfileTrend(
            @RequestParam long bookId,
            @RequestParam(required = false, defaultValue = "DEEP") String basis,
            @RequestParam(defaultValue = "30") int days) {
        return Result.ok(bookProfileSnapshotService.trend(bookId, basis, days));
    }

    @Operation(summary = "全站读者画像维度与分桶（人群选书页的选择器数据源）")
    @GetMapping("/profile-dimensions")
    public Result<List<BookProfileVO.Dimension>> getProfileDimensions() {
        return Result.ok(bookProfileService.getSiteDimensions());
    }

    @Operation(summary = "画像反查：某人群读得最多/最集中的书")
    @GetMapping("/profile-books")
    public Result<List<ProfileBookVO>> getProfileBooks(
            @RequestParam String dim,
            @RequestParam String value,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false, defaultValue = "READ") String basis,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false, defaultValue = "LIFT") String sortBy) {
        return Result.ok(bookProfileService.getProfileBooks(dim, value, days, start, end, basis, limit, sortBy));
    }

    @Operation(summary = "更新 AI 成本预算")
    @PutMapping("/budget")
    public Result<AiBudgetDTO> updateBudget(@RequestBody AiBudgetUpdateRequest request) {
        return Result.ok(budgetService.update(request));
    }
}
