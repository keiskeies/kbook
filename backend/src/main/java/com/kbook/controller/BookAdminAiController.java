package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.entity.AiConversation;
import com.kbook.service.BookAdminChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 管理员 AI 对话控制器 — 图书管理员智能助手
 * <p>
 * 仅管理员可访问，提供自然语言驱动的图书管理能力。
 * 管理员在对话框中输入需求（如"删除张三的所有书"），
 * AI 自动调用对应的工具方法完成任务。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/ai")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class BookAdminAiController {

    private final BookAdminChatService adminChatService;

    /**
     * 创建新会话
     */
    @PostMapping("/sessions")
    public Result<Map<String, String>> createSession() {
        Long userId = extractUserId();
        String sessionId = adminChatService.createSession(userId);
        return Result.ok(Map.of("sessionId", sessionId));
    }

    /**
     * 流式对话 — SSE
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody Map<String, String> body) {
        Long userId = extractUserId();
        String sessionId = body.get("sessionId");
        String message = body.get("message");

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
     * 非流式对话
     */
    @PostMapping("/chat")
    public Result<Map<String, String>> chat(@RequestBody Map<String, String> body) {
        Long userId = extractUserId();
        String sessionId = body.get("sessionId");
        String message = body.get("message");

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
     * 获取对话历史
     */
    @GetMapping("/history")
    public Result<List<AiConversation>> getHistory(@RequestParam String sessionId) {
        Long userId = extractUserId();
        return Result.ok(adminChatService.getHistory(userId, sessionId));
    }

    /**
     * 获取会话列表
     */
    @GetMapping("/sessions")
    public Result<List<String>> getSessions() {
        Long userId = extractUserId();
        return Result.ok(adminChatService.getSessions(userId));
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        Long userId = extractUserId();
        adminChatService.deleteSession(userId, sessionId);
        return Result.ok();
    }

    /** 从认证信息中提取用户 ID */
    private Long extractUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Long) return (Long) principal;
        if (principal instanceof UserDetails) {
            try {
                return Long.parseLong(((UserDetails) principal).getUsername());
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "用户信息异常");
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户信息获取失败");
    }
}
