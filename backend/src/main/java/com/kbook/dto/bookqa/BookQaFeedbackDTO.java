package com.kbook.dto.bookqa;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 图书问答反馈闭环的请求/响应结构（方案 P2-5）。集中在一个文件里，避免五六个只有三个字段的类。 */
public final class BookQaFeedbackDTO {

    private BookQaFeedbackDTO() {}

    /** 提交 / 修改反馈 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubmitRequest {
        /** UP / DOWN */
        private String rating;
        /** WRONG / NOT_GROUNDED / OFF_TOPIC / TOO_LONG / OTHER（DOWN 时建议必填） */
        private String reason;
        private String comment;
    }

    /** 当前用户对该回答的反馈状态；未反馈时 data 为 null */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VO {
        private Long id;
        private Long conversationId;
        private String rating;
        private String reason;
        private String comment;
        private String contextMode;
        private String reviewStatus;
    }

    /** 待标注队列条目：带上问题与回答预览，管理员不用切页面就能判断要不要进黄金集 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueueItem {
        private Long id;
        private Long bookId;
        private String bookTitle;
        private Long conversationId;
        private String sessionId;
        private String rating;
        private String reason;
        private String comment;
        private String contextMode;
        private String reviewStatus;
        private String reviewNote;
        private String createdAt;
        private String question;
        private String answerPreview;
    }

    /** 标注结论 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReviewRequest {
        /** ANNOTATED（已纳入黄金集）/ DISMISSED（误报）/ QUEUED（退回队列） */
        private String status;
        private String note;
    }

    /** 反馈看板汇总 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Stats {
        private long total;
        private long up;
        private long down;
        private long queued;
        private long annotated;
        private long dismissed;
        private double downRatio;
    }
}
