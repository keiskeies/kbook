package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.entity.TtsConfig;
import com.kbook.service.tts.TtsConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * TTS 配置管理控制器 — 管理员 CRUD
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/tts-config")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "TTS配置")
public class AdminTtsConfigController {

    private final TtsConfigService ttsConfigService;

    public AdminTtsConfigController(TtsConfigService ttsConfigService) {
        this.ttsConfigService = ttsConfigService;
    }

    @Operation(summary = "获取所有TTS配置")
    @GetMapping
    public Result<List<TtsConfig>> listAll() {
        return Result.ok(ttsConfigService.listAll());
    }

    @Operation(summary = "获取当前TTS配置(管理端)")
    @GetMapping("/active")
    public Result<TtsConfig> getActiveConfigAdmin() {
        TtsConfig config = ttsConfigService.getActiveConfig();
        if (config == null) {
            return Result.fail("未配置 TTS");
        }
        return Result.ok(config);
    }

    @Operation(summary = "创建TTS配置")
    @PostMapping
    public Result<TtsConfig> create(@RequestBody TtsConfig config) {
        if (config.getName() == null || config.getName().isBlank()) {
            return Result.fail("配置名称不能为空");
        }
        if (config.getTtsType() == null || config.getProvider() == null) {
            return Result.fail("缺少必要参数：ttsType, provider");
        }
        return Result.ok(ttsConfigService.create(config));
    }

    @Operation(summary = "更新TTS配置")
    @PutMapping("/{id}")
    public Result<TtsConfig> update(@PathVariable Long id, @RequestBody TtsConfig config) {
        try {
            return Result.ok(ttsConfigService.update(id, config));
        } catch (RuntimeException e) {
            return Result.fail(e.getMessage());
        }
    }

    @Operation(summary = "删除TTS配置")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        ttsConfigService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "切换默认TTS配置")
    @PostMapping("/{id}/switch-default")
    public Result<TtsConfig> switchDefault(@PathVariable Long id) {
        try {
            return Result.ok(ttsConfigService.switchDefault(id));
        } catch (RuntimeException e) {
            return Result.fail(e.getMessage());
        }
    }
}
