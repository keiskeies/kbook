package com.kbook.service.embedding;

import com.kbook.service.book.TextStructureExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 解析质量报告单测（入库计划 §4.1 / §5 验收口径）。
 * <p>
 * 重点：乱码率的两个口径必须区分（按块拒 vs 入库后按字符）、章节覆盖率的分母、
 * 以及落库 JSON 的标量字段不能因截断而丢失。
 */
class ParseQualityReportTest {

    private static List<TextChunker.Chunk> chunks(String... texts) {
        List<TextChunker.Chunk> list = new java.util.ArrayList<>();
        int pos = 0;
        for (String t : texts) {
            list.add(new TextChunker.Chunk(t, pos, pos + t.length(), TextChunker.CutReason.PARAGRAPH));
            pos += t.length();
        }
        return list;
    }

    private static String longCleanText(String head) {
        StringBuilder sb = new StringBuilder(head);
        while (sb.length() < 400) {
            sb.append("，").append("正常中文内容用于填满一个可入库的块");
        }
        return sb.toString();
    }

    @Nested
    @DisplayName("统计口径")
    class Stats {

        @Test
        void 拒绝分布与硬切计数进入统计() {
            String clean = longCleanText("开头");
            List<TextChunker.Chunk> raw = chunks(clean, "短", clean, "  ");
            ContentQualityGate.Result gate = ContentQualityGate.filter(raw);

            ParseQualityReport.Stats stats = ParseQualityReport.of(
                    String.join("", raw.stream().map(TextChunker.Chunk::text).toList()),
                    raw, gate, List.of(), gate.accepted().size(), "model-x");

            assertThat(stats.rawChunks()).isEqualTo(4);
            assertThat(stats.acceptedChunks()).isEqualTo(gate.accepted().size());
            assertThat(stats.rejectedChunks()).isEqualTo(gate.rejected());
            assertThat(stats.rejectReasons()).containsKeys("TOO_SHORT", "BLANK");
            assertThat(stats.embeddingModel()).isEqualTo("model-x");
        }

        @Test
        void 乱码率取入库内容里活下来的块_被整块拒掉的坏字符不再计入() {
            // 闸门按块判：坏字符超 2% 整块被拒。报告要回答的是"进了索引的正文里还有多少坏字符"，
            // 所以被拒块的坏字符不该再计入 garbledRatio。
            String withBadTail = longCleanText("好字") + "\uFFFD".repeat(12);
            List<TextChunker.Chunk> raw = chunks(withBadTail);
            ContentQualityGate.Result gate = ContentQualityGate.filter(raw);
            assertThat(gate.accepted()).isEmpty(); // 前置条件：该块确实被闸门拒了

            ParseQualityReport.Stats stats = ParseQualityReport.of(
                    withBadTail, raw, gate, List.of(), 0, "m");

            assertThat(stats.garbledRatio()).isZero();
            assertThat(stats.rejectedChunks()).isEqualTo(1);
            assertThat(stats.rejectReasons()).containsEntry("GARBLED", 1);
        }

        @Test
        void 活下来的块里的少量坏字符仍按字符比例计入() {
            // 一块里坏字符不足 2% 能通过闸门；这部分坏字符确实进了索引，必须能在报告里看到
            String mostlyClean = longCleanText("正文") + "\uFFFD\uFFFD";
            List<TextChunker.Chunk> raw = chunks(mostlyClean);
            ContentQualityGate.Result gate = ContentQualityGate.filter(raw);
            assertThat(gate.accepted()).hasSize(1);

            ParseQualityReport.Stats stats = ParseQualityReport.of(
                    mostlyClean, raw, gate, List.of(), 1, "m");

            assertThat(stats.garbledRatio()).isGreaterThan(0).isLessThan(ContentQualityGate.MAX_GARBLED_RATIO);
        }

        @Test
        void 章节覆盖率按章节区间并集除以全文长度() {
            String content = "x".repeat(1000);
            List<TextStructureExtractor.Chapter> chapters = List.of(
                    new TextStructureExtractor.Chapter(0, "第一章", 0, 400),
                    new TextStructureExtractor.Chapter(1, "第二章", 500, 800));

            ContentQualityGate.Result gate = ContentQualityGate.filter(chunks(content));
            ParseQualityReport.Stats stats = ParseQualityReport.of(
                    content, List.of(new TextChunker.Chunk(content, 0, content.length(),
                            TextChunker.CutReason.PARAGRAPH)), gate, chapters, 1, "m");

            assertThat(stats.chapters()).isEqualTo(2);
            assertThat(stats.chapterCoverage()).isEqualTo(0.7);
        }

        @Test
        void 无章节时覆盖率为0而不是异常() {
            ContentQualityGate.Result gate = ContentQualityGate.filter(chunks(longCleanText("正文")));
            ParseQualityReport.Stats stats = ParseQualityReport.of(
                    longCleanText("正文"), chunks(longCleanText("正文")), gate, List.of(), 1, "m");

            assertThat(stats.chapters()).isZero();
            assertThat(stats.chapterCoverage()).isZero();
        }

        @Test
        void 空输入不抛异常() {
            ContentQualityGate.Result gate = ContentQualityGate.filter(List.of());
            ParseQualityReport.Stats stats = ParseQualityReport.of(null, List.of(), gate, null, 0, null);

            assertThat(stats.contentChars()).isZero();
            assertThat(stats.rawChunks()).isZero();
            assertThat(stats.rejectedRatio()).isZero();
        }
    }

    @Nested
    @DisplayName("落库 JSON")
    class Json {

        @Test
        void 标量字段齐全可解析() {
            String content = longCleanText("正文");
            List<TextChunker.Chunk> raw = chunks(content, "短");
            ContentQualityGate.Result gate = ContentQualityGate.filter(raw);
            ParseQualityReport.Stats stats = ParseQualityReport.of(
                    content, raw, gate, List.of(), gate.accepted().size(), "openai/text-embedding-3");

            String json = ParseQualityReport.toJson(stats);

            assertThat(json)
                    .contains("\"contentChars\":" + content.length())
                    .contains("\"acceptedChunks\":" + gate.accepted().size())
                    .contains("\"garbledRatio\":")
                    .contains("\"chapterCoverage\":0.0000")
                    .contains("\"embeddingModel\":\"openai/text-embedding-3\"")
                    .contains("\"rejectReasons\":{\"TOO_SHORT\":1}}");
        }

        @Test
        void 五种拒绝原因都进JSON() {
            List<TextChunker.Chunk> raw = new java.util.ArrayList<>();
            raw.add(new TextChunker.Chunk("   ", 0, 3, TextChunker.CutReason.PARAGRAPH));                // BLANK
            raw.add(new TextChunker.Chunk("短", 0, 1, TextChunker.CutReason.PARAGRAPH));           // TOO_SHORT
            raw.add(new TextChunker.Chunk(longCleanText("乱") + "\uFFFD".repeat(30), 0, 1,
                    TextChunker.CutReason.PARAGRAPH));                                                  // GARBLED
            raw.add(new TextChunker.Chunk("★☆★☆★☆★☆★☆★☆★☆★☆★☆★☆★☆★☆★☆★☆★☆★☆★☆★", 0, 1,
                    TextChunker.CutReason.PARAGRAPH));                                                  // SYMBOL_ONLY
            String dup = longCleanText("重复块");
            raw.add(new TextChunker.Chunk(dup, 0, 1, TextChunker.CutReason.PARAGRAPH));
            raw.add(new TextChunker.Chunk(dup, 0, 1, TextChunker.CutReason.PARAGRAPH));            // DUPLICATE
            ContentQualityGate.Result gate = ContentQualityGate.filter(raw);

            ParseQualityReport.Stats stats = ParseQualityReport.of(
                    String.join("", dup, dup), raw, gate, List.of(), gate.accepted().size(), "m");
            String json = ParseQualityReport.toJson(stats);

            assertThat(json)
                    .contains("\"BLANK\":1")
                    .contains("\"TOO_SHORT\":1")
                    .contains("\"GARBLED\":1")
                    .contains("\"SYMBOL_ONLY\":1")
                    .doesNotContain(",,");
        }

        @Test
        void null统计返回null不写空对象() {
            assertThat(ParseQualityReport.toJson(null)).isNull();
        }
    }
}
