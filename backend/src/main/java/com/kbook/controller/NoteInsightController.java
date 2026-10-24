package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.note.NoteInsightGenerateRequest;
import com.kbook.dto.note.NoteInsightVO;
import com.kbook.service.note.NoteInsightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 笔记成长总结接口 — 全部私有，强制限定当前用户
 */
@RestController
@RequestMapping("/api/notes/insights")
@RequiredArgsConstructor
@Tag(name = "笔记成长总结")
public class NoteInsightController extends BaseController {

    private final NoteInsightService noteInsightService;

    @Operation(summary = "生成成长总结")
    @PostMapping
    public Result<NoteInsightVO> generate(@Valid @RequestBody(required = false) NoteInsightGenerateRequest req) {
        return Result.ok(noteInsightService.generate(extractUserId(), req != null ? req : new NoteInsightGenerateRequest()));
    }

    @Operation(summary = "我的成长总结列表")
    @GetMapping
    public Result<List<NoteInsightVO>> list() {
        return Result.ok(noteInsightService.list(extractUserId()));
    }

    @Operation(summary = "成长总结详情")
    @GetMapping("/{insightId}")
    public Result<NoteInsightVO> get(@PathVariable Long insightId) {
        return Result.ok(noteInsightService.get(extractUserId(), insightId));
    }
}
