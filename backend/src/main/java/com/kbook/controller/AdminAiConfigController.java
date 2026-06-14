package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.config.ChatModelFactory;
import com.kbook.config.ai.AiConfig;
import com.kbook.config.ai.AiConfigProvider;
import com.kbook.config.ai.AiConfigSummary;
import com.kbook.entity.AiProviderConfig;
import com.kbook.repository.AiProviderConfigRepository;
import com.kbook.service.ai.AiProviderConfigService;
import dev.langchain4j.model.chat.ChatModel;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI 配置管理控制器 — 供应商 CRUD + 系统配置文件管理
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/ai-config")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "AI配置管理")
public class AdminAiConfigController {

    private final AiProviderConfigRepository configRepository;
    private final AiProviderConfigService providerConfigService;
    private final ChatModelFactory chatModelFactory;
    private final AiConfigProvider configProvider;

    // ==================== AI 供应商配置 CRUD ====================

    @GetMapping
    public Result<List<AiProviderConfig>> listAll() {
        return Result.ok(configRepository.findAll());
    }

    @GetMapping("/purpose/{purpose}")
    public Result<List<AiProviderConfig>> getByPurpose(@PathVariable String purpose) {
        return Result.ok(providerConfigService.getConfigsByPurpose(purpose));
    }

    @GetMapping("/{purpose}/default")
    public Result<AiProviderConfig> getDefaultByPurpose(@PathVariable String purpose) {
        return Result.ok(providerConfigService.getChatConfig());
    }

    @PostMapping
    public Result<AiProviderConfig> create(@RequestBody AiProviderConfig config) {
        if (config.getName() == null || config.getName().isBlank()) {
            return Result.fail("配置名称不能为空");
        }
        if (config.getPurpose() == null || config.getProvider() == null
                || config.getBaseUrl() == null || config.getModelName() == null) {
            return Result.fail("缺少必要参数：purpose, provider, baseUrl, modelName");
        }

        if (config.getIsDefault() == null) {
            config.setIsDefault(false);
        }

        AiProviderConfig saved = configRepository.save(config);
        log.info("AI 配置已创建: id={}, name={}, purpose={}, provider={}, model={}, enabled={}",
                saved.getId(), saved.getName(), saved.getPurpose(), saved.getProvider(), saved.getModelName(), saved.getEnabled());

        return Result.ok(saved);
    }

    @PutMapping("/{id}")
    public Result<AiProviderConfig> update(@PathVariable Long id, @RequestBody AiProviderConfig config) {
        AiProviderConfig existing = configRepository.findById(id).orElse(null);
        if (existing == null) {
            return Result.fail("配置不存在");
        }

        if (config.getName() != null) existing.setName(config.getName());
        if (config.getProvider() != null) existing.setProvider(config.getProvider());
        if (config.getBaseUrl() != null) existing.setBaseUrl(config.getBaseUrl());
        if (config.getModelName() != null) existing.setModelName(config.getModelName());
        if (config.getApiKey() != null) existing.setApiKey(config.getApiKey());
        if (config.getTemperature() != null) existing.setTemperature(config.getTemperature());
        if (config.getTimeout() != null) existing.setTimeout(config.getTimeout());
        if (config.getToolsEnabled() != null) existing.setToolsEnabled(config.getToolsEnabled());
        if (config.getRagTopK() != null) existing.setRagTopK(config.getRagTopK());
        if (config.getMaxTokens() != null) existing.setMaxTokens(config.getMaxTokens());
        if (config.getEnabled() != null) existing.setEnabled(config.getEnabled());

        if (Boolean.TRUE.equals(config.getIsDefault()) && !existing.getIsDefault()) {
            configRepository.clearDefaultForPurpose(existing.getPurpose(), id);
            existing.setIsDefault(true);
        }

        AiProviderConfig saved = configRepository.save(existing);
        log.info("AI 配置已更新: id={}, name={}, purpose={}, provider={}, model={}, enabled={}, isDefault={}",
                saved.getId(), saved.getName(), saved.getPurpose(), saved.getProvider(), saved.getModelName(), saved.getEnabled(), saved.getIsDefault());

        providerConfigService.invalidateChatCache();

        return Result.ok(saved);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        configRepository.deleteById(id);
        providerConfigService.invalidateChatCache();
        log.info("AI 配置已删除: id={}", id);
        return Result.ok();
    }

    @PostMapping("/{id}/switch-default")
    public Result<AiProviderConfig> switchDefault(@PathVariable Long id) {
        try {
            AiProviderConfig saved = providerConfigService.switchDefault(id);
            return Result.ok(saved);
        } catch (RuntimeException e) {
            return Result.fail(e.getMessage());
        }
    }

    @PostMapping("/{id}/test")
    public Result<String> testConnection(@PathVariable Long id) {
        AiProviderConfig config = configRepository.findById(id).orElse(null);
        if (config == null) {
            return Result.fail("配置不存在");
        }

        try {
            ChatModel testModel = chatModelFactory.buildChatModelForTest(id);
            String response = testModel.chat("请用中文回复：连接测试成功");
            return Result.ok(response);
        } catch (Exception e) {
            log.warn("AI 配置测试失败: id={}, error={}", id, e.getMessage());
            return Result.fail("连接测试失败: " + e.getMessage());
        }
    }

    // ==================== AI 系统配置文件管理 ====================

    @GetMapping("/file")
    public Result<AiConfig> getFullConfig() {
        return Result.ok(configProvider.getConfig());
    }

    @GetMapping("/file/summary")
    public Result<AiConfigSummary> getSummary() {
        return Result.ok(configProvider.buildSummary());
    }

    @PostMapping("/file/reload")
    public Result<Void> reload() {
        configProvider.reload();
        log.info("管理员触发了 AI 配置热加载");
        return Result.ok();
    }
}
