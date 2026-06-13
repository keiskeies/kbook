package com.kbook.dto.debate;

import com.kbook.entity.debate.DebateSession;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 辩论会话视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebateSessionVO {

    private Long id;
    private String sessionId;
    private Long bookId;
    private String topic;
    private String topicSource;
    private String proRoleKeys;
    private String conRoleKeys;
    private Integer currentRound;
    private String currentPhase;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 从实体构建视图对象
     */
    public static DebateSessionVO from(DebateSession entity) {
        return DebateSessionVO.builder()
                .id(entity.getId())
                .sessionId(entity.getSessionId())
                .bookId(entity.getBookId())
                .topic(entity.getTopic())
                .topicSource(entity.getTopicSource())
                .proRoleKeys(entity.getProRoleKeys())
                .conRoleKeys(entity.getConRoleKeys())
                .currentRound(entity.getCurrentRound())
                .currentPhase(entity.getCurrentPhase())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
