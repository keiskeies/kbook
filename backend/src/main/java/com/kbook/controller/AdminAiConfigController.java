package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.config.ChatModelFactory;
import com.kbook.config.ai.AiConfigProvider;
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
import java.util.stream.Collectors;

/**
 * AI 配置管理控制器 — 对话模型 + 嵌入模型 CRUD
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

    @PostMapping
    public Result<AiProviderConfig> create(@RequestBody AiProviderConfig config) {
        if (config.getName() == null || config.getName().isBlank()) {
            return Result.fail("配置名称不能为空");
        }
        if (config.getPurpose() == null || config.getProvider() == null
                || config.getBaseUrl() == null || config.getModelName() == null) {
            return Result.fail("缺少必要参数：purpose, provider, baseUrl, modelName");
        }

        AiProviderConfig saved = providerConfigService.saveConfig(config);
        log.info("AI 配置已创建: id={}, name={}, purpose={}, roles={}, provider={}, model={}",
                saved.getId(), saved.getName(), saved.getPurpose(), saved.getRoles(),
                saved.getProvider(), saved.getModelName());

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
        if (config.getRoles() != null) existing.setRoles(config.getRoles());
        if (config.getEmbeddingDimension() != null) existing.setEmbeddingDimension(config.getEmbeddingDimension());
        // 思考模式（声明模型支持的思考能力）
        if (config.getThinkingMode() != null) existing.setThinkingMode(config.getThinkingMode());

        AiProviderConfig saved = providerConfigService.saveConfig(existing);
        log.info("AI 配置已更新: id={}, name={}, purpose={}, roles={}, provider={}, model={}",
                saved.getId(), saved.getName(), saved.getPurpose(), saved.getRoles(),
                saved.getProvider(), saved.getModelName());

        return Result.ok(saved);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        providerConfigService.deleteConfig(id);
        log.info("AI 配置已删除: id={}", id);
        return Result.ok();
    }

    @PostMapping("/{id}/set-role/{role}")
    public Result<AiProviderConfig> setRole(@PathVariable Long id, @PathVariable String role) {
        AiProviderConfig config = configRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("配置不存在: " + id));

        if (!"CHAT".equalsIgnoreCase(config.getPurpose())) {
            return Result.fail("仅 CHAT 用途支持角色设置");
        }
        if (!List.of("QA", "TOOL").contains(role.toUpperCase())) {
            return Result.fail("角色必须是 QA 或 TOOL");
        }

        String upperRole = role.toUpperCase();
        String currentRoles = config.getRoles();
        if (currentRoles != null && currentRoles.contains(upperRole)) {
            // 移除该角色（仅从当前配置中移除）
            config.setRoles(currentRoles
                    .replace(upperRole, "")
                    .replace(",,", ",")
                    .replaceAll("^,|,$", "")
                    .trim());
        } else {
            // 添加该角色 — 先从所有其他 CHAT 配置中移除该角色，保证唯一
            List<AiProviderConfig> others = configRepository
                    .findAllByPurposeAndRolesContaining("CHAT", "%" + upperRole + "%")
                    .stream()
                    .filter(c -> !c.getId().equals(id))
                    .collect(Collectors.toList());
            for (AiProviderConfig other : others) {
                String otherRoles = other.getRoles();
                if (otherRoles != null) {
                    other.setRoles(otherRoles
                            .replace(upperRole, "")
                            .replace(",,", ",")
                            .replaceAll("^,|,$", "")
                            .trim());
                    configRepository.save(other);
                }
            }

            String newRoles = (currentRoles != null && !currentRoles.isBlank())
                    ? currentRoles + "," + upperRole
                    : upperRole;
            config.setRoles(newRoles);
        }

        AiProviderConfig saved = providerConfigService.saveConfig(config);
        log.info("已切换配置角色: id={}, name={}, roles={}", id, config.getName(), saved.getRoles());
        return Result.ok(saved);
    }

    @PostMapping("/{id}/activate")
    public Result<AiProviderConfig> activate(@PathVariable Long id) {
        AiProviderConfig config = configRepository.findById(id).orElse(null);
        if (config == null) {
            return Result.fail("配置不存在");
        }
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            return Result.fail("不能激活已禁用的配置");
        }
        // 强制触发 @PreUpdate 使 updatedAt 刷新，成为 "最新更新的启用配置"
        config.setUpdatedAt(java.time.LocalDateTime.now());
        AiProviderConfig saved = providerConfigService.saveConfig(config);
        log.info("已激活嵌入模型配置: id={}, name={}", id, config.getName());
        return Result.ok(saved);
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

    @PostMapping("/file/reload")
    public Result<Void> reload() {
        configProvider.reload();
        log.info("管理员触发了 AI 配置热加载");
        return Result.ok();
    }
}
