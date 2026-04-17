package com.kbook.service;

import com.kbook.config.ChatModelFactory;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import io.qdrant.client.grpc.Collections.QuantizationConfig;
import io.qdrant.client.grpc.Collections.QuantizationConfigDiff;
import io.qdrant.client.grpc.Collections.ScalarQuantization;
import io.qdrant.client.grpc.Collections.QuantizationType;

/**
 * 向量嵌入服务 — 负责书籍向量生成、Qdrant 存储、RAG 内容检索
 * <p>
 * 两个 Qdrant Collection：
 * 1. kbook_books — 书籍元数据向量（标题+作者+标签+简介 → 1个向量/书），用于推荐召回
 * 2. kbook_content — 书籍内容分块向量（内容段落 → N个向量/书），用于 RAG 语义检索
 * <p>
 * Embedding 模型：qwen3-embedding:4b (1024维)
 */
@Slf4j
@Service
public class EmbeddingService {

    private final QdrantClient qdrantClient;
    private final BookRepository bookRepository;

    /**
     * 使用 @Lazy 打破循环依赖：AiToolService → RecommendService → EmbeddingService → AiProviderConfigService → AiToolService
     */
    private final AiProviderConfigService aiProviderConfigService;
    private final ChatModelFactory chatModelFactory;

    public EmbeddingService(QdrantClient qdrantClient,
                            BookRepository bookRepository,
                            @Lazy AiProviderConfigService aiProviderConfigService,
                            ChatModelFactory chatModelFactory) {
        this.qdrantClient = qdrantClient;
        this.bookRepository = bookRepository;
        this.aiProviderConfigService = aiProviderConfigService;
        this.chatModelFactory = chatModelFactory;
    }

    @Value("${kbook.qdrant.book-collection:kbook_books}")
    private String bookCollectionName;

    @Value("${kbook.qdrant.content-collection:kbook_content}")
    private String contentCollectionName;

    /** 是否启用量化（默认 true） */
    @Value("${kbook.qdrant.quantization.enabled:true}")
    private boolean quantizationEnabled;

    /** 量化分位数（默认 0.99，裁剪 1% 异常值） */
    @Value("${kbook.qdrant.quantization.quantile:0.99}")
    private float quantizationQuantile;

    /** 量化向量是否常驻 RAM（默认 true，加速搜索） */
    @Value("${kbook.qdrant.quantization.always-ram:true}")
    private boolean quantizationAlwaysRam;

    /**
     * 向量维度（qwen3-embedding:4b = 1024）
     */
    private static final int VECTOR_DIMENSION = 1024;

    /**
     * RAG 内容分块大小（字符数）
     */
    private static final int CHUNK_SIZE = 800;

    /**
     * RAG 内容分块重叠大小
     */
    private static final int CHUNK_OVERLAP = 200;

    private EmbeddingModel embeddingModel;
    private EmbeddingStore<TextSegment> bookEmbeddingStore;
    private EmbeddingStore<TextSegment> contentEmbeddingStore;

    /**
     * 初始化：创建 Qdrant Collection + 构建 EmbeddingModel 和 Store
     */
    @PostConstruct
    public void init() {
        // 1. 创建 Qdrant Collection（即使模型未就绪也应创建，这样后续扫描时可直接写入）
        try {
            createCollectionIfNotExists(bookCollectionName);
            createCollectionIfNotExists(contentCollectionName);
        } catch (Exception e) {
            log.error("Qdrant Collection 创建失败: {}", e.getMessage(), e);
        }

        // 2. 构建 EmbeddingStore（依赖 Collection 已存在）
        try {
            buildEmbeddingStores();
        } catch (Exception e) {
            log.error("EmbeddingStore 构建失败: {}", e.getMessage(), e);
        }

        // 3. Embedding 模型延迟初始化，避免循环依赖
        // 将在首次使用时通过 ensureEmbeddingModelInitialized() 初始化
        log.info("EmbeddingService 初始化完成 (model 延迟加载): bookCollection={}, contentCollection={}",
                bookCollectionName, contentCollectionName);
    }

    /**
     * 确保 Embedding 模型已初始化（延迟加载，避免循环依赖）
     */
    private synchronized void ensureEmbeddingModelInitialized() {
        if (embeddingModel == null) {
            try {
                initEmbeddingModel();
                log.info("Embedding 模型延迟初始化成功");
            } catch (Exception e) {
                log.error("Embedding 模型初始化失败: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 初始化 Embedding 模型
     */
    private void initEmbeddingModel() {
        var activeConfig = aiProviderConfigService.getActiveConfig();
        log.info("开始初始化 Embedding 模型: activeConfig={}, baseUrl={}, embeddingModel={}",
                activeConfig != null ? activeConfig.getConfigName() : "null",
                activeConfig != null ? activeConfig.getBaseUrl() : "default",
                chatModelFactory != null ? chatModelFactory.getClass().getSimpleName() : "null");

        if (chatModelFactory != null) {
            embeddingModel = chatModelFactory.buildEmbeddingModel(activeConfig);
        }

        // 验证模型可用性：试 embed 一段文本
        if (embeddingModel != null) {
            try {
                var testResult = embeddingModel.embed("测试");
                if (testResult != null) {
                    log.info("Embedding 模型初始化验证成功: vectorDim={}", testResult.content().vector().length);
                } else {
                    log.error("Embedding 模型初始化验证失败: embed 返回空结果");
                    embeddingModel = null;
                }
            } catch (Exception e) {
                log.error("Embedding 模型初始化验证失败（调用 Ollama 失败）: {}", e.getMessage(), e);
                embeddingModel = null;
            }
        } else {
            log.error("Embedding 模型构建返回 null: activeConfig={}", activeConfig);
        }
    }

    /**
     * 在 Qdrant 中创建 Collection（如果不存在），并配置标量量化
     */
    private void createCollectionIfNotExists(String collectionName) {
        try {
            if (qdrantClient == null) {
                log.warn("QdrantClient 未初始化，无法创建 Collection: {}", collectionName);
                return;
            }
            List<String> collectionNames = qdrantClient.listCollectionsAsync().get();
            boolean exists = collectionNames.contains(collectionName);

            if (!exists) {
                var collectionBuilder = io.qdrant.client.grpc.Collections.CreateCollection.newBuilder()
                        .setCollectionName(collectionName)
                        .setVectorsConfig(io.qdrant.client.grpc.Collections.VectorsConfig.newBuilder()
                                .setParams(io.qdrant.client.grpc.Collections.VectorParams.newBuilder()
                                        .setSize(VECTOR_DIMENSION)
                                        .setDistance(io.qdrant.client.grpc.Collections.Distance.Cosine)
                                        .build())
                                .build());

                // 标量量化配置：float32 → int8，内存占用降约 75%
                if (quantizationEnabled) {
                    ScalarQuantization scalarQuant = ScalarQuantization.newBuilder()
                            .setType(QuantizationType.Int8)
                            .setQuantile(quantizationQuantile)
                            .setAlwaysRam(quantizationAlwaysRam)
                            .build();
                    QuantizationConfig quantConfig = QuantizationConfig.newBuilder()
                            .setScalar(scalarQuant)
                            .build();
                    collectionBuilder.setQuantizationConfig(quantConfig);
                    log.info("Collection [{}] 创建时启用标量量化: type=int8, quantile={}, alwaysRam={}",
                            collectionName, quantizationQuantile, quantizationAlwaysRam);
                }

                qdrantClient.createCollectionAsync(collectionBuilder.build()).get();
                log.info("Qdrant Collection 创建成功: {} (量化={})", collectionName, quantizationEnabled);
            } else {
                log.debug("Qdrant Collection 已存在: {}", collectionName);
                // 已存在的 Collection：更新量化配置
                updateQuantizationConfig(collectionName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("创建 Qdrant Collection 被中断: {} - {}", collectionName, e.getMessage());
        } catch (ExecutionException e) {
            log.error("创建 Qdrant Collection 失败: {} - {}", collectionName, e.getCause() != null ? e.getCause().getMessage() : e.getMessage(), e);
        }
    }

    /**
     * 更新已有 Collection 的量化配置（如果启用了量化但 Collection 未配置）
     */
    private void updateQuantizationConfig(String collectionName) {
        if (!quantizationEnabled) return;
        try {
            // 检查当前量化配置
            var collectionInfo = qdrantClient.getCollectionInfoAsync(collectionName).get();
            var currentQuantConfig = collectionInfo.getConfig().getQuantizationConfig();

            if (currentQuantConfig.hasScalar()
                    && currentQuantConfig.getScalar().getType() == QuantizationType.Int8) {
                log.debug("Collection [{}] 已启用标量量化，跳过更新", collectionName);
                return;
            }

            // 更新增量配置：UpdateCollection 需要 QuantizationConfigDiff
            // QuantizationConfigDiff.scalar 字段类型就是 ScalarQuantization（无 Diff 版本）
            ScalarQuantization scalarQuant = ScalarQuantization.newBuilder()
                    .setType(QuantizationType.Int8)
                    .setQuantile(quantizationQuantile)
                    .setAlwaysRam(quantizationAlwaysRam)
                    .build();
            QuantizationConfigDiff quantConfigDiff = QuantizationConfigDiff.newBuilder()
                    .setScalar(scalarQuant)
                    .build();

            var updateBuilder = io.qdrant.client.grpc.Collections.UpdateCollection.newBuilder()
                    .setCollectionName(collectionName)
                    .setQuantizationConfig(quantConfigDiff);

            qdrantClient.updateCollectionAsync(updateBuilder.build()).get();
            log.info("Collection [{}] 已更新标量量化配置: type=int8, quantile={}, alwaysRam={}",
                    collectionName, quantizationQuantile, quantizationAlwaysRam);
        } catch (Exception e) {
            log.warn("更新 Collection [{}] 量化配置失败: {}", collectionName, e.getMessage());
        }
    }

    /**
     * 构建 EmbeddingStore 实例
     */
    private void buildEmbeddingStores() {
        bookEmbeddingStore = QdrantEmbeddingStore.builder()
                .client(qdrantClient)
                .collectionName(bookCollectionName)
                .build();

        contentEmbeddingStore = QdrantEmbeddingStore.builder()
                .client(qdrantClient)
                .collectionName(contentCollectionName)
                .build();
    }

    // ==================== 书籍元数据向量（用于推荐召回） ====================

    /**
     * 为书籍生成元数据向量并存储到 Qdrant
     * 元数据 = 标题 + 作者 + 标签 + 简介 → 1个 embedding
     */
    public void generateBookEmbedding(Long bookId) {
        try {
            ensureEmbeddingModelInitialized();
            if (embeddingModel == null || bookEmbeddingStore == null) {
                log.warn("Embedding 模型或 Store 未初始化，跳过书籍向量生成: bookId={}", bookId);
                return;
            }

            Book book = bookRepository.findById(bookId).orElse(null);
            if (book == null) return;

            // 构建元数据文本
            String metadataText = buildBookMetadataText(book);
            if (metadataText.isBlank()) {
                log.debug("书籍元数据为空，跳过向量生成: bookId={}", bookId);
                return;
            }

            // 生成 embedding
            Embedding embedding = embeddingModel.embed(metadataText).content();

            // 构建带元数据的 TextSegment
            TextSegment segment = TextSegment.from(metadataText,
                    new Metadata().put("bookId", bookId)
                            .put("title", book.getTitle() != null ? book.getTitle() : "")
                            .put("author", book.getAuthor() != null ? book.getAuthor() : "")
                            .put("rating", book.getRating() != null ? book.getRating() : 0.0)
                            .put("readCount", book.getReadCount() != null ? book.getReadCount() : 0L));

            // 存入 Qdrant（先删除该书已有的旧向量，确保幂等）
            removeBookEmbedding(bookId);
            String id = bookEmbeddingStore.add(embedding, segment);

            // 验证写入结果
            boolean exists = hasBookEmbedding(bookId);
            log.info("书籍元数据向量生成完成: bookId={}, textLen={}, storeId={}, qdrantVerified={}",
                    bookId, metadataText.length(), id, exists);
        } catch (Exception e) {
            log.error("书籍元数据向量生成失败: bookId={} - {}", bookId, e.getMessage());
        }
    }

    /**
     * 通过元数据向量搜索相似书籍（用于推荐召回）
     *
     * @param queryText      查询文本（如用户画像描述或用户兴趣关键词）
     * @param maxResults     最大返回数量
     * @param minScore       最低相似度阈值
     * @param excludeBookIds 需要排除的图书ID
     * @return 匹配结果列表
     */
    public List<EmbeddingMatch<TextSegment>> searchSimilarBooks(String queryText, int maxResults,
                                                                double minScore, List<Long> excludeBookIds) {
        ensureEmbeddingModelInitialized();
        if (embeddingModel == null || bookEmbeddingStore == null) {
            log.warn("Embedding 模型或 Store 未初始化，无法执行向量搜索");
            return List.of();
        }

        try {
            Embedding queryEmbedding = embeddingModel.embed(queryText).content();

            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults)
                    .minScore(minScore)
                    .build();

            List<EmbeddingMatch<TextSegment>> matches = bookEmbeddingStore.search(request)
                    .matches();

            // 排除已读书籍
            if (excludeBookIds != null && !excludeBookIds.isEmpty()) {
                matches = matches.stream()
                        .filter(m -> {
                            if (m.embedded() != null && m.embedded().metadata() != null) {
                                Long bookId = m.embedded().metadata().getLong("bookId");
                                return bookId != null && !excludeBookIds.contains(bookId);
                            }
                            return true;
                        })
                        .toList();
            }

            log.debug("向量搜索完成: query='{}', hits={}, afterFilter={}",
                    queryText.length() > 30 ? queryText.substring(0, 30) : queryText,
                    maxResults, matches.size());

            return matches;
        } catch (Exception e) {
            log.error("向量搜索失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== RAG 内容向量（用于语义检索） ====================

    /**
     * 为书籍生成 RAG 内容向量并存储到 Qdrant
     * 将书籍内容按 CHUNK_SIZE 分块，每块生成一个 embedding
     */
    public void generateContentEmbedding(Long bookId, String content) {
        try {
            ensureEmbeddingModelInitialized();
            if (embeddingModel == null || contentEmbeddingStore == null) {
                log.warn("Embedding 模型或 Store 未初始化，跳过内容向量生成: bookId={}", bookId);
                return;
            }

            if (content == null || content.isBlank()) {
                log.debug("书籍内容为空，跳过内容向量生成: bookId={}", bookId);
                return;
            }

            // 分块
            List<String> chunks = splitContent(content);
            log.info("书籍内容分块: bookId={}, totalChars={}, chunks={}", bookId, content.length(), chunks.size());

            // 先删除该书已有的旧内容向量，确保幂等
            removeContentEmbedding(bookId);

            // 生成 embedding 并存储（逐个处理）
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                Embedding embedding = embeddingModel.embed(chunk).content();

                TextSegment segment = TextSegment.from(chunk,
                        new Metadata().put("bookId", bookId)
                                .put("chunkIndex", i)
                                .put("totalChunks", chunks.size()));

                contentEmbeddingStore.add(embedding, segment);
            }

            log.info("书籍内容向量生成完成: bookId={}, chunks={}", bookId, chunks.size());

            // 验证写入结果
            boolean exists = hasContentEmbedding(bookId);
            log.info("书籍内容向量写入验证: bookId={}, qdrantVerified={}", bookId, exists);
        } catch (Exception e) {
            log.error("书籍内容向量生成失败: bookId={} - {}", bookId, e.getMessage());
        }
    }

    /**
     * RAG 语义检索：根据查询在书籍内容中搜索相关片段
     *
     * @param query      查询文本
     * @param maxResults 最大返回数量
     * @param bookId     可选，限定在某本书内搜索
     * @return 匹配的内容片段
     */
    public List<EmbeddingMatch<TextSegment>> searchContent(String query, int maxResults, Long bookId) {
        ensureEmbeddingModelInitialized();
        if (embeddingModel == null || contentEmbeddingStore == null) {
            return List.of();
        }

        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();

            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults)
                    .minScore(0.5)
                    .build();

            List<EmbeddingMatch<TextSegment>> matches = contentEmbeddingStore.search(request).matches();

            // 如果指定了 bookId，过滤结果
            if (bookId != null) {
                matches = matches.stream()
                        .filter(m -> m.embedded() != null && m.embedded().metadata() != null
                                && bookId.equals(m.embedded().metadata().getLong("bookId")))
                        .toList();
            }

            return matches;
        } catch (Exception e) {
            log.error("RAG 内容检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 根据用户画像搜索相关书籍内容片段（用于 AI 推理辅助）
     */
    public List<EmbeddingMatch<TextSegment>> searchContentByUserProfile(String userProfileDesc, int maxResults) {
        return searchContent(userProfileDesc, maxResults, null);
    }

    // ==================== 管理操作 ====================

    /**
     * 重建所有书籍的元数据向量（管理员操作）
     */
    public int rebuildAllBookEmbeddings() {
        ensureEmbeddingModelInitialized();
        if (embeddingModel == null || bookEmbeddingStore == null) {
            log.warn("Embedding 模型或 Store 未初始化，无法重建向量");
            return 0;
        }

        List<Book> allBooks = bookRepository.findAll();
        log.info("开始重建所有书籍元数据向量: totalBooks={}", allBooks.size());

        int count = 0;
        for (Book book : allBooks) {
            try {
                String metadataText = buildBookMetadataText(book);
                if (metadataText.isBlank()) continue;

                // 先删除该书已有的旧向量
                removeBookEmbedding(book.getId());

                Embedding embedding = embeddingModel.embed(metadataText).content();
                TextSegment segment = TextSegment.from(metadataText,
                        new Metadata().put("bookId", book.getId())
                                .put("title", book.getTitle() != null ? book.getTitle() : "")
                                .put("author", book.getAuthor() != null ? book.getAuthor() : "")
                                .put("rating", book.getRating() != null ? book.getRating() : 0.0)
                                .put("readCount", book.getReadCount() != null ? book.getReadCount() : 0L));

                bookEmbeddingStore.add(embedding, segment);
                count++;

                if (count % 10 == 0) {
                    log.info("重建进度: {}/{}", count, allBooks.size());
                }

                // 避免触发 API 限流
                Thread.sleep(100);
            } catch (Exception e) {
                log.warn("重建向量失败: bookId={} - {}", book.getId(), e.getMessage());
            }
        }

        log.info("重建完成: {}/{} 成功", count, allBooks.size());
        return count;
    }

    /**
     * 检查 Embedding 功能是否可用
     */
    public boolean isAvailable() {
        return embeddingModel != null && bookEmbeddingStore != null && contentEmbeddingStore != null;
    }

    /**
     * 检查指定书籍是否已有元数据向量（通过 Qdrant 精确查询 bookId）
     */
    public boolean hasBookEmbedding(Long bookId) {
        try {
            if (qdrantClient == null) return false;
            io.qdrant.client.grpc.Common.Filter filter = io.qdrant.client.grpc.Common.Filter.newBuilder()
                    .addMust(matchKeyword("bookId", String.valueOf(bookId)))
                    .build();
            Long count = qdrantClient.countAsync(bookCollectionName, filter, true).get();
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("检查书籍向量存在性失败: bookId={} - {}", bookId, e.getMessage());
            return false;
        }
    }

    /**
     * 检查指定书籍是否已有内容向量
     */
    public boolean hasContentEmbedding(Long bookId) {
        try {
            if (qdrantClient == null) return false;
            io.qdrant.client.grpc.Common.Filter filter = io.qdrant.client.grpc.Common.Filter.newBuilder()
                    .addMust(matchKeyword("bookId", String.valueOf(bookId)))
                    .build();
            Long count = qdrantClient.countAsync(contentCollectionName, filter, true).get();
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("检查内容向量存在性失败: bookId={} - {}", bookId, e.getMessage());
            return false;
        }
    }

    /**
     * 删除指定书籍的元数据向量（通过 bookId 精确匹配 payload）
     */
    public void removeBookEmbedding(Long bookId) {
        try {
            if (qdrantClient == null) return;
            io.qdrant.client.grpc.Common.Filter filter = io.qdrant.client.grpc.Common.Filter.newBuilder()
                    .addMust(matchKeyword("bookId", String.valueOf(bookId)))
                    .build();
            qdrantClient.deleteAsync(bookCollectionName, filter).get();
            log.debug("已删除旧书籍元数据向量: bookId={}", bookId);
        } catch (Exception e) {
            log.debug("删除旧书籍元数据向量失败（可能不存在）: bookId={} - {}", bookId, e.getMessage());
        }
    }

    /**
     * 删除指定书籍的内容向量（通过 bookId 精确匹配 payload）
     */
    public void removeContentEmbedding(Long bookId) {
        try {
            if (qdrantClient == null) return;
            io.qdrant.client.grpc.Common.Filter filter = io.qdrant.client.grpc.Common.Filter.newBuilder()
                    .addMust(matchKeyword("bookId", String.valueOf(bookId)))
                    .build();
            qdrantClient.deleteAsync(contentCollectionName, filter).get();
            log.debug("已删除旧内容向量: bookId={}", bookId);
        } catch (Exception e) {
            log.debug("删除旧内容向量失败（可能不存在）: bookId={} - {}", bookId, e.getMessage());
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 预编译正则，用于标签清理
     */
    private static final java.util.regex.Pattern TAGS_CLEAN_PATTERN = java.util.regex.Pattern.compile("[\\[\\]\"]");
    
    /**
     * 构建书籍元数据文本（用于生成 embedding）
     * 增强策略：标题 + 作者 + 标签 + 简介(1500字) + 目录 + 核心章节摘要
     * 优化版本：使用预编译正则 + StringBuilder 预分配
     */
    private String buildBookMetadataText(Book book) {
        // 预估容量：标题(50) + 作者(30) + 标签(100) + 简介(1500) + 目录(500) + 摘要(2000) ≈ 4180
        StringBuilder sb = new StringBuilder(4200);

        if (book.getTitle() != null && !book.getTitle().isBlank()) {
            sb.append("书名：").append(book.getTitle()).append('\n');
        }
        if (book.getAuthor() != null && !book.getAuthor().isBlank()) {
            sb.append("作者：").append(book.getAuthor()).append('\n');
        }
        if (book.getFormatTags() != null && !book.getFormatTags().isBlank()) {
            // 使用预编译正则，避免每次重新编译
            String tags = TAGS_CLEAN_PATTERN.matcher(book.getFormatTags())
                    .replaceAll("")
                    .replace(',', '、');
            sb.append("标签：").append(tags).append('\n');
        }
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            // 简介取前1500字（增强语义信号）
            String desc = book.getDescription();
            if (desc.length() > 1500) {
                sb.append("简介：").append(desc, 0, 1500).append('\n');
            } else {
                sb.append("简介：").append(desc).append('\n');
            }
        }
        if (book.getToc() != null && !book.getToc().isBlank()) {
            // 目录结构蕴含主题和组织方式
            sb.append("目录：\n").append(book.getToc()).append('\n');
        }
        if (book.getChapterSummary() != null && !book.getChapterSummary().isBlank()) {
            // 核心章节摘要提供深层语义
            sb.append("核心内容：\n").append(book.getChapterSummary()).append('\n');
        }

        return sb.toString().trim();
    }

    /**
     * 将书籍内容按固定大小分块（带重叠）
     * 高性能版本：预转换 char[] + 简化重叠逻辑
     */
    private List<String> splitContent(String content) {
        ArrayList<String> chunks = new ArrayList<>();
        int contentLen = content.length();
        if (contentLen == 0) return chunks;

        // 预分配容量
        int estimatedChunks = contentLen / (CHUNK_SIZE - CHUNK_OVERLAP) + 1;
        chunks.ensureCapacity(estimatedChunks);

        // 一次性转换为 char[]，避免重复 charAt 调用
        char[] chars = content.toCharArray();

        int pos = 0;
        while (pos < contentLen) {
            // 计算当前块的结束位置
            int chunkEnd = Math.min(pos + CHUNK_SIZE, contentLen);

            // 如果不是最后一块，尝试在句子边界断开
            if (chunkEnd < contentLen) {
                // 从后往前搜索句子结束符（最多回溯 CHUNK_OVERLAP 个字符）
                int searchStart = Math.max(pos, chunkEnd - CHUNK_OVERLAP);
                for (int i = chunkEnd - 1; i >= searchStart; i--) {
                    char c = chars[i];
                    if (c == '\n' || c == '。' || c == '！' || c == '？') {
                        chunkEnd = i + 1;
                        break;
                    }
                }
            }

            // 提取并添加分块
            String chunk = new String(chars, pos, chunkEnd - pos).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            // 移动到下一块的起始位置
            // 关键修复：确保每次都向前推进，避免死循环
            int nextPos = chunkEnd - CHUNK_OVERLAP;
            if (nextPos <= pos) {
                // 如果没有推进，直接跳到 chunkEnd
                pos = chunkEnd;
            } else {
                pos = nextPos;
            }
        }

        log.debug("分块完成: totalChars={}, chunks={}", contentLen, chunks.size());
        return chunks;
    }

    // ==================== 诊断方法 ====================

    /**
     * 诊断 Qdrant 和 Embedding 模型状态
     * 返回连接状态、集合信息、模型状态等，帮助排查向量数据未写入的问题
     * 优化版本：并行执行多个异步调用，减少总等待时间
     */
    public Map<String, Object> diagnose() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();

        // 1. Qdrant 连接测试
        try {
            List<String> collections = qdrantClient.listCollectionsAsync().get();
            result.put("qdrant.connected", true);
            result.put("qdrant.collections", collections);
        } catch (Exception e) {
            result.put("qdrant.connected", false);
            result.put("qdrant.error", e.getMessage());
            return result;
        }

        // 2. 并行获取两个集合的详情（减少等待时间）
        try {
            var bookInfoFuture = qdrantClient.getCollectionInfoAsync(bookCollectionName);
            var contentInfoFuture = qdrantClient.getCollectionInfoAsync(contentCollectionName);

            // 同时等待两个异步操作完成
            var bookInfo = bookInfoFuture.get();
            var contentInfo = contentInfoFuture.get();

            result.put("qdrant.bookCollection", Map.of(
                    "name", bookCollectionName,
                    "vectorCount", bookInfo.getPointsCount(),
                    "vectorSize", bookInfo.getConfig().getParams().getVectorsConfig().getParams().getSize(),
                    "status", bookInfo.getStatus().name()
            ));

            result.put("qdrant.contentCollection", Map.of(
                    "name", contentCollectionName,
                    "vectorCount", contentInfo.getPointsCount(),
                    "vectorSize", contentInfo.getConfig().getParams().getVectorsConfig().getParams().getSize(),
                    "status", contentInfo.getStatus().name()
            ));
        } catch (Exception e) {
            result.put("qdrant.collectionInfo.error", e.getMessage());
        }

        // 3. Embedding 模型状态
        result.put("embedding.modelInitialized", embeddingModel != null);
        result.put("embedding.bookStoreInitialized", bookEmbeddingStore != null);
        result.put("embedding.contentStoreInitialized", contentEmbeddingStore != null);

        // 4. 尝试初始化模型（如果还没初始化）
        if (embeddingModel == null) {
            try {
                ensureEmbeddingModelInitialized();
                result.put("embedding.modelInitAttempt", embeddingModel != null ? "SUCCESS" : "FAILED");
            } catch (Exception e) {
                result.put("embedding.modelInitAttempt", "FAILED: " + e.getMessage());
            }
        }

        // 5. 测试 embed 调用
        if (embeddingModel != null) {
            try {
                var testEmbed = embeddingModel.embed("测试Embedding调用");
                if (testEmbed != null) {
                    result.put("embedding.testCall", "SUCCESS");
                    result.put("embedding.vectorDim", testEmbed.content().vector().length);
                } else {
                    result.put("embedding.testCall", "FAILED: empty result");
                }
            } catch (Exception e) {
                result.put("embedding.testCall", "FAILED: " + e.getMessage());
            }
        }

        return result;
    }
}
