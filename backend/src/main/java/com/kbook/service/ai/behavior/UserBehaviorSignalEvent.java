package com.kbook.service.ai.behavior;

/**
 * 用户手动提问事件。
 *
 * <p>由 {@code BookChatService} / {@code AiChatService} 在保存用户消息后发布，
 * 由 {@code BehaviorProfileService} 异步监听，写入 Redis 信号队列。
 *
 * <p>区分手动提问 vs 点击追问：
 * <ul>
 *   <li>manual=true：用户在输入框敲字发送，反映真实意图</li>
 *   <li>manual=false：用户点击 AI 推荐的追问问题，信号较弱</li>
 * </ul>
 * 抽取器据此加权（手动 weight=1.0，点击 weight=0.3）。
 */
public record UserBehaviorSignalEvent(
        Long userId,
        String sessionId,
        String type,        // "book_chat" / "assistant"
        Long bookId,        // 图书问答时非空，AI 助理为 null
        String content,     // 用户原始问题
        boolean manual      // 是否手动输入
) {
}
