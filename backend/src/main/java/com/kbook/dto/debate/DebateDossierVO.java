package com.kbook.dto.debate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 辩论论点弹药库 VO — 后端接入层与前端战场地图共用的数据结构。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebateDossierVO {

    private String sessionId;

    /** GENERATING / READY / FAILED */
    private String status;

    private List<FocalPointVO> focalPoints;

    private BattleStateVO battleState;

    /** 单个分歧点：争议焦点 + 双方论证方向 + 书中定位 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FocalPointVO {
        private Integer id;
        private String title;
        private String proThesis;
        private String conThesis;
        /** 仅章节名或序号，不含原文 */
        private String bookAnchor;
        private List<String> concepts;
    }

    /** 尚未被回应的质疑（评论性转述，不含原文） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChallengeVO {
        /** 发言位 key，如 CON_1 */
        private String from;
        private String text;
        /** 该质疑指向的一方：PRO / CON */
        private String targetSide;
    }

    /** 动态战场状态 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BattleStateVO {
        private Integer currentFocalPointId;
        private List<ChallengeVO> unansweredChallenges;
        private List<String> brokenPoints;
        private List<String> establishedPoints;
    }
}
