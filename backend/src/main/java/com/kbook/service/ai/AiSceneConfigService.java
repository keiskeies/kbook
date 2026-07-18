package com.kbook.service.ai;

import com.kbook.entity.AiProviderConfig;
import com.kbook.entity.AiScene;
import com.kbook.entity.AiSceneConfig;
import com.kbook.repository.AiProviderConfigRepository;
import com.kbook.repository.AiSceneConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * AI 场景配置解析服务 — 路由层核心。
 * <p>
 * 解析路径（按优先级回退）：
 * <ol>
 *   <li>精确匹配：scene_key 已绑定且绑定的 config enabled=true → 返回</li>
 *   <li>分类回退：按 {@link AiScene#getDefaultCategory()} 走 roles/purpose 默认</li>
 * </ol>
 *
 * @see AiProviderConfigService 默认配置查询（roles=QA/TOOL 等）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSceneConfigService {

    private final AiSceneConfigRepository sceneConfigRepository;
    private final AiProviderConfigRepository providerConfigRepository;
    private final AiProviderConfigService providerConfigService;

    /**
     * 解析场景对应的 AI 配置（按优先级回退）。
     *
     * @param scene AI 场景
     * @return 启用的 AI 配置；找不到抛 IllegalStateException
     */
    public AiProviderConfig resolveConfig(AiScene scene) {
        // 1. 精确匹配：场景已绑定配置
        Optional<AiSceneConfig> bound = sceneConfigRepository.findBySceneKey(scene);
        if (bound.isPresent()) {
            Long configId = bound.get().getConfigId();
            Optional<AiProviderConfig> cfg = providerConfigRepository.findById(configId)
                    .filter(AiProviderConfig::getEnabled);
            if (cfg.isPresent()) {
                log.trace("场景 [{}] 命中专属配置: id={}, name={}",
                        scene, configId, cfg.get().getName());
                return cfg.get();
            }
            // 绑定的配置被禁用或删除，进入回退
            log.warn("场景 [{}] 绑定的配置 id={} 已禁用或不存在，回退到默认分类 {}",
                    scene, configId, scene.getDefaultCategory());
        }

        // 2. 分类回退
        AiProviderConfig fallback = resolveByCategory(scene);
        if (bound.isEmpty()) {
            log.debug("场景 [{}] 未配置专属，使用分类 [{}] 默认配置: id={}, name={}",
                    scene, scene.getDefaultCategory(), fallback.getId(), fallback.getName());
        }
        return fallback;
    }

    /**
     * 按场景默认分类回退查询。
     */
    private AiProviderConfig resolveByCategory(AiScene scene) {
        AiProviderConfig cfg;
        switch (scene.getDefaultCategory()) {
            case QA:
            case QA_WITHOUT_THINKING:
                cfg = providerConfigService.getChatConfigByRole("QA");
                if (cfg == null) {
                    throw new IllegalStateException(
                            "场景 [" + scene + "] 回退到 QA 默认配置失败，请添加 roles=QA 的启用配置");
                }
                return cfg;
            case TOOL:
            case COMPRESSION:
                // 压缩复用 TOOL 配置，温度由调用方覆盖为 0.1
                cfg = providerConfigService.getChatConfigByRole("TOOL");
                if (cfg == null) {
                    throw new IllegalStateException(
                            "场景 [" + scene + "] 回退到 TOOL 默认配置失败，请添加 roles=TOOL 的启用配置");
                }
                return cfg;
            case VISION:
                throw new IllegalStateException(
                        "场景 [" + scene + "] 默认分类为 VISION，但 vision 配置应由 ChatModelFactory.buildVisionChatModel() 处理");
            case EMBEDDING:
                cfg = providerConfigService.getFirstEnabledByPurpose("EMBEDDING");
                if (cfg == null) {
                    throw new IllegalStateException(
                            "场景 [" + scene + "] 回退到 EMBEDDING 默认配置失败，请添加 purpose=EMBEDDING 的启用配置");
                }
                return cfg;
            default:
                throw new IllegalStateException("未知场景分类: " + scene.getDefaultCategory());
        }
    }

    /**
     * 绑定场景到指定配置（覆盖式更新），含思考参数。
     */
    @Transactional(rollbackFor = Exception.class)
    public AiSceneConfig bind(AiScene scene, Long configId,
                              Boolean thinkingEnabled, String reasoningEffort, Integer thinkingBudget) {
        if (!providerConfigRepository.existsById(configId)) {
            throw new IllegalArgumentException("配置不存在: " + configId);
        }
        AiSceneConfig config = sceneConfigRepository.findBySceneKey(scene)
                .orElseGet(() -> {
                    AiSceneConfig c = new AiSceneConfig();
                    c.setSceneKey(scene);
                    return c;
                });
        config.setConfigId(configId);
        config.setThinkingEnabled(thinkingEnabled);
        config.setReasoningEffort(reasoningEffort);
        config.setThinkingBudget(thinkingBudget);
        AiSceneConfig saved = sceneConfigRepository.save(config);
        log.info("场景 [{}] 绑定配置: configId={}, thinkingEnabled={}, reasoningEffort={}, thinkingBudget={}",
                scene, configId, thinkingEnabled, reasoningEffort, thinkingBudget);
        return saved;
    }

    /**
     * 绑定场景到指定配置（仅 configId，不修改思考参数）— 兼容旧接口。
     */
    @Transactional(rollbackFor = Exception.class)
    public AiSceneConfig bind(AiScene scene, Long configId) {
        return bind(scene, configId, null, null, null);
    }

    /**
     * 清除场景绑定（回退到默认分类）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void unbind(AiScene scene) {
        sceneConfigRepository.findBySceneKey(scene).ifPresent(c -> {
            sceneConfigRepository.delete(c);
            log.info("场景 [{}] 绑定已清除", scene);
        });
    }

    /**
     * 判断场景是否已显式绑定（而非走默认回退）。
     */
    public boolean isExplicitlyBound(AiScene scene) {
        return sceneConfigRepository.existsBySceneKey(scene);
    }

    /**
     * 获取场景的显式绑定记录（含思考参数），未绑定时返回 empty。
     */
    public Optional<AiSceneConfig> getExplicitBinding(AiScene scene) {
        return sceneConfigRepository.findBySceneKey(scene);
    }

    /**
     * 解析场景的完整运行时配置 — 含 AI 配置 + 思考参数（联动）。
     * <p>
     * 思考参数解析优先级：
     * <ol>
     *   <li>显式绑定的 AiSceneConfig 字段（thinkingEnabled/reasoningEffort/thinkingBudget）</li>
     *   <li>未显式绑定时：thinkingEnabled 回退到 {@link AiScene#isThinking()}，
     *       reasoningEffort/thinkingBudget 为 null</li>
     * </ul>
     *
     * @param scene AI 场景
     * @return 解析后的完整配置（AiProviderConfig + 思考参数）
     */
    public ResolvedSceneConfig resolveSceneConfig(AiScene scene) {
        AiProviderConfig providerConfig = resolveConfig(scene);
        Optional<AiSceneConfig> binding = sceneConfigRepository.findBySceneKey(scene);

        boolean thinkingEnabled;
        String reasoningEffort = null;
        Integer thinkingBudget = null;

        if (binding.isPresent()) {
            // 显式绑定：用绑定记录的思考参数
            AiSceneConfig sc = binding.get();
            thinkingEnabled = sc.getThinkingEnabled() != null ? sc.getThinkingEnabled() : scene.isThinking();
            reasoningEffort = sc.getReasoningEffort();
            thinkingBudget = sc.getThinkingBudget();
        } else {
            // 未绑定：用场景默认 thinking，无 effort/budget
            thinkingEnabled = scene.isThinking();
        }

        return new ResolvedSceneConfig(providerConfig, scene, thinkingEnabled, reasoningEffort, thinkingBudget);
    }

    /**
     * 解析后的场景配置 — ChatModelFactory 构建模型时的输入。
     */
    public record ResolvedSceneConfig(
            AiProviderConfig providerConfig,
            AiScene scene,
            boolean thinkingEnabled,
            String reasoningEffort,
            Integer thinkingBudget
    ) {}
}
