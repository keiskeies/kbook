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
 * 向量检索 — 相似书籍检索、内容片段检索（含查询扩展与领域上下文）、按 chunkIndex 定位。
 */
@Slf4j
@Service
public class EmbeddingSearchService {

    private final EmbeddingStoreManager storeManager;
    private final EmbeddingIndexService indexService;
    private final ApplicationEventPublisher eventPublisher;
    private final io.qdrant.client.QdrantClient qdrantClient;
    private final QdrantProperties qdrantProps;
    private static final String PAYLOAD_TEXT_KEY = "text_segment";

    public EmbeddingSearchService(EmbeddingStoreManager storeManager,
                                 EmbeddingIndexService indexService,
                                 ApplicationEventPublisher eventPublisher,
                                 io.qdrant.client.QdrantClient qdrantClient,
                                 QdrantProperties qdrantProps) {
        this.storeManager = storeManager;
        this.indexService = indexService;
        this.eventPublisher = eventPublisher;
        this.qdrantClient = qdrantClient;
        this.qdrantProps = qdrantProps;
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
        storeManager.ensureEmbeddingModelInitialized();
        if (storeManager.getEmbeddingModel() == null || storeManager.getBookEmbeddingStore() == null) {
            log.warn("Embedding 模型或 Store 未初始化，无法执行向量搜索");
            return List.of();
        }

        try {
            Embedding queryEmbedding = storeManager.getEmbeddingModel().embed(queryText).content();

            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults)
                    .minScore(minScore)
                    .build();

            List<EmbeddingMatch<TextSegment>> matches = storeManager.getBookEmbeddingStore().search(request)
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
        storeManager.ensureEmbeddingModelInitialized();
        if (storeManager.getEmbeddingModel() == null) {
            return List.of();
        }

        List<EmbeddingMatch<TextSegment>> results = List.of();
        try {
            String expandedQuery = expandQueryWithContext(query, book);
            Embedding queryEmbedding = storeManager.getEmbeddingModel().embed(expandedQuery).content();

            if (book != null && qdrantClient != null) {
                results = searchContentWithFilter(queryEmbedding, maxResults, book.getId());
            } else if (storeManager.getContentEmbeddingStore() != null) {
                EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(maxResults)
                        .minScore(0.2)
                        .build();
                results = storeManager.getContentEmbeddingStore().search(request).matches();
            }

            // 记录命中统计（有结果 = 命中，无结果 = 未命中）
            // 通过事件解耦：EmbeddingService 发布 RagHitEvent，RagHitStatisticsService 监听处理
            if (book != null) {
                if (results.isEmpty()) {
                    eventPublisher.publishEvent(RagHitEvent.miss(book.getId()));
                } else {
                    double topScore = results.stream()
                            .mapToDouble(EmbeddingMatch::score)
                            .max()
                            .orElse(0.0);
                    eventPublisher.publishEvent(RagHitEvent.hit(book.getId(), topScore));
                }
            }

            return results;
        } catch (Exception e) {
            log.error("RAG 内容检索失败: {}", e.getMessage());
            // 异常时也记录未命中
            if (book != null) {
                eventPublisher.publishEvent(RagHitEvent.miss(book.getId()));
            }
            return List.of();
        }
    }

    /**
     * 按 chunkIndex 精确拉取内容分块（用于邻域扩展）。
     * 完全绕过向量相似度，按位置精确拉取。
     *
     * @param bookId     书籍 ID
     * @param chunkIndex 目标 chunkIndex
     * @return 匹配的分块；不存在返回 null
     */
    public EmbeddingMatch<TextSegment> searchContentByChunkIndex(Long bookId, int chunkIndex) {
        if (qdrantClient == null) return null;
        try {
            var filter = io.qdrant.client.grpc.Common.Filter.newBuilder()
                    .addMust(match("bookId", bookId))
                    .addMust(match("chunkIndex", chunkIndex))
                    .build();

            var builder = io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                    .setCollectionName(qdrantProps.getContentCollection())
                    .setFilter(filter)
                    .setLimit(1)
                    .setWithPayload(io.qdrant.client.grpc.Points.WithPayloadSelector.newBuilder().setEnable(true).build());

            var response = qdrantClient.scrollAsync(builder.build())
                    .get(15, java.util.concurrent.TimeUnit.SECONDS);
            var points = response.getResultList();
            if (points.isEmpty()) return null;

            var point = points.get(0);
            var payloadMap = point.getPayloadMap();
            String text = null;
            if (payloadMap.containsKey(PAYLOAD_TEXT_KEY)) {
                text = storeManager.extractPayloadString(payloadMap, PAYLOAD_TEXT_KEY);
            } else if (payloadMap.containsKey("text")) {
                text = storeManager.extractPayloadString(payloadMap, "text");
            }
            if (text == null || text.isBlank()) return null;

            Metadata metadata = new Metadata();
            metadata.put("bookId", bookId);
            metadata.put("chunkIndex", chunkIndex);
            if (payloadMap.containsKey("chapterIdx")) {
                Long chapterIdx = storeManager.extractPayloadLong(payloadMap, "chapterIdx");
                if (chapterIdx != null) metadata.put("chapterIdx", chapterIdx);
            }
            TextSegment segment = TextSegment.from(text, metadata);
            // score 设为 0，邻域扩展的 score 由调用方折扣后赋值
            return new EmbeddingMatch<>(0.0, String.valueOf(point.getId().getNum()), null, segment);
        } catch (Exception e) {
            log.warn("按 chunkIndex 查询失败: bookId={}, chunkIndex={} - {}", bookId, chunkIndex, e.getMessage());
            return null;
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
            var filter = storeManager.buildBookIdFilter(bookId);

            // 先用 0.2 阈值搜索（内容检索场景短查询 vs 长段落，0.3 偏严）
            List<EmbeddingMatch<TextSegment>> matches = storeManager.doFilterSearch(queryEmbedding, maxResults, bookId, filter, 0.2f);

            // 如果无结果，降级到 0 阈值重试
            if (matches.isEmpty()) {
                log.debug("scoreThreshold=0.2 无结果，降级到 0 阈值重试: bookId={}", bookId);
                matches = storeManager.doFilterSearch(queryEmbedding, maxResults, bookId, filter, 0.0f);
                if (!matches.isEmpty()) {
                    float topScore = matches.get(0).score().floatValue();
                    if (topScore < 0.1) {
                        // score 极低，检查是否向量模型不匹配
                        String storedModel = indexService.getStoredEmbeddingModelName(bookId, matches);
                        if (storedModel != null && !storedModel.equals(storeManager.getCurrentEmbeddingModelName())) {
                            log.warn("RAG 检索 score 极低 (最高={}), bookId={} — 向量模型不匹配! 存储模型={}, 当前模型={}, 需要重建向量",
                                    String.format("%.4f", topScore), bookId, storedModel, storeManager.getCurrentEmbeddingModelName());
                        } else {
                            log.warn("RAG 检索 score 极低 (最高={}), bookId={} — 存储模型={}, 当前模型={}, 可能是向量维度异常或量化问题",
                                    String.format("%.4f", topScore), bookId, storedModel, storeManager.getCurrentEmbeddingModelName());
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
                return storeManager.getContentEmbeddingStore().search(request).matches().stream()
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
                var checkFilter = storeManager.buildBookIdFilter(bookId);
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
                            storeManager.extractPayloadString(p, PAYLOAD_TEXT_KEY) != null ? Objects.requireNonNull(storeManager.extractPayloadString(p, PAYLOAD_TEXT_KEY)).length() : 0);
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
}
