package com.kbook.service.recommend.event;

/**
 * 图书丢入垃圾桶事件 — 推荐侧监听后从用户推荐集合中移除该书。
 * <p>
 * 替代原 BookTrashService → RecommendService 的直接调用（M4 解循环依赖：
 * 两个服务原先互相注入，改为单向：推荐侧仅保留对回收站查询的依赖）。
 * 同步事件：监听方在发布方事务内执行，与原直接调用语义一致。
 */
public record BookTrashedEvent(Long userId, Long bookId) {
}
