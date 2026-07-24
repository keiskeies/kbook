package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.user.BehaviorProfileVO;
import com.kbook.service.ai.behavior.BehaviorProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 用户行为画像控制器 — "AI 眼中的你"。
 * <p>
 * 行为画像（L2）完全透明：用户可查看、删除单条信号、整体重置。
 * 删除的信号进入 suppressedSignals，下次抽取时 LLM 不会再加强它。
 */
@Slf4j
@RestController
@RequestMapping("/api/user/behavior-profile")
@RequiredArgsConstructor
@Tag(name = "用户行为画像")
public class BehaviorProfileController extends BaseController {

    private final BehaviorProfileService behaviorProfileService;

    /**
     * 获取当前用户的行为画像。
     * 画像不存在时返回空 VO（各列表为空）。
     */
    @Operation(summary = "获取行为画像")
    @GetMapping
    public Result<BehaviorProfileVO> getProfile() {
        Long userId = extractUserId();
        return Result.ok(behaviorProfileService.getProfileVO(userId));
    }

    /**
     * 删除指定信号（加入 suppressedSignals，下次抽取不再加强）。
     *
     * @param field 字段名：interestTags / readingMotivations / knowledgeGaps / valueOrientation
     * @param value 要删除的值
     */
    @Operation(summary = "删除单条画像信号")
    @DeleteMapping("/signal")
    public Result<Boolean> suppressSignal(@RequestParam String field,
                                          @RequestParam String value) {
        Long userId = extractUserId();
        boolean ok = behaviorProfileService.suppressSignal(userId, field, value);
        return Result.ok(ok);
    }

    /**
     * 重置整个行为画像（保留 suppressedSignals，防止已删除信号被重新抽取）。
     */
    @Operation(summary = "重置行为画像")
    @DeleteMapping
    public Result<Boolean> resetProfile() {
        Long userId = extractUserId();
        boolean ok = behaviorProfileService.resetProfile(userId);
        return Result.ok(ok);
    }
}
