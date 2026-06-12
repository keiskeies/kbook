package com.kbook.common.util;

import java.io.Serializable;
import java.util.function.Function;

/**
 * 序列化的函数式接口，用于 Lambda 表达式获取实体字段名
 * <p>
 * 使用示例：
 * <pre>
 * Book::getId    // 获取 "id" 字段名
 * Book::getTitle // 获取 "title" 字段名
 * </pre>
 *
 * @param <T> 实体类型
 * @param <R> 字段返回类型
 */
@FunctionalInterface
public interface SFunction<T, R> extends Function<T, R>, Serializable {
}
