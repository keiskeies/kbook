package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.config.ai.AiConfig;
import com.kbook.config.ai.AiConfigProvider;
import com.kbook.config.ai.AiConfigSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * AI 配置管理 API — 管理员可查看和重载系统 AI 配置
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/ai-config-file")
@RequiredArgsConstructor
public class AiConfigAdminController {

    private final AiConfigProvider configProvider;

    /**
     * 获取完整配置（含所有 prompt，仅管理员可见）
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<AiConfig> getFullConfig() {
        return Result.ok(configProvider.getConfig());
    }

    /**
     * 获取配置摘要（不含 prompt，供前端展示角色列表、颜色、图标等）
     */
    @GetMapping("/summary")
    public Result<AiConfigSummary> getSummary() {
        return Result.ok(configProvider.buildSummary());
    }

    /**
     * 热加载配置文件
     */
    @PostMapping("/reload")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> reload() {
        configProvider.reload();
        log.info("管理员触发了 AI 配置热加载");
        return Result.ok();
    }
}
