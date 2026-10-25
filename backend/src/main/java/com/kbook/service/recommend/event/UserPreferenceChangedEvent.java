package com.kbook.service.recommend.event;

/**
 * 用户画像/偏好变更事件 — 推荐侧监听后异步重算该用户的推荐。
 * <p>
 * 替代原 UserService → RecommendService 的直接调用（M4 解循环依赖：
 * RecommendService 直接依赖 UserService，原先靠 UserService 侧 @Lazy 字段打破环）。
 * 监听方为 @Async，保持原 asyncRecompute 的"发后即忘"语义。
 */
public record UserPreferenceChangedEvent(Long userId) {
}
