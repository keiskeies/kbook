package com.kbook.service.recommend.event;

/**
 * 图书移出垃圾桶事件 — 推荐侧监听后重算该书得分并加入用户推荐集合。
 * 同步事件：监听方在发布方事务内执行，与原直接调用语义一致。
 */
public record BookRestoredEvent(Long userId, Long bookId) {
}
