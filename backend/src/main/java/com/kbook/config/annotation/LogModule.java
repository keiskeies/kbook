package com.kbook.config.annotation;

import java.lang.annotation.*;

/**
 * 日志模块注解 — 标注在 Service 类上，用于标识模块名
 * <p>
 * 使用方式：@LogModule("图书")
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LogModule {
    /** 模块名称，如 "图书"、"用户"、"书架" */
    String value();
}
