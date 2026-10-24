package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.admin.AiBudgetDTO;
import com.kbook.dto.admin.AiBudgetUpdateRequest;
import com.kbook.dto.admin.DashboardResponse;
import com.kbook.service.admin.AdminDashboardService;
import com.kbook.service.admin.AiBudgetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

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

    @Operation(summary = "更新 AI 成本预算")
    @PutMapping("/budget")
    public Result<AiBudgetDTO> updateBudget(@RequestBody AiBudgetUpdateRequest request) {
        return Result.ok(budgetService.update(request));
    }
}
