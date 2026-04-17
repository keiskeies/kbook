package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.entity.AiConversation;
import com.kbook.repository.AiConversationRepository;
import com.kbook.service.AiChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 助理控制器 — 对话接口（SSE 流式 + 普通）
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiChatService chatService;
    private final AiConversationRepository conversationRepository;

    /** 默认快捷提问（数据库中无记录时的兜底） */
    private static final List<String> DEFAULT_PROMPTS = List.of(
            "推荐一本科幻小说",
            "最近有什么热门书？",
            "阅读排行榜 TOP3",
            "评分最高的书有哪些？"
    );

    /**
     * 创建新会话
     */
    @PostMapping("/sessions")
    public Result<Map<String, String>> createSession(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        Long userId = extractUserId(userDetails);
        String sessionId = chatService.createSession(userId);
        return Result.ok(Map.of("sessionId", sessionId));
    }

    /**
     * 流式对话 — SSE
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody Map<String, String> body
    ) {
        Long userId = extractUserId(userDetails);
        String sessionId = body.get("sessionId");
        String message = body.get("message");

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = chatService.createSession(userId);
        }
        if (message == null || message.isBlank()) {
            SseEmitter emitter = new SseEmitter();
            emitter.completeWithError(new IllegalArgumentException("消息不能为空"));
            return emitter;
        }

        log.debug("流式对话: userId={}, sessionId={}, message={}", userId, sessionId, message);
        return chatService.streamChat(userId, sessionId, message);
    }

    /**
     * 非流式对话
     */
    @PostMapping("/chat")
    public Result<Map<String, String>> chat(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody Map<String, String> body
    ) {
        Long userId = extractUserId(userDetails);
        String sessionId = body.get("sessionId");
        String message = body.get("message");

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = chatService.createSession(userId);
        }
        if (message == null || message.isBlank()) {
            return Result.fail("消息不能为空");
        }

        String response = chatService.chat(userId, sessionId, message);
        return Result.ok(Map.of(
                "sessionId", sessionId,
                "response", response
        ));
    }

    /**
     * 获取对话历史
     */
    @GetMapping("/history")
    public Result<List<AiConversation>> getHistory(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam String sessionId
    ) {
        Long userId = extractUserId(userDetails);
        return Result.ok(chatService.getHistory(userId, sessionId));
    }

    /**
     * 获取会话列表
     */
    @GetMapping("/sessions")
    public Result<List<String>> getSessions(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        Long userId = extractUserId(userDetails);
        return Result.ok(chatService.getSessionIds(userId));
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String sessionId
    ) {
        Long userId = extractUserId(userDetails);
        chatService.deleteSession(userId, sessionId);
        return Result.ok();
    }

    /**
     * 获取热门提问 — 基于全站用户提问统计
     * GET /api/ai/hot-prompts?count=4
     */
    @GetMapping("/hot-prompts")
    public Result<List<String>> getHotPrompts(
            @RequestParam(defaultValue = "4") int count) {
        try {
            List<String> hotPrompts = conversationRepository.findHotPrompts(count);
            if (hotPrompts.isEmpty()) {
                // 数据库中暂无记录，返回默认提示
                return Result.ok(new ArrayList<>(DEFAULT_PROMPTS.subList(0, Math.min(count, DEFAULT_PROMPTS.size()))));
            }
            return Result.ok(hotPrompts);
        } catch (Exception e) {
            log.warn("获取热门提问失败: {}", e.getMessage());
            return Result.ok(new ArrayList<>(DEFAULT_PROMPTS.subList(0, Math.min(count, DEFAULT_PROMPTS.size()))));
        }
    }

    /** 从认证信息中提取用户 ID */
    private Long extractUserId(UserDetails userDetails) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            log.warn("用户未认证，无法访问 AI 接口");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }

        Object principal = authentication.getPrincipal();
        // JwtAuthenticationFilter 设置的 principal 是 Long 类型（userId）
        if (principal instanceof Long) {
            return (Long) principal;
        }
        // 兼容 UserDetails 类型
        if (principal instanceof UserDetails) {
            try {
                return Long.parseLong(((UserDetails) principal).getUsername());
            } catch (NumberFormatException e) {
                log.error("用户 ID 格式错误: {}", ((UserDetails) principal).getUsername());
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "用户信息异常");
            }
        }
        log.error("无法从认证信息中获取用户详情: {}", principal.getClass().getName());
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户信息获取失败");
    }
}
