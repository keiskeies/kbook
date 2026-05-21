package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.entity.AiConversation;
import com.kbook.entity.AiSession;
import com.kbook.service.BookChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookChatController extends BaseController {

    private final BookChatService bookChatService;

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

    @GetMapping("/{bookId}/chat/suggestions")
    public Result<List<String>> getSuggestedQuestions(@PathVariable Long bookId) {
        return Result.ok(bookChatService.getSuggestedQuestions(bookId));
    }

    @GetMapping("/{bookId}/chat/history")
    public Result<List<AiConversation>> getBookChatHistory(
            @PathVariable Long bookId,
            @RequestParam(required = false) String sessionId
    ) {
        Long userId = extractUserId();
        return Result.ok(bookChatService.getBookChatHistory(userId, bookId, sessionId));
    }

    @GetMapping("/{bookId}/chat/sessions")
    public Result<List<AiSession>> getBookChatSessions(@PathVariable Long bookId) {
        Long userId = extractUserId();
        return Result.ok(bookChatService.getBookChatSessions(userId, bookId));
    }

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
