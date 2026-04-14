package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.entity.AiConversation;
import com.kbook.service.BookChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * 图书问答控制器 — 基于书籍 RAG 内容的深度问答
 * <p>
 * 用户在图书详情页点击「AI 伴读」按钮后进入问答界面，
 * AI 基于该书的全量 RAG 向量检索 + LLM 生成精准回答。
 */
@Slf4j
@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookChatController {

    private final BookChatService bookChatService;

    /**
     * 流式图书问答 — SSE
     * POST /api/books/{bookId}/chat/stream
     * Body: { "message": "这本书的主旨是什么？", "sessionId": "可选" }
     */
    @PostMapping(value = "/{bookId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamBookChat(
            @PathVariable Long bookId,
            @RequestBody Map<String, String> body
    ) {
        Long userId = extractUserId();
        String message = body.get("message");
        String sessionId = body.get("sessionId");

        if (message == null || message.isBlank()) {
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send(SseEmitter.event().name("error").data("问题不能为空"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        return bookChatService.streamBookChat(userId, bookId, message, sessionId);
    }

    /**
     * 获取图书推荐问题
     * GET /api/books/{bookId}/chat/suggestions
     */
    @GetMapping("/{bookId}/chat/suggestions")
    public Result<List<String>> getSuggestedQuestions(@PathVariable Long bookId) {
        return Result.ok(bookChatService.getSuggestedQuestions(bookId));
    }

    /**
     * 获取图书问答历史
     * GET /api/books/{bookId}/chat/history?sessionId=xxx
     */
    @GetMapping("/{bookId}/chat/history")
    public Result<List<AiConversation>> getBookChatHistory(
            @PathVariable Long bookId,
            @RequestParam(required = false) String sessionId
    ) {
        Long userId = extractUserId();
        return Result.ok(bookChatService.getBookChatHistory(userId, bookId, sessionId));
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
