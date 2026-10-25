package com.kbook.common.enums;
import com.kbook.common.enums.NoteSourceType;

/**
 * 笔记来源类型
 */
public enum NoteSourceType {

    /** 无来源，用户自己写的想法 */
    MANUAL,
    /** 书评 */
    REVIEW,
    /** 短评 */
    COMMENT,
    /** AI 图书问答 */
    AI_CHAT,
    /** 圆桌派 / 辩论 */
    ROUND_TABLE
}
