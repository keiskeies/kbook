package com.kbook.common.enums;
import com.kbook.common.enums.CaptureMode;

/**
 * 收录时的展示方式
 */
public enum CaptureMode {

    /** 笔记中展示来源全文 */
    FULL,
    /** 只展示我截取的那一段 */
    EXCERPT,
    /** 来源只作为引用卡片，正文是我自己的话 */
    REFERENCE
}
