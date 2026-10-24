package com.kbook.service.note;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.config.ChatModelFactory;
import com.kbook.config.properties.QdrantProperties;
import com.kbook.dto.note.NoteSourceSnapshot;
import com.kbook.entity.Note;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.qdrant.client.ConditionFactory.match;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

/**
 * 笔记向量服务 — 把用户的私人笔记写入独立的 Qdrant 集合 {@code kbook_user_notes}，
 * 支撑「按语义找自己的笔记」。与书籍向量完全隔离，所有检索强制按 userId 过滤。
 * <p>
 * 全部方法 best-effort：向量库不可用或模型未配置时只记日志，绝不影响笔记本身的增删改。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteEmbeddingService {

    private static final String PAYLOAD_TEXT_KEY = "text_segment";
    private static final int MAX_TEXT_CHARS = 2000;

    private final QdrantClient qdrantClient;
    private final ChatModelFactory chatModelFactory;
    private final QdrantProperties qdrantProps;
    private final ObjectMapper objectMapper;

    private EmbeddingModel embeddingModel;

    /** 命中结果：笔记 ID + 相似度 */
    public record NoteHit(Long noteId, double score) {
    }

    @PostConstruct
    public void init() {
        if (qdrantClient == null) {
            log.warn("QdrantClient 未初始化，笔记向量功能不可用");
            return;
        }
        try {
            String name = qdrantProps.getUserNoteCollection();
            List<String> names = qdrantClient.listCollectionsAsync().get(10, TimeUnit.SECONDS);
            if (!names.contains(name)) {
                var params = VectorParams.newBuilder()
                        .setSize(qdrantProps.getVectorDimension())
                        .setDistance(Distance.Cosine)
                        .setOnDisk(qdrantProps.isVectorsOnDisk())
                        .build();
                var request = io.qdrant.client.grpc.Collections.CreateCollection.newBuilder()
                        .setCollectionName(name)
                        .setVectorsConfig(VectorsConfig.newBuilder().setParams(params).build())
                        .build();
                qdrantClient.createCollectionAsync(request).get(10, TimeUnit.SECONDS);
                log.info("Qdrant 笔记向量集合已创建: {}", name);
            }
        } catch (Exception e) {
            log.warn("笔记向量集合初始化失败: {}", e.getMessage());
        }
    }

    /**
     * 写入/更新一条笔记向量（确定性 pointId，重复写幂等）
     */
    public void index(Note note) {
        if (note == null || note.getId() == null || !ensureModel()) return;
        try {
            String text = buildNoteText(note);
            if (text == null || text.isBlank()) return;
            float[] vector = embeddingModel.embed(text).content().vector();
            if (vector == null || vector.length == 0) return;

            Map<String, Value> payload = new HashMap<>();
            payload.put(PAYLOAD_TEXT_KEY, value(text));
            payload.put("userId", value(note.getUserId()));
            payload.put("noteId", value(note.getId()));

            PointStruct point = PointStruct.newBuilder()
                    .setId(id(pointId(note.getId())))
                    .setVectors(vectors(vector))
                    .putAllPayload(payload)
                    .build();
            qdrantClient.upsertAsync(qdrantProps.getUserNoteCollection(), List.of(point)).get(10, TimeUnit.SECONDS);
            log.debug("笔记向量已写入: noteId={}", note.getId());
        } catch (Exception e) {
            log.warn("笔记向量写入失败: noteId={} - {}", note.getId(), e.getMessage());
        }
    }

    /**
     * 删除一条笔记向量
     */
    public void remove(Long noteId) {
        if (noteId == null || qdrantClient == null) return;
        try {
            var filter = io.qdrant.client.grpc.Common.Filter.newBuilder()
                    .addMust(match("noteId", noteId))
                    .build();
            qdrantClient.deleteAsync(qdrantProps.getUserNoteCollection(), filter).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("笔记向量删除失败: noteId={} - {}", noteId, e.getMessage());
        }
    }

    /**
     * 在当前用户的笔记里做语义检索，按相关度返回 noteId
     */
    public List<NoteHit> search(Long userId, String query, int limit) {
        if (query == null || query.isBlank() || !ensureModel()) return List.of();
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            var filter = io.qdrant.client.grpc.Common.Filter.newBuilder()
                    .addMust(match("userId", userId))
                    .build();
            SearchPoints request = SearchPoints.newBuilder()
                    .setCollectionName(qdrantProps.getUserNoteCollection())
                    .setLimit(limit)
                    .setScoreThreshold(0.2f)
                    .setFilter(filter)
                    .addAllVector(queryEmbedding.vectorAsList())
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                    .build();

            List<ScoredPoint> points = qdrantClient.searchAsync(request).get(10, TimeUnit.SECONDS);
            List<NoteHit> hits = new ArrayList<>(points.size());
            for (ScoredPoint point : points) {
                Value noteId = point.getPayloadMap().get("noteId");
                if (noteId != null && noteId.hasIntegerValue()) {
                    hits.add(new NoteHit(noteId.getIntegerValue(), point.getScore()));
                }
            }
            return hits;
        } catch (Exception e) {
            log.warn("笔记语义检索失败: userId={} - {}", userId, e.getMessage());
            return List.of();
        }
    }

    // ==================== 内部方法 ====================

    private synchronized boolean ensureModel() {
        if (embeddingModel != null) return true;
        try {
            EmbeddingModel model = chatModelFactory.buildDefaultEmbeddingModel();
            if (model == null) {
                log.warn("Embedding 模型未配置，笔记向量功能暂不可用");
                return false;
            }
            var test = model.embed("测试");
            if (test == null || test.content() == null) {
                log.warn("Embedding 模型自检失败，笔记向量功能暂不可用");
                return false;
            }
            embeddingModel = model;
            log.info("笔记 Embedding 模型初始化成功: dim={}", test.content().vector().length);
            return true;
        } catch (Exception e) {
            log.warn("笔记 Embedding 模型初始化失败: {}", e.getMessage());
            return false;
        }
    }

    private UUID pointId(Long noteId) {
        return UUID.nameUUIDFromBytes(("user_note:" + noteId).getBytes(StandardCharsets.UTF_8));
    }

    private String buildNoteText(Note note) {
        StringBuilder sb = new StringBuilder();
        if (note.getBookTitle() != null) {
            sb.append("《").append(note.getBookTitle()).append("》 ");
        }
        if (note.getTitle() != null) {
            sb.append(note.getTitle()).append("\n");
        }
        if (note.getContentMd() != null && !note.getContentMd().isBlank()) {
            sb.append(note.getContentMd());
        } else if (note.getSourceSnapshot() != null && !note.getSourceSnapshot().isBlank()) {
            try {
                NoteSourceSnapshot snapshot = objectMapper.readValue(note.getSourceSnapshot(), NoteSourceSnapshot.class);
                String fallback = switch (note.getCaptureMode()) {
                    case EXCERPT -> snapshot.getExcerpt();
                    case REFERENCE -> snapshot.getSourceTitle();
                    default -> snapshot.getSourceContent();
                };
                if (fallback != null) sb.append(fallback);
            } catch (Exception e) {
                log.debug("笔记向量文本解析快照失败: noteId={}", note.getId());
            }
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) return null;
        return text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
    }
}
