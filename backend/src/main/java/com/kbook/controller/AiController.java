package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.entity.AiConversation;
import com.kbook.entity.AiSession;
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

@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController extends BaseController {

    private final AiChatService chatService;
    private final AiConversationRepository conversationRepository;

    private static final List<String> DEFAULT_PROMPTS = List.of(
            "推荐几本关于成长与情感的高分书籍",
            "有哪些值得读的历史类好书？",
            "职场新人适合读什么书来提升自己？",
            "最近有什么精彩的悬疑或科幻小说推荐吗？"
    );

    @PostMapping("/sessions")
    public Result<Map<String, String>> createSession() {
        Long userId = extractUserId();
        String sessionId = chatService.createSession(userId);
        return Result.ok(Map.of("sessionId", sessionId));
    }

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

    @GetMapping("/history")
    public Result<List<AiConversation>> getHistory(@RequestParam String sessionId) {
        Long userId = extractUserId();
        return Result.ok(chatService.getHistory(userId, sessionId));
    }

    @GetMapping("/sessions")
    public Result<List<AiSession>> getSessions() {
        Long userId = extractUserId();
        return Result.ok(chatService.getSessions(userId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        Long userId = extractUserId();
        chatService.deleteSession(userId, sessionId);
        return Result.ok();
    }

    @GetMapping("/hot-prompts")
    public Result<List<String>> getHotPrompts(
            @RequestParam(defaultValue = "4") int count) {
        try {
            List<String> hotPrompts = conversationRepository.findHotPrompts(count);
            if (hotPrompts.isEmpty()) {
                return Result.ok(new ArrayList<>(DEFAULT_PROMPTS.subList(0, Math.min(count, DEFAULT_PROMPTS.size()))));
            }
            return Result.ok(hotPrompts);
        } catch (Exception e) {
            log.warn("获取热门提问失败: {}", e.getMessage());
            return Result.ok(new ArrayList<>(DEFAULT_PROMPTS.subList(0, Math.min(count, DEFAULT_PROMPTS.size()))));
        }
    }

}
