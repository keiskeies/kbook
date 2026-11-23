package com.kbook.entity.debate;

import com.kbook.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 辩论论点弹药库实体 — 每场深度辩论一份。
 * <p>
 * focalPoints / battleState 均以 JSON 文本存储，字段结构见
 * {@code DebateDossierVO.FocalPointVO} / {@code DebateDossierVO.BattleStateVO}。
 * status 流转：GENERATING → READY / FAILED。
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "debate_dossiers", indexes = {
        @Index(name = "idx_debate_dossier_session_id", columnList = "session_id")
})
public class DebateDossier extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话唯一标识（对应 debate_sessions.session_id） */
    @Column(name = "session_id", nullable = false, length = 100, unique = true)
    private String sessionId;

    /** 分歧点列表 JSON（FocalPointVO 数组） */
    @Column(name = "focal_points", columnDefinition = "TEXT")
    private String focalPoints;

    /** 战场状态 JSON（BattleStateVO 对象） */
    @Column(name = "battle_state", columnDefinition = "TEXT")
    private String battleState;

    /** 生成状态：GENERATING / READY / FAILED */
    @Column(name = "status", length = 20)
    private String status;

    @Override
    public Long getId() {
        return id;
    }
}
