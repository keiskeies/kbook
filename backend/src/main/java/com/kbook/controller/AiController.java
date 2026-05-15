package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.entity.AiConversation;
import com.kbook.repository.AiConversationRepository;
import com.kbook.service.AiChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
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
public class AiController extends BaseController {

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
    public Result<Map<String, String>> createSession() {
        Long userId = extractUserId();
        String sessionId = chatService.createSession(userId);
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
    public Result<Map<String, String>> chat(@RequestBody Map<String, String> body) {
        Long userId = extractUserId();
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
    public Result<List<AiConversation>> getHistory(@RequestParam String sessionId) {
        Long userId = extractUserId();
        return Result.ok(chatService.getHistory(userId, sessionId));
    }

    /**
     * 获取会话列表
     */
    @GetMapping("/sessions")
    public Result<List<String>> getSessions() {
        Long userId = extractUserId();
        return Result.ok(chatService.getSessionIds(userId));
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        Long userId = extractUserId();
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

}
