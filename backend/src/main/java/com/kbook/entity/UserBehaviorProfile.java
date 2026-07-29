package com.kbook.entity;

import com.kbook.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 用户行为画像实体（L2 画像）。
 *
 * <p>与 {@link User} 上的 L1 静态画像分层：
 * <ul>
 *   <li>L1：用户主动填写，低频变化（年龄/性别/MBTI/职业等）</li>
 *   <li>L2：从用户在图书问答、AI 助理中手动输入的提问中抽取，
 *       反映近期兴趣、认知、动机、价值观，按滑动窗口周期性更新</li>
 * </ul>
 *
 * <p>所有结构化字段使用 TEXT 存储 JSON，schema 频繁迭代时无需改表。
 * 长度由 {@link com.kbook.service.ai.behavior.BehaviorProfileBuilder} 强制限制：
 * interestTags ≤ 5，motivations ≤ 3，knowledgeGaps ≤ 3，valueOrientation ≤ 5。
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_behavior_profile", indexes = {
        @Index(name = "idx_bp_user_id", columnList = "user_id")
})
public class UserBehaviorProfile extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 ID（一对一） */
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /**
     * 兴趣主题（JSON 数组，每项 {"tag":"职场女性困境","weight":0.8}）。
     * 最多 5 项；累计抽取时由 LLM 决定加强/衰减/删除。
     */
    @Column(name = "interest_tags", columnDefinition = "TEXT")
    private String interestTags;

    /**
     * 阅读动机（JSON 数组，每项 {"motivation":"价值观共鸣","weight":0.7}）。
     * 最多 3 项。
     */
    @Column(name = "reading_motivations", columnDefinition = "TEXT")
    private String readingMotivations;

    /**
     * 知识盲区（JSON 数组，纯字符串，如 ["女性主义理论谱系","宏观经济周期"]）。
     * 最多 3 项。指用户提问中暴露的"想懂但还不懂"的概念。
     */
    @Column(name = "knowledge_gaps", columnDefinition = "TEXT")
    private String knowledgeGaps;

    /**
     * 价值观倾向（JSON 数组，纯字符串，如 ["女性主义","职业平等"]）。
     * 最多 5 项。
     */
    @Column(name = "value_orientation", columnDefinition = "TEXT")
    private String valueOrientation;

    /** 认知深度：SURFACE / ANALYTICAL / CRITICAL */
    @Enumerated(EnumType.STRING)
    @Column(name = "cognitive_depth", length = 20)
    private CognitiveDepth cognitiveDepth;

    /** 情绪基调：SEEKING_VALIDATION / EXPLORING / QUESTIONING / RESIGNED / OPTIMISTIC */
    @Enumerated(EnumType.STRING)
    @Column(name = "emotional_tone", length = 30)
    private EmotionalTone emotionalTone;

    /**
     * 性格特质（JSON 数组，每项 {"tag":"内省","weight":0.8}）。
     * 最多 5 项。描述用户稳定的性格倾向，从提问风格和关注点推断。
     */
    @Column(name = "personality_traits", columnDefinition = "TEXT")
    private String personalityTraits;

    /** 思维方式：SYSTEMATIC / DIVERGENT / CRITICAL / INTUITIVE / PRAGMATIC */
    @Enumerated(EnumType.STRING)
    @Column(name = "thinking_style", length = 20)
    private ThinkingStyle thinkingStyle;

    /** 读者人格：DEEP_DIVER / EXPLORER / QUESTIONER / CONTEMPLATOR / SEEKER */
    @Enumerated(EnumType.STRING)
    @Column(name = "reader_archetype", length = 20)
    private ReaderArchetype readerArchetype;

    /**
     * 人生困惑（JSON 数组，纯字符串）。
     * 最多 3 项。用户提问中暴露的"想从书中找答案"的具体困惑，
     * 如 "该不该放弃稳定工作追求理想"、"如何面对亲人衰老"。
     */
    @Column(name = "confusions", columnDefinition = "TEXT")
    private String confusions;

    /**
     * 当前处境（一段简短描述，≤ 50 字）。
     * LLM 综合用户多个提问推断的人生阶段/境遇概述，
     * 如 "职场转型期，对未来方向感到迷茫"。
     */
    @Column(name = "life_context", length = 100)
    private String lifeContext;

    /**
     * 已被用户主动删除的信号（JSON 数组，纯字符串）。
     * 下次抽取时 LLM 会看到这些信号并禁止再加强它们。
     */
    @Column(name = "suppressed_signals", columnDefinition = "TEXT")
    private String suppressedSignals;

    /** 累计抽取的提问数（用于展示和审计） */
    @Column(name = "total_signals")
    private Integer totalSignals;

    /** 最后一次抽取时间 */
    @Column(name = "last_inferred_at")
    private LocalDateTime lastInferredAt;

    /**
     * 最近 20 条原始提问（JSON 字符串数组），用于复盘/审计/重新抽取。
     * 滚动保留：每次新提问追加，超过 20 条淘汰最旧的。
     */
    @Column(name = "recent_signals", columnDefinition = "TEXT")
    private String recentSignals;

    @Override
    public Long getId() {
        return id;
    }

    /** 认知深度枚举 */
    public enum CognitiveDepth {
        SURFACE,    // 表层：复述情节、问基本信息
        ANALYTICAL, // 分析：拆解因果、对照前后
        CRITICAL    // 批判：质疑结论、反思前提
    }

    /** 情绪基调枚举 */
    public enum EmotionalTone {
        SEEKING_VALIDATION, // 寻求认同（"是不是该这样"）
        EXPLORING,          // 探索（"还有什么是这样"）
        QUESTIONING,        // 质疑（"这说得对吗"）
        RESIGNED,           // 无奈（"道理我都懂但..."）
        OPTIMISTIC          // 积极（"那我该怎么做"）
    }

    /** 思维方式枚举 */
    public enum ThinkingStyle {
        SYSTEMATIC, // 系统型：构建体系、追问根因
        DIVERGENT,  // 发散型：跳跃联想、跨界连接
        CRITICAL,   // 批判型：习惯质疑、寻找破绽
        INTUITIVE,  // 直觉型：凭感觉下判断、不喜深究
        PRAGMATIC   // 务实型：关注实用、不纠结理论
    }

    /** 读者人格枚举 */
    public enum ReaderArchetype {
        DEEP_DIVER,    // 深潜者：少量主题深挖
        EXPLORER,      // 探索者：跨界游走、广度优先
        QUESTIONER,    // 追问者：不断追问、不满足表面
        CONTEMPLATOR,  // 沉思者：慢读、内省、与自身对照
        SEEKER         // 求索者：带着人生困惑找答案
    }
}
