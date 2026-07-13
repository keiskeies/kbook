package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.book.BookProjection;
import com.kbook.dto.recommend.MatchScoreDetailVO;
import com.kbook.dto.recommend.RecommendedItem;
import com.kbook.entity.User;
import com.kbook.service.book.BookParserService;
import com.kbook.service.book.BookService;
import com.kbook.service.tools.DimensionStatsService;
import com.kbook.service.embedding.EmbeddingService;
import com.kbook.service.recommend.RecommendCoefficientService;
import com.kbook.service.recommend.RecommendMatchCalculator;
import com.kbook.service.recommend.RecommendService;
import com.kbook.service.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 推荐控制器 — 个性化推荐 API + 管理端向量重建
 */
@Slf4j
@RestController
@RequestMapping("/api/recommend")
@RequiredArgsConstructor
@Tag(name = "推荐")
public class RecommendController extends BaseController {

    private final RecommendService recommendService;
    private final EmbeddingService embeddingService;
    private final BookParserService bookParserService;
    private final BookService bookService;
    private final UserService userService;
    private final RecommendCoefficientService coefficientService;
    private final DimensionStatsService dimensionStatsService;
    private final ObjectMapper objectMapper;

    /**
     * 获取个性化推荐（从 Redis Sorted Set 取 top N）
     */
    @Operation(summary = "获取推荐")
    @GetMapping
    public Result<List<RecommendedItem>> getRecommendations(
            Authentication authentication,
            @RequestParam(defaultValue = "10") int count) {
        Long userId = (Long) authentication.getPrincipal();
        List<RecommendedItem> items =
                recommendService.getPersonalizedRecommendations(userId, count);
        return Result.ok(items);
    }

    /**
     * 分页查询推荐结果（从 Redis Sorted Set）
     */
    @Operation(summary = "分页获取推荐")
    @GetMapping("/page")
    public Result<Map<String, Object>> getRecommendationsPage(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(recommendService.getRecommendationsPage(userId, page, size));
    }

    /**
     * 清除推荐缓存（用户更新画像后调用）
     */
    @Operation(summary = "清除推荐缓存")
    @DeleteMapping("/cache")
    public Result<Void> clearCache(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        recommendService.clearUserCache(userId);
        recommendService.asyncRecompute(userId);
        return Result.ok(null);
    }

    /**
     * SSE 流式生成推荐（带进度报告）
     * 清除缓存后重新计算，通过 SSE 实时推送进度
     */
    @Operation(summary = "流式生成推荐")
    @GetMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateRecommendations(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();

        return withSseLimit(userId, () -> {
            SseEmitter emitter = new SseEmitter(600_000L);

            emitter.onTimeout(() -> log.warn("推荐生成SSE超时: userId={}", userId));
            emitter.onError(e -> log.warn("推荐生成SSE错误: userId={}", userId));

            CompletableFuture.runAsync(() ->
                    recommendService.generateWithProgress(userId, emitter));

            return emitter;
        });
    }

    /**
     * 批量获取规则匹配分（轻量级，基于用户画像+书籍relevanceScores）
     * 用于在图书列表中展示"与你的匹配度"
     */
//    @Operation(summary = "批量获取匹配分")
    @GetMapping("/match-scores")
    public Result<Map<Long, Double>> getMatchScores(
            Authentication authentication,
            @RequestParam List<Long> bookIds) {
        Long userId = (Long) authentication.getPrincipal();
        Map<Long, Double> scores = recommendService.batchCalculateMatchScores(userId, bookIds);
        return Result.ok(scores);
    }

    @Operation(summary = "获取匹配详情")
    @GetMapping("/match-detail/{bookId}")
    public Result<MatchScoreDetailVO> getMatchScoreDetail(
            Authentication authentication,
            @PathVariable Long bookId) {
        Long userId = (Long) authentication.getPrincipal();
        User user = userService.getUserById(userId);
        BookProjection bp = bookService.getBookProjectionById(bookId);
        if (bp == null) {
            return Result.fail("图书不存在");
        }
        MatchScoreDetailVO detail = RecommendMatchCalculator.calculateMatchScoreDetail(
                user, bp, coefficientService, objectMapper, dimensionStatsService);
        double fullScore = recommendService.computeFullScore(user, bp, userId);
        detail.setOverallScore(fullScore);
        return Result.ok(detail);
    }


}
