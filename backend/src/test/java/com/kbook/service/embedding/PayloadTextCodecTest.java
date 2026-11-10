package com.kbook.service.embedding;

import com.kbook.config.properties.BookQaProperties;
import com.kbook.repository.BookRepository;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** P2-3：向量库脱敏存储 + 本地按偏移还原，必须做到「下游文本不变、库里不再有原文」。 */
class PayloadTextCodecTest {

    private static final Long BOOK_ID = 42L;
    private static final int START = 30;
    private static final int END = 96;

    private BookRepository bookRepository;
    private BookQaProperties properties;
    private String fullText;
    private String raw;

    @BeforeEach
    void setUp() {
        bookRepository = mock(BookRepository.class);
        properties = new BookQaProperties();
        StringBuilder sb = new StringBuilder("【前言】本书讨论思维模型与投资决策的关系。\n");
        while (sb.length() < START) sb.append("铺垫");
        sb.append("多元思维模型要求我们同时用概率论、复利效应、逆向思考和安全边际去审视每一个决策，"
                + "而不是依赖单一学科的直觉判断；这句话是本书第三章的核心论点，后面会反复引用。");
        while (sb.length() < 300) sb.append("补充材料与延伸讨论。");
        fullText = sb.toString();
        raw = fullText.substring(START, END);
        when(bookRepository.findFullTextById(BOOK_ID)).thenReturn(List.of(fullText));
    }

    private PayloadTextCodec codec(boolean enabled) {
        properties.setPayloadDesensitizationEnabled(enabled);
        return new PayloadTextCodec(bookRepository, properties);
    }

    private static TextSegment segment(String text) {
        return TextSegment.from(text, new Metadata()
                .put("bookId", BOOK_ID)
                .put("chunkIndex", 7)
                .put("totalChunks", 273)
                .put("startChar", START)
                .put("endChar", END)
                .put("chapterIndex", 3)
                .put("chapterTitle", "多元思维模型"));
    }

    @Test
    void disabledKeepsRawTextByteForByte() {
        PayloadTextCodec codec = codec(false);
        assertEquals(raw, codec.forIndex(segment(raw)));
        assertFalse(PayloadTextCodec.looksLikeDigest(codec.forIndex(segment(raw))));
        verifyNoInteractions(bookRepository);
    }

    @Test
    void digestContainsStructureButNoVerbatimPassage() {
        PayloadTextCodec codec = codec(true);
        String digest = codec.forIndex(segment(raw));

        assertTrue(PayloadTextCodec.looksLikeDigest(digest));
        assertTrue(digest.contains("多元思维模型"), "应保留章节标题");
        assertTrue(digest.contains("[30,96]"), "应保留字符偏移");
        assertTrue(digest.contains("7/273"), "应保留片段号");
        // 合规底线：不能出现原文连续 20 字
        for (int i = 0; i + 20 <= raw.length(); i++) {
            assertFalse(digest.contains(raw.substring(i, i + 20)),
                    "定位串不得包含原文连续片段: " + raw.substring(i, i + 20));
        }
        assertNotEquals(raw, digest);
    }

    @Test
    void restoreReturnsExactOriginalSlice() {
        PayloadTextCodec codec = codec(true);
        String digest = codec.forIndex(segment(raw));

        assertEquals(raw, codec.restore(digest, BOOK_ID, (long) START, (long) END, 1L));
        assertEquals(raw, codec.restore(TextSegment.from(digest, segment(raw).metadata())));
    }

    @Test
    void missingFullTextFailsSafeToRawIndexing() {
        when(bookRepository.findFullTextById(BOOK_ID)).thenReturn(List.of());
        PayloadTextCodec codec = codec(true);
        assertEquals(raw, codec.forIndex(segment(raw)),
                "没有本地全文时不脱敏，否则会造出不可还原的死向量");
    }

    @Test
    void restoreWorksEvenWhenRedactionFlagIsLost() {
        PayloadTextCodec codec = codec(true);
        String digest = codec.forIndex(segment(raw));
        // LangChain4j 降级路径可能丢掉 text_redacted 标记，靠定位串前缀识别
        assertEquals(raw, codec.restore(digest, BOOK_ID, (long) START, (long) END, null));
    }

    @Test
    void nonRedactedPayloadIsReturnedUnchanged() {
        PayloadTextCodec codec = codec(true);
        assertEquals(raw, codec.restore(raw, BOOK_ID, (long) START, (long) END, null));
    }

    @Test
    void outOfRangeOffsetsAreClamped() {
        PayloadTextCodec codec = codec(true);
        String digest = codec.forIndex(segment(raw));
        assertEquals(fullText.substring(START), codec.restore(digest, BOOK_ID, (long) START, 999999L, 1L));
    }

    @Test
    void topicKeywordsAreDiscreteTokens() {
        String text = "多元思维模型要求我们同时用概率论、复利效应、逆向思考和安全边际去审视每一个决策，"
                + "概率论与复利效应在投资决策里反复出现，逆向思考和安全边际同样重要，"
                + "思维模型因此成为本书的核心，决策质量也随之提升。";
        String topics = PayloadTextCodec.topicKeywords(text, 6);
        assertFalse(topics.isBlank());
        assertTrue(topics.split("、").length <= 6);
        assertEquals(-1, topics.indexOf('，'), "不应输出整句原文");
    }

    @Test
    void cacheIsUsedAcrossChunks() {
        PayloadTextCodec codec = codec(true);
        codec.forIndex(segment(raw));
        codec.forIndex(segment(raw));
        codec.restore(codec.forIndex(segment(raw)), BOOK_ID, (long) START, (long) END, 1L);
        verify(bookRepository, atLeastOnce()).findFullTextById(BOOK_ID);
        verify(bookRepository, atMost(1)).findFullTextById(anyLong());
    }

    @Test
    void invalidateForcesReloadBeforeReindex() {
        PayloadTextCodec codec = codec(true);
        codec.forIndex(segment(raw));
        codec.invalidate(BOOK_ID);
        codec.forIndex(segment(raw));
        verify(bookRepository, times(2)).findFullTextById(BOOK_ID);
    }

    @Test
    void payloadMapRestoreUsesQdrantLongs() {
        PayloadTextCodec codec = codec(true);
        String digest = codec.forIndex(segment(raw));
        EmbeddingStoreManager store = mock(EmbeddingStoreManager.class);
        Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload = new HashMap<>();
        when(store.extractPayloadLong(same(payload), eq("bookId"))).thenReturn(BOOK_ID);
        when(store.extractPayloadLong(same(payload), eq("startChar"))).thenReturn((long) START);
        when(store.extractPayloadLong(same(payload), eq("endChar"))).thenReturn((long) END);
        when(store.extractPayloadLong(same(payload), eq(PayloadTextCodec.REDACTED_FLAG_KEY))).thenReturn(1L);

        assertEquals(raw, codec.restore(digest, payload, store));
    }
}
