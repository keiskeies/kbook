package com.kbook.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 请求级工具结果缓存 — 存储当前请求中 AI 工具返回的书名 → bookId 映射。
 * <p>
 * 使用 ThreadLocal 而非 @RequestScope，避免 Spring 代理在线程池中解析失败。
 * 每次 AI 对话前由 AiChatService 创建实例并绑定到当前线程，
 * 对话结束后清理。
 */
public class ToolResultContext {

    private static final ThreadLocal<ToolResultContext> CURRENT = new ThreadLocal<>();

    /**
     * 书名 → bookId（保持插入顺序）
     */
    private final Map<String, Long> bookMap = new LinkedHashMap<>();

    /**
     * 本轮对话中工具是否被调用过
     */
    private boolean toolCalled = false;

    /**
     * 绑定当前实例到线程
     */
    public static void bind(ToolResultContext ctx) {
        CURRENT.set(ctx);
    }

    /**
     * 获取当前线程绑定的实例
     */
    public static ToolResultContext current() {
        return CURRENT.get();
    }

    /**
     * 清理当前线程绑定
     */
    public static void unbind() {
        CURRENT.remove();
    }

    /**
     * 记录工具返回的一本书
     */
    public void addBook(String title, Long bookId) {
        if (title != null && !title.isBlank() && bookId != null) {
            bookMap.putIfAbsent(title.trim(), bookId);
        }
    }

    /**
     * 标记本轮有工具被调用
     */
    public void markToolCalled() {
        this.toolCalled = true;
    }

    /**
     * 是否有工具被调用过
     */
    public boolean isToolCalled() {
        return toolCalled;
    }

    /**
     * 获取书名→bookId 映射的副本
     */
    public Map<String, Long> getBookMap() {
        return new LinkedHashMap<>(bookMap);
    }

    /**
     * 是否有数据
     */
    public boolean hasBooks() {
        return !bookMap.isEmpty();
    }

    /**
     * 清空
     */
    public void clear() {
        bookMap.clear();
        toolCalled = false;
    }
}
