package com.kbook.entity;

import com.kbook.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * AI 场景配置映射实体 — 一个场景绑定一个 AI 配置。
 * <p>
 * 设计：与 {@link AiProviderConfig} 解耦，避免一条 AI 配置服务多场景时
 * 在 AiProviderConfig 上堆砌逗号分隔字段。
 * <p>
 * 查询路径：
 * <ol>
 *   <li>精确匹配：scene_key = ? AND config_id 指向启用的 AiProviderConfig</li>
 *   <li>回退：按 {@link AiScene.Category} 走 roles/purpose 默认查询</li>
 * </ol>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_scene_config",
        indexes = {
                @Index(name = "idx_scene_config", columnList = "scene_key, config_id", unique = true)
        })
public class AiSceneConfig extends BaseEntity {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 场景标识 — 对应 {@link AiScene#name()}。
     * 唯一约束：一个场景只能绑定一条记录（覆盖更新）。
     */
    @Column(nullable = false, length = 50, unique = true)
    @Enumerated(EnumType.STRING)
    private AiScene sceneKey;

    /**
     * 绑定的 AI 配置 ID（外键到 ai_provider_config.id）。
     * 必须指向 enabled=true 的配置；被绑定的配置禁用时回退到默认。
     */
    @Column(nullable = false)
    private Long configId;

    /**
     * 是否开启思考 — 联动 {@link AiProviderConfig#getThinkingMode()}：
     * <ul>
     *   <li>NONE 模式：忽略此字段，不发送任何思考参数</li>
     *   <li>SWITCH/REASONING_EFFORT/THINKING_BUDGET 模式：true=开启思考，false=关闭</li>
     * </ul>
     * 为 null 时回退到 {@link AiScene#isThinking()} 默认值。
     */
    private Boolean thinkingEnabled;

    /**
     * 思考强度（low/medium/high）— 仅当绑定配置的 thinkingMode=REASONING_EFFORT 时有效。
     * 为 null 时不发送 reasoning_effort 参数（由模型默认决定）。
     * 联动：前端仅当 boundConfig.thinkingMode=REASONING_EFFORT 时显示此选项。
     */
    @Column(length = 10)
    private String reasoningEffort;

    /**
     * 思考 token 预算 — 仅当绑定配置的 thinkingMode=THINKING_BUDGET 时有效。
     * 为 null 时不发送 thinking_budget 参数。
     * 联动：前端仅当 boundConfig.thinkingMode=THINKING_BUDGET 时显示此选项。
     */
    private Integer thinkingBudget;

    @Override
    public Long getId() {
        return id;
    }
}
