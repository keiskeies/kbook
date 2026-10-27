package com.kbook.service.embedding;

import com.google.common.util.concurrent.Futures;
import com.kbook.config.properties.QdrantProperties;
import com.kbook.repository.BookRepository;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Qdrant scroll 分页契约单测。
 * <p>
 * 回归背景：scroll 是游标语义，必须把上一页返回的 next_page_offset 写回下一次请求
 * （{@code setOffset}），否则每轮都重复取第一页，循环永不退出——2026-10-26 运行时
 * 实测该缺陷直接导致列表型问题的 TOC_RANGE 档卡死 4 分钟以上。
 */
class EmbeddingIndexServiceScrollTest {

    private static final String TEXT_KEY = "text_segment";

    private QdrantClient qdrantClient;
    private EmbeddingIndexService service;
    private final List<Points.ScrollPoints> requests = new ArrayList<>();

    @BeforeEach
    void setUp() {
        EmbeddingStoreManager storeManager = mock(EmbeddingStoreManager.class);
        BookRepository bookRepository = mock(BookRepository.class);
        QdrantProperties qdrantProps = mock(QdrantProperties.class);
        qdrantClient = mock(QdrantClient.class);

        when(qdrantProps.getContentCollection()).thenReturn("kbook_content");
        when(storeManager.buildBookIdFilter(anyLong()))
                .thenReturn(Common.Filter.getDefaultInstance());
        when(storeManager.extractPayloadString(anyMap(), eq(TEXT_KEY)))
                .thenAnswer(inv -> {
                    JsonWithInt.Value v = (JsonWithInt.Value) ((Map<?, ?>) inv.getArgument(0)).get(TEXT_KEY);
                    return v == null ? null : v.getStringValue();
                });
        when(storeManager.extractPayloadLong(anyMap(), eq("chunkIndex")))
                .thenAnswer(inv -> {
                    JsonWithInt.Value v = (JsonWithInt.Value) ((Map<?, ?>) inv.getArgument(0)).get("chunkIndex");
                    return v == null ? null : Long.valueOf(v.getStringValue());
                });

        service = new EmbeddingIndexService(storeManager, bookRepository, qdrantProps, qdrantClient,
                mock(ParseQualityReportService.class));
        requests.clear();
    }

    private void stubPages(Points.ScrollResponse... pages) {
        when(qdrantClient.scrollAsync(any())).thenAnswer(inv -> {
            Points.ScrollPoints req = inv.getArgument(0);
            requests.add(req);
            int idx = requests.size() - 1;
            Points.ScrollResponse page = pages[Math.min(idx, pages.length - 1)];
            return Futures.immediateFuture(page);
        });
    }

    private static Points.RetrievedPoint point(long pointId, String text, int chunkIndex) {
        Map<String, JsonWithInt.Value> payload = new java.util.HashMap<>();
        payload.put(TEXT_KEY, io.qdrant.client.ValueFactory.value(text));
        payload.put("chunkIndex", io.qdrant.client.ValueFactory.value(String.valueOf(chunkIndex)));
        return Points.RetrievedPoint.newBuilder()
                .setId(io.qdrant.client.PointIdFactory.id(pointId))
                .putAllPayload(payload)
                .build();
    }

    @Test
    @DisplayName("分页：把上一页的 next_page_offset 写回下一次请求，避免重复取第一页")
    void writesNextPageOffsetBackIntoRequest() {
        Common.PointId offset = io.qdrant.client.PointIdFactory.id(100L);
        stubPages(
                Points.ScrollResponse.newBuilder()
                        .addResult(point(100L, "第一页文本", 0))
                        .setNextPageOffset(offset)
                        .build(),
                Points.ScrollResponse.newBuilder()
                        .addResult(point(101L, "第二页文本", 1))
                        .build());

        List<EmbeddingIndexService.ChunkInfo> chunks = service.scrollAllContentChunks(1L);

        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(EmbeddingIndexService.ChunkInfo::text)
                .containsExactly("第一页文本", "第二页文本");

        // 两页各请求一次，且第二次请求带上了上一页返回的游标
        verify(qdrantClient, times(2)).scrollAsync(any());
        assertThat(requests.get(0).hasOffset()).isFalse();
        assertThat(requests.get(1).getOffset()).isEqualTo(offset);
    }

    @Test
    @DisplayName("分页：最后一页无 next_page_offset 时停止翻页")
    void stopsWhenNoNextPageOffset() {
        stubPages(Points.ScrollResponse.newBuilder()
                .addResult(point(100L, "唯一一页", 0))
                .build());

        List<EmbeddingIndexService.ChunkInfo> chunks = service.scrollAllContentChunks(1L);

        assertThat(chunks).hasSize(1);
        verify(qdrantClient, times(1)).scrollAsync(any());
    }

    @Test
    @DisplayName("分页：空页立即终止，不再继续翻页（兜底防死循环）")
    void emptyPageTerminatesLoop() {
        stubPages(Points.ScrollResponse.newBuilder()
                .setNextPageOffset(io.qdrant.client.PointIdFactory.id(100L))
                .build());

        List<EmbeddingIndexService.ChunkInfo> chunks = service.scrollAllContentChunks(1L);

        assertThat(chunks).isEmpty();
        verify(qdrantClient, times(1)).scrollAsync(any());
    }

    @Test
    @DisplayName("范围 scroll 复用同一分页逻辑，结果按 chunkIndex 升序")
    void rangeScrollSortsByChunkIndex() {
        stubPages(
                Points.ScrollResponse.newBuilder()
                        .addResult(point(100L, "后页", 7))
                        .setNextPageOffset(io.qdrant.client.PointIdFactory.id(100L))
                        .build(),
                Points.ScrollResponse.newBuilder()
                        .addResult(point(101L, "前页", 2))
                        .build());

        List<EmbeddingIndexService.ChunkInfo> chunks = service.scrollChunksByIndexRange(1L, 0, 10);

        assertThat(chunks).extracting(EmbeddingIndexService.ChunkInfo::chunkIndex)
                .containsExactly(2, 7);
        assertThat(chunks).extracting(EmbeddingIndexService.ChunkInfo::text)
                .containsExactly("前页", "后页");
        assertThat(requests.get(1).hasOffset()).isTrue();
    }

    @Test
    @DisplayName("范围 scroll：minIdx > maxIdx 时直接返回空，不发请求")
    void rangeScrollRejectsInvertedRange() {
        stubPages(Points.ScrollResponse.newBuilder().build());

        assertThat(service.scrollChunksByIndexRange(1L, 10, 5)).isEmpty();
        verify(qdrantClient, times(0)).scrollAsync(any());
    }
}
