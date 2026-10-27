package com.kbook.service.embedding;

import com.kbook.config.ChatModelFactory;
import com.kbook.config.properties.QdrantProperties;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.embedding.event.RagHitEvent;
import org.springframework.context.ApplicationEventPublisher;
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
 * 向量存储基础设施 — Embedding 模型懒初始化、Qdrant 集合/索引管理、payload 构造、upsert 与过滤检索原语。
 */
@Slf4j
@Service
public class EmbeddingStoreManager {

    private static final String PAYLOAD_TEXT_KEY = "text_segment";

    private final QdrantClient qdrantClient;
    private final ChatModelFactory chatModelFactory;
    private final QdrantProperties qdrantProps;

    private EmbeddingModel embeddingModel;
    private EmbeddingStore<TextSegment> bookEmbeddingStore;
    private EmbeddingStore<TextSegment> contentEmbeddingStore;
    private String currentEmbeddingModelName;
    private Integer currentEmbeddingDim;

    public EmbeddingStoreManager(QdrantClient qdrantClient, ChatModelFactory chatModelFactory,
                                QdrantProperties qdrantProps) {
        this.qdrantClient = qdrantClient;
        this.chatModelFactory = chatModelFactory;
        this.qdrantProps = qdrantProps;
    }

    EmbeddingModel getEmbeddingModel() { return embeddingModel; }
    EmbeddingStore<TextSegment> getBookEmbeddingStore() { return bookEmbeddingStore; }
    EmbeddingStore<TextSegment> getContentEmbeddingStore() { return contentEmbeddingStore; }

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
    synchronized void ensureEmbeddingModelInitialized() {
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
                    currentEmbeddingDim = testResult.content().vector().length;
                    log.info("Embedding 模型初始化验证成功: vectorDim={}", currentEmbeddingDim);
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
     * 当前 embedding 模型标识（baseUrl + "/" + modelName）
     * 用于写入 Book.contentEmbeddingModel 字段，做向量一致性校验
     */
    public String getCurrentEmbeddingModelName() {
        ensureEmbeddingModelInitialized();
        return currentEmbeddingModelName;
    }

    /**
     * 当前 embedding 模型维度
     */
    public Integer getCurrentEmbeddingDim() {
        ensureEmbeddingModelInitialized();
        return currentEmbeddingDim;
    }

    /**
     * 在 Qdrant 中创建 Collection（如果不存在），并配置标量量化
     */
    void createCollectionIfNotExists(String collectionName, boolean enableQuantization) {
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

    /**
     * 构建 Qdrant payload Map（从 TextSegment 的文本和 metadata）
     * 使用 Qdrant 官方 ValueFactory 构建 Value
     */
    Map<String, io.qdrant.client.grpc.JsonWithInt.Value> buildQdrantPayload(TextSegment segment) {
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
    io.qdrant.client.grpc.JsonWithInt.Value toQdrantValue(Object val) {
        if (val instanceof String s) return value(s);
        else if (val instanceof Long l) return value(l);
        else if (val instanceof Integer i) return value(i.longValue());
        else if (val instanceof Double d) return value(d);
        else if (val instanceof Float f) return value(f.doubleValue());
        else if (val instanceof Boolean b) return value(b);
        else return value(String.valueOf(val));
    }

    /**
     * 构建按 bookId 精确匹配的 Qdrant Filter
     */
    io.qdrant.client.grpc.Common.Filter buildBookIdFilter(Long bookId) {
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
    String directUpsertPoint(String collectionName, float[] vector,
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
    void directBatchUpsertPoints(String collectionName,
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
    double vectorNorm(float[] vector) {
        double norm = 0;
        for (float v : vector) norm += v * v;
        return Math.sqrt(norm);
    }

    /**
     * 从 Qdrant payload Map 中提取字符串值
     */
    String extractPayloadString(Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload, String key) {
        io.qdrant.client.grpc.JsonWithInt.Value value = payload.get(key);
        if (value == null) return null;
        if (value.hasStringValue()) return value.getStringValue();
        if (value.hasIntegerValue()) return String.valueOf(value.getIntegerValue());
        if (value.hasDoubleValue()) return String.valueOf(value.getDoubleValue());
        return null;
    }

    /**
     * 计算两个向量的余弦相似度
     */
    double cosineSimilarity(float[] a, float[] b) {
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
    List<Float> floatArrayToList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add(v);
        }
        return list;
    }

    /**
     * 从 Qdrant payload Map 中提取 Long 值
     */
    Long extractPayloadLong(Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload, String key) {
        io.qdrant.client.grpc.JsonWithInt.Value value = payload.get(key);
        if (value == null) return null;
        if (value.hasIntegerValue()) return value.getIntegerValue();
        if (value.hasDoubleValue()) return (long) value.getDoubleValue();
        return null;
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
     * 执行带 filter 的 Qdrant 向量搜索
     */
    List<EmbeddingMatch<TextSegment>> doFilterSearch(
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
                Long startChar = extractPayloadLong(payload, "startChar");
                if (startChar != null) {
                    metadata.put("startChar", startChar);
                }
                Long endChar = extractPayloadLong(payload, "endChar");
                if (endChar != null) {
                    metadata.put("endChar", endChar);
                }
                String cutReason = extractPayloadString(payload, "cutReason");
                if (cutReason != null) {
                    metadata.put("cutReason", cutReason);
                }
                String chapterTitle = extractPayloadString(payload, "chapterTitle");
                if (chapterTitle != null) {
                    metadata.put("chapterTitle", chapterTitle);
                }
                Long chapterIndex = extractPayloadLong(payload, "chapterIndex");
                if (chapterIndex != null) {
                    metadata.put("chapterIndex", chapterIndex);
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
}
