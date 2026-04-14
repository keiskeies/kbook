package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.entity.AiProviderConfig;
import com.kbook.service.AiProviderConfigService;
import com.kbook.service.AiProviderConfigService.ConnectionTestResult;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理员 AI 提供商配置控制器
 * <p>
 * 路径 /api/admin/ai-provider，仅管理员可访问。
 * 支持配置 CRUD、启用/禁用、连接测试。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/ai-provider")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AiProviderConfigController {

    private final AiProviderConfigService providerConfigService;

    /** 获取所有配置 */
    @GetMapping
    public Result<List<AiProviderConfig>> listConfigs() {
        return Result.ok(providerConfigService.getAllConfigs());
    }

    /** 获取当前活跃配置 */
    @GetMapping("/active")
    public Result<AiProviderConfig> getActiveConfig() {
        return Result.ok(providerConfigService.getActiveConfig());
    }

    /** 获取指定配置 */
    @GetMapping("/{id}")
    public Result<AiProviderConfig> getConfig(@PathVariable Long id) {
        AiProviderConfig config = providerConfigService.getConfigById(id);
        if (config == null) {
            return Result.fail("配置不存在");
        }
        // API Key 脱敏
        config.setApiKey(maskApiKey(config.getApiKey()));
        return Result.ok(config);
    }

    /** 保存配置（新增或更新） */
    @PostMapping
    public Result<AiProviderConfig> saveConfig(@RequestBody AiProviderConfig config) {
        // 校验必填字段
        if (config.getProvider() == null || config.getProvider().isBlank()) {
            return Result.fail("提供商类型不能为空");
        }
        if (config.getConfigName() == null || config.getConfigName().isBlank()) {
            return Result.fail("配置名称不能为空");
        }
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            return Result.fail("端点地址不能为空");
        }
        if (config.getModelName() == null || config.getModelName().isBlank()) {
            return Result.fail("模型名称不能为空");
        }

        // 如果 apiKey 是脱敏的，不覆盖原有值
        if (config.getApiKey() != null && config.getApiKey().contains("****")) {
            AiProviderConfig existing = config.getId() != null
                    ? providerConfigService.getConfigById(config.getId())
                    : null;
            if (existing != null && existing.getApiKey() != null) {
                config.setApiKey(existing.getApiKey());
            } else {
                config.setApiKey(null);
            }
        }

        AiProviderConfig saved = providerConfigService.saveConfig(config);
        log.info("管理员保存 AI 配置: id={}, name={}, provider={}, model={}",
                saved.getId(), saved.getConfigName(), saved.getProvider(), saved.getModelName());

        // 返回时脱敏
        saved.setApiKey(maskApiKey(saved.getApiKey()));
        return Result.ok(saved);
    }

    /** 删除配置 */
    @DeleteMapping("/{id}")
    public Result<Void> deleteConfig(@PathVariable Long id) {
        providerConfigService.deleteConfig(id);
        log.info("管理员删除 AI 配置: id={}", id);
        return Result.ok();
    }

    /** 启用指定配置（同时禁用其他所有配置） */
    @PostMapping("/{id}/enable")
    public Result<AiProviderConfig> enableConfig(@PathVariable Long id) {
        AiProviderConfig config = providerConfigService.enableConfig(id);
        config.setApiKey(maskApiKey(config.getApiKey()));
        return Result.ok(config);
    }

    /** 禁用指定配置 */
    @PostMapping("/{id}/disable")
    public Result<Void> disableConfig(@PathVariable Long id) {
        AiProviderConfig config = providerConfigService.getConfigById(id);
        if (config == null) {
            return Result.fail("配置不存在");
        }
        config.setEnabled(false);
        providerConfigService.saveConfig(config);
        return Result.ok();
    }

    /** 连接测试 — 测试已保存的配置 */
    @PostMapping("/{id}/test")
    public Result<ConnectionTestResult> testConnection(@PathVariable Long id) {
        log.info("管理员测试 AI 连接: configId={}", id);
        ConnectionTestResult result = providerConfigService.testConnectionById(id);
        return Result.ok(result);
    }

    /** 连接测试 — 测试未保存的配置（实时测试） */
    @PostMapping("/test")
    public Result<ConnectionTestResult> testNewConnection(@RequestBody AiProviderConfig config) {
        log.info("管理员测试新 AI 配置: provider={}, model={}", config.getProvider(), config.getModelName());
        ConnectionTestResult result = providerConfigService.testConnection(config);
        return Result.ok(result);
    }

    /** API Key 脱敏：只保留前4位和后4位 */
    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return apiKey == null ? null : "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
