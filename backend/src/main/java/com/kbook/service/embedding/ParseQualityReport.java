package com.kbook.service.embedding;

import com.kbook.service.book.TextStructureExtractor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析/入库质量报告（入库计划 §4.1 `books.parse_quality`）。
 * <p>
 * 闸门（{@link ContentQualityGate}）本来就统计了每个拒绝原因，但这些数字此前只进日志就丢了：
 * 「乱码率 &lt;1%」「解析报告可查」是阶段 A 的验收项，没有落库就没人能查，
 * 只能靠人工翻日志、且换一本书就查不到历史。
 * <p>
 * 只统计确定性事实（字符数、块数、拒绝分布、章节覆盖、硬切数），不调模型。
 */
public final class ParseQualityReport {

    public record Stats(
            int contentChars,
            int rawChunks,
            int acceptedChunks,
            int rejectedChunks,
            int hardCuts,
            int indexedChunks,
            double garbledRatio,
            boolean garbledHeavy,
            Map<String, Integer> rejectReasons,
            int chapters,
            double chapterCoverage,
            String embeddingModel) {

        /** 闸门整体拒绝率，用于回答"这本书要不要重新解析"。 */
        public double rejectedRatio() {
            return rawChunks == 0 ? 0 : (double) rejectedChunks / rawChunks;
        }
    }

    private ParseQualityReport() {
    }

    /**
     * @param gate       闸门结果（含各拒绝原因计数）
     * @param chapters   结构提取出的章节
     * @param indexedChunks 实际写入向量库的块数（零向量会被丢弃，故可能小于 acceptedChunks）
     */
    public static Stats of(String content,
                           List<TextChunker.Chunk> rawChunks,
                           ContentQualityGate.Result gate,
                           List<TextStructureExtractor.Chapter> chapters,
                           int indexedChunks,
                           String embeddingModel) {
        List<TextChunker.Chunk> accepted = gate.accepted();
        int hardCuts = (int) accepted.stream().filter(TextChunker.Chunk::hardCut).count();

        Map<String, Integer> reasons = new LinkedHashMap<>();
        gate.rejectedCounts().forEach((reason, count) -> reasons.put(reason.name(), count));

        return new Stats(
                content == null ? 0 : content.length(),
                rawChunks == null ? 0 : rawChunks.size(),
                accepted.size(),
                gate.rejected(),
                hardCuts,
                indexedChunks,
                garbledCharRatio(accepted),
                gate.garbledHeavy(),
                reasons,
                chapters == null ? 0 : chapters.size(),
                chapterCoverage(content, chapters),
                embeddingModel);
    }

    /**
     * 进索引正文的乱码字符占比（坏字符定义见 {@link ContentQualityGate#charStats(String)}）。
     * <p>
     * 计划 §5 的「乱码率 &lt;1%」口径是**字符**级、并且是对入库内容统计：
     * 闸门是按块判（一块里 2% 坏字符就整块被拒），坏字符被拒后就不进索引了，
     * 所以这里对 accepted 块再统计一次才对应得上验收线。
     */
    static double garbledCharRatio(List<TextChunker.Chunk> accepted) {
        int total = 0;
        int bad = 0;
        for (TextChunker.Chunk chunk : accepted) {
            ContentQualityGate.CharStats stats = ContentQualityGate.charStats(chunk.text());
            total += stats.total();
            bad += stats.bad();
        }
        return total == 0 ? 0 : (double) bad / total;
    }

    /**
     * 章节覆盖率：全文里有多少字符落在「能定位到章节」的区间内。
     * <p>
     * 章节元数据决定引用标签能不能显示真实章节名；覆盖率低说明结构提取没吃下这本书
     * （标题行样式不匹配），这时先修提取器，别调检索。
     */
    static double chapterCoverage(String content, List<TextStructureExtractor.Chapter> chapters) {
        if (content == null || content.isEmpty() || chapters == null || chapters.isEmpty()) {
            return 0;
        }
        int covered = 0;
        for (TextStructureExtractor.Chapter chapter : chapters) {
            int start = Math.max(0, chapter.startChar());
            int end = Math.min(content.length(), chapter.endChar());
            if (end > start) {
                covered += end - start;
            }
        }
        return Math.min(1.0, (double) covered / content.length());
    }

    /** 落库用的 JSON 文本；保留标量字段，拒绝原因明细过长时截断。 */
    public static String toJson(Stats stats) {
        if (stats == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append('{')
                .append("\"contentChars\":").append(stats.contentChars()).append(',')
                .append("\"rawChunks\":").append(stats.rawChunks()).append(',')
                .append("\"acceptedChunks\":").append(stats.acceptedChunks()).append(',')
                .append("\"rejectedChunks\":").append(stats.rejectedChunks()).append(',')
                .append("\"rejectedRatio\":").append(round4(stats.rejectedRatio())).append(',')
                .append("\"garbledRatio\":").append(round4(stats.garbledRatio())).append(',')
                .append("\"garbledHeavy\":").append(stats.garbledHeavy()).append(',')
                .append("\"hardCuts\":").append(stats.hardCuts()).append(',')
                .append("\"indexedChunks\":").append(stats.indexedChunks()).append(',')
                .append("\"chapters\":").append(stats.chapters()).append(',')
                .append("\"chapterCoverage\":").append(round4(stats.chapterCoverage())).append(',');
        if (stats.embeddingModel() != null) {
            sb.append("\"embeddingModel\":\"").append(escape(stats.embeddingModel())).append("\",");
        }
        sb.append("\"rejectReasons\":{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : stats.rejectReasons().entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        sb.append("}}");
        return sb.toString();
    }

    private static String round4(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    private static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
