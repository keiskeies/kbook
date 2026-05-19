package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.config.ChatModelFactory;
import com.kbook.entity.AiProviderConfig;
import com.kbook.repository.AiProviderConfigRepository;
import com.kbook.service.AiProviderConfigService;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI 供应商配置管理 — 管理员接口
 * <p>
 * 提供对话 AI 配置的 CRUD 操作。
 * 支持多配置管理，可切换默认（激活）配置。
 * 配置变更后自动清除缓存，下次对话请求将使用新配置构建模型。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/ai-config")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AiProviderConfigController {

    private final AiProviderConfigRepository configRepository;
    private final AiProviderConfigService providerConfigService;
    private final ChatModelFactory chatModelFactory;

    /**
     * 获取所有 AI 配置
     */
    @GetMapping
    public Result<List<AiProviderConfig>> listAll() {
        return Result.ok(configRepository.findAll());
    }

    /**
     * 按用途获取所有配置列表（含默认标记）
     */
    @GetMapping("/purpose/{purpose}")
    public Result<List<AiProviderConfig>> getByPurpose(@PathVariable String purpose) {
        return Result.ok(providerConfigService.getConfigsByPurpose(purpose));
    }

    /**
     * 按用途获取默认（激活）配置
     */
    @GetMapping("/{purpose}/default")
    public Result<AiProviderConfig> getDefaultByPurpose(@PathVariable String purpose) {
        return Result.ok(providerConfigService.getChatConfig());
    }

    /**
     * 创建 AI 配置
     * <p>
     * 新配置默认 isDefault=false，需手动切换激活。
     */
    @PostMapping
    public Result<AiProviderConfig> create(@RequestBody AiProviderConfig config) {
        // 参数校验
        if (config.getName() == null || config.getName().isBlank()) {
            return Result.fail("配置名称不能为空");
        }
        if (config.getPurpose() == null || config.getProvider() == null
                || config.getBaseUrl() == null || config.getModelName() == null) {
            return Result.fail("缺少必要参数：purpose, provider, baseUrl, modelName");
        }

        // 新配置默认不设为默认
        if (config.getIsDefault() == null) {
            config.setIsDefault(false);
        }

        AiProviderConfig saved = configRepository.save(config);
        log.info("AI 配置已创建: id={}, name={}, purpose={}, provider={}, model={}, enabled={}",
                saved.getId(), saved.getName(), saved.getPurpose(), saved.getProvider(), saved.getModelName(), saved.getEnabled());

        return Result.ok(saved);
    }

    /**
     * 更新 AI 配置
     */
    @PutMapping("/{id}")
    public Result<AiProviderConfig> update(@PathVariable Long id, @RequestBody AiProviderConfig config) {
        AiProviderConfig existing = configRepository.findById(id).orElse(null);
        if (existing == null) {
            return Result.fail("配置不存在");
        }

        // 更新字段
        if (config.getName() != null) existing.setName(config.getName());
        if (config.getProvider() != null) existing.setProvider(config.getProvider());
        if (config.getBaseUrl() != null) existing.setBaseUrl(config.getBaseUrl());
        if (config.getModelName() != null) existing.setModelName(config.getModelName());
        if (config.getApiKey() != null) existing.setApiKey(config.getApiKey());
        if (config.getTemperature() != null) existing.setTemperature(config.getTemperature());
        if (config.getTimeout() != null) existing.setTimeout(config.getTimeout());
        if (config.getToolsEnabled() != null) existing.setToolsEnabled(config.getToolsEnabled());
        if (config.getRagTopK() != null) existing.setRagTopK(config.getRagTopK());
        if (config.getEnabled() != null) existing.setEnabled(config.getEnabled());

        // 如果请求中设置了 isDefault=true，切换默认
        if (Boolean.TRUE.equals(config.getIsDefault()) && !existing.getIsDefault()) {
            configRepository.clearDefaultForPurpose(existing.getPurpose(), id);
            existing.setIsDefault(true);
        }

        AiProviderConfig saved = configRepository.save(existing);
        log.info("AI 配置已更新: id={}, name={}, purpose={}, provider={}, model={}, enabled={}, isDefault={}",
                saved.getId(), saved.getName(), saved.getPurpose(), saved.getProvider(), saved.getModelName(), saved.getEnabled(), saved.getIsDefault());

        // 清除对话缓存
        providerConfigService.invalidateChatCache();

        return Result.ok(saved);
    }

    /**
     * 删除 AI 配置
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        configRepository.deleteById(id);
        providerConfigService.invalidateChatCache();
        log.info("AI 配置已删除: id={}", id);
        return Result.ok();
    }

    /**
     * 切换默认（激活）配置
     * <p>
     * 将指定配置设为该 purpose 的默认配置，同 purpose 的其他配置自动取消默认。
     */
    @PostMapping("/{id}/switch-default")
    public Result<AiProviderConfig> switchDefault(@PathVariable Long id) {
        try {
            AiProviderConfig saved = providerConfigService.switchDefault(id);
            return Result.ok(saved);
        } catch (RuntimeException e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 测试 AI 配置连接
     * <p>
     * 尝试用该配置发送一条简单消息，验证连接是否正常
     */
    @PostMapping("/{id}/test")
    public Result<String> testConnection(@PathVariable Long id) {
        AiProviderConfig config = configRepository.findById(id).orElse(null);
        if (config == null) {
            return Result.fail("配置不存在");
        }

        try {
            ChatModel testModel = chatModelFactory.buildChatModel(config);
            String response = testModel.chat("请用中文回复：连接测试成功");
            return Result.ok(response);
        } catch (Exception e) {
            log.warn("AI 配置测试失败: id={}, error={}", id, e.getMessage());
            return Result.fail("连接测试失败: " + e.getMessage());
        }
    }
}
