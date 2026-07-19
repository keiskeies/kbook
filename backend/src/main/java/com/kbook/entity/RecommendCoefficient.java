package com.kbook.entity;

import com.kbook.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

/**
 * 推荐算法系数实体
 * 存储推荐算法的所有可调系数，支持管理员手动覆盖和系统自动调参
 * <p>
 * 系数分类：
 * - FUSION：四路召回融合权重（rule/vector/collab/explore）
 * - QUALITY：质量因子分段参数（very_low/low/below_avg/good/excellent/unknown）
 * - FRESHNESS：新鲜度参数（days_max/days_decay/bonus_max/bonus_min）
 * - MATCH：画像匹配参数（age_weight/mbti_weight/adjacent_decay/opposite_penalty/opposite_threshold）
 * - COVERAGE：覆盖度衰减参数（dim5~dim1）
 * - PREFERENCE：偏好加成参数（tag/author/format）
 * - OTHER：其他参数（max_same_author/mmr_lambda/explore_random_count）
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "recommend_coefficient", uniqueConstraints = {
        @UniqueConstraint(name = "uk_category_key", columnNames = {"category", "coeff_key"})
})
public class RecommendCoefficient extends BaseEntity {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 系数分类 */
    @Column(nullable = false, length = 20)
    private String category;

    /** 系数键名 */
    @Column(name = "coeff_key", nullable = false, length = 50)
    private String coeffKey;

    /** 当前值 */
    @Column(nullable = false)
    private Double coeffValue;

    /** 默认值（用于重置） */
    @Column(nullable = false)
    private Double defaultValue;

    /** 最小允许值（调参下限） */
    @Column(nullable = false)
    private Double minValue;

    /** 最大允许值（调参上限） */
    @Column(nullable = false)
    private Double maxValue;

    /** 系数说明 */
    @Column(length = 200)
    private String description;

    /** 是否被管理员手动锁定（锁定后自动调参不修改） */
    @Column(nullable = false)
    @Builder.Default
    private Boolean locked = false;

    @Override
    public Long getId() {
        return id;
    }
}
