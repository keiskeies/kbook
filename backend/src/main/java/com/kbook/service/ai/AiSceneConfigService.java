package com.kbook.service.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kbook.common.util.TransactionUtils;
import com.kbook.config.AiConfigCacheInvalidator;
import com.kbook.entity.AiProviderConfig;
import com.kbook.entity.AiScene;
import com.kbook.entity.AiSceneConfig;
import com.kbook.repository.AiProviderConfigRepository;
import com.kbook.repository.AiSceneConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

/**
 * AI 场景配置解析服务 — 路由层核心。
 * <p>
 * 解析路径（按优先级回退）：
 * <ol>
 *   <li>精确匹配：scene_key 已绑定且绑定的 config enabled=true → 返回</li>
 *   <li>分类回退：按 {@link AiScene#getDefaultCategory()} 走 roles/purpose 默认</li>
 * </ol>
 * <p>
 * 缓存：场景解析结果（配置 + 绑定 + 思考参数）以 {@link AiScene} 为 key 缓存，
 * 单次解析后供 {@link #resolveConfig} / {@link #resolveSceneConfig} 共享；
 * {@link #isExplicitlyBound} / {@link #getExplicitBinding} 使用独立的只读绑定缓存 —
 * 二者在"场景未绑定且分类回退配置缺失"时<b>不抛异常</b>（与改造前
 * existsBySceneKey / findBySceneKey 语义一致），而解析入口照常抛出。
 * 热点路径不再产生 SQL。失效由 {@link AiConfigCacheInvalidator} 统一驱动：
 * 本副本配置写入后立即清空，其他副本通过 Redis 版本号在约 1s 内感知；
 * 另有 {@value #SCENE_CACHE_TTL_MINUTES} 分钟 TTL 兜底。
 *
 * @see AiProviderConfigService 默认配置查询（roles=QA/TOOL 等）
 */
@Slf4j
@Service
public class AiSceneConfigService {

    /** 场景缓存 TTL（分钟）— 兜底：即使版本号机制失效，陈旧数据也不会无限期存活 */
    private static final long SCENE_CACHE_TTL_MINUTES = 10;

    private final AiSceneConfigRepository sceneConfigRepository;
    private final AiProviderConfigRepository providerConfigRepository;
    private final AiProviderConfigService providerConfigService;
    private final AiConfigCacheInvalidator cacheInvalidator;

    /** 场景解析结果缓存，key = AiScene */
    private final Cache<AiScene, SceneResolution> sceneCache = Caffeine.newBuilder()
            .maximumSize(256)
            .expireAfterWrite(Duration.ofMinutes(SCENE_CACHE_TTL_MINUTES))
            .build();

    public AiSceneConfigService(AiSceneConfigRepository sceneConfigRepository,
                                AiProviderConfigRepository providerConfigRepository,
                                AiProviderConfigService providerConfigService,
                                AiConfigCacheInvalidator cacheInvalidator) {
        this.sceneConfigRepository = sceneConfigRepository;
        this.providerConfigRepository = providerConfigRepository;
        this.providerConfigService = providerConfigService;
        this.cacheInvalidator = cacheInvalidator;
    }

    /** 向失效协调器注册本服务的本地缓存清空回调 */
    @PostConstruct
    void registerCacheInvalidation() {
        cacheInvalidator.register(this::invalidateSceneCache);
    }

    /** 清空场景解析缓存（本副本） */
    public void invalidateSceneCache() {
        sceneCache.invalidateAll();
    }

    /**
     * 解析场景对应的 AI 配置（按优先级回退）。
     *
     * @param scene AI 场景
     * @return 启用的 AI 配置；找不到抛 IllegalStateException
     */
    public AiProviderConfig resolveConfig(AiScene scene) {
        return resolve(scene).resolved().providerConfig();
    }

    /**
     * 解析场景的完整运行时配置 — 含 AI 配置 + 思考参数（联动），带缓存。
     * <p>
     * 思考参数解析优先级：
     * <ol>
     *   <li>显式绑定的 AiSceneConfig 字段（thinkingEnabled/reasoningEffort/thinkingBudget）</li>
     *   <li>未显式绑定时：thinkingEnabled 回退到 {@link AiScene#isThinking()}，
     *       reasoningEffort/thinkingBudget 为 null</li>
     * </ol>
     *
     * @param scene AI 场景
     * @return 解析后的完整配置（AiProviderConfig + 思考参数）
     */
    public ResolvedSceneConfig resolveSceneConfig(AiScene scene) {
        return resolve(scene).resolved();
    }

    /**
     * 判断场景是否已显式绑定（而非走默认回退）。
     * <p>
     * 优先读取解析缓存（热路径零 SQL）；缓存缺失时直接查库，
     * 与改造前的 existsBySceneKey 语义一致 — 场景未绑定且分类回退配置缺失时
     * 仅返回 false，<b>不</b>触发（也不抛出）完整解析。
     */
    public boolean isExplicitlyBound(AiScene scene) {
        SceneResolution cached = sceneCache.getIfPresent(scene);
        if (cached != null) {
            return cached.explicitlyBound();
        }
        return sceneConfigRepository.existsBySceneKey(scene);
    }

    /**
     * 获取场景的显式绑定记录（含思考参数），未绑定时返回 empty。
     * <p>
     * 读取策略同 {@link #isExplicitlyBound}：缓存命中返回共享的只读绑定记录，
     * 未命中直接查库，不触发完整解析、不抛异常。调用方不得修改返回的实体。
     */
    public Optional<AiSceneConfig> getExplicitBinding(AiScene scene) {
        SceneResolution cached = sceneCache.getIfPresent(scene);
        if (cached != null) {
            return Optional.ofNullable(cached.binding());
        }
        return sceneConfigRepository.findBySceneKey(scene);
    }

    /** 缓存读取入口 */
    private SceneResolution resolve(AiScene scene) {
        return sceneCache.get(scene, this::loadResolution);
    }

    /**
     * 真实解析（仅缓存未命中时执行一次）：
     * 单次 {@code findBySceneKey} 同时用于配置路由与思考参数解析，
     * 消除此前的重复查询。
     */
    private SceneResolution loadResolution(AiScene scene) {
        // 1. 精确匹配：场景已绑定配置
        Optional<AiSceneConfig> binding = sceneConfigRepository.findBySceneKey(scene);
        AiProviderConfig providerConfig = null;
        if (binding.isPresent()) {
            Long configId = binding.get().getConfigId();
            providerConfig = providerConfigRepository.findById(configId)
                    .filter(AiProviderConfig::getEnabled)
                    .orElse(null);
            if (providerConfig != null) {
                log.trace("场景 [{}] 命中专属配置: id={}, name={}", scene, configId, providerConfig.getName());
            } else {
                // 绑定的配置被禁用或删除，进入回退
                log.warn("场景 [{}] 绑定的配置 id={} 已禁用或不存在，回退到默认分类 {}",
                        scene, configId, scene.getDefaultCategory());
            }
        }

        // 2. 分类回退
        if (providerConfig == null) {
            providerConfig = resolveByCategory(scene);
            if (binding.isEmpty()) {
                log.debug("场景 [{}] 未配置专属，使用分类 [{}] 默认配置: id={}, name={}",
                        scene, scene.getDefaultCategory(), providerConfig.getId(), providerConfig.getName());
            }
        }

        // 3. 思考参数（复用第 1 步的 binding，不再二次查询）
        boolean thinkingEnabled;
        String reasoningEffort = null;
        Integer thinkingBudget = null;
        if (binding.isPresent()) {
            AiSceneConfig sc = binding.get();
            thinkingEnabled = sc.getThinkingEnabled() != null ? sc.getThinkingEnabled() : scene.isThinking();
            reasoningEffort = sc.getReasoningEffort();
            thinkingBudget = sc.getThinkingBudget();
        } else {
            thinkingEnabled = scene.isThinking();
        }

        return new SceneResolution(
                new ResolvedSceneConfig(providerConfig, scene, thinkingEnabled, reasoningEffort,
                        thinkingBudget, null),
                binding.orElse(null));
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
                // 优先查 purpose=VISION 的 DB 配置（用户在后台配置的视觉模型）
                AiProviderConfig visionCfg = providerConfigService.getFirstEnabledByPurpose("VISION");
                if (visionCfg != null) {
                    return visionCfg;
                }
                // 回退到 QA 配置（与原 ChatModelFactory.buildVisionChatModel 回退逻辑一致）
                // 注意：ChatModelFactory.buildForScene 在 VISION 场景未绑定时，
                // 会优先尝试 YML kbook.ai.vision 配置覆盖此回退结果
                cfg = providerConfigService.getChatConfigByRole("QA");
                if (cfg == null) {
                    throw new IllegalStateException(
                            "场景 [" + scene + "] VISION 回退到 QA 默认配置失败，请添加 purpose=VISION 或 roles=QA 的启用配置");
                }
                return cfg;
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
        // 提交后再失效：避免事务回滚时误清缓存、或用未提交的数据重建缓存
        TransactionUtils.afterCommit(cacheInvalidator::bumpVersion);
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
            // 提交后再失效（跨副本 + 本地）
            TransactionUtils.afterCommit(cacheInvalidator::bumpVersion);
            log.info("场景 [{}] 绑定已清除", scene);
        });
    }

    /**
     * 缓存条目：一次解析的结果，同时提供配置路由与绑定/思考参数信息。
     */
    public record SceneResolution(ResolvedSceneConfig resolved, AiSceneConfig binding) {
        public boolean explicitlyBound() {
            return binding != null;
        }
    }

    /**
     * 解析后的场景配置 — ChatModelFactory 构建模型时的输入。
     *
     * @param maxOutputTokens 输出上限（§4.2 可选，空 = 现状不变）
     */
    public record ResolvedSceneConfig(
            AiProviderConfig providerConfig,
            AiScene scene,
            boolean thinkingEnabled,
            String reasoningEffort,
            Integer thinkingBudget,
            Integer maxOutputTokens
    ) {}
}
