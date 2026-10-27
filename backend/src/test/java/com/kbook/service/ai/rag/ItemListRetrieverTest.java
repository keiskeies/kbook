package com.kbook.service.ai.rag;

import com.kbook.entity.Book;
import com.kbook.service.ai.outline.AnswerOutline.Item;
import com.kbook.service.embedding.EmbeddingIndexService.ChunkInfo;
import com.kbook.service.embedding.EmbeddingService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 逐条检索回传单测：材料文本与真正入选的 chunk 必须一起返回，供流水线快照使用。
 */
class ItemListRetrieverTest {

    private EmbeddingService embeddingService;
    private ItemListRetriever retriever;
    private Book book;

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        retriever = new ItemListRetriever(embeddingService, Runnable::run);
        book = Book.builder().id(1L).build();
    }

    private Map<Integer, CompletableFuture<ItemListRetriever.Material>> prefetch(Item item) {
        return retriever.prefetchAllAsync(book, List.of(item));
    }

    @Test
    @DisplayName("锚点材料同时回传 chunkIndex")
    void anchorMaterialReturnsTextAndChunkMatch() {
        when(embeddingService.scrollChunksByIndexRange(1L, 4, 7))
                .thenReturn(List.of(new ChunkInfo("锚点材料正文", 100L, 5)));

        ItemListRetriever.Material material = prefetch(
                new Item(1, "标题", "gist", List.of(), List.of(5))).get(1).join();

        assertThat(material.text()).contains("锚点材料正文");
        assertThat(material.matches()).hasSize(1);
        assertThat(RagPipelineComponents.getChunkIndex(material.matches().get(0))).isEqualTo(5);
    }

    @Test
    @DisplayName("关键词材料同时回传 chunkIndex")
    void keywordMaterialReturnsChunkMatch() {
        EmbeddingMatch<TextSegment> hit = new EmbeddingMatch<>(0.9, "x", null,
                TextSegment.from("关键词材料正文", new Metadata().put("chunkIndex", 9)));
        when(embeddingService.searchContent("关键词", 4, book)).thenReturn(List.of(hit));

        ItemListRetriever.Material material = prefetch(
                new Item(1, "标题", "gist", List.of("关键词"), null)).get(1).join();

        assertThat(material.text()).contains("关键词材料正文");
        assertThat(material.matches())
                .extracting(RagPipelineComponents::getChunkIndex)
                .contains(9);
    }

    @Test
    @DisplayName("没有锚点也没有关键词时返回空材料")
    void noAnchorNoKeywordReturnsEmpty() {
        ItemListRetriever.Material material = prefetch(
                new Item(1, "标题", "gist", List.of(), null)).get(1).join();

        assertThat(material.text()).isEmpty();
        assertThat(material.matches()).isEmpty();
    }
}
