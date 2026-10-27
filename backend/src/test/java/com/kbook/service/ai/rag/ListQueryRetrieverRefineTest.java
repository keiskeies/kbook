package com.kbook.service.ai.rag;

import com.kbook.config.ChatModelFactory;
import com.kbook.entity.AiScene;
import com.kbook.entity.Book;
import com.kbook.service.embedding.EmbeddingIndexService.ChunkInfo;
import com.kbook.service.embedding.EmbeddingService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 列表型 LLM 精筛（分批并行）单测。
 * <p>
 * 回归背景：2026-10-26 运行时实测，212 chunks 分 15 批串行精筛耗时 97.5s，
 * 是列表型问答的首要延迟来源。改为分批并行后需保持与串行版一致的结果顺序。
 */
class ListQueryRetrieverRefineTest {

    private static final Pattern CHUNK_INDEX = Pattern.compile("\\[chunkIndex=(\\d+)\\]");
    private static final int CHUNK_COUNT = 40;

    private EmbeddingService embeddingService;
    private ChatModelFactory chatModelFactory;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        chatModelFactory = mock(ChatModelFactory.class);
        executor = Executors.newFixedThreadPool(8);

        when(embeddingService.countContentChunks(anyLong())).thenReturn(100L);
        List<ChunkInfo> chunks = new ArrayList<>();
        for (int i = 0; i < CHUNK_COUNT; i++) {
            chunks.add(new ChunkInfo("书籍章节结构 内容" + i, i, i));
        }
        when(embeddingService.scrollChunksByIndexRange(eq(1L), anyInt(), anyInt()))
                .thenReturn(chunks);

        ChatModel chapterModel = mock(ChatModel.class);
        when(chapterModel.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("START_CHAPTER: 1\nEND_CHAPTER: 4"))
                .build());
        when(chatModelFactory.buildForScene(AiScene.LIST_QUERY_CHAPTER_RANGE)).thenReturn(chapterModel);
    }

    /**
     * 精筛模型：从提示词里读出本批 chunkIndex，按 {@code selector} 决定返回哪些编号。
     * {@code selector} 收到本批的 chunkIndex 列表。
     */
    private ChatModel refineModel(java.util.function.Function<List<Integer>, List<Integer>> selector,
                                  AtomicInteger concurrency, AtomicInteger peakConcurrency,
                                  java.util.function.Predicate<List<Integer>> failOn) {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyList())).thenAnswer(inv -> {
            List<ChatMessage> messages = inv.getArgument(0);
            String prompt = ((dev.langchain4j.data.message.UserMessage) messages.get(0)).singleText();
            List<Integer> batch = new ArrayList<>();
            Matcher m = CHUNK_INDEX.matcher(prompt);
            while (m.find()) batch.add(Integer.parseInt(m.group(1)));

            int current = concurrency.incrementAndGet();
            peakConcurrency.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(150);
                if (failOn != null && failOn.test(batch)) {
                    throw new RuntimeException("模拟 LLM 故障");
                }
                List<Integer> selected = selector.apply(batch);
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(selected.stream().map(String::valueOf)
                                .reduce((a, b) -> a + "," + b).orElse("")))
                        .build();
            } finally {
                concurrency.decrementAndGet();
            }
        });
        return model;
    }

    private Book book() {
        return Book.builder().id(1L).toc("第一章\n第二章\n第三章\n第四章").build();
    }

    @Test
    @DisplayName("精筛分批并行执行：并发度 > 1（串行实现下恒为 1）")
    void refineBatchesRunInParallel() {
        AtomicInteger concurrency = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ChatModel refine = refineModel(batch -> batch, concurrency, peak, null);
        when(chatModelFactory.buildForScene(AiScene.LIST_QUERY_REFINE)).thenReturn(refine);

        ListQueryRetriever retriever =
                new ListQueryRetriever(embeddingService, chatModelFactory, executor);
        ListQueryRetriever.RetrievalResult result =
                retriever.tocRangeRetrieve(book(), "书籍章节结构", "这本书的“书籍章节结构”是什么？", 100000);

        assertThat(result.matches()).isNotEmpty();
        // 40 chunks / 15 = 3 批，全部同时在跑 → 峰值并发 3
        assertThat(peak.get()).isGreaterThanOrEqualTo(2);
        assertThat(concurrency.get()).isZero();
    }

    @Test
    @DisplayName("精筛结果顺序与串行版一致：按批次序拼接，批内保持 LLM 选择顺序")
    void refinedOrderMatchesSerialSemantics() {
        // 每批返回倒序的两个编号 → 批内 LLM 顺序生效，跨批按批次序
        ChatModel refine = refineModel(
                batch -> {
                    List<Integer> reversed = new ArrayList<>(batch);
                    java.util.Collections.reverse(reversed);
                    return reversed.subList(0, Math.min(2, reversed.size()));
                },
                new AtomicInteger(), new AtomicInteger(), null);
        when(chatModelFactory.buildForScene(AiScene.LIST_QUERY_REFINE)).thenReturn(refine);

        ListQueryRetriever retriever =
                new ListQueryRetriever(embeddingService, chatModelFactory, executor);
        ListQueryRetriever.RetrievalResult result =
                retriever.tocRangeRetrieve(book(), "书籍章节结构", "这本书的“书籍章节结构”是什么？", 100000);

        // 分数 = 1/(rank+1)：rank 由精筛顺序决定。批0 的两个倒序编号排在最前。
        List<dev.langchain4j.store.embedding.EmbeddingMatch<dev.langchain4j.data.segment.TextSegment>> matches =
                result.matches();
        assertThat(matches).isNotEmpty();
        double firstScore = matches.stream()
                .filter(m -> m.embedded() != null
                        && m.embedded().metadata().getLong("chunkIndex") == 14)
                .findFirst().orElseThrow().score();
        double secondScore = matches.stream()
                .filter(m -> m.embedded() != null
                        && m.embedded().metadata().getLong("chunkIndex") == 13)
                .findFirst().orElseThrow().score();
        // 批0 = chunkIndex 0..14，倒序取 [14,13] → rank 0 / rank 1
        assertThat(firstScore).isEqualTo(1.0);
        assertThat(secondScore).isEqualTo(0.5);

        // 批1 = chunkIndex 15..29，倒序取 [29,28] → rank 2 / rank 3
        double third = matches.stream()
                .filter(m -> m.embedded() != null
                        && m.embedded().metadata().getLong("chunkIndex") == 29)
                .findFirst().orElseThrow().score();
        assertThat(third).isEqualTo(1.0 / 3);
    }

    @Test
    @DisplayName("某批 LLM 失败时保留该批全部（保守策略，不丢内容）")
    void failedBatchKeepsAllChunks() {
        ChatModel refine = refineModel(
                batch -> batch.subList(0, 1),
                new AtomicInteger(), new AtomicInteger(),
                batch -> batch.contains(20));
        when(chatModelFactory.buildForScene(AiScene.LIST_QUERY_REFINE)).thenReturn(refine);

        ListQueryRetriever retriever =
                new ListQueryRetriever(embeddingService, chatModelFactory, executor);
        ListQueryRetriever.RetrievalResult result =
                retriever.tocRangeRetrieve(book(), "书籍章节结构", "这本书的“书籍章节结构”是什么？", 100000);

        // 失败批（chunkIndex 15..29）全部保留，其余批只保留 1 条
        long keptFromFailedBatch = result.matches().stream()
                .filter(m -> m.embedded() != null)
                .map(m -> m.embedded().metadata().getLong("chunkIndex"))
                .filter(i -> i >= 15 && i <= 29)
                .count();
        assertThat(keptFromFailedBatch).isEqualTo(15);
    }
}
