package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.admin.DashboardResponse;
import com.kbook.service.admin.AdminDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @Operation(summary = "获取仪表盘数据")
    @GetMapping
    public Result<DashboardResponse> getDashboard() {
        return Result.ok(dashboardService.getDashboard());
    }
}
