package com.kbook.common.enums;
import com.kbook.common.enums.NoteType;

/**
 * 笔记类型 — 决定 AI 成长总结时的权重：
 * THOUGHT / REVIEW_CAPTURE 中用户自己的批注是主料，纯 EXCERPT 只作「近期关注什么」的佐证。
 */
public enum NoteType {

    /** 纯想法，无来源 */
    THOUGHT,
    /** 摘录他人内容 */
    EXCERPT,
    /** 由书评收录而来 */
    REVIEW_CAPTURE,
    /** 来自 AI 对话 / 圆桌派 / 辩论 */
    CONVERSATION
}
