package com.kbook.entity;

/**
 * 笔记来源的存活状态 — 来源删除只改状态与指针，不动快照
 */
public enum SourceStatus {

    /** 来源仍可访问 */
    ACTIVE,
    /** 来源已删除，笔记保留快照 */
    DELETED
}
