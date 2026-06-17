package com.kbook.service.embedding;

import com.kbook.config.ChatModelFactory;
import com.kbook.config.properties.QdrantProperties;
import com.kbook.entity.Book;
import com.kbook.service.book.BookService;
import org.springframework.context.annotation.Lazy;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.QuantizationConfig;
import io.qdrant.client.grpc.Collections.QuantizationConfigDiff;
import io.qdrant.client.grpc.Collections.QuantizationType;
import io.qdrant.client.grpc.Collections.ScalarQuantization;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.ConditionFactory.match;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

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
    private final ChatModelFactory chatModelFactory;
    private final QdrantProperties qdrantProps;
    private final BookService bookService;
    private final RagHitStatisticsService ragHitStatisticsService;

    public EmbeddingService(QdrantClient qdrantClient,
                            ChatModelFactory chatModelFactory,
                            QdrantProperties qdrantProps,
                            @Lazy BookService bookService,
                            @Lazy RagHitStatisticsService ragHitStatisticsService) {
        this.qdrantClient = qdrantClient;
        this.chatModelFactory = chatModelFactory;
        this.qdrantProps = qdrantProps;
        this.bookService = bookService;
        this.ragHitStatisticsService = ragHitStatisticsService;
    }

    /**
     * RAG 内容分块大小（字符数） — 委托给 QdrantProperties
     */

    private static final String PAYLOAD_TEXT_KEY = "text_segment";

    /**
     * 内容向量批量 embed 的批次大小（避免单次请求过大导致超时）
     */
    private static final int EMBED_BATCH_SIZE = 20;

    private EmbeddingModel embeddingModel;
    private EmbeddingStore<TextSegment> bookEmbeddingStore;
    private EmbeddingStore<TextSegment> contentEmbeddingStore;
    /**
     * 当前 embedding 模型标识（用于写入和校验向量一致性）
     */
    private String currentEmbeddingModelName;

    /**
     * 初始化：创建 Qdrant Collection + 构建 EmbeddingModel 和 Store
     */
    @PostConstruct
    public void init() {
        // 1. 创建 Qdrant Collection（即使模型未就绪也应创建，这样后续扫描时可直接写入）
        try {
            createCollectionIfNotExists(qdrantProps.getBookCollection(), false);
            createCollectionIfNotExists(qdrantProps.getContentCollection(), true);
        } catch (Exception e) {
            log.error("Qdrant Collection 创建失败: {}", e.getMessage(), e);
        }

        // 2. 创建 payload 索引（加速 bookId 过滤查询，170万+数据无索引会导致全量扫描）
        try {
            createPayloadIndexIfNeeded(qdrantProps.getBookCollection());
            createPayloadIndexIfNeeded(qdrantProps.getContentCollection());
        } catch (Exception e) {
            log.error("Payload 索引创建失败: {}", e.getMessage(), e);
        }

        // 3. 构建 EmbeddingStore（依赖 Collection 已存在）
        try {
            buildEmbeddingStores();
        } catch (Exception e) {
            log.error("EmbeddingStore 构建失败: {}", e.getMessage(), e);
        }

        // 4. Embedding 模型延迟初始化，避免循环依赖
        // 将在首次使用时通过 ensureEmbeddingModelInitialized() 初始化
        log.info("EmbeddingService 初始化完成 (model 延迟加载): bookCollection={}, contentCollection={}",
                qdrantProps.getBookCollection(), qdrantProps.getContentCollection());
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
        log.info("开始初始化 Embedding 模型: baseUrl={}, model={}",
                chatModelFactory.getEmbeddingBaseUrl(),
                chatModelFactory.getEmbeddingModelName());

        embeddingModel = chatModelFactory.buildDefaultEmbeddingModel();

        // 记录当前模型标识（baseUrl + embeddingModelName），用于向量一致性校验
        String embeddingBaseUrl = chatModelFactory.getEmbeddingBaseUrl();
        String embeddingModelName = chatModelFactory.getEmbeddingModelName();
        currentEmbeddingModelName = embeddingBaseUrl + "/" + embeddingModelName;
        log.info("Embedding 模型标识: {}", currentEmbeddingModelName);

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
                log.error("Embedding 模型初始化验证失败: {}", e.getMessage(), e);
                embeddingModel = null;
            }
        } else {
            log.error("Embedding 模型构建返回 null");
        }
    }

    /**
     * 在 Qdrant 中创建 Collection（如果不存在），并配置标量量化
     */
    private void createCollectionIfNotExists(String collectionName, boolean enableQuantization) {
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
                                        .setSize(qdrantProps.getVectorDimension())
                                        .setDistance(io.qdrant.client.grpc.Collections.Distance.Cosine)
                                        .setOnDisk(qdrantProps.isVectorsOnDisk())
                                        .build())
                                .build());

                if (enableQuantization && qdrantProps.getQuantization().isEnabled()) {
                    ScalarQuantization scalarQuant = ScalarQuantization.newBuilder()
                            .setType(QuantizationType.Int8)
                            .setQuantile(qdrantProps.getQuantization().getQuantile())
                            .setAlwaysRam(qdrantProps.getQuantization().isAlwaysRam())
                            .build();
                    QuantizationConfig quantConfig = QuantizationConfig.newBuilder()
                            .setScalar(scalarQuant)
                            .build();
                    collectionBuilder.setQuantizationConfig(quantConfig);
                    log.info("Collection [{}] 创建时启用标量量化: type=int8, quantile={}, alwaysRam={}",
                            collectionName, qdrantProps.getQuantization().getQuantile(), qdrantProps.getQuantization().isAlwaysRam());
                }

                qdrantClient.createCollectionAsync(collectionBuilder.build()).get();
                log.info("Qdrant Collection 创建成功: {} (量化={}, onDisk={})", collectionName, enableQuantization, qdrantProps.isVectorsOnDisk());

                try {
                    var optimizersConfig = io.qdrant.client.grpc.Collections.OptimizersConfigDiff.newBuilder()
                            .setIndexingThreshold(1000)
                            .build();
                    var updateBuilder = io.qdrant.client.grpc.Collections.UpdateCollection.newBuilder()
                            .setCollectionName(collectionName)
                            .setOptimizersConfig(optimizersConfig);
                    qdrantClient.updateCollectionAsync(updateBuilder.build()).get();
                    log.info("Collection [{}] indexing_threshold 已设为 1000 (默认 20000 过高)", collectionName);
                } catch (Exception ex) {
                    log.warn("设置 indexing_threshold 失败: {} - {}", collectionName, ex.getMessage());
                }
            } else {
                log.debug("Qdrant Collection 已存在: {}", collectionName);
                if (enableQuantization) {
                    updateQuantizationConfig(collectionName);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("创建 Qdrant Collection 被中断: {} - {}", collectionName, e.getMessage());
        } catch (ExecutionException e) {
            log.error("创建 Qdrant Collection 失败: {} - {}", collectionName, e.getCause() != null ? e.getCause().getMessage() : e.getMessage(), e);
        }
    }

    /**
     * 更新已有 Collection 的配置（量化参数 + 向量存储方式）
     * 修复：即使已启用量化，alwaysRam/onDisk 变更时也需要更新
     */
    private void updateQuantizationConfig(String collectionName) {
        if (!qdrantProps.getQuantization().isEnabled()) return;
        try {
            var collectionInfo = qdrantClient.getCollectionInfoAsync(collectionName).get();
            var currentQuantConfig = collectionInfo.getConfig().getQuantizationConfig();

            boolean needUpdateQuant = false;
            if (!currentQuantConfig.hasScalar()
                    || currentQuantConfig.getScalar().getType() != QuantizationType.Int8) {
                needUpdateQuant = true;
            } else if (currentQuantConfig.getScalar().getAlwaysRam() != qdrantProps.getQuantization().isAlwaysRam()) {
                needUpdateQuant = true;
                log.info("Collection [{}] alwaysRam 变更: {} → {}", collectionName,
                        currentQuantConfig.getScalar().getAlwaysRam(), qdrantProps.getQuantization().isAlwaysRam());
            }

            if (needUpdateQuant) {
                ScalarQuantization scalarQuant = ScalarQuantization.newBuilder()
                        .setType(QuantizationType.Int8)
                        .setQuantile(qdrantProps.getQuantization().getQuantile())
                        .setAlwaysRam(qdrantProps.getQuantization().isAlwaysRam())
                        .build();
                QuantizationConfigDiff quantConfigDiff = QuantizationConfigDiff.newBuilder()
                        .setScalar(scalarQuant)
                        .build();

                var updateBuilder = io.qdrant.client.grpc.Collections.UpdateCollection.newBuilder()
                        .setCollectionName(collectionName)
                        .setQuantizationConfig(quantConfigDiff);

                qdrantClient.updateCollectionAsync(updateBuilder.build()).get();
                log.info("Collection [{}] 已更新标量量化配置: type=int8, quantile={}, alwaysRam={}",
                        collectionName, qdrantProps.getQuantization().getQuantile(), qdrantProps.getQuantization().isAlwaysRam());
            }

            // 更新向量 on_disk 配置
            var currentVectorParams = collectionInfo.getConfig().getParams().getVectorsConfig().getParams();
            if (currentVectorParams.getOnDisk() != qdrantProps.isVectorsOnDisk()) {
                var vectorsConfigDiff = io.qdrant.client.grpc.Collections.VectorsConfigDiff.newBuilder()
                        .setParams(io.qdrant.client.grpc.Collections.VectorParamsDiff.newBuilder()
                                .setOnDisk(qdrantProps.isVectorsOnDisk())
                                .build())
                        .build();
                var updateBuilder = io.qdrant.client.grpc.Collections.UpdateCollection.newBuilder()
                        .setCollectionName(collectionName)
                        .setVectorsConfig(vectorsConfigDiff);
                qdrantClient.updateCollectionAsync(updateBuilder.build()).get();
                log.info("Collection [{}] 已更新向量存储配置: onDisk={} (was {})",
                        collectionName, qdrantProps.isVectorsOnDisk(), currentVectorParams.getOnDisk());
            }

            if (!needUpdateQuant && currentVectorParams.getOnDisk() == qdrantProps.isVectorsOnDisk()) {
                log.debug("Collection [{}] 量化与存储配置均无变化，跳过更新", collectionName);
            }
        } catch (Exception e) {
            log.warn("更新 Collection [{}] 配置失败: {}", collectionName, e.getMessage());
        }
    }

    /**
     * 为 Collection 的指定字段创建 payload 索引
     * 使用 Qdrant REST API（gRPC client 1.17.0 的 createFieldIndexAsync 签名不兼容）
     * 无索引时带 payload filter 的查询会全量扫描，170万+数据极慢甚至超时
     */
    private void createPayloadIndexIfNeeded(String collectionName) {
        try {
            var httpClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();

            var body = """
                    {"field_name":"%s","field_schema":"integer"}""".formatted("bookId");

            var requestBuilder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://" + qdrantProps.getHost() + ":6333/collections/" + collectionName + "/index"))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(body));

            if (qdrantProps.getApiKey() != null && !qdrantProps.getApiKey().isBlank()) {
                requestBuilder.header("api-key", qdrantProps.getApiKey());
            }

            var response = httpClient.send(requestBuilder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                log.info("Payload 索引创建/已存在: collection={}, field={}", collectionName, "bookId");
            } else {
                String respBody = response.body();
                if (respBody.contains("already")) {
                    log.debug("Payload 索引已存在: collection={}, field={}", collectionName, "bookId");
                } else {
                    log.warn("Payload 索引创建失败: collection={}, field={}, status={}, body={}",
                            collectionName, "bookId", response.statusCode(), respBody);
                }
            }
        } catch (Exception e) {
            log.warn("Payload 索引创建异常: collection={}, field={} - {}", collectionName, "bookId", e.getMessage());
        }
    }

    /**
     * 构建 EmbeddingStore 实例
     */
    private void buildEmbeddingStores() {
        bookEmbeddingStore = QdrantEmbeddingStore.builder()
                .client(qdrantClient)
                .collectionName(qdrantProps.getBookCollection())
                .build();

        contentEmbeddingStore = QdrantEmbeddingStore.builder()
                .client(qdrantClient)
                .collectionName(qdrantProps.getContentCollection())
                .build();
    }

    // ==================== 直接写入 Qdrant（绕过 QdrantEmbeddingStore 零向量 bug） ====================

    /**
     * 构建 Qdrant payload Map（从 TextSegment 的文本和 metadata）
     * 使用 Qdrant 官方 ValueFactory 构建 Value
     */
    private Map<String, io.qdrant.client.grpc.JsonWithInt.Value> buildQdrantPayload(TextSegment segment) {
        Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload = new java.util.HashMap<>();
        // 文本内容（与 QdrantEmbeddingStore 使用相同的 key "text_segment"）
        payload.put(PAYLOAD_TEXT_KEY, value(segment.text()));

        if (segment.metadata() != null) {
            for (var entry : segment.metadata().toMap().entrySet()) {
                payload.put(entry.getKey(), toQdrantValue(entry.getValue()));
            }
        }
        return payload;
    }

    /**
     * 将 Java Object 转换为 Qdrant Value（使用 ValueFactory）
     */
    private io.qdrant.client.grpc.JsonWithInt.Value toQdrantValue(Object val) {
        if (val instanceof String s) return value(s);
        else if (val instanceof Long l) return value(l);
        else if (val instanceof Integer i) return value(i.longValue());
        else if (val instanceof Double d) return value(d);
        else if (val instanceof Float f) return value(f.doubleValue());
        else if (val instanceof Boolean b) return value(b);
        else return value(String.valueOf(val));
    }

    // ==================== 通用 Qdrant 操作辅助 ====================

    /**
     * 构建按 bookId 精确匹配的 Qdrant Filter
     */
    private io.qdrant.client.grpc.Common.Filter buildBookIdFilter(Long bookId) {
        return io.qdrant.client.grpc.Common.Filter.newBuilder()
                .addMust(match("bookId", bookId))
                .build();
    }

    /**
     * 通过 qdrantClient 直接写入单条向量到 Qdrant（绕过 QdrantEmbeddingStore 的零向量 bug）
     * 使用 Qdrant 官方工厂方法构建 PointStruct，确保向量数据正确传递
     *
     * @return pointId（UUID），失败返回 null
     */
    private String directUpsertPoint(String collectionName, float[] vector,
                                     Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload) {
        String pointId = UUID.randomUUID().toString();

        try {
            // 使用 Qdrant 工厂方法构建 PointStruct
            var point = io.qdrant.client.grpc.Points.PointStruct.newBuilder()
                    .setId(id(UUID.fromString(pointId)))
                    .setVectors(vectors(vector))
                    .putAllPayload(payload)
                    .build();

            // 写入 Qdrant
            qdrantClient.upsertAsync(collectionName, java.util.List.of(point))
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);

            log.debug("直接写入向量成功: collection={}, pointId={}", collectionName, pointId);
            return pointId;
        } catch (Exception e) {
            log.error("直接写入向量失败: collection={}, pointId={} - {}", collectionName, pointId, e.getMessage());
            return null;
        }
    }

    /**
     * 通过 qdrantClient 批量写入向量到 Qdrant（绕过 QdrantEmbeddingStore 的零向量 bug）
     * 使用 gRPC PointStruct 批量构建，确保向量数据正确传递
     */
    private void directBatchUpsertPoints(String collectionName,
                                         List<float[]> vectorsList,
                                         List<Map<String, JsonWithInt.Value>> payloads) {
        if (vectorsList.isEmpty()) return;

        try {
            List<io.qdrant.client.grpc.Points.PointStruct> points = new ArrayList<>(vectorsList.size());

            for (int i = 0; i < vectorsList.size(); i++) {
                String pointId = UUID.randomUUID().toString();
                float[] vec = vectorsList.get(i);

                // 诊断：再次验证传入的向量
                if (i == 0) {
                    double checkNorm = 0;
                    for (float v : vec) checkNorm += v * v;
                    log.debug("directBatchUpsertPoints 诊断: 接收向量 norm={}", Math.sqrt(checkNorm));
                }

                var point = io.qdrant.client.grpc.Points.PointStruct.newBuilder()
                        .setId(id(UUID.fromString(pointId)))
                        .setVectors(vectors(vec))
                        .putAllPayload(payloads.get(i))
                        .build();

                points.add(point);
            }

            qdrantClient.upsertAsync(collectionName, points)
                    .get(60, java.util.concurrent.TimeUnit.SECONDS);

            log.debug("批量写入向量成功: collection={}, count={}", collectionName, vectorsList.size());
        } catch (Exception e) {
            log.error("批量写入向量失败: collection={}, count={} - {}", collectionName, vectorsList.size(), e.getMessage());
        }
    }

    /**
     * 计算向量的 L2 范数（用于验证嵌入向量非零）
     */
    private double vectorNorm(float[] vector) {
        double norm = 0;
        for (float v : vector) norm += v * v;
        return Math.sqrt(norm);
    }

    // ==================== 书籍元数据向量（用于推荐召回） ====================

    /**
     * 为书籍生成元数据向量并存储到 Qdrant
     * 元数据 = 标题 + 作者 + 标签 + 简介 → 1个 embedding
     */
    public void generateBookEmbedding(Book book) {
        if (book == null) return;
        try {
            ensureEmbeddingModelInitialized();
            if (embeddingModel == null) {
                log.warn("Embedding 模型未初始化，跳过书籍向量生成: bookId={}", book.getId());
                return;
            }
            upsertBookEmbedding(book);
        } catch (Exception e) {
            log.error("书籍元数据向量生成失败: bookId={} - {}", book.getId(), e.getMessage());
        }
    }

    /**
     * 核心方法：为单本书生成并写入元数据向量
     */
    public void upsertBookEmbedding(Book book) {
        Long bookId = book.getId();
        String metadataText = buildBookMetadataText(book);
        if (metadataText.isBlank()) {
            log.debug("书籍元数据为空，跳过向量生成: bookId={}", bookId);
            return;
        }

        Embedding embedding = embeddingModel.embed(metadataText).content();
        float[] vector = embedding.vector();
        double norm = vectorNorm(vector);
        if (norm < 0.001) {
            log.error("生成的 embedding 向量为零! bookId={}, model={}, norm={}",
                    bookId, currentEmbeddingModelName, String.format("%.6f", norm));
            return;
        }

        TextSegment segment = TextSegment.from(metadataText,
                new Metadata()
                        .put("bookId", bookId)
                        .put("embeddingModel", currentEmbeddingModelName));

        removeBookEmbedding(bookId);

        Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload = buildQdrantPayload(segment);
        String id = directUpsertPoint(qdrantProps.getBookCollection(), vector, payload);

        boolean exists = hasBookEmbedding(bookId);
        log.info("书籍元数据向量生成完成: bookId={}, textLen={}, storeId={}, qdrantVerified={}, vectorNorm={}",
                bookId, metadataText.length(), id, exists, String.format("%.4f", norm));
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

            for (EmbeddingMatch<TextSegment> match : matches) {
                log.debug("向量搜索结果: score={}, bookId={}, textLen={}", match.score(), match.embedded().metadata().getLong("bookId"), match.embedded().text().replaceAll("\\n",  " "));
            }

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


    /**
     * 为图书生成 RAG 内容向量（返回 chunk 数量）
     * <p>
     * 单线程顺序处理所有 batch。
     */
    public int generateContentEmbeddingWithCount(Long bookId, String content) {
        try {
            ensureEmbeddingModelInitialized();
            if (embeddingModel == null) {
                log.warn("Embedding 模型未初始化，跳过内容向量生成: bookId={}", bookId);
                return 0;
            }

            if (content == null || content.isBlank()) {
                log.debug("书籍内容为空，跳过内容向量生成: bookId={}", bookId);
                return 0;
            }

            long startTime = System.currentTimeMillis();

            List<String> chunks = splitContent(content);
            log.info("书籍内容分块: bookId={}, totalChars={}, chunks={}", bookId, content.length(), chunks.size());

            removeContentEmbedding(bookId);

            int totalChunks = chunks.size();
            log.info("内容向量生成: bookId={}, chunks={}", bookId, totalChunks);

            int totalProcessed = 0;
            for (int batchStart = 0; batchStart < totalChunks; batchStart += EMBED_BATCH_SIZE) {
                int batchEnd = Math.min(batchStart + EMBED_BATCH_SIZE, totalChunks);
                List<String> batchChunks = chunks.subList(batchStart, batchEnd);
                totalProcessed += processContentBatch(bookId, batchChunks, batchStart, totalChunks);
                if (totalProcessed % 100 == 0 || totalProcessed >= totalChunks) {
                    log.info("内容向量生成进度: bookId={}, {}/{}", bookId, totalProcessed, totalChunks);
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("书籍内容向量生成完成: bookId={}, chunks={}, elapsed={}ms",
                    bookId, totalProcessed, elapsed);

            return totalProcessed;
        } catch (Exception e) {
            log.error("书籍内容向量生成失败: bookId={} - {}", bookId, e.getMessage());
            return 0;
        }
    }

    /**
     * 处理单个 batch 的 embedding 生成与入库（含失败回退）
     */
    private int processContentBatch(Long bookId, List<String> batchChunks, int batchStart, int totalChunks) {
        try {
            // 构建 TextSegment
            List<TextSegment> segments = new ArrayList<>(batchChunks.size());
            for (int i = 0; i < batchChunks.size(); i++) {
                int globalIndex = batchStart + i;
                segments.add(TextSegment.from(batchChunks.get(i),
                        new Metadata().put("bookId", bookId)
                                .put("chunkIndex", globalIndex)
                                .put("totalChunks", totalChunks)
                                .put("embeddingModel", currentEmbeddingModelName)));
            }

            // 批量 embed
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

            List<float[]> vectors = new ArrayList<>(embeddings.size());
            List<Map<String, JsonWithInt.Value>> payloads = new ArrayList<>(embeddings.size());

            for (int i = 0; i < embeddings.size(); i++) {
                float[] vec = embeddings.get(i).vector();
                double vNorm = vectorNorm(vec);
                if (vNorm < 0.001) {
                    log.warn("chunkIndex={} 零向量，跳过: bookId={}", batchStart + i, bookId);
                    continue;
                }
                vectors.add(vec);
                payloads.add(buildQdrantPayload(segments.get(i)));
            }

            if (!vectors.isEmpty()) {
                directBatchUpsertPoints(qdrantProps.getContentCollection(), vectors, payloads);
            }

            return vectors.size();
        } catch (Exception e) {
            log.warn("批量embed失败，回退逐条处理: bookId={}, batch={}-{} - {}",
                    bookId, batchStart, batchStart + batchChunks.size() - 1, e.getMessage());

            int fallbackCount = 0;
            List<float[]> fallbackVectors = new ArrayList<>();
            List<Map<String, JsonWithInt.Value>> fallbackPayloads = new ArrayList<>();

            for (int i = 0; i < batchChunks.size(); i++) {
                try {
                    int globalIndex = batchStart + i;
                    TextSegment segment = TextSegment.from(batchChunks.get(i),
                            new Metadata().put("bookId", bookId)
                                    .put("chunkIndex", globalIndex)
                                    .put("totalChunks", totalChunks)
                                    .put("embeddingModel", currentEmbeddingModelName));
                    Embedding embedding = embeddingModel.embed(segment).content();

                    float[] vec = embedding.vector();
                    double vNorm = vectorNorm(vec);
                    if (vNorm < 0.001) {
                        log.warn("chunkIndex={} 零向量，跳过: bookId={}", globalIndex, bookId);
                        continue;
                    }
                    fallbackPayloads.add(buildQdrantPayload(segment));
                    fallbackVectors.add(vec);

                    if (fallbackVectors.size() >= EMBED_BATCH_SIZE || i == batchChunks.size() - 1) {
                        directBatchUpsertPoints(qdrantProps.getContentCollection(), fallbackVectors, fallbackPayloads);
                        fallbackCount += fallbackVectors.size();
                        fallbackVectors.clear();
                        fallbackPayloads.clear();
                    }
                } catch (Exception ex) {
                    log.warn("逐条embed失败: bookId={}, index={} - {}", bookId, batchStart + i, ex.getMessage());
                }
            }
            return fallbackCount;
        }
    }

    /**
     * RAG 语义检索：根据查询在书籍内容中搜索相关片段
     * <p>
     * 自动记录命中/未命中统计到 RagHitStatisticsService，用于风控重建。
     *
     * @param query      查询文本
     * @param maxResults 最大返回数量
     * @param book       可选，限定在某本书内搜索
     * @return 匹配的内容片段
     */
    public List<EmbeddingMatch<TextSegment>> searchContent(String query, int maxResults, Book book) {
        ensureEmbeddingModelInitialized();
        if (embeddingModel == null) {
            return List.of();
        }

        List<EmbeddingMatch<TextSegment>> results = List.of();
        try {
            String expandedQuery = expandQueryWithContext(query, book);
            Embedding queryEmbedding = embeddingModel.embed(expandedQuery).content();

            if (book != null && qdrantClient != null) {
                results = searchContentWithFilter(queryEmbedding, maxResults, book.getId());
            } else if (contentEmbeddingStore != null) {
                EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(maxResults)
                        .minScore(0.2)
                        .build();
                results = contentEmbeddingStore.search(request).matches();
            }

            // 记录命中统计（有结果 = 命中，无结果 = 未命中）
            if (book != null && ragHitStatisticsService != null) {
                if (results.isEmpty()) {
                    ragHitStatisticsService.recordMiss(book.getId());
                } else {
                    double topScore = results.stream()
                            .mapToDouble(EmbeddingMatch::score)
                            .max()
                            .orElse(0.0);
                    ragHitStatisticsService.recordHit(book.getId(), topScore);
                }
            }

            return results;
        } catch (Exception e) {
            log.error("RAG 内容检索失败: {}", e.getMessage());
            // 异常时也记录未命中
            if (book != null && ragHitStatisticsService != null) {
                ragHitStatisticsService.recordMiss(book.getId());
            }
            return List.of();
        }
    }

    /**
     * 使用 Qdrant 原生 filter 搜索内容向量（按 bookId 精确过滤）
     * 解决：LangChain4j EmbeddingSearchRequest 不支持 Qdrant payload filter，
     * 导致先取 top-N 再内存过滤，当数据量大时目标书的结果被其他书挤掉
     */
    private List<EmbeddingMatch<TextSegment>> searchContentWithFilter(
            Embedding queryEmbedding, int maxResults, Long bookId) {
        try {
            var filter = buildBookIdFilter(bookId);

            // 先用 0.2 阈值搜索（内容检索场景短查询 vs 长段落，0.3 偏严）
            List<EmbeddingMatch<TextSegment>> matches = doFilterSearch(queryEmbedding, maxResults, bookId, filter, 0.2f);

            // 如果无结果，降级到 0 阈值重试
            if (matches.isEmpty()) {
                log.debug("scoreThreshold=0.2 无结果，降级到 0 阈值重试: bookId={}", bookId);
                matches = doFilterSearch(queryEmbedding, maxResults, bookId, filter, 0.0f);
                if (!matches.isEmpty()) {
                    float topScore = matches.get(0).score().floatValue();
                    if (topScore < 0.1) {
                        // score 极低，检查是否向量模型不匹配
                        String storedModel = getStoredEmbeddingModelName(bookId, matches);
                        if (storedModel != null && !storedModel.equals(currentEmbeddingModelName)) {
                            log.warn("RAG 检索 score 极低 (最高={}), bookId={} — 向量模型不匹配! 存储模型={}, 当前模型={}, 需要重建向量",
                                    String.format("%.4f", topScore), bookId, storedModel, currentEmbeddingModelName);
                        } else {
                            log.warn("RAG 检索 score 极低 (最高={}), bookId={} — 存储模型={}, 当前模型={}, 可能是向量维度异常或量化问题",
                                    String.format("%.4f", topScore), bookId, storedModel, currentEmbeddingModelName);
                        }
                    } else {
                        // score 在 0.1~0.2 之间，属于正常的语义距离偏大
                        log.info("RAG 检索 score 偏低 (最高={}), bookId={} — 查询与内容语义距离较大，已降级返回",
                                String.format("%.4f", topScore), bookId);
                    }
                }
            }

            // 诊断：仍然无结果时检查原因
            if (matches.isEmpty()) {
                diagnoseContentSearch(bookId, queryEmbedding);
            }

            return matches;
        } catch (Exception e) {
            log.warn("Qdrant filter 搜索失败，回退 LangChain4j 搜索: bookId={} - {}", bookId, e.getMessage());
            try {
                EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(maxResults * 10)
                        .minScore(0.2)
                        .build();
                return contentEmbeddingStore.search(request).matches().stream()
                        .filter(m -> m.embedded() != null && m.embedded().metadata() != null
                                && bookId.equals(m.embedded().metadata().getLong("bookId")))
                        .limit(maxResults)
                        .toList();
            } catch (Exception ex) {
                log.error("降级搜索也失败: {}", ex.getMessage());
                return List.of();
            }
        }
    }

    /**
     * 从 Qdrant payload Map 中提取字符串值
     */
    private String extractPayloadString(Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload, String key) {
        io.qdrant.client.grpc.JsonWithInt.Value value = payload.get(key);
        if (value == null) return null;
        if (value.hasStringValue()) return value.getStringValue();
        if (value.hasIntegerValue()) return String.valueOf(value.getIntegerValue());
        if (value.hasDoubleValue()) return String.valueOf(value.getDoubleValue());
        return null;
    }

    /**
     * 自检：验证 Embedding 向量一致性，定位 score 极低的根因
     * <p>
     * 诊断逻辑：
     * 1. 稳定性测试：同一文本 embed 两次，验证模型输出一致
     * 2. 通过 Qdrant REST API 读取存储向量的原始值（绕过 gRPC API 兼容问题）
     * 3. 直接余弦相似度对比：存储向量 vs 重新 embed 的向量
     * - 直接相似度 ≈1.0 但 Qdrant search score ≈0 → Qdrant 搜索/量化有问题
     * - 直接相似度 ≈0 → 存储向量与当前模型不在同一向量空间
     * 4. Qdrant 搜索验证：用重新 embed 的向量搜索 Qdrant，验证搜索 score 是否合理
     *
     * @param bookId 要检查的书籍 ID
     * @return 诊断结果 Map
     */
    public Map<String, Object> selfCheckEmbedding(Long bookId) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        ensureEmbeddingModelInitialized();

        result.put("currentModel", currentEmbeddingModelName);
        result.put("modelInitialized", embeddingModel != null);

        if (embeddingModel == null) {
            result.put("error", "Embedding 模型未初始化");
            return result;
        }

        try {
            // ========== 第 1 步：稳定性测试 ==========
            String testText = "这是一个测试文本用于验证embedding模型的稳定性";
            float[] emb1 = embeddingModel.embed(testText).content().vector();
            float[] emb2 = embeddingModel.embed(testText).content().vector();
            double selfSimilarity = cosineSimilarity(emb1, emb2);
            result.put("step1_stability.selfSimilarity", selfSimilarity);
            result.put("step1_stability.pass", selfSimilarity > 0.999);
            result.put("step1_stability.vectorDim", emb1.length);

            if (bookId == null || qdrantClient == null) {
                result.put("note", "未提供 bookId，跳过向量对比测试");
                return result;
            }

            // ========== 第 2 步：通过 Qdrant REST API 读取存储向量 ==========
            // 用 scroll API（REST）取一条完整数据（含向量），绕过 gRPC API 兼容问题
            String storedText = null;
            String storedModel = null;
            float[] storedVector = null;

            try {
                var httpClient = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(5))
                        .build();

                // 用 filter 滚动获取 bookId 对应的一条数据（含向量）
                String scrollBody = """
                        {"filter":{"must":[{"key":"bookId","match":{"value":%d}}]},"limit":1,"with_payload":true,"with_vector":true}"""
                        .formatted(bookId);

                var httpRequest = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create("http://" + qdrantProps.getHost() + ":6333/collections/" + qdrantProps.getContentCollection() + "/points/scroll"))
                        .timeout(java.time.Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(scrollBody));

                if (qdrantProps.getApiKey() != null && !qdrantProps.getApiKey().isBlank()) {
                    httpRequest.header("api-key", qdrantProps.getApiKey());
                }

                var response = httpClient.send(httpRequest.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    var jsonResp = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                            .readTree(response.body());
                    var pointsNode = jsonResp.at("/result/points");
                    if (pointsNode.isArray() && !pointsNode.isEmpty()) {
                        var firstPoint = pointsNode.get(0);

                        // 提取 payload
                        var payloadNode = firstPoint.at("/payload");
                        if (!payloadNode.isMissingNode()) {
                            var textSegNode = payloadNode.get(PAYLOAD_TEXT_KEY);
                            storedText = textSegNode != null && textSegNode.isTextual() ? textSegNode.asText() : null;
                            var modelNode = payloadNode.get("embeddingModel");
                            storedModel = modelNode != null && modelNode.isTextual() ? modelNode.asText() : null;
                        }

                        // 提取向量数据
                        var vectorNode = firstPoint.at("/vector");
                        if (vectorNode.isArray()) {
                            // 默认向量（数组格式）
                            storedVector = new float[vectorNode.size()];
                            for (int i = 0; i < vectorNode.size(); i++) {
                                storedVector[i] = (float) vectorNode.get(i).asDouble();
                            }
                        } else if (vectorNode.isObject()) {
                            // 命名向量（对象格式），取第一个
                            var field = vectorNode.fieldNames().next();
                            var arrNode = vectorNode.get(field);
                            if (arrNode.isArray()) {
                                storedVector = new float[arrNode.size()];
                                for (int i = 0; i < arrNode.size(); i++) {
                                    storedVector[i] = (float) arrNode.get(i).asDouble();
                                }
                                result.put("step2_storedVector.vectorName", field);
                            }
                        }
                    }
                } else {
                    result.put("step2_storedVector.restApiError", "HTTP " + response.statusCode() + ": " + response.body());
                }
            } catch (Exception e) {
                result.put("step2_storedVector.restApiException", e.getMessage());
            }

            // 填充存储向量信息
            result.put("step2_storedVector.storedModel", storedModel);
            result.put("step2_storedVector.currentModel", currentEmbeddingModelName);
            result.put("step2_storedVector.modelMatch", storedModel != null && storedModel.equals(currentEmbeddingModelName));
            result.put("step2_storedVector.textLength", storedText != null ? storedText.length() : 0);
            result.put("step2_storedVector.textPreview", storedText != null && storedText.length() > 100
                    ? storedText.substring(0, 100) + "..." : storedText);

            if (storedVector != null) {
                result.put("step2_storedVector.vectorDim", storedVector.length);
                result.put("step2_storedVector.vectorSample", String.format("[%.4f, %.4f, %.4f, %.4f, %.4f]",
                        storedVector[0], storedVector.length > 1 ? storedVector[1] : 0,
                        storedVector.length > 2 ? storedVector[2] : 0, storedVector.length > 3 ? storedVector[3] : 0,
                        storedVector.length > 4 ? storedVector[4] : 0));
                double storedNorm = 0;
                for (float v : storedVector) storedNorm += v * v;
                storedNorm = Math.sqrt(storedNorm);
                result.put("step2_storedVector.vectorNorm", String.format("%.4f", storedNorm));

                // ========== 第 3 步：直接向量对比 ==========
                if (storedText != null && !storedText.isBlank()) {
                    String reEmbedText = storedText.length() > 200 ? storedText.substring(0, 200) : storedText;
                    float[] freshVector = embeddingModel.embed(reEmbedText).content().vector();
                    result.put("step3_directCompare.freshVectorDim", freshVector.length);
                    result.put("step3_directCompare.freshVectorSample", String.format("[%.4f, %.4f, %.4f, %.4f, %.4f]",
                            freshVector[0], freshVector.length > 1 ? freshVector[1] : 0,
                            freshVector.length > 2 ? freshVector[2] : 0, freshVector.length > 3 ? freshVector[3] : 0,
                            freshVector.length > 4 ? freshVector[4] : 0));
                    double freshNorm = 0;
                    for (float v : freshVector) freshNorm += v * v;
                    freshNorm = Math.sqrt(freshNorm);
                    result.put("step3_directCompare.freshVectorNorm", String.format("%.4f", freshNorm));

                    double directSimilarity = cosineSimilarity(storedVector, freshVector);
                    result.put("step3_directCompare.cosineSimilarity", directSimilarity);

                    if (directSimilarity < 0.1) {
                        result.put("step3_directCompare.diagnosis",
                                "CRITICAL: 直接对比相似度极低! 存储向量与当前模型生成的向量不在同一空间。"
                                        + "可能原因：1)向量写入时用了不同Ollama实例/端口 2)向量写入失败写入了全零/随机值 "
                                        + "3)langchain4j-qdrant写入格式与Qdrant存储格式不兼容");
                    } else if (directSimilarity < 0.5) {
                        result.put("step3_directCompare.diagnosis",
                                "WARNING: 直接对比相似度偏低。可能是截取前200字与完整文本的语义差异，"
                                        + "也可能是向量空间轻微不一致");
                    } else {
                        result.put("step3_directCompare.diagnosis", "OK: 存储向量与当前模型在同一向量空间");
                    }
                } else {
                    result.put("step3_directCompare.error", "存储文本为空，无法重新 embed");
                }
            } else {
                result.put("step2_storedVector.error", "无法从 Qdrant 取回原始向量数据");
            }

            // ========== 第 4 步：Qdrant 搜索验证 ==========
            if (storedText != null && !storedText.isBlank()) {
                String reEmbedText = storedText.length() > 200 ? storedText.substring(0, 200) : storedText;
                Embedding reQueryEmbedding = embeddingModel.embed(reEmbedText).content();

                var reSearchPoints = io.qdrant.client.grpc.Points.SearchPoints.newBuilder()
                        .setCollectionName(qdrantProps.getContentCollection())
                        .setLimit(5)
                        .setScoreThreshold(0.0f)
                        .setFilter(buildBookIdFilter(bookId))
                        .addAllVector(reQueryEmbedding.vectorAsList().stream().toList())
                        .setWithPayload(io.qdrant.client.grpc.Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                        .build();
                var reResults = qdrantClient.searchAsync(reSearchPoints).get(15, java.util.concurrent.TimeUnit.SECONDS);

                if (!reResults.isEmpty()) {
                    double topScore = reResults.get(0).getScore();
                    result.put("step4_qdrantSearch.topScore", topScore);
                    result.put("step4_qdrantSearch.hitCount", reResults.size());
                    List<Double> scores = reResults.stream().limit(5)
                            .map(sp -> (double) sp.getScore()).toList();
                    result.put("step4_qdrantSearch.top5Scores", scores);

                    if (topScore < 0.1) {
                        result.put("step4_qdrantSearch.diagnosis",
                                "CRITICAL: Qdrant搜索score极低! 如果step3直接对比相似度正常(>0.5)，"
                                        + "则说明Qdrant量化/存储层面有问题，建议关闭量化或重建collection");
                    } else if (topScore < 0.5) {
                        result.put("step4_qdrantSearch.diagnosis",
                                "WARNING: Qdrant搜索score偏低，可能是量化精度损失或文本截取差异");
                    } else {
                        result.put("step4_qdrantSearch.diagnosis", "OK: Qdrant搜索正常");
                    }
                } else {
                    result.put("step4_qdrantSearch.error", "重新embed后搜索无结果");
                }
            }

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    /**
     * 计算两个向量的余弦相似度
     */
    private double cosineSimilarity(float[] a, float[] b) {
        double dotProduct = 0, normA = 0, normB = 0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 将 float[] 转为 List<Float>（Arrays.stream 不支持 float[]）
     */
    private List<Float> floatArrayToList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add(v);
        }
        return list;
    }

    /**
     * 从 Qdrant payload Map 中提取 Long 值
     */
    private Long extractPayloadLong(Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload, String key) {
        io.qdrant.client.grpc.JsonWithInt.Value value = payload.get(key);
        if (value == null) return null;
        if (value.hasIntegerValue()) return value.getIntegerValue();
        if (value.hasDoubleValue()) return (long) value.getDoubleValue();
        return null;
    }

    /**
     * RAG 查询扩展：用书籍领域上下文 + 语义同义词扩展短查询，提升与长段落的匹配度
     * <p>
     * 原理：用户问题通常很短（如"主角的成长"），而内容分块是 800 字长段落，
     * 短文本 vs 长文本的余弦相似度天然偏低。扩展策略：
     * 1. 语义同义词扩展：将核心关键词扩展为近义词，覆盖更多表达方式
     * 2. 书籍领域上下文：用标签/简介中的领域词汇锚定查询到该书的主题向量空间
     * <p>
     * 注意：不再拼接书名/作者，因为：
     * - 内容 chunk 不包含书名/作者，拼接后反而引入噪声
     * - bookId filter 已精确限定搜索范围，书名/作者信息冗余
     */
    private String expandQueryWithContext(String query, Book book) {
        if (query == null || query.isBlank()) return query;
        try {
            StringBuilder expanded = new StringBuilder(query);

            if (book != null) {
                String domainContext = extractDomainContext(book);
                if (!domainContext.isBlank()) {
                    expanded.append(" ").append(domainContext);
                }
            }

            return expanded.toString();
        } catch (Exception e) {
            return query;
        }
    }

    private String extractDomainContext(Book book) {
        StringBuilder context = new StringBuilder();

        if (book.getFormatTags() != null && !book.getFormatTags().isBlank()) {
            String tags = book.getFormatTags()
                    .replaceAll("[\\[\\]\"]", "")
                    .replace(",", " ");
            String[] tagArr = tags.split("\\s+");
            int limit = Math.min(tagArr.length, 5);
            for (int i = 0; i < limit; i++) {
                if (!tagArr[i].isBlank()) {
                    context.append(tagArr[i]).append(" ");
                }
            }
        }

        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            String desc = book.getDescription().length() > 100
                    ? book.getDescription().substring(0, 100)
                    : book.getDescription();
            context.append(desc);
        }

        return context.toString().trim();
    }

    /**
     * 获取指定书籍已存储向量的 embedding 模型标识
     * 从已有的低分搜索结果中提取 payload 的 embeddingModel 字段
     */
    private String getStoredEmbeddingModelName(Long bookId, List<EmbeddingMatch<TextSegment>> matches) {
        // 优先从搜索结果中提取
        if (matches != null && !matches.isEmpty()) {
            var first = matches.get(0);
            if (first.embedded() != null && first.embedded().metadata() != null) {
                String model = first.embedded().metadata().getString("embeddingModel");
                if (model != null) return model;
            }
        }
        // 回退：用 Qdrant 搜索获取 payload
        try {
            if (qdrantClient == null) return null;
            var filter = buildBookIdFilter(bookId);
            // 构造一个非零向量做搜索（全零向量在某些 Qdrant 版本上可能搜不到结果）
            float[] probeVector = new float[qdrantProps.getVectorDimension()];
            probeVector[0] = 0.01f;
            var searchPoints = io.qdrant.client.grpc.Points.SearchPoints.newBuilder()
                    .setCollectionName(qdrantProps.getContentCollection())
                    .setLimit(1)
                    .setScoreThreshold(0.0f)
                    .setFilter(filter)
                    .addAllVector(floatArrayToList(probeVector))
                    .setWithPayload(io.qdrant.client.grpc.Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    .build();
            var results = qdrantClient.searchAsync(searchPoints).get(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!results.isEmpty()) {
                var payload = results.get(0).getPayloadMap();
                return extractPayloadString(payload, "embeddingModel");
            }
        } catch (Exception e) {
            log.debug("获取存储向量模型标识失败: bookId={} - {}", bookId, e.getMessage());
        }
        return null;
    }

    // ==================== 管理操作 ====================

    /**
     * 清空所有内容向量（kbook_content 集合）
     */
    public long clearAllContentEmbeddings() {
        try {
            if (qdrantClient == null) {
                log.warn("Qdrant 客户端未初始化");
                return 0;
            }

            // 获取当前总数
            long totalCount = qdrantClient.countAsync(qdrantProps.getContentCollection()).get(30, java.util.concurrent.TimeUnit.SECONDS);
            log.info("开始清空内容向量库: totalVectors={}", totalCount);

            // 使用 delete 删除整个集合的所有点
            qdrantClient.deleteAsync(
                    qdrantProps.getContentCollection(),
                    Common.Filter.newBuilder().build()
            ).get(60, java.util.concurrent.TimeUnit.SECONDS);

            log.info("内容向量库已清空: deletedVectors={}", totalCount);
            return totalCount;
        } catch (Exception e) {
            log.error("清空内容向量库失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 检查 Embedding 功能是否可用
     * 注意：会先触发延迟初始化，确保模型已尝试加载
     */
    public boolean isAvailable() {
        ensureEmbeddingModelInitialized();
        return embeddingModel != null && bookEmbeddingStore != null && contentEmbeddingStore != null;
    }

    /**
     * 检查指定书籍是否已有元数据向量（通过 Qdrant 精确查询 bookId）
     */
    public boolean hasBookEmbedding(Long bookId) {
        try {
            if (qdrantClient == null) return false;
            Long count = qdrantClient.countAsync(qdrantProps.getBookCollection(), buildBookIdFilter(bookId), true).get(15, java.util.concurrent.TimeUnit.SECONDS);
            return count != null && count > 0;
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("检查书籍向量存在性超时: bookId={} - {}", bookId, e.getMessage());
            return false;
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
            Long count = qdrantClient.countAsync(qdrantProps.getContentCollection(), buildBookIdFilter(bookId), true).get(15, java.util.concurrent.TimeUnit.SECONDS);
            return count != null && count > 0;
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("检查内容向量存在性超时: bookId={} - {}", bookId, e.getMessage());
            return false;
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
            qdrantClient.deleteAsync(qdrantProps.getBookCollection(), buildBookIdFilter(bookId)).get(30, java.util.concurrent.TimeUnit.SECONDS);
            log.debug("已删除旧书籍元数据向量: bookId={}", bookId);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("删除旧书籍元数据向量超时，跳过: bookId={} - {}", bookId, e.getMessage());
        } catch (Exception e) {
            log.debug("删除旧书籍元数据向量失败（可能不存在）: bookId={} - {}", bookId, e.getMessage());
        }
    }

    /**
     * 检测指定书籍的内容向量是否为零向量
     * 通过 Qdrant REST API scroll 获取向量并检查前 10 维
     *
     * @return true = 检测到零向量（数据异常，需要重建）
     */
    public boolean detectZeroContentVectors(Long bookId) {
        try {
            var httpClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();
            String scrollBody = """
                    {"filter":{"must":[{"key":"bookId","match":{"value":%d}}]},"limit":1,"with_payload":true,"with_vector":true}"""
                    .formatted(bookId);
            var httpRequest = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://" + qdrantProps.getHost() + ":6333/collections/" + qdrantProps.getContentCollection() + "/points/scroll"))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(scrollBody));
            if (qdrantProps.getApiKey() != null && !qdrantProps.getApiKey().isBlank()) {
                httpRequest.header("api-key", qdrantProps.getApiKey());
            }
            var response = httpClient.send(httpRequest.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var jsonResp = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                        .readTree(response.body());
                var pointsNode = jsonResp.at("/result/points");
                if (pointsNode.isArray()) {
                    if (pointsNode.isEmpty()) {
                        log.error("[零向量检测] bookId={} 的内容向量为空! Qdrant 中不存在该书记录。需要重建。", bookId);
                        return true;
                    }
                    var vectorNode = pointsNode.get(0).at("/vector");
                    if (vectorNode.isArray()) {
                        boolean allZero = true;
                        int checkDims = Math.min(10, vectorNode.size());
                        for (int i = 0; i < checkDims; i++) {
                            if (Math.abs(vectorNode.get(i).asDouble()) > 0.0001) {
                                allZero = false;
                                break;
                            }
                        }
                        if (allZero) {
                            log.error("[零向量检测] bookId={} 的存储向量为全零! 共 {} 维。需要重建该书的内容向量。",
                                    bookId, vectorNode.size());
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[零向量检测] 失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 删除指定书籍的内容向量（通过 bookId 精确匹配 payload）
     */
    public void removeContentEmbedding(Long bookId) {
        try {
            if (qdrantClient == null) return;
            qdrantClient.deleteAsync(qdrantProps.getContentCollection(), buildBookIdFilter(bookId)).get(30, java.util.concurrent.TimeUnit.SECONDS);
            log.debug("已删除旧内容向量: bookId={}", bookId);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("删除旧内容向量超时，跳过: bookId={} - {}", bookId, e.getMessage());
        } catch (Exception e) {
            log.debug("删除旧内容向量失败（可能不存在）: bookId={} - {}", bookId, e.getMessage());
        }
    }

    /**
     * 获取内容向量的总条目数
     */
    public long getTotalContentEmbeddingCount() {
        try {
            if (qdrantClient == null) return 0;
            var info = qdrantClient.getCollectionInfoAsync(qdrantProps.getContentCollection()).get(15, java.util.concurrent.TimeUnit.SECONDS);
            return info.getPointsCount();
        } catch (Exception e) {
            log.warn("获取内容向量总数失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 执行带 filter 的 Qdrant 向量搜索
     */
    private List<EmbeddingMatch<TextSegment>> doFilterSearch(
            Embedding queryEmbedding, int maxResults, Long bookId,
            io.qdrant.client.grpc.Common.Filter filter, float scoreThreshold) {
        try {
            var searchPoints = io.qdrant.client.grpc.Points.SearchPoints.newBuilder()
                    .setCollectionName(qdrantProps.getContentCollection())
                    .setLimit(maxResults)
                    .setScoreThreshold(scoreThreshold)
                    .setFilter(filter)
                    .addAllVector(queryEmbedding.vectorAsList().stream().toList())
                    .setWithPayload(io.qdrant.client.grpc.Points.WithPayloadSelector.newBuilder()
                            .setEnable(true).build())
                    .build();

            var searchResults = qdrantClient.searchAsync(searchPoints).get();

            List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
            for (var scoredPoint : searchResults) {
                double score = scoredPoint.getScore();
                var payload = scoredPoint.getPayloadMap();
                if (payload.isEmpty()) continue;

                String text = extractPayloadString(payload, PAYLOAD_TEXT_KEY);
                if (text == null || text.isBlank()) continue;

                Metadata metadata = new Metadata();
                Long bookIdLong = extractPayloadLong(payload, "bookId");
                if (bookIdLong != null) {
                    metadata.put("bookId", bookIdLong);
                }
                Long chunkIndex = extractPayloadLong(payload, "chunkIndex");
                if (chunkIndex != null) {
                    metadata.put("chunkIndex", chunkIndex);
                }
                String embeddingModel = extractPayloadString(payload, "embeddingModel");
                if (embeddingModel != null) {
                    metadata.put("embeddingModel", embeddingModel);
                }

                TextSegment segment = TextSegment.from(text, metadata);
                String embeddingId = String.valueOf(scoredPoint.getId().getNum());
                matches.add(new EmbeddingMatch<>(score, embeddingId, null, segment));
            }

            if (!matches.isEmpty() && matches.get(0).score() < 0.1) {
                float[] qVec = queryEmbedding.vector();
                String qVecSample = String.format("[%.4f, %.4f, %.4f, %.4f, %.4f]",
                        qVec.length > 0 ? qVec[0] : 0, qVec.length > 1 ? qVec[1] : 0,
                        qVec.length > 2 ? qVec[2] : 0, qVec.length > 3 ? qVec[3] : 0,
                        qVec.length > 4 ? qVec[4] : 0);
                double qVecNorm = 0;
                for (float v : qVec) qVecNorm += v * v;
                qVecNorm = Math.sqrt(qVecNorm);
                log.warn("[向量诊断] query向量异常: bookId={}, threshold={}, hits={}, topScore={}, queryDim={}, queryNorm={}, querySample={}, currentModel={}",
                        bookId, scoreThreshold, matches.size(),
                        String.format("%.6f", matches.get(0).score()),
                        qVec.length, String.format("%.4f", qVecNorm), qVecSample,
                        currentEmbeddingModelName);
            } else {
                log.debug("Qdrant filter 搜索完成: bookId={}, threshold={}, hits={}, topScore={}, minScore={}",
                        bookId, scoreThreshold, matches.size(),
                        matches.isEmpty() ? "N/A" : String.format("%.4f", matches.get(0).score()),
                        matches.isEmpty() ? "N/A" : String.format("%.4f", matches.get(matches.size() - 1).score()));
            }
            return matches;
        } catch (Exception e) {
            log.warn("Qdrant filter 搜索失败: bookId={}, threshold={} - {}", bookId, scoreThreshold, e.getMessage());
            return List.of();
        }
    }

    /**
     *
     */
    private void diagnoseContentSearch(Long bookId, Embedding queryEmbedding) {
        try {
            var collectionInfo = qdrantClient.getCollectionInfoAsync(qdrantProps.getContentCollection()).get();
            long totalPoints = collectionInfo.getPointsCount();
            log.warn("[诊断] contentCollection 总点数: {}, 搜索 bookId={} 返回 0", totalPoints, bookId);

            if (totalPoints == 0) {
                log.warn("[诊断] 内容集合为空，数据可能未写入");
                return;
            }

            try {
                var checkFilter = buildBookIdFilter(bookId);
                var checkSearch = io.qdrant.client.grpc.Points.SearchPoints.newBuilder()
                        .setCollectionName(qdrantProps.getContentCollection())
                        .setLimit(3)
                        .setScoreThreshold(0.0f)
                        .setFilter(checkFilter)
                        .addAllVector(queryEmbedding.vectorAsList().stream().toList())
                        .setWithPayload(io.qdrant.client.grpc.Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                        .build();
                var checkResults = qdrantClient.searchAsync(checkSearch).get(30, java.util.concurrent.TimeUnit.SECONDS);
                log.warn("[诊断] bookId={} 用 filter + scoreThreshold=0 搜索命中: {} 条", bookId, checkResults.size());
                if (!checkResults.isEmpty()) {
                    var first = checkResults.get(0);
                    var p = first.getPayloadMap();
                    log.warn("[诊断] 首条结果: score={}, bookId={}, textLen={}",
                            first.getScore(),
                            p.containsKey("bookId") ? p.get("bookId").getIntegerValue() : "null",
                            extractPayloadString(p, PAYLOAD_TEXT_KEY) != null ? Objects.requireNonNull(extractPayloadString(p, PAYLOAD_TEXT_KEY)).length() : 0);
                }
            } catch (Exception e) {
                log.warn("[诊断] filter 搜索检查失败: {}", e.getMessage());
            }

            try {
                var noFilterSearch = io.qdrant.client.grpc.Points.SearchPoints.newBuilder()
                        .setCollectionName(qdrantProps.getContentCollection())
                        .setLimit(3)
                        .setScoreThreshold(0.3f)
                        .addAllVector(queryEmbedding.vectorAsList().stream().toList())
                        .setWithPayload(io.qdrant.client.grpc.Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                        .build();
                var noFilterResults = qdrantClient.searchAsync(noFilterSearch).get();
                log.warn("[诊断] 不带 filter 搜索命中: {} 条", noFilterResults.size());
                for (var sp : noFilterResults) {
                    var p = sp.getPayloadMap();
                    var bv = p.get("bookId");
                    String bvType = "null";
                    String bvStr = "null";
                    if (bv != null) {
                        if (bv.hasIntegerValue()) {
                            bvType = "integer";
                            bvStr = String.valueOf(bv.getIntegerValue());
                        } else if (bv.hasStringValue()) {
                            bvType = "string";
                            bvStr = bv.getStringValue();
                        } else if (bv.hasDoubleValue()) {
                            bvType = "double";
                            bvStr = String.valueOf(bv.getDoubleValue());
                        } else {
                            bvType = "other";
                        }
                    }
                    log.warn("[诊断] 采样点: score={}, bookId={} (type={}), 所有key={}",
                            sp.getScore(), bvStr, bvType, p.keySet());
                }
            } catch (Exception e) {
                log.warn("[诊断] 无filter搜索失败: {}", e.getMessage());
            }

            try {
                var httpClient = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(5))
                        .build();
                String scrollBody = """
                        {"filter":{"must":[{"key":"bookId","match":{"value":%d}}]},"limit":1,"with_payload":true,"with_vector":true}"""
                        .formatted(bookId);
                var httpRequest = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create("http://" + qdrantProps.getHost() + ":6333/collections/" + qdrantProps.getContentCollection() + "/points/scroll"))
                        .timeout(java.time.Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(scrollBody));
                if (qdrantProps.getApiKey() != null && !qdrantProps.getApiKey().isBlank()) {
                    httpRequest.header("api-key", qdrantProps.getApiKey());
                }
                var response = httpClient.send(httpRequest.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    var jsonResp = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                            .readTree(response.body());
                    var pointsNode = jsonResp.at("/result/points");
                    if (pointsNode.isArray() && !pointsNode.isEmpty()) {
                        var vectorNode = pointsNode.get(0).at("/vector");
                        if (vectorNode.isArray()) {
                            boolean allZero = true;
                            int checkDims = Math.min(10, vectorNode.size());
                            for (int i = 0; i < checkDims; i++) {
                                if (Math.abs(vectorNode.get(i).asDouble()) > 0.0001) {
                                    allZero = false;
                                    break;
                                }
                            }
                            if (allZero) {
                                log.error("[诊断] bookId={} 的存储向量为全零! 共 {} 维，前 {} 维均为 0。"
                                                + "原因：数据在零向量校验修复前写入。需要重建该书的内容向量。",
                                        bookId, vectorNode.size(), checkDims);
                            } else {
                                log.info("[诊断] bookId={} 的存储向量非零 (前10维有非零值)，向量数据正常", bookId);
                            }
                        }
                    } else {
                        log.warn("[诊断] bookId={} 在 Qdrant 中不存在内容向量数据（scroll 也无结果）", bookId);
                    }
                }
            } catch (Exception e) {
                log.warn("[诊断] 零向量检测失败: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.warn("[诊断] 诊断过程异常: {}", e.getMessage());
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
        StringBuilder sb = new StringBuilder(3000);

        sb.append("书名:").append(book.getTitle() != null ? book.getTitle() : "").append(";");
        sb.append("作者:").append(book.getAuthor() != null ? book.getAuthor() : "").append(";");
        sb.append("评分:").append(book.getRating() != null ? book.getRating() : 0.0).append(";");

        if (book.getFormatTags() != null && !book.getFormatTags().isBlank()) {
            String tags = TAGS_CLEAN_PATTERN.matcher(book.getFormatTags()).replaceAll("").replace(',', '、');
            sb.append("标签:").append(tags).append(";");
        } else {
            sb.append("标签:;");
        }

        if (book.getConceptTags() != null && !book.getConceptTags().isBlank()) {
            String concept = TAGS_CLEAN_PATTERN.matcher(book.getConceptTags()).replaceAll("").replace(',', '、');
            sb.append("核心概念:").append(concept).append(";");
        }

        if (book.getReaderNeedTags() != null && !book.getReaderNeedTags().isBlank()) {
            String readerNeed = TAGS_CLEAN_PATTERN.matcher(book.getReaderNeedTags()).replaceAll("").replace(',', '、');
            sb.append("读者需求:").append(readerNeed).append(";");
        }

        if (book.getTargetReaderTags() != null && !book.getTargetReaderTags().isBlank()) {
            String targetReader = TAGS_CLEAN_PATTERN.matcher(book.getTargetReaderTags()).replaceAll("").replace(',', '、');
            sb.append("目标读者:").append(targetReader).append(";");
        }

        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            sb.append("简介:").append(book.getDescription()).append(";");
        } else {
            sb.append("简介:;");
        }

//        String summary = bookService.resolveBookSummary(book);
//        if (summary != null && !summary.isBlank()) {
//            sb.append("摘要:").append(summary).append(";");
//        } else if (book.getToc() != null && !book.getToc().isBlank()) {
//            sb.append("目录:").append(book.getToc()).append(";");
//        }

        return sb.toString();
    }

    /**
     * 将书籍内容按固定大小分块（带重叠）
     * 高性能版本：预转换 char[] + 简化重叠逻辑
     */
    private List<String> splitContent(String content) {
        ArrayList<String> chunks = new ArrayList<>();
        int contentLen = content.length();
        if (contentLen == 0) return chunks;

        int estimatedChunks = contentLen / (qdrantProps.getChunkSize() - qdrantProps.getChunkOverlap()) + 1;
        chunks.ensureCapacity(estimatedChunks);

        char[] chars = content.toCharArray();

        int pos = 0;
        while (pos < contentLen) {
            int chunkEnd = Math.min(pos + qdrantProps.getChunkSize(), contentLen);

            if (chunkEnd < contentLen) {
                int searchStart = Math.max(pos, chunkEnd - qdrantProps.getChunkOverlap());
                for (int i = chunkEnd - 1; i >= searchStart; i--) {
                    char c = chars[i];
                    if (c == '\n' || c == '。' || c == '！' || c == '？') {
                        chunkEnd = i + 1;
                        break;
                    }
                }
            }

            String chunk = new String(chars, pos, chunkEnd - pos).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            int nextPos = chunkEnd - qdrantProps.getChunkOverlap();
            if (nextPos <= pos) {
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

        try {
            List<String> collections = qdrantClient.listCollectionsAsync().get();
            result.put("qdrant.connected", true);
            result.put("qdrant.collections", collections);
        } catch (Exception e) {
            result.put("qdrant.connected", false);
            result.put("qdrant.error", e.getMessage());
            return result;
        }

        try {
            var bookInfoFuture = qdrantClient.getCollectionInfoAsync(qdrantProps.getBookCollection());
            var contentInfoFuture = qdrantClient.getCollectionInfoAsync(qdrantProps.getContentCollection());

            var bookInfo = bookInfoFuture.get();
            var contentInfo = contentInfoFuture.get();

            result.put("qdrant.bookCollection", Map.of(
                    "name", qdrantProps.getBookCollection(),
                    "vectorCount", bookInfo.getPointsCount(),
                    "vectorSize", bookInfo.getConfig().getParams().getVectorsConfig().getParams().getSize(),
                    "status", bookInfo.getStatus().name()
            ));

            result.put("qdrant.contentCollection", Map.of(
                    "name", qdrantProps.getContentCollection(),
                    "vectorCount", contentInfo.getPointsCount(),
                    "vectorSize", contentInfo.getConfig().getParams().getVectorsConfig().getParams().getSize(),
                    "status", contentInfo.getStatus().name()
            ));
        } catch (Exception e) {
            result.put("qdrant.collectionInfo.error", e.getMessage());
        }

        result.put("embedding.modelInitialized", embeddingModel != null);
        result.put("embedding.bookStoreInitialized", bookEmbeddingStore != null);
        result.put("embedding.contentStoreInitialized", contentEmbeddingStore != null);

        if (embeddingModel == null) {
            try {
                ensureEmbeddingModelInitialized();
                result.put("embedding.modelInitAttempt", embeddingModel != null ? "SUCCESS" : "FAILED");
            } catch (Exception e) {
                result.put("embedding.modelInitAttempt", "FAILED: " + e.getMessage());
            }
        }

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

    // ==================== 全量 Scroll（RoundTableCoverageService 用） ====================

    /**
     * 从 Qdrant gRPC scroll 获取指定书籍的所有内容分块。
     * <p>
     * 使用 gRPC scroll API 分页（200条/页），避免直接调 REST API。
     * 替换 RoundTableCoverageService 之前的 HttpClient 直调方式。
     *
     * @param bookId 书籍 ID
     * @return 内容分块列表（text + pointId）
     */
    public List<ChunkInfo> scrollAllContentChunks(Long bookId) {
        List<ChunkInfo> chunks = new ArrayList<>();
        if (qdrantClient == null) {
            log.warn("QdrantClient 未初始化，跳过 scrollContentChunks: bookId={}", bookId);
            return chunks;
        }

        try {
            var filter = buildBookIdFilter(bookId);
            Common.PointId offset = null;

            do {
                var builder = io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                        .setCollectionName(qdrantProps.getContentCollection())
                        .setFilter(filter)
                        .setLimit(200)
                        .setWithPayload(io.qdrant.client.grpc.Points.WithPayloadSelector.newBuilder().setEnable(true).build());

                var response = qdrantClient.scrollAsync(builder.build())
                        .get(30, java.util.concurrent.TimeUnit.SECONDS);
                var points = response.getResultList();

                for (var point : points) {
                    var payloadMap = point.getPayloadMap();
                    String text = null;
                    if (payloadMap.containsKey(PAYLOAD_TEXT_KEY)) {
                        text = extractPayloadString(payloadMap, PAYLOAD_TEXT_KEY);
                    } else if (payloadMap.containsKey("text")) {
                        text = extractPayloadString(payloadMap, "text");
                    }
                    if (text != null && !text.isBlank()) {
                        chunks.add(new ChunkInfo(text, point.getId().getNum()));
                    }
                }

                offset = response.hasNextPageOffset() ? response.getNextPageOffset() : null;
            } while (offset != null);
        } catch (Exception e) {
            log.warn("从 Qdrant scroll 获取内容分块失败: bookId={} - {}", bookId, e.getMessage());
        }

        return chunks;
    }

    /**
     * 内容分块简略信息（text + pointId），用于全量 scroll 场景
     */
    public record ChunkInfo(String text, long pointId) {
    }
}
