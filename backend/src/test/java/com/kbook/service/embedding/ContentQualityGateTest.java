package com.kbook.service.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入库质量闸门单测（入库计划 §3.1 第 5 条）。
 */
class ContentQualityGateTest {

    private static TextChunker.Chunk chunk(String text) {
        return new TextChunker.Chunk(text, 0, text.length(), TextChunker.CutReason.PARAGRAPH);
    }

    private static String body(String seed, int repeat) {
        return (seed + "这是一段正常的中文正文内容，用于测试质量闸门的通过路径。").repeat(repeat);
    }

    @Test
    @DisplayName("正常块全部通过")
    void 正常块通过() {
        List<TextChunker.Chunk> chunks = List.of(chunk(body("一", 2)), chunk(body("二", 2)));

        ContentQualityGate.Result r = ContentQualityGate.filter(chunks);

        assertThat(r.accepted()).hasSize(2);
        assertThat(r.rejected()).isZero();
    }

    @Test
    @DisplayName("空块与极短块被拒")
    void 空块与极短块被拒() {
        List<TextChunker.Chunk> chunks = new ArrayList<>();
        chunks.add(chunk("   "));
        chunks.add(chunk("目录 第一章"));
        chunks.add(chunk(body("正", 2)));

        ContentQualityGate.Result r = ContentQualityGate.filter(chunks);

        assertThat(r.accepted()).hasSize(1);
        assertThat(r.rejectedCounts()).containsEntry(ContentQualityGate.RejectReason.BLANK, 1);
        assertThat(r.rejectedCounts()).containsEntry(ContentQualityGate.RejectReason.TOO_SHORT, 1);
    }

    @Test
    @DisplayName("乱码块被拒并触发乱码告警")
    void 乱码块被拒() {
        String garbled = "锟斤拷".repeat(20) + "\uFFFD\uFFFD\uFFFD\uFFFD\n";
        List<TextChunker.Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            chunks.add(chunk(garbled));
        }
        chunks.add(chunk(body("正", 2)));

        ContentQualityGate.Result r = ContentQualityGate.filter(chunks);

        assertThat(r.accepted()).hasSize(1);
        assertThat(r.rejectedCounts()).containsKey(ContentQualityGate.RejectReason.GARBLED);
        assertThat(r.garbledHeavy()).isTrue();
    }

    @Test
    @DisplayName("纯排版符号块被拒")
    void 纯符号块被拒() {
        List<TextChunker.Chunk> chunks = List.of(chunk("★★★★★◆◆◆◆◆■■■■■".repeat(5)), chunk(body("正", 2)));

        ContentQualityGate.Result r = ContentQualityGate.filter(chunks);

        assertThat(r.accepted()).hasSize(1);
        assertThat(r.rejectedCounts()).containsKey(ContentQualityGate.RejectReason.SYMBOL_ONLY);
    }

    @Test
    @DisplayName("完全相同（含空白差异）的重复块只保留一份")
    void 重复块只留一份() {
        String text = body("页眉重复", 3);
        List<TextChunker.Chunk> chunks = List.of(chunk(text), chunk("  " + text + "\n\n"));

        ContentQualityGate.Result r = ContentQualityGate.filter(chunks);

        assertThat(r.accepted()).hasSize(1);
        assertThat(r.rejectedCounts()).containsEntry(ContentQualityGate.RejectReason.DUPLICATE, 1);
    }

    @Test
    @DisplayName("全文都短时不启用最短长度限制，避免整本被拒")
    void 短文档不做长度限制() {
        List<TextChunker.Chunk> chunks = List.of(chunk("短文"), chunk("更短"));

        ContentQualityGate.Result r = ContentQualityGate.filter(chunks);

        assertThat(r.accepted()).hasSize(2);
        assertThat(r.rejected()).isZero();
    }

    @Test
    @DisplayName("空输入安全")
    void 空输入安全() {
        assertThat(ContentQualityGate.filter(null).accepted()).isEmpty();
        assertThat(ContentQualityGate.filter(List.of()).accepted()).isEmpty();
    }

    @Test
    @DisplayName("比例计算：纯空白不计入分母")
    void 比例计算() {
        assertThat(ContentQualityGate.garbledRatio("  \n\t")).isZero();
        assertThat(ContentQualityGate.symbolRatio("中文abc123，。")).isZero();
        assertThat(ContentQualityGate.garbledRatio("\uFFFD\uFFFDab")).isEqualTo(0.5);
    }

    @Test
    @DisplayName("检索侧乱码判定与入库闸门同阈值")
    void 检索侧乱码判定() {
        assertThat(ContentQualityGate.isGarbled("正常的书中原文，没有乱码。")).isFalse();
        assertThat(ContentQualityGate.isGarbled("\uFFFD\uFFFD\uFFFD\uFFFD\uFFFDabc")).isTrue();
    }

    @Test
    @DisplayName("超长重复块不去重，避免误删书内合法重复的整段内容")
    void 长重复块保留() {
        String longText = body("重复练习", 30); // > MAX_DEDUP_CHARS
        assertThat(longText.length()).isGreaterThan(ContentQualityGate.MAX_DEDUP_CHARS);
        List<TextChunker.Chunk> chunks = List.of(chunk(longText), chunk(longText));

        ContentQualityGate.Result r = ContentQualityGate.filter(chunks);

        assertThat(r.accepted()).hasSize(2);
        assertThat(r.rejected()).isZero();
    }
}