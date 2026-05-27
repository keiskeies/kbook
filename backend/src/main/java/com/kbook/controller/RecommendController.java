package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.MatchScoreDetailVO;
import com.kbook.dto.RecommendedItem;
import com.kbook.entity.Book;
import com.kbook.entity.User;
import com.kbook.service.BookParserService;
import com.kbook.service.BookService;
import com.kbook.service.DimensionStatsService;
import com.kbook.service.EmbeddingService;
import com.kbook.service.RecommendCoefficientService;
import com.kbook.service.RecommendMatchCalculator;
import com.kbook.service.RecommendService;
import com.kbook.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class RecommendController {

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
    @GetMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateRecommendations(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();

        SseEmitter emitter = new SseEmitter(600_000L);

        emitter.onTimeout(() -> log.warn("推荐生成SSE超时: userId={}", userId));
        emitter.onError(e -> log.warn("推荐生成SSE错误: userId={}", userId));

        CompletableFuture.runAsync(() ->
                recommendService.generateWithProgress(userId, emitter));

        return emitter;
    }

    /**
     * 批量获取规则匹配分（轻量级，基于用户画像+书籍relevanceScores）
     * 用于在图书列表中展示"与你的匹配度"
     */
    @GetMapping("/match-scores")
    public Result<Map<Long, Double>> getMatchScores(
            Authentication authentication,
            @RequestParam List<Long> bookIds) {
        Long userId = (Long) authentication.getPrincipal();
        Map<Long, Double> scores = recommendService.batchCalculateMatchScores(userId, bookIds);
        return Result.ok(scores);
    }

    @GetMapping("/match-detail/{bookId}")
    public Result<MatchScoreDetailVO> getMatchScoreDetail(
            Authentication authentication,
            @PathVariable Long bookId) {
        Long userId = (Long) authentication.getPrincipal();
        User user = userService.getUserById(userId);
        Book book = bookService.getBookById(bookId);
        if (book == null) {
            return Result.fail("图书不存在");
        }
        MatchScoreDetailVO detail = RecommendMatchCalculator.calculateMatchScoreDetail(
                user, book, coefficientService, objectMapper, dimensionStatsService);
        return Result.ok(detail);
    }


}
