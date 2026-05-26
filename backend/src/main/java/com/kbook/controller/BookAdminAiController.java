package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.entity.AiConversation;
import com.kbook.entity.AiSession;
import com.kbook.service.BookAdminChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 管理员 AI 对话控制器 — 管理员专属 AI 聊天接口
 * <p>
 * 与 AiController 功能类似，但使用独立的 BookAdminChatService，
 * 管理员可在此进行图书管理相关的 AI 对话。
 * 需要 ADMIN 角色。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/ai")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class BookAdminAiController extends BaseController {

    /** 管理员 AI 对话服务 */
    private final BookAdminChatService adminChatService;

    /**
     * 创建新的管理员 AI 对话会话
     * @return 包含 sessionId 的结果
     */
    @PostMapping("/sessions")
    public Result<Map<String, String>> createSession() {
        Long userId = extractUserId();
        String sessionId = adminChatService.createSession(userId);
        return Result.ok(Map.of("sessionId", sessionId));
    }

    /**
     * 管理员流式 AI 对话（SSE）
     * @param body 包含 sessionId 和 message 的请求体
     * @return SSE 事件流
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody Map<String, String> body) {
        Long userId = extractUserId();
        String sessionId = body.get("sessionId");
        String message = body.get("message");

        // 若未传 sessionId，自动创建新会话
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = adminChatService.createSession(userId);
        }
        if (message == null || message.isBlank()) {
            SseEmitter emitter = new SseEmitter();
            emitter.completeWithError(new IllegalArgumentException("消息不能为空"));
            return emitter;
        }

        return adminChatService.streamChat(userId, sessionId, message);
    }

    /**
     * 管理员非流式 AI 对话
     * @param body 包含 sessionId 和 message 的请求体
     * @return 包含 sessionId 和 response 的结果
     */
    @PostMapping("/chat")
    public Result<Map<String, String>> chat(@RequestBody Map<String, String> body) {
        Long userId = extractUserId();
        String sessionId = body.get("sessionId");
        String message = body.get("message");

        // 若未传 sessionId，自动创建新会话
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = adminChatService.createSession(userId);
        }
        if (message == null || message.isBlank()) {
            return Result.fail("消息不能为空");
        }

        String response = adminChatService.chat(userId, sessionId, message);
        return Result.ok(Map.of("sessionId", sessionId, "response", response));
    }

    /**
     * 获取指定会话的对话历史
     * @param sessionId 会话ID
     * @return 对话记录列表
     */
    @GetMapping("/history")
    public Result<List<AiConversation>> getHistory(@RequestParam String sessionId) {
        Long userId = extractUserId();
        return Result.ok(adminChatService.getHistory(userId, sessionId));
    }

    /**
     * 获取当前管理员的所有会话列表
     * @return 会话列表
     */
    @GetMapping("/sessions")
    public Result<List<AiSession>> getSessions() {
        Long userId = extractUserId();
        return Result.ok(adminChatService.getSessions(userId));
    }

    /**
     * 删除指定会话及其对话历史
     * @param sessionId 会话ID
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        Long userId = extractUserId();
        adminChatService.deleteSession(userId, sessionId);
        return Result.ok();
    }

}
