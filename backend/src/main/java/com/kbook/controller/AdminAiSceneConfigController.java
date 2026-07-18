package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.entity.AiProviderConfig;
import com.kbook.entity.AiScene;
import com.kbook.entity.AiSceneConfig;
import com.kbook.repository.AiProviderConfigRepository;
import com.kbook.service.ai.AiSceneConfigService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 场景配置管理控制器 — 管理每个业务场景绑定的 AI 配置。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/ai-scene-config")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "AI场景配置管理")
public class AdminAiSceneConfigController {

    private final AiSceneConfigService sceneConfigService;
    private final AiProviderConfigRepository providerConfigRepository;

    /**
     * 列出所有场景及其当前绑定状态（按枚举顺序）。
     */
    @GetMapping
    public Result<List<SceneView>> listScenes() {
        List<SceneView> views = Arrays.stream(AiScene.values())
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .map(this::toView)
                .collect(Collectors.toList());
        return Result.ok(views);
    }

    /**
     * 绑定场景到指定配置（含思考参数）。
     */
    @PostMapping("/{scene}/bind/{configId}")
    public Result<AiSceneConfig> bind(
            @PathVariable AiScene scene,
            @PathVariable Long configId,
            @RequestBody(required = false) BindRequest req) {
        try {
            Boolean thinkingEnabled = req != null ? req.getThinkingEnabled() : null;
            String reasoningEffort = req != null ? req.getReasoningEffort() : null;
            Integer thinkingBudget = req != null ? req.getThinkingBudget() : null;
            AiSceneConfig saved = sceneConfigService.bind(scene, configId,
                    thinkingEnabled, reasoningEffort, thinkingBudget);
            return Result.ok(saved);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    /** 绑定请求体 */
    @lombok.Data
    public static class BindRequest {
        /** 是否开启思考，null=用场景默认 */
        private Boolean thinkingEnabled;
        /** 思考强度 low/medium/high，仅当配置 thinkingMode=REASONING_EFFORT 时有效 */
        private String reasoningEffort;
        /** 思考 token 预算，仅当配置 thinkingMode=THINKING_BUDGET 时有效 */
        private Integer thinkingBudget;
    }

    /**
     * 清除场景绑定（回退到默认分类）。
     */
    @DeleteMapping("/{scene}")
    public Result<Void> unbind(@PathVariable AiScene scene) {
        sceneConfigService.unbind(scene);
        return Result.ok();
    }

    /** 组装场景视图（含当前绑定配置信息 + 思考参数） */
    private SceneView toView(AiScene scene) {
        SceneView v = new SceneView();
        v.setSceneKey(scene.name());
        v.setDisplayName(scene.getDisplayName());
        v.setDefaultCategory(scene.getDefaultCategory().name());
        v.setStreaming(scene.isStreaming());
        v.setThinking(scene.isThinking());

        // 查找当前绑定的配置
        AiProviderConfig bound = null;
        try {
            bound = sceneConfigService.resolveConfig(scene);
        } catch (Exception e) {
            // 默认配置不存在时，bound=null，前端显示"未配置"
        }

        if (bound != null) {
            v.setBoundConfigId(bound.getId());
            v.setBoundConfigName(bound.getName());
            v.setBoundProvider(bound.getProvider().name());
            v.setBoundModelName(bound.getModelName());
            v.setBoundEnabled(bound.getEnabled());
            // 联动：返回绑定配置的 thinkingMode，前端据此渲染思考表单
            v.setBoundThinkingMode(bound.getThinkingMode() != null
                    ? bound.getThinkingMode().name() : "SWITCH");
        }

        // 标记是否为显式绑定
        boolean explicit = sceneConfigService.isExplicitlyBound(scene);
        v.setExplicitlyBound(explicit);

        // 返回显式绑定的思考参数（仅显式绑定时有值）
        sceneConfigService.getExplicitBinding(scene).ifPresent(sc -> {
            v.setThinkingEnabled(sc.getThinkingEnabled());
            v.setReasoningEffort(sc.getReasoningEffort());
            v.setThinkingBudget(sc.getThinkingBudget());
        });

        return v;
    }

    /** 场景视图 DTO */
    @lombok.Data
    public static class SceneView {
        private String sceneKey;
        private String displayName;
        private String defaultCategory;
        private boolean streaming;
        private boolean thinking;
        /** 当前生效的配置 ID（显式绑定或默认回退） */
        private Long boundConfigId;
        private String boundConfigName;
        private String boundProvider;
        private String boundModelName;
        private Boolean boundEnabled;
        /** 绑定配置的 thinkingMode — 联动前端思考表单渲染 */
        private String boundThinkingMode;
        /** 是否为显式绑定（true）而非默认回退（false） */
        private boolean explicitlyBound;
        /** 显式绑定的思考参数（仅 explicitlyBound=true 时有值） */
        private Boolean thinkingEnabled;
        private String reasoningEffort;
        private Integer thinkingBudget;
    }
}
