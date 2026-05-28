package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.entity.AiConversation;
import com.kbook.entity.AiSession;
import com.kbook.service.BookChatService;
import com.kbook.service.ReadingProgressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 图书 AI 对话控制器 — 针对单本书的 AI 问答接口
 * <p>
 * 提供基于图书内容的 RAG 对话、推荐提问、历史记录、追问生成等功能。
 * 继承 BaseController 以复用 extractUserId() 方法。
 */
@Slf4j
@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookChatController extends BaseController {

    /** 图书 AI 对话服务 */
    private final BookChatService bookChatService;

    /** 阅读进度服务 — 用于在提问时记录最近阅读 */
    private final ReadingProgressService readingProgressService;

    /**
     * 流式图书 AI 对话（SSE）
     * @param bookId 图书ID
     * @param body 包含 message 和 sessionId 的请求体
     * @return SSE 事件流
     */
    @PostMapping(value = "/{bookId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamBookChat(
            @PathVariable Long bookId,
            @RequestBody Map<String, String> body
    ) {
        Long userId = extractUserId();
        String message = body.get("message");
        String sessionId = body.get("sessionId");
        boolean regenerate = Boolean.parseBoolean(body.get("regenerate"));

        if (message == null || message.isBlank()) {
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send(SseEmitter.event().name("error").data("问题不能为空"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        // 将图书加入最近阅读（已有记录则更新时间，无记录则设进度为 0%）
        readingProgressService.reportProgress(userId, bookId, 0.0, "chat");

        return bookChatService.streamBookChat(userId, bookId, message, sessionId, regenerate);
    }

    /**
     * 获取图书推荐提问
     * @param bookId 图书ID
     * @return 推荐提问列表
     */
    @GetMapping("/{bookId}/chat/suggestions")
    public Result<List<String>> getSuggestedQuestions(@PathVariable Long bookId) {
        return Result.ok(bookChatService.getSuggestedQuestions(bookId));
    }

    /**
     * 获取图书对话历史
     * @param bookId 图书ID
     * @param sessionId 可选的会话ID，用于筛选特定会话
     * @return 对话记录列表
     */
    @GetMapping("/{bookId}/chat/history")
    public Result<List<AiConversation>> getBookChatHistory(
            @PathVariable Long bookId,
            @RequestParam(required = false) String sessionId
    ) {
        Long userId = extractUserId();
        return Result.ok(bookChatService.getBookChatHistory(userId, bookId, sessionId));
    }

    /**
     * 获取图书对话会话列表
     * @param bookId 图书ID
     * @return 会话列表
     */
    @GetMapping("/{bookId}/chat/sessions")
    public Result<List<AiSession>> getBookChatSessions(@PathVariable Long bookId) {
        Long userId = extractUserId();
        return Result.ok(bookChatService.getBookChatSessions(userId, bookId));
    }

    /**
     * 生成追问问题
     * <p>
     * 根据用户的问题和 AI 的回答，生成后续推荐提问
     * @param bookId 图书ID
     * @param body 包含 question、answer 和 sessionId 的请求体
     * @return 追问问题列表
     */
    @PostMapping("/{bookId}/chat/follow-up")
    public Result<List<String>> generateFollowUpQuestions(
            @PathVariable Long bookId,
            @RequestBody Map<String, String> body
    ) {
        String question = body.get("question");
        String answer = body.get("answer");
        String sessionId = body.get("sessionId");
        List<String> followUps = bookChatService.generateFollowUpQuestions(bookId, question, answer);
        if (!followUps.isEmpty() && sessionId != null && !sessionId.isBlank()) {
            Long userId = extractUserId();
            bookChatService.saveFollowUpQuestions(userId, sessionId, bookId, followUps);
        }
        return Result.ok(followUps);
    }

}
