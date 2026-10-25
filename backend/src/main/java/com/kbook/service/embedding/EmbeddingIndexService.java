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
 * 向量生成与维护 — 书籍元数据/内容分块向量的生成、写入、清理、重建、统计与滚动读取。
 */
@Slf4j
@Service
public class EmbeddingIndexService {

    /** 内容向量批量 embed 的批次大小 */
    private static final int EMBED_BATCH_SIZE = 20;
    private static final String PAYLOAD_TEXT_KEY = "text_segment";
    private static final java.util.regex.Pattern TAGS_CLEAN_PATTERN = java.util.regex.Pattern.compile("[\\[\\]\"]");

    private final EmbeddingStoreManager storeManager;
    private final BookRepository bookRepository;
    private final QdrantProperties qdrantProps;
    private final io.qdrant.client.QdrantClient qdrantClient;

    public EmbeddingIndexService(EmbeddingStoreManager storeManager, BookRepository bookRepository,
                                QdrantProperties qdrantProps,
                                io.qdrant.client.QdrantClient qdrantClient) {
        this.storeManager = storeManager;
        this.bookRepository = bookRepository;
        this.qdrantProps = qdrantProps;
        this.qdrantClient = qdrantClient;
    }

    /**
     * 为书籍生成元数据向量并存储到 Qdrant
     * 元数据 = 标题 + 作者 + 标签 + 简介 → 1个 embedding
     */
    public void generateBookEmbedding(Book book) {
        if (book == null) return;
        try {
            storeManager.ensureEmbeddingModelInitialized();
            if (storeManager.getEmbeddingModel() == null) {
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

        Embedding embedding = storeManager.getEmbeddingModel().embed(metadataText).content();
        float[] vector = embedding.vector();
        double norm = storeManager.vectorNorm(vector);
        if (norm < 0.001) {
            log.error("生成的 embedding 向量为零! bookId={}, model={}, norm={}",
                    bookId, storeManager.getCurrentEmbeddingModelName(), String.format("%.6f", norm));
            return;
        }

        TextSegment segment = TextSegment.from(metadataText,
                new Metadata()
                        .put("bookId", bookId)
                        .put("storeManager.getEmbeddingModel()", storeManager.getCurrentEmbeddingModelName()));

        removeBookEmbedding(bookId);

        Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload = storeManager.buildQdrantPayload(segment);
        String id = storeManager.directUpsertPoint(qdrantProps.getBookCollection(), vector, payload);

        boolean exists = hasBookEmbedding(bookId);
        log.info("书籍元数据向量生成完成: bookId={}, textLen={}, storeId={}, qdrantVerified={}, vectorNorm={}",
                bookId, metadataText.length(), id, exists, String.format("%.4f", norm));
    }

    /**
     * 为图书生成 RAG 内容向量（返回 chunk 数量）
     * <p>
     * 单线程顺序处理所有 batch。
     */
    public int generateContentEmbeddingWithCount(Long bookId, String content) {
        try {
            storeManager.ensureEmbeddingModelInitialized();
            if (storeManager.getEmbeddingModel() == null) {
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
                                .put("storeManager.getEmbeddingModel()", storeManager.getCurrentEmbeddingModelName())));
            }

            // 批量 embed
            List<Embedding> embeddings = storeManager.getEmbeddingModel().embedAll(segments).content();

            List<float[]> vectors = new ArrayList<>(embeddings.size());
            List<Map<String, JsonWithInt.Value>> payloads = new ArrayList<>(embeddings.size());

            for (int i = 0; i < embeddings.size(); i++) {
                float[] vec = embeddings.get(i).vector();
                double vNorm = storeManager.vectorNorm(vec);
                if (vNorm < 0.001) {
                    log.warn("chunkIndex={} 零向量，跳过: bookId={}", batchStart + i, bookId);
                    continue;
                }
                vectors.add(vec);
                payloads.add(storeManager.buildQdrantPayload(segments.get(i)));
            }

            if (!vectors.isEmpty()) {
                storeManager.directBatchUpsertPoints(qdrantProps.getContentCollection(), vectors, payloads);
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
                                    .put("storeManager.getEmbeddingModel()", storeManager.getCurrentEmbeddingModelName()));
                    Embedding embedding = storeManager.getEmbeddingModel().embed(segment).content();

                    float[] vec = embedding.vector();
                    double vNorm = storeManager.vectorNorm(vec);
                    if (vNorm < 0.001) {
                        log.warn("chunkIndex={} 零向量，跳过: bookId={}", globalIndex, bookId);
                        continue;
                    }
                    fallbackPayloads.add(storeManager.buildQdrantPayload(segment));
                    fallbackVectors.add(vec);

                    if (fallbackVectors.size() >= EMBED_BATCH_SIZE || i == batchChunks.size() - 1) {
                        storeManager.directBatchUpsertPoints(qdrantProps.getContentCollection(), fallbackVectors, fallbackPayloads);
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
        storeManager.ensureEmbeddingModelInitialized();

        result.put("currentModel", storeManager.getCurrentEmbeddingModelName());
        result.put("modelInitialized", storeManager.getEmbeddingModel() != null);

        if (storeManager.getEmbeddingModel() == null) {
            result.put("error", "Embedding 模型未初始化");
            return result;
        }

        try {
            // ========== 第 1 步：稳定性测试 ==========
            String testText = "这是一个测试文本用于验证embedding模型的稳定性";
            float[] emb1 = storeManager.getEmbeddingModel().embed(testText).content().vector();
            float[] emb2 = storeManager.getEmbeddingModel().embed(testText).content().vector();
            double selfSimilarity = storeManager.cosineSimilarity(emb1, emb2);
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
                            var modelNode = payloadNode.get("storeManager.getEmbeddingModel()");
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
            result.put("step2_storedVector.currentModel", storeManager.getCurrentEmbeddingModelName());
            result.put("step2_storedVector.modelMatch", storedModel != null && storedModel.equals(storeManager.getCurrentEmbeddingModelName()));
            result.put("step2_storedVector.textLength", storedText != null ? storedText.length() : 0);
            result.put("step2_storedVector.textPreview", storedText);

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
                    float[] freshVector = storeManager.getEmbeddingModel().embed(reEmbedText).content().vector();
                    result.put("step3_directCompare.freshVectorDim", freshVector.length);
                    result.put("step3_directCompare.freshVectorSample", String.format("[%.4f, %.4f, %.4f, %.4f, %.4f]",
                            freshVector[0], freshVector.length > 1 ? freshVector[1] : 0,
                            freshVector.length > 2 ? freshVector[2] : 0, freshVector.length > 3 ? freshVector[3] : 0,
                            freshVector.length > 4 ? freshVector[4] : 0));
                    double freshNorm = 0;
                    for (float v : freshVector) freshNorm += v * v;
                    freshNorm = Math.sqrt(freshNorm);
                    result.put("step3_directCompare.freshVectorNorm", String.format("%.4f", freshNorm));

                    double directSimilarity = storeManager.cosineSimilarity(storedVector, freshVector);
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
                Embedding reQueryEmbedding = storeManager.getEmbeddingModel().embed(reEmbedText).content();

                var reSearchPoints = io.qdrant.client.grpc.Points.SearchPoints.newBuilder()
                        .setCollectionName(qdrantProps.getContentCollection())
                        .setLimit(5)
                        .setScoreThreshold(0.0f)
                        .setFilter(storeManager.buildBookIdFilter(bookId))
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
     * 获取指定书籍已存储向量的 embedding 模型标识
     * 从已有的低分搜索结果中提取 payload 的 storeManager.getEmbeddingModel() 字段
     */
    String getStoredEmbeddingModelName(Long bookId, List<EmbeddingMatch<TextSegment>> matches) {
        // 优先从搜索结果中提取
        if (matches != null && !matches.isEmpty()) {
            var first = matches.get(0);
            if (first.embedded() != null && first.embedded().metadata() != null) {
                String model = first.embedded().metadata().getString("storeManager.getEmbeddingModel()");
                if (model != null) return model;
            }
        }
        // 回退：用 Qdrant 搜索获取 payload
        try {
            if (qdrantClient == null) return null;
            var filter = storeManager.buildBookIdFilter(bookId);
            // 构造一个非零向量做搜索（全零向量在某些 Qdrant 版本上可能搜不到结果）
            float[] probeVector = new float[qdrantProps.getVectorDimension()];
            probeVector[0] = 0.01f;
            var searchPoints = io.qdrant.client.grpc.Points.SearchPoints.newBuilder()
                    .setCollectionName(qdrantProps.getContentCollection())
                    .setLimit(1)
                    .setScoreThreshold(0.0f)
                    .setFilter(filter)
                    .addAllVector(storeManager.floatArrayToList(probeVector))
                    .setWithPayload(io.qdrant.client.grpc.Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    .build();
            var results = qdrantClient.searchAsync(searchPoints).get(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!results.isEmpty()) {
                var payload = results.get(0).getPayloadMap();
                return storeManager.extractPayloadString(payload, "storeManager.getEmbeddingModel()");
            }
        } catch (Exception e) {
            log.debug("获取存储向量模型标识失败: bookId={} - {}", bookId, e.getMessage());
        }
        return null;
    }

    /**
     * 获取 kbook_books 向量库的总条目数
     */
    public long getTotalBookEmbeddingCount() {
        try {
            if (qdrantClient == null) return 0;
            var info = qdrantClient.getCollectionInfoAsync(qdrantProps.getBookCollection()).get(15, java.util.concurrent.TimeUnit.SECONDS);
            return info.getPointsCount();
        } catch (Exception e) {
            log.warn("获取书籍向量总数失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 清空所有书籍元数据向量（kbook_books 集合）
     */
    public long clearAllBookEmbeddings() {
        try {
            if (qdrantClient == null) {
                log.warn("Qdrant 客户端未初始化");
                return 0;
            }
            long totalCount = qdrantClient.countAsync(qdrantProps.getBookCollection()).get(30, java.util.concurrent.TimeUnit.SECONDS);
            log.info("开始清空书籍向量库: totalVectors={}", totalCount);

            qdrantClient.deleteAsync(
                    qdrantProps.getBookCollection(),
                    Common.Filter.newBuilder().build()
            ).get(60, java.util.concurrent.TimeUnit.SECONDS);

            log.info("书籍向量库已清空: deletedVectors={}", totalCount);
            return totalCount;
        } catch (Exception e) {
            log.error("清空书籍向量库失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 全量刷新 kbook_books 向量库
     * 清空后逐本重新生成元数据向量
     *
     * @param progressCallback 进度回调 (processed, total)
     */
    public void rebuildAllBookEmbeddings(java.util.function.BiConsumer<Long, Long> progressCallback) {
        storeManager.ensureEmbeddingModelInitialized();
        if (storeManager.getEmbeddingModel() == null) {
            throw new RuntimeException("Embedding 模型未初始化，无法重建书籍向量");
        }

        long totalBooks = bookRepository.count();
        log.info("开始全量重建书籍向量: totalBooks={}", totalBooks);

        clearAllBookEmbeddings();

        int batchSize = 50;
        long processed = 0;
        long failed = 0;
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, batchSize);

        while (true) {
            var page = bookRepository.findAllByOrderByIdAsc(pageable);
            if (page.isEmpty()) break;

            for (Book book : page.getContent()) {
                try {
                    upsertBookEmbedding(book);
                    processed++;
                } catch (Exception e) {
                    log.error("书籍向量生成失败: bookId={}, title={}", book.getId(), book.getTitle(), e);
                    failed++;
                }
                if (processed % 10 == 0 || processed == totalBooks) {
                    progressCallback.accept(processed, totalBooks);
                }
            }

            if (page.isLast()) break;
            pageable = page.nextPageable();
        }

        log.info("全量重建书籍向量完成: processed={}, failed={}, total={}", processed, failed, totalBooks);
    }

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
     * 检查指定书籍是否已有元数据向量（通过 Qdrant 精确查询 bookId）
     */
    public boolean hasBookEmbedding(Long bookId) {
        try {
            if (qdrantClient == null) return false;
            Long count = qdrantClient.countAsync(qdrantProps.getBookCollection(), storeManager.buildBookIdFilter(bookId), true).get(15, java.util.concurrent.TimeUnit.SECONDS);
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
            Long count = qdrantClient.countAsync(qdrantProps.getContentCollection(), storeManager.buildBookIdFilter(bookId), true).get(15, java.util.concurrent.TimeUnit.SECONDS);
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
            qdrantClient.deleteAsync(qdrantProps.getBookCollection(), storeManager.buildBookIdFilter(bookId)).get(30, java.util.concurrent.TimeUnit.SECONDS);
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
            qdrantClient.deleteAsync(qdrantProps.getContentCollection(), storeManager.buildBookIdFilter(bookId)).get(30, java.util.concurrent.TimeUnit.SECONDS);
            log.debug("已删除旧内容向量: bookId={}", bookId);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("删除旧内容向量超时，跳过: bookId={} - {}", bookId, e.getMessage());
        } catch (Exception e) {
            log.debug("删除旧内容向量失败（可能不存在）: bookId={} - {}", bookId, e.getMessage());
        }
    }

    /**
     * 获取 kbook_content 中有向量的不重复书籍数量
     */
    public long getContentEmbeddedBookCount() {
        try {
            if (qdrantClient == null) return 0;
            var collectionInfo = qdrantClient.getCollectionInfoAsync(qdrantProps.getContentCollection()).get(15, java.util.concurrent.TimeUnit.SECONDS);
            return collectionInfo.getPointsCount() > 0 ? bookRepository.countByContentEmbeddedTrue() : 0;
        } catch (Exception e) {
            log.warn("获取内容向量书籍数失败: {}", e.getMessage());
            return 0;
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

        return sb.toString();
    }

    /**
     * 将书籍内容按固定大小分块（带重叠，段落感知）
     * <p>
     * 以 chunkSize 字符为目标长度，切点优先选段落边界（\n\n），其次行边界（\n），
     * 再次句末标点（。！？），最后才硬切。搜索窗口允许略超 chunkSize（+100），
     * 用小幅长度波动换取完整段落，避免语义单元被腰斩。
     */
    private List<String> splitContent(String content) {
        ArrayList<String> chunks = new ArrayList<>();
        int contentLen = content.length();
        if (contentLen == 0) return chunks;

        int chunkSize = qdrantProps.getChunkSize();
        int chunkOverlap = qdrantProps.getChunkOverlap();
        int estimatedChunks = contentLen / (chunkSize - chunkOverlap) + 1;
        chunks.ensureCapacity(estimatedChunks);

        char[] chars = content.toCharArray();
        // 略超 chunkSize 寻找段落边界，换语义完整性
        int lookahead = Math.min(100, chunkOverlap / 2);

        int pos = 0;
        while (pos < contentLen) {
            int chunkEnd = Math.min(pos + chunkSize, contentLen);

            if (chunkEnd < contentLen) {
                // 向前回退窗口：先尝试段落/行边界，再退到句末标点
                int backStart = Math.max(pos, chunkEnd - chunkOverlap);
                // 向后延伸窗口：若超出 chunkEnd 不多，可接受以落在段落边界
                int fwdEnd = Math.min(contentLen, chunkEnd + lookahead);

                int cut = findChunkBoundary(chars, backStart, chunkEnd, fwdEnd);
                if (cut > 0) {
                    chunkEnd = cut;
                }
                // cut==0 表示未找到任何边界，保持硬切在 chunkSize
            }

            String chunk = new String(chars, pos, chunkEnd - pos).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            int nextPos = chunkEnd - chunkOverlap;
            if (nextPos <= pos) {
                pos = chunkEnd;
            } else {
                pos = nextPos;
            }
        }

        log.debug("分块完成: totalChars={}, chunks={}", contentLen, chunks.size());
        return chunks;
    }

    /**
     * 在 [backStart, fwdEnd) 范围内寻找最佳切点（chunkEnd 的调整值）。
     * <p>
     * 优先级（高 → 低）：
     * 1. 向后延伸 [chunkEnd, fwdEnd) 内的 \n\n（段落边界，允许 chunk 略长）
     * 2. 回退 [backStart, chunkEnd) 内的 \n\n
     * 3. 回退 [backStart, chunkEnd) 内的 \n（行边界）
     * 4. 回退 [backStart, chunkEnd) 内的 句末标点（。！？）
     * <p>
     * 返回切点之后的索引（即新 chunkEnd），未找到返回 0。
     */
    private int findChunkBoundary(char[] chars, int backStart, int chunkEnd, int fwdEnd) {
        // 1. 向后延伸找段落边界（\n\n），允许略超 chunkSize 换取完整段落
        for (int i = chunkEnd; i < fwdEnd - 1; i++) {
            if (chars[i] == '\n' && chars[i + 1] == '\n') {
                return i + 2;
            }
        }
        // 2. 回退找段落边界（\n\n）
        for (int i = chunkEnd - 2; i >= backStart; i--) {
            if (chars[i] == '\n' && chars[i + 1] == '\n') {
                return i + 2;
            }
        }
        // 3. 回退找行边界（\n）
        for (int i = chunkEnd - 1; i >= backStart; i--) {
            if (chars[i] == '\n') {
                return i + 1;
            }
        }
        // 4. 回退找句末标点（。！？）
        for (int i = chunkEnd - 1; i >= backStart; i--) {
            char c = chars[i];
            if (c == '。' || c == '！' || c == '？') {
                return i + 1;
            }
        }
        return 0;
    }

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

        result.put("embedding.modelInitialized", storeManager.getEmbeddingModel() != null);
        result.put("embedding.bookStoreInitialized", storeManager.getBookEmbeddingStore() != null);
        result.put("embedding.contentStoreInitialized", storeManager.getContentEmbeddingStore() != null);

        if (storeManager.getEmbeddingModel() == null) {
            try {
                storeManager.ensureEmbeddingModelInitialized();
                result.put("embedding.modelInitAttempt", storeManager.getEmbeddingModel() != null ? "SUCCESS" : "FAILED");
            } catch (Exception e) {
                result.put("embedding.modelInitAttempt", "FAILED: " + e.getMessage());
            }
        }

        if (storeManager.getEmbeddingModel() != null) {
            try {
                var testEmbed = storeManager.getEmbeddingModel().embed("测试Embedding调用");
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
            var filter = storeManager.buildBookIdFilter(bookId);
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
                        text = storeManager.extractPayloadString(payloadMap, PAYLOAD_TEXT_KEY);
                    } else if (payloadMap.containsKey("text")) {
                        text = storeManager.extractPayloadString(payloadMap, "text");
                    }
                    if (text != null && !text.isBlank()) {
                        Long idxLong = storeManager.extractPayloadLong(payloadMap, "chunkIndex");
                        int chunkIndex = idxLong != null ? idxLong.intValue() : -1;
                        chunks.add(new ChunkInfo(text, point.getId().getNum(), chunkIndex));
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
     * 内容分块简略信息（text + pointId + chunkIndex），用于全量 scroll 场景
     */
    public record ChunkInfo(String text, long pointId, int chunkIndex) {
    }

    /**
     * 获取指定书籍的内容向量分块数（用于列表型问题 RAG 策略选择）。
     *
     * @param bookId 书籍 ID
     * @return 分块数量；查询失败返回 0
     */
    public long countContentChunks(Long bookId) {
        if (qdrantClient == null) return 0;
        try {
            Long count = qdrantClient.countAsync(
                    qdrantProps.getContentCollection(),
                    storeManager.buildBookIdFilter(bookId),
                    true).get(15, java.util.concurrent.TimeUnit.SECONDS);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.warn("获取内容分块数失败: bookId={} - {}", bookId, e.getMessage());
            return 0;
        }
    }

    /**
     * 按 chunkIndex 范围 scroll 内容分块（用于列表型问题档 2/档 3 的邻域扩展）。
     * 完全绕过向量相似度，只按位置范围拉取。
     *
     * @param bookId 书籍 ID
     * @param minIdx 起始 chunkIndex（含）
     * @param maxIdx 结束 chunkIndex（含）
     * @return 范围内的内容分块（按 chunkIndex 升序）
     */
    public List<ChunkInfo> scrollChunksByIndexRange(Long bookId, int minIdx, int maxIdx) {
        List<ChunkInfo> chunks = new ArrayList<>();
        if (qdrantClient == null) {
            log.warn("QdrantClient 未初始化，跳过 scrollChunksByIndexRange: bookId={}", bookId);
            return chunks;
        }
        if (minIdx > maxIdx) return chunks;

        try {
            // 复合 filter：bookId 精确匹配 + chunkIndex 范围
            // 用 ConditionFactory 工厂方法（与 buildBookIdFilter 一致），Range 字段是 double 类型
            io.qdrant.client.grpc.Common.Filter filter = io.qdrant.client.grpc.Common.Filter.newBuilder()
                    .addMust(match("bookId", bookId))
                    .addMust(io.qdrant.client.ConditionFactory.range("chunkIndex",
                            io.qdrant.client.grpc.Common.Range.newBuilder()
                                    .setGte((double) minIdx)
                                    .setLte((double) maxIdx)
                                    .build()))
                    .build();

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
                        text = storeManager.extractPayloadString(payloadMap, PAYLOAD_TEXT_KEY);
                    } else if (payloadMap.containsKey("text")) {
                        text = storeManager.extractPayloadString(payloadMap, "text");
                    }
                    if (text == null || text.isBlank()) continue;
                    Long idxLong = storeManager.extractPayloadLong(payloadMap, "chunkIndex");
                    int chunkIndex = idxLong != null ? idxLong.intValue() : -1;
                    chunks.add(new ChunkInfo(text, point.getId().getNum(), chunkIndex));
                }

                offset = response.hasNextPageOffset() ? response.getNextPageOffset() : null;
            } while (offset != null);

            // 按 chunkIndex 升序排序，保证阅读顺序
            chunks.sort(java.util.Comparator.comparingInt(ChunkInfo::chunkIndex));
        } catch (Exception e) {
            log.warn("按 chunkIndex 范围 scroll 失败: bookId={}, range=[{}-{}] - {}",
                    bookId, minIdx, maxIdx, e.getMessage());
        }

        return chunks;
    }
}
