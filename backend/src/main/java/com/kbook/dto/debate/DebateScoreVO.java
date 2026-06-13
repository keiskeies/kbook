package com.kbook.dto.debate;

import com.kbook.entity.debate.DebateScore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 辩论评分视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebateScoreVO {

    private Long id;
    private String sessionId;
    private Long messageId;
    private String roleKey;
    private String positionKey;
    private String side;
    private Integer roundNumber;
    private String roundType;
    private Double logicScore;
    private Double evidenceScore;
    private Double rebuttalScore;
    private Double impactScore;
    private Double humorScore;
    private Double clarityScore;
    private Double noveltyScore;
    private Double averageScore;

    /**
     * 从实体构建视图对象
     */
    public static DebateScoreVO from(DebateScore entity) {
        return DebateScoreVO.builder()
                .id(entity.getId())
                .sessionId(entity.getSessionId())
                .messageId(entity.getMessageId())
                .roleKey(entity.getRoleKey())
                .positionKey(entity.getPositionKey())
                .side(entity.getSide())
                .roundNumber(entity.getRoundNumber())
                .roundType(entity.getRoundType())
                .logicScore(entity.getLogicScore())
                .evidenceScore(entity.getEvidenceScore())
                .rebuttalScore(entity.getRebuttalScore())
                .impactScore(entity.getImpactScore())
                .humorScore(entity.getHumorScore())
                .clarityScore(entity.getClarityScore())
                .noveltyScore(entity.getNoveltyScore())
                .averageScore(entity.getAverageScore())
                .build();
    }
}
