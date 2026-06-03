package com.kbook.config.annotation;

import java.lang.annotation.*;

/**
 * 操作日志注解 — 标注在 Service 方法上，用于 AOP 日志记录
 * <p>
 * 使用方式：@LogAction("生成速读摘要")
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LogAction {
    /** 操作名称，如 "生成速读摘要" */
    String value();
}
