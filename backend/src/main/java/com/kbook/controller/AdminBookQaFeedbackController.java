package com.kbook.controller;

import com.kbook.common.api.PageResult;
import com.kbook.common.api.Result;
import com.kbook.dto.bookqa.BookQaFeedbackDTO;
import com.kbook.service.ai.AnswerFeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 图书问答反馈的标注队列与看板（方案 P2-5）。
 * <p>
 * 差评进入 {@code QUEUED} 后在这里人工判定：确实影响回答质量的标注为 {@code ANNOTATED}
 * 并补进 {@code docs/eval/golden-set.real.jsonl}，误报标为 {@code DISMISSED}。
 */
@RestController
@RequestMapping("/api/admin/book-qa/feedback")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "问答反馈标注")
public class AdminBookQaFeedbackController {

    private final AnswerFeedbackService answerFeedbackService;

    @Operation(summary = "待标注队列")
    @GetMapping("/queue")
    public Result<PageResult<BookQaFeedbackDTO.QueueItem>> queue(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return Result.ok(answerFeedbackService.queue(status, page, size));
    }

    @Operation(summary = "反馈看板汇总")
    @GetMapping("/stats")
    public Result<BookQaFeedbackDTO.Stats> stats() {
        return Result.ok(answerFeedbackService.stats());
    }

    @Operation(summary = "提交标注结论")
    @PostMapping("/{id}/review")
    public Result<Void> review(@PathVariable Long id,
                               @RequestBody BookQaFeedbackDTO.ReviewRequest body) {
        try {
            answerFeedbackService.review(id, body);
            return Result.ok();
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    @Operation(summary = "按书统计好评差评")
    @GetMapping("/by-book/{bookId}")
    public Result<?> countByBook(@PathVariable Long bookId) {
        return Result.ok(answerFeedbackService.countByRatingForBook(bookId));
    }
}
