package com.kbook.service.embedding;

import com.kbook.service.embedding.TextChunker.Chunk;
import com.kbook.service.embedding.TextChunker.CutReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构感知分块器单测：段落/句子优先、硬切兜底、偏移正确、短尾合并。
 */
class TextChunkerTest {

    @Test
    @DisplayName("优先在段落边界切分，不把段落从中间截断")
    void splitsAtParagraphBoundary() {
        String a = "甲".repeat(500);
        String b = "乙".repeat(500);
        String text = a + "\n\n" + b;

        List<Chunk> chunks = TextChunker.split(text, 800, 200);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).text()).isEqualTo(a);
        assertThat(chunks.get(0).reason()).isEqualTo(CutReason.PARAGRAPH);
        assertThat(chunks.get(1).text()).contains(b);
        assertThat(chunks.get(1).reason()).isEqualTo(CutReason.END);
    }

    @Test
    @DisplayName("句子边界优先于换行，避免 PDF/OCR 中句换行切断句子")
    void sentenceBoundaryBeatsLineBreak() {
        String text = "甲".repeat(150) + "\n" + "乙".repeat(150) + "。"
                + "丙".repeat(500) + "。";

        List<Chunk> chunks = TextChunker.split(text, 200, 50);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).reason()).isEqualTo(CutReason.SENTENCE);
        assertThat(chunks.get(0).text()).endsWith("。");
    }

    @Test
    @DisplayName("单句超长才硬切，并标记 HARD")
    void hardCutsOnlyWhenNoBoundary() {
        String text = "无".repeat(3000);

        List<Chunk> chunks = TextChunker.split(text, 800, 200);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).reason()).isEqualTo(CutReason.HARD);
        assertThat(chunks).allSatisfy(c ->
                assertThat(c.text().length()).isLessThanOrEqualTo(TextChunker.MAX_CHUNK_CHARS));
    }

    @Test
    @DisplayName("末块过短时并回前一块")
    void mergesTinyTail() {
        String a = "甲".repeat(700);
        String b = "乙".repeat(50);
        String text = a + "\n\n" + b;

        List<Chunk> chunks = TextChunker.split(text, 700, 100);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains(a).contains(b);
    }

    @Test
    @DisplayName("相邻块保留重叠，且偏移能还原原文")
    void keepsOverlapAndOffsets() {
        String unit = "甲".repeat(250) + "。";
        String text = unit.repeat(8);

        List<Chunk> chunks = TextChunker.split(text, 400, 100);

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks.get(1).startChar()).isLessThan(chunks.get(0).endChar());
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.startChar()).isGreaterThanOrEqualTo(0);
            assertThat(c.endChar()).isLessThanOrEqualTo(text.length());
            assertThat(text.substring(c.startChar(), c.endChar()).trim()).isEqualTo(c.text());
        });
    }

    @Test
    @DisplayName("空白内容返回空列表")
    void blankContentReturnsEmpty() {
        assertThat(TextChunker.split(null, 800, 200)).isEmpty();
        assertThat(TextChunker.split("   \n\n  ", 800, 200)).isEmpty();
    }
}
