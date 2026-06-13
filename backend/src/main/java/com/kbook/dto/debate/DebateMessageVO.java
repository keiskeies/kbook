package com.kbook.dto.debate;

import com.kbook.entity.debate.DebateMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 辩论消息视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebateMessageVO {

    private Long id;
    private String sessionId;
    private String roleKey;
    private String roleName;
    private String positionKey;
    private String side;
    private String content;
    private Integer roundNumber;
    private String roundType;
    private String examRole;
    private Integer phaseOrder;
    private LocalDateTime createdAt;

    /**
     * 从实体构建视图对象
     */
    public static DebateMessageVO from(DebateMessage entity) {
        return DebateMessageVO.builder()
                .id(entity.getId())
                .sessionId(entity.getSessionId())
                .roleKey(entity.getRoleKey())
                .roleName(entity.getRoleName())
                .positionKey(entity.getPositionKey())
                .side(entity.getSide())
                .content(entity.getContent())
                .roundNumber(entity.getRoundNumber())
                .roundType(entity.getRoundType())
                .examRole(entity.getExamRole())
                .phaseOrder(entity.getPhaseOrder())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
