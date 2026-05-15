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
     * 按用途获取配置
     */
    @GetMapping("/{purpose}")
    public Result<AiProviderConfig> getByPurpose(@PathVariable String purpose) {
        return Result.ok(configRepository.findByPurpose(purpose).orElse(null));
    }

    /**
     * 创建或更新 AI 配置
     * <p>
     * 如果同 purpose 已存在则更新，否则创建。
     * 更新后自动清除对话缓存。
     */
    @PostMapping
    public Result<AiProviderConfig> save(@RequestBody AiProviderConfig config) {
        // 参数校验
        if (config.getPurpose() == null || config.getProvider() == null
                || config.getBaseUrl() == null || config.getModelName() == null) {
            return Result.fail("缺少必要参数：purpose, provider, baseUrl, modelName");
        }

        // 同 purpose 已存在则更新
        configRepository.findByPurpose(config.getPurpose()).ifPresent(existing -> {
            if (config.getId() == null) {
                config.setId(existing.getId());
            }
        });

        AiProviderConfig saved = configRepository.save(config);
        log.info("AI 配置已保存: purpose={}, provider={}, model={}, enabled={}",
                saved.getPurpose(), saved.getProvider(), saved.getModelName(), saved.getEnabled());

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
