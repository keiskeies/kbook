package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.common.util.CommonUtils;
import com.kbook.dto.bookqa.BookQaFeedbackDTO;
import com.kbook.entity.AiConversation;
import com.kbook.entity.AiRetrievalSnapshot;
import com.kbook.entity.AiSession;
import com.kbook.service.ai.AnswerFeedbackService;
import com.kbook.service.ai.BookChatService;
import com.kbook.service.progress.ReadingProgressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "图书问答")
public class BookChatController extends BaseController {

    /** 图书 AI 对话服务 */
    private final BookChatService bookChatService;

    /** 阅读进度服务 — 用于在提问时记录最近阅读 */
    private final ReadingProgressService readingProgressService;

    /** 回答反馈闭环（方案 P2-5） */
    private final AnswerFeedbackService answerFeedbackService;

    /**
     * 流式图书 AI 对话（SSE）
     * @param bookId 图书ID
     * @param body 包含 message 和 sessionId 的请求体
     * @return SSE 事件流
     */
    @Operation(summary = "图书问答")
    @PostMapping(value = "/{bookId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamBookChat(
            @PathVariable Long bookId,
            @RequestBody Map<String, Object> body
    ) {
        Long userId = extractUserId();
        String message = (String) body.get("message");
        String sessionId = (String) body.get("sessionId");
        // manual=true 表示用户在输入框敲字发送（强信号）；未传或 false 表示点击追问（弱信号）
        boolean manual = Boolean.parseBoolean(String.valueOf(body.getOrDefault("manual", "true")));
        // regenerate=true 表示对最后一条回答重新生成：删除旧回答、复用原用户消息
        boolean regenerate = Boolean.parseBoolean(String.valueOf(body.getOrDefault("regenerate", "false")));
        // 客户端能力声明。旧版前端把不认识的命名事件当成回答正文渲染，
        // 所以只有明确声明能处理 conversation_id 的前端才下发这条元数据事件。
        Object clientCaps = body.get("clientCaps");
        boolean emitConversationId = clientCaps instanceof java.util.Collection<?> caps
                && caps.contains("conversation_id");

        if (message == null || message.isBlank()) {
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send(SseEmitter.event().name("error").data("问题不能为空"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        // 提示词注入检测（P1 #17）：截断超长输入 + 拦截明显注入模式
        String safeMessage = CommonUtils.sanitizeAiInput(message);
        if (safeMessage == null) {
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send(SseEmitter.event().name("error").data("该问题包含不允许的内容，请正常提问"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        // 将图书加入最近阅读（已有记录则更新时间，无记录则设进度为 0%）
        readingProgressService.reportProgress(userId, bookId, 0.0, "chat");

        return withSseLimit(userId, () -> bookChatService.streamBookChat(
                userId, bookId, safeMessage, sessionId, manual, regenerate, emitConversationId));
    }

    /**
     * 获取图书推荐提问
     * @param bookId 图书ID
     * @return 推荐提问列表
     */
    @Operation(summary = "获取推荐问题")
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
    @Operation(summary = "获取图书对话历史")
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
    @Operation(summary = "获取图书对话会话")
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
    @Operation(summary = "生成追问问题")
    @PostMapping("/{bookId}/chat/follow-up")
    public Result<List<String>> generateFollowUpQuestions(
            @PathVariable Long bookId,
            @RequestBody Map<String, String> body
    ) {
        Long userId = extractUserId();
        String question = body.get("question");
        String answer = body.get("answer");
        String sessionId = body.get("sessionId");
        List<String> followUps = bookChatService.generateFollowUpQuestions(userId, bookId, question, answer);
        if (!followUps.isEmpty() && sessionId != null && !sessionId.isBlank()) {
            bookChatService.saveFollowUpQuestions(userId, sessionId, bookId, followUps);
        }
        return Result.ok(followUps);
    }

    /**
     * 查询某条回答实际使用的检索依据（归属校验）。
     */
    @Operation(summary = "查询回答依据")
    @GetMapping("/{bookId}/chat/{conversationId}/evidence")
    public Result<List<AiRetrievalSnapshot>> getRetrievalEvidence(
            @PathVariable Long bookId,
            @PathVariable Long conversationId
    ) {
        Long userId = extractUserId();
        return Result.ok(bookChatService.getRetrievalEvidence(userId, bookId, conversationId));
    }

    /**
     * 提交 / 修改某条回答的「有用 / 没用」反馈（方案 P2-5）。
     * 差评会自动进入待标注队列。
     */
    @Operation(summary = "提交回答反馈")
    @PostMapping("/{bookId}/chat/{conversationId}/feedback")
    public Result<BookQaFeedbackDTO.VO> submitFeedback(
            @PathVariable Long bookId,
            @PathVariable Long conversationId,
            @RequestBody BookQaFeedbackDTO.SubmitRequest body
    ) {
        Long userId = extractUserId();
        try {
            return Result.ok(answerFeedbackService.submit(userId, bookId, conversationId, body));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    /** 查询当前用户在该回答上的反馈；未反馈时 data 为 null。 */
    @Operation(summary = "查询回答反馈")
    @GetMapping("/{bookId}/chat/{conversationId}/feedback")
    public Result<BookQaFeedbackDTO.VO> getFeedback(
            @PathVariable Long bookId,
            @PathVariable Long conversationId
    ) {
        return Result.ok(answerFeedbackService.get(extractUserId(), conversationId));
    }

    /** 打开历史会话时批量取反馈，key = conversationId 字符串。 */
    @Operation(summary = "批量查询回答反馈")
    @PostMapping("/{bookId}/chat/feedback-batch")
    public Result<java.util.Map<Long, BookQaFeedbackDTO.VO>> batchFeedback(
            @PathVariable Long bookId,
            @RequestBody java.util.Map<String, Object> body
    ) {
        Object raw = body == null ? null : body.get("conversationIds");
        List<Long> ids = new java.util.ArrayList<>();
        if (raw instanceof java.util.Collection<?> c) {
            for (Object o : c) {
                try { ids.add(Long.valueOf(String.valueOf(o))); } catch (NumberFormatException ignored) { }
            }
        }
        return Result.ok(answerFeedbackService.batch(extractUserId(), ids));
    }

    /**
     * 导出图书问答对话记录
     * <p>
     * 验证用户权限和对话归属，返回格式化的对话文本
     * @param bookId 图书ID
     * @param sessionId 会话ID
     * @return 格式化的对话文本
     */
    @Operation(summary = "导出图书问答对话")
    @GetMapping("/{bookId}/chat/export")
    public Result<Map<String, String>> exportBookChatHistory(
            @PathVariable Long bookId,
            @RequestParam String sessionId
    ) {
        Long userId = extractUserId();
        return Result.ok(bookChatService.exportBookChatHistory(userId, bookId, sessionId));
    }

}
