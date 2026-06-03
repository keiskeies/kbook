package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.config.properties.AiProviderProperties;
import com.kbook.entity.AiConversation;
import com.kbook.entity.AiSession;
import com.kbook.repository.AiConversationRepository;
import com.kbook.service.AiChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 对话控制器 — 通用 AI 聊天接口
 * <p>
 * 提供会话管理、流式/非流式对话、历史记录查询、热门提问推荐等功能。
 * 继承 BaseController 以复用 extractUserId() 方法。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "AI助手")
public class AiController extends BaseController {

    /** AI 对话服务 */
    private final AiChatService chatService;

    /** AI 对话记录仓库 */
    private final AiConversationRepository conversationRepository;

    /** AI 供应商配置属性（预设列表） */
    private final AiProviderProperties aiProviderProperties;

    /** 默认热门提问（当数据库无数据时的兜底） */
    private static final List<String> DEFAULT_PROMPTS = List.of(
            "推荐几本关于成长与情感的高分书籍",
            "有哪些值得读的历史类好书？",
            "职场新人适合读什么书来提升自己？",
            "最近有什么精彩的悬疑或科幻小说推荐吗？"
    );

    /**
     * 创建新的 AI 对话会话
     * @return 包含 sessionId 的结果
     */
    @Operation(summary = "创建对话会话")
    @PostMapping("/sessions")
    public Result<Map<String, String>> createSession() {
        Long userId = extractUserId();
        String sessionId = chatService.createSession(userId);
        return Result.ok(Map.of("sessionId", sessionId));
    }

    /**
     * 流式 AI 对话（SSE）
     * @param body 包含 sessionId 和 message 的请求体
     * @return SSE 事件流
     */
    @Operation(summary = "AI流式对话")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody Map<String, String> body) {
        Long userId = extractUserId();
        String sessionId = body.get("sessionId");
        String message = body.get("message");

        // 若未传 sessionId，自动创建新会话
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
     * 获取指定会话的对话历史
     * @param sessionId 会话ID
     * @return 对话记录列表
     */
    @Operation(summary = "获取对话历史")
    @GetMapping("/history")
    public Result<List<AiConversation>> getHistory(@RequestParam String sessionId) {
        Long userId = extractUserId();
        return Result.ok(chatService.getHistory(userId, sessionId));
    }

    /**
     * 获取当前用户的所有会话列表
     * @return 会话列表
     */
    @Operation(summary = "获取会话列表")
    @GetMapping("/sessions")
    public Result<List<AiSession>> getSessions() {
        Long userId = extractUserId();
        return Result.ok(chatService.getSessions(userId));
    }

    /**
     * 删除指定会话及其对话历史
     * @param sessionId 会话ID
     */
    @Operation(summary = "删除对话会话")
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        Long userId = extractUserId();
        chatService.deleteSession(userId, sessionId);
        return Result.ok();
    }

    /**
     * 获取热门提问推荐
     * <p>
     * 优先从数据库统计高频提问，若为空则返回默认预设列表
     * @param count 返回数量，默认4
     * @return 热门提问列表
     */
    @Operation(summary = "获取热门提问")
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

    /**
     * 获取 AI 供应商预设列表
     * @return 供应商预设配置列表
     */
    @Operation(summary = "获取AI供应商预设")
    @GetMapping("/providers/presets")
    public Result<List<AiProviderProperties.ProviderPreset>> getProviderPresets() {
        return Result.ok(aiProviderProperties.getProviders());
    }

}
