package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.RecommendedItem;
import com.kbook.service.BookParserService;
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
    private final BookParserService bookParserService;

    /**
     * 获取个性化推荐
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

    /**
     * 自检 Embedding 模型一致性（管理员）
     * 对指定书籍的已有向量做重新 embed 对比，验证向量空间是否一致
     * 如果 reEmbedSearch.topScore < 0.5，说明存储向量与当前模型生成的向量不在同一空间
     *
     * @param bookId 要检查的书籍 ID（必填）
     */
    @GetMapping("/admin/self-check-embedding")
    public Result<Map<String, Object>> selfCheckEmbedding(
            @RequestParam Long bookId) {
        return Result.ok(embeddingService.selfCheckEmbedding(bookId));
    }

    /**
     * 重建指定书籍的元数据向量（管理员）
     * 绕过 QdrantEmbeddingStore 零向量 bug，使用 qdrantClient 直接写入
     *
     * @param bookId 书籍 ID
     */
    @PostMapping("/admin/rebuild-book-embedding")
    public Result<Map<String, Object>> rebuildBookEmbedding(@RequestParam Long bookId) {
        log.info("管理员触发：重建书籍元数据向量 bookId={}", bookId);
        embeddingService.generateBookEmbedding(bookId);
        boolean exists = embeddingService.hasBookEmbedding(bookId);
        return Result.ok(Map.of("bookId", bookId, "bookEmbeddingExists", exists,
                "message", exists ? "重建成功" : "重建失败，请检查日志"));
    }

    /**
     * 重建指定书籍的内容向量（管理员，用于 RAG 语义检索）
     * 绕过 QdrantEmbeddingStore 零向量 bug，使用 qdrantClient 直接写入
     * 会自动从书籍文件中提取内容
     *
     * @param bookId 书籍 ID
     */
    @PostMapping("/admin/rebuild-content-embedding")
    public Result<Map<String, Object>> rebuildContentEmbedding(@RequestParam Long bookId) {
        log.info("管理员触发：重建书籍内容向量 bookId={}", bookId);
        bookParserService.generateContentEmbedding(bookId);
        long count = embeddingService.getContentEmbeddingCount(bookId);
        return Result.ok(Map.of("bookId", bookId, "contentVectorCount", count,
                "message", count > 0 ? "重建成功" : "重建失败，请检查日志"));
    }
}
