package com.kbook.service.embedding;

import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 向量层一致性校验器 — 启动后异步扫描数据库，定位需要重建内容向量的书籍
 * <p>
 * 触发条件（满足任一即标记需要重建）：
 * 1. Book.contentEmbedded=true 但 contentEmbeddingModel 为 null（历史数据，迁移前生成）
 * 2. Book.contentEmbeddingModel 与当前 EmbeddingService 配置的模型标识不一致
 * 3. Book.contentEmbeddingDim 与当前模型维度不一致（同名模型维度变更）
 * <p>
 * 仅记录警告日志，不自动重建（重建成本高，需管理员确认）。
 * 通过 AdminBookController GET /api/admin/books/embeddings/consistency 端点查看列表。
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class EmbeddingConsistencyChecker implements ApplicationRunner {

    private final EmbeddingService embeddingService;
    private final BookRepository bookRepository;

    @Override
    public void run(ApplicationArguments args) {
        // 异步执行避免阻塞启动；EmbeddingService 延迟初始化模型，启动时模型未必就绪
        CompletableFuture.runAsync(this::performCheck);
    }

    /**
     * 执行一致性校验：扫描数据库里 contentEmbedded=true 的书籍，
     * 与当前 EmbeddingService 配置对比，记录需要重建的书籍
     */
    private void performCheck() {
        try {
            // 等待 EmbeddingService 模型延迟初始化完成（首次 embed 测试）
            String currentModel = embeddingService.getCurrentEmbeddingModelName();
            Integer currentDim = embeddingService.getCurrentEmbeddingDim();

            if (currentModel == null) {
                log.warn("[向量一致性] Embedding 模型未初始化（可能未配置或服务不可用），跳过启动校验");
                return;
            }

            log.info("[向量一致性] 启动校验开始: currentModel={}, currentDim={}", currentModel, currentDim);

            List<Book> needingRebuild = bookRepository.findBooksNeedingContentRebuild(currentModel);

            // 进一步过滤：dim 不匹配的也算需要重建
            List<Book> dimMismatch = needingRebuild.stream()
                    .filter(b -> b.getContentEmbeddingModel() != null
                            && b.getContentEmbeddingModel().equals(currentModel)
                            && b.getContentEmbeddingDim() != null
                            && !b.getContentEmbeddingDim().equals(currentDim))
                    .toList();

            // 不匹配 model 的（含历史 null）
            List<Book> modelMismatch = needingRebuild.stream()
                    .filter(b -> !dimMismatch.contains(b))
                    .toList();

            long totalEmbedded = bookRepository.countByContentEmbeddedTrue();

            if (needingRebuild.isEmpty()) {
                log.info("[向量一致性] 校验通过: 已向量化书籍 {} 本，全部使用当前模型 {}", totalEmbedded, currentModel);
                return;
            }

            log.warn("[向量一致性] 发现 {} / {} 本书籍需要重建内容向量:", needingRebuild.size(), totalEmbedded);
            if (!modelMismatch.isEmpty()) {
                log.warn("[向量一致性] - 模型标识不匹配: {} 本 (含历史 null 数据)", modelMismatch.size());
                modelMismatch.stream().limit(10).forEach(b ->
                        log.warn("[向量一致性]   * bookId={}, title={}, storedModel={}",
                                b.getId(), b.getTitle(), b.getContentEmbeddingModel()));
                if (modelMismatch.size() > 10) {
                    log.warn("[向量一致性]   ... 及其余 {} 本（仅显示前 10 本）", modelMismatch.size() - 10);
                }
            }
            if (!dimMismatch.isEmpty()) {
                log.warn("[向量一致性] - 维度不匹配: {} 本 (currentDim={})", dimMismatch.size(), currentDim);
                dimMismatch.stream().limit(10).forEach(b ->
                        log.warn("[向量一致性]   * bookId={}, title={}, storedDim={}",
                                b.getId(), b.getTitle(), b.getContentEmbeddingDim()));
            }

            log.warn("[向量一致性] 校验完成。可通过管理后台 GET /api/admin/books/embeddings/consistency 查看完整列表，"
                    + "并调用 POST /api/admin/books/vector/rebuild-content 触发重建");
        } catch (Exception e) {
            log.warn("[向量一致性] 启动校验失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 查询当前需要重建内容向量的书籍列表（供 AdminBookController 调用）
     */
    public List<Book> findBooksNeedingRebuild() {
        String currentModel = embeddingService.getCurrentEmbeddingModelName();
        if (currentModel == null) {
            return List.of();
        }
        return bookRepository.findBooksNeedingContentRebuild(currentModel);
    }

    /**
     * 当前配置的 embedding 模型标识（供 AdminBookController 返回）
     */
    public String currentModel() {
        return embeddingService.getCurrentEmbeddingModelName();
    }

    /**
     * 当前配置的 embedding 维度（供 AdminBookController 返回）
     */
    public Integer currentDim() {
        return embeddingService.getCurrentEmbeddingDim();
    }
}
