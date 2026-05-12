package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.service.EmbeddingService;
import com.kbook.service.RecommendService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    /**
     * 获取个性化推荐
     */
    @GetMapping
    public Result<List<RecommendService.RecommendedItem>> getRecommendations(
            Authentication authentication,
            @RequestParam(defaultValue = "10") int count) {
        Long userId = (Long) authentication.getPrincipal();
        List<RecommendService.RecommendedItem> items =
                recommendService.getPersonalizedRecommendations(userId, count);
        return Result.ok(items);
    }

    /**
     * 清除推荐缓存（用户更新画像后调用）
     */
    @DeleteMapping("/cache")
    public Result<Void> clearCache(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        recommendService.clearUserCache(userId);
        return Result.ok(null);
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

    // ==================== 管理员操作 ====================

    /**
     * 重建所有书籍的元数据向量（管理员）
     */
    @PostMapping("/admin/rebuild-embeddings")
    public Result<Map<String, Object>> rebuildEmbeddings() {
        log.info("管理员触发：重建所有书籍元数据向量");
        int count = embeddingService.rebuildAllBookEmbeddings();
        return Result.ok(Map.of("processed", count, "message", "重建完成"));
    }

    /**
     * 诊断 Qdrant 和 Embedding 模型状态（管理员）
     * 用于排查向量数据未写入的问题
     */
    @GetMapping("/admin/diagnose-embedding")
    public Result<Map<String, Object>> diagnoseEmbedding() {
        return Result.ok(embeddingService.diagnose());
    }
}
