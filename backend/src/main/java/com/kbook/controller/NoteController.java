package com.kbook.controller;

import com.kbook.common.api.PageResult;
import com.kbook.common.api.Result;
import com.kbook.dto.note.NoteCreateRequest;
import com.kbook.dto.note.NoteRevisionVO;
import com.kbook.dto.note.NoteStatsVO;
import com.kbook.dto.note.NoteUpdateRequest;
import com.kbook.dto.note.NoteVO;
import com.kbook.dto.note.ReviewCaptureRequest;
import com.kbook.service.note.NoteCaptureService;
import com.kbook.service.note.NoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 笔记接口 — 全部私有，任何查询都强制限定当前用户
 */
@RestController
@RequestMapping("/api/notes")
@RequiredArgsConstructor
@Tag(name = "笔记")
public class NoteController extends BaseController {

    private final NoteService noteService;
    private final NoteCaptureService noteCaptureService;

    @Operation(summary = "新建纯想法笔记")
    @PostMapping
    public Result<NoteVO> createNote(@Valid @RequestBody NoteCreateRequest req) {
        return Result.ok(noteService.createThought(extractUserId(), req));
    }

    @Operation(summary = "笔记时间轴")
    @GetMapping("/timeline")
    public Result<PageResult<NoteVO>> getTimeline(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long bookId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(noteService.getTimeline(extractUserId(), type, bookId, from, to, page, size));
    }

    @Operation(summary = "笔记数量")
    @GetMapping("/count")
    public Result<Long> countNotes() {
        return Result.ok(noteService.countNotes(extractUserId()));
    }

    @Operation(summary = "笔记成长概览")
    @GetMapping("/stats")
    public Result<NoteStatsVO> getStats() {
        return Result.ok(noteService.getStats(extractUserId()));
    }

    @Operation(summary = "时光机：翻出一条过去的笔记")
    @GetMapping("/throwback")
    public Result<NoteVO> getThrowback() {
        return Result.ok(noteService.getThrowback(extractUserId()));
    }

    @Operation(summary = "搜索我的笔记")
    @GetMapping("/search")
    public Result<PageResult<NoteVO>> searchNotes(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(noteService.search(extractUserId(), keyword, page, size));
    }

    @Operation(summary = "语义搜索我的笔记")
    @GetMapping("/search/semantic")
    public Result<List<NoteVO>> semanticSearch(
            @RequestParam String q,
            @RequestParam(defaultValue = "20") int limit) {
        return Result.ok(noteService.semanticSearch(extractUserId(), q, limit));
    }

    @Operation(summary = "把书评收录为笔记")
    @PostMapping("/capture/review/{reviewId}")
    public Result<NoteVO> captureReview(@PathVariable Long reviewId,
                                        @Valid @RequestBody ReviewCaptureRequest req) {
        return Result.ok(noteCaptureService.captureReview(extractUserId(), reviewId, req));
    }

    @Operation(summary = "笔记详情")
    @GetMapping("/{noteId}")
    public Result<NoteVO> getNote(@PathVariable Long noteId) {
        return Result.ok(noteService.getNote(extractUserId(), noteId));
    }

    @Operation(summary = "笔记历史版本")
    @GetMapping("/{noteId}/revisions")
    public Result<List<NoteRevisionVO>> getRevisions(@PathVariable Long noteId) {
        return Result.ok(noteService.getRevisions(extractUserId(), noteId));
    }

    @Operation(summary = "编辑笔记")
    @PutMapping("/{noteId}")
    public Result<NoteVO> updateNote(@PathVariable Long noteId,
                                     @Valid @RequestBody NoteUpdateRequest req) {
        return Result.ok(noteService.updateNote(extractUserId(), noteId, req));
    }

    @Operation(summary = "删除笔记（软删）")
    @DeleteMapping("/{noteId}")
    public Result<Void> deleteNote(@PathVariable Long noteId) {
        noteService.deleteNote(extractUserId(), noteId);
        return Result.ok();
    }
}
