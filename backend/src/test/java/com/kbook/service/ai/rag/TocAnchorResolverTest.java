package com.kbook.service.ai.rag;

import com.kbook.service.ai.rag.TocAnchorResolver.TocLine;
import com.kbook.service.embedding.EmbeddingIndexService.ChunkInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目录行 → chunkIndex 锚点解析单测。
 * <p>
 * 夹具复刻 book 24689《穷查理宝典》的真实版面：22 行目录（其中 8 行是书名/扉页/版权页/容器行）、
 * 14 个真实章节、章节长度从 12 段到 70 段不等、书末尾带一份内嵌目录。
 * 该版面下「按目录行均分映射」会漏掉每个真实章节的开头（实测 14/14）。
 */
class TocAnchorResolverTest {

    /** 复刻真实目录（books.toc，保留缩进） */
    private static final String TOC_TEXT = """
            穷查理宝典（珍藏本）：芒格智慧箴言与私人书单
              扉页
              序言 书中自有黄金屋
              上篇 芒格智慧箴言
                第一章 查理·芒格传略
                第二章 歌颂长者：芒格论晚年
                第三章 忆念：晚辈谈芒格
                第四章 芒格主义：查理的即席谈话
              下篇 芒格私人书单
                第一辑 自然
                第二辑 人类
                第三辑 经济
                第四辑 投资
                第五辑 人生
                第六辑 处世
              版权页
            穷查理宝典（珍藏本）：芒格最重要的三场演讲
              扉页
              第一讲 最有用的商业智慧有哪些？
              第二讲 如何获得精彩人生？
              第三讲 聪明人为什么会犯错？
              版权页""";

    /** 真实章节在全书中的起始 chunkIndex（实测） */
    private static final Map<Integer, Integer> REAL_ANCHORS = Map.ofEntries(
            Map.entry(5, 17),    // 第一章
            Map.entry(6, 31),    // 第二章
            Map.entry(7, 41),    // 第三章
            Map.entry(8, 53),    // 第四章
            Map.entry(10, 83),   // 第一辑 自然
            Map.entry(11, 88),   // 第二辑 人类
            Map.entry(12, 99),   // 第三辑 经济
            Map.entry(13, 112),  // 第四辑 投资
            Map.entry(14, 123),  // 第五辑 人生
            Map.entry(15, 136),  // 第六辑 处世
            Map.entry(19, 149),  // 第一讲
            Map.entry(20, 190),  // 第二讲
            Map.entry(21, 202)); // 第三讲

    private static final int TOTAL_CHUNKS = 273;

    /**
     * 构造夹具：每个章节的标题出现在其起始 chunk 的正文里；
     * 末尾两个 chunk 塞进整份目录（模拟电子书排版留下的内嵌目录）。
     */
    private static List<ChunkInfo> buildChunks() {
        List<ChunkInfo> chunks = new ArrayList<>();
        for (int i = 0; i < TOTAL_CHUNKS; i++) {
            chunks.add(new ChunkInfo("正文内容 ".repeat(20), i, i));
        }
        // 序言正文（无序数标记的叶子行，靠标题唯一命中定位）
        chunks.set(1, new ChunkInfo("序言 书中自有黄金屋 文/李录 " + chunks.get(1).text(), 1, 1));
        for (Map.Entry<Integer, Integer> e : REAL_ANCHORS.entrySet()) {
            String heading = tocLineText(e.getKey());
            int idx = e.getValue();
            ChunkInfo old = chunks.get(idx);
            chunks.set(idx, new ChunkInfo(heading + " " + old.text(), old.pointId(), old.chunkIndex()));
        }
        // 末尾内嵌目录：正文里整份目录都出现
        String embedded = String.join("\n", TOC_TEXT.split("\\R"));
        for (int i : new int[]{TOTAL_CHUNKS - 2, TOTAL_CHUNKS - 1}) {
            ChunkInfo old = chunks.get(i);
            chunks.set(i, new ChunkInfo(embedded + " " + old.text(), old.pointId(), old.chunkIndex()));
        }
        // 真实正文里的交叉引用：正文提到「第三章」但不含完整标题，不能被当成锚点
        ChunkInfo cross = chunks.get(60);
        chunks.set(60, new ChunkInfo("如第三章所述。" + cross.text(), cross.pointId(), cross.chunkIndex()));
        return chunks;
    }

    private static String tocLineText(int lineNumber) {
        return TOC_TEXT.split("\\R")[lineNumber - 1].trim();
    }

    @Test
    @DisplayName("目录层级解析：容器行的内容块延伸到下一同级行之前")
    void parsesTocHierarchy() {
        List<TocLine> lines = TocAnchorResolver.parseToc(TOC_TEXT);

        assertThat(lines).hasSize(22);
        assertThat(lines.get(3).trimmed()).isEqualTo("上篇 芒格智慧箴言");
        assertThat(lines.get(3).blockEnd()).isEqualTo(8);    // 上篇 → 第一章~第四章
        assertThat(lines.get(8).trimmed()).isEqualTo("下篇 芒格私人书单");
        assertThat(lines.get(8).blockEnd()).isEqualTo(15);   // 下篇 → 第一辑~第六辑
        assertThat(lines.get(9).blockEnd()).isEqualTo(10);   // 第一辑 是叶子
        assertThat(lines.get(2).blockEnd()).isEqualTo(3);    // 序言 是叶子
    }

    @Test
    @DisplayName("锚点按「序数标记+标题」定位到真实章节起点，13 个真实章节全部命中")
    void resolvesRealSectionAnchors() {
        Map<Integer, Integer> anchors =
                TocAnchorResolver.resolveAnchors(TocAnchorResolver.parseToc(TOC_TEXT), buildChunks());

        assertThat(anchors).containsAllEntriesOf(REAL_ANCHORS);
    }

    @Test
    @DisplayName("无序数标记的叶子行靠标题唯一命中定位（序言）")
    void anchorsLeafLineByUniqueTitle() {
        Map<Integer, Integer> anchors =
                TocAnchorResolver.resolveAnchors(TocAnchorResolver.parseToc(TOC_TEXT), buildChunks());

        assertThat(anchors).containsEntry(3, 1);
    }

    @Test
    @DisplayName("书名行/扉页/版权页/容器行不产生锚点")
    void skipsNonSectionLines() {
        Map<Integer, Integer> anchors =
                TocAnchorResolver.resolveAnchors(TocAnchorResolver.parseToc(TOC_TEXT), buildChunks());

        assertThat(anchors).doesNotContainKeys(1, 2, 4, 9, 16, 17, 18, 22);
    }

    @Test
    @DisplayName("书末尾内嵌目录块不会被当成锚点目标")
    void ignoresEmbeddedTocAtBookEnd() {
        Map<Integer, Integer> anchors =
                TocAnchorResolver.resolveAnchors(TocAnchorResolver.parseToc(TOC_TEXT), buildChunks());

        // 内嵌目录在 271/272，若被当成锚点，命中会落在这两个块上
        assertThat(anchors.values()).allSatisfy(v -> assertThat(v).isLessThan(TOTAL_CHUNKS - 2));
    }

    @Test
    @DisplayName("区间计算：起点取范围内首个锚点，终点取下一锚点的前一块")
    void resolvesRangeBetweenAnchors() {
        List<TocLine> lines = TocAnchorResolver.parseToc(TOC_TEXT);
        Map<Integer, Integer> anchors = TocAnchorResolver.resolveAnchors(lines, buildChunks());

        // 下篇「私人书单」= 目录行 10..15（第一辑..第六辑）→ 真实 83..148
        int[] range = TocAnchorResolver.resolveRange(anchors, lines, 10, 15, TOTAL_CHUNKS);

        assertThat(range).isNotNull();
        assertThat(range[0]).isLessThanOrEqualTo(83);
        assertThat(range[1]).isEqualTo(148);
    }

    @Test
    @DisplayName("区间计算：选容器行时按内容块展开到它下面的章节")
    void expandsContainerLineToItsBlock() {
        List<TocLine> lines = TocAnchorResolver.parseToc(TOC_TEXT);
        Map<Integer, Integer> anchors = TocAnchorResolver.resolveAnchors(lines, buildChunks());

        // 「上篇」= 目录行 4，内容块 4..8 → 真实 17..82
        int[] range = TocAnchorResolver.resolveRange(anchors, lines, 4, 4, TOTAL_CHUNKS);

        assertThat(range).isNotNull();
        assertThat(range[0]).isLessThanOrEqualTo(17);
        assertThat(range[1]).isEqualTo(82);
    }

    @Test
    @DisplayName("区间计算：范围延伸到末尾时以全书末块收尾")
    void resolvesRangeToBookEnd() {
        List<TocLine> lines = TocAnchorResolver.parseToc(TOC_TEXT);
        Map<Integer, Integer> anchors = TocAnchorResolver.resolveAnchors(lines, buildChunks());

        // 第三讲 = 目录行 21 → 真实 202..272
        int[] range = TocAnchorResolver.resolveRange(anchors, lines, 21, 21, TOTAL_CHUNKS);

        assertThat(range).isNotNull();
        assertThat(range[0]).isLessThanOrEqualTo(202);
        assertThat(range[1]).isEqualTo(TOTAL_CHUNKS - 1);
    }

    @Test
    @DisplayName("范围内没有锚点时返回 null，交给调用方回退")
    void returnsNullWhenNoAnchorInRange() {
        List<TocLine> lines = TocAnchorResolver.parseToc(TOC_TEXT);
        Map<Integer, Integer> anchors = TocAnchorResolver.resolveAnchors(lines, buildChunks());

        // 「版权页」这类无锚点的叶子行 → 没有可用区间
        assertThat(TocAnchorResolver.resolveRange(anchors, lines, 16, 16, TOTAL_CHUNKS)).isNull();
        assertThat(TocAnchorResolver.resolveRange(anchors, lines, 6, 3, TOTAL_CHUNKS)).isNull();
    }

    @Test
    @DisplayName("弱匹配时锚点必须单调前进，避免正文交叉引用把锚点拉早")
    void weakMatchKeepsAnchorsMonotonic() {
        String toc = """
                    第一章 开篇
                    第二章 某某章节""";
        List<ChunkInfo> chunks = new ArrayList<>();
        chunks.add(new ChunkInfo("如第二章所述，这里只是交叉引用。", 0, 0));
        chunks.add(new ChunkInfo("正文".repeat(30), 1, 1));
        chunks.add(new ChunkInfo("第二章 某某章节 正文", 2, 2));

        Map<Integer, Integer> anchors = TocAnchorResolver.resolveAnchors(
                TocAnchorResolver.parseToc(toc), chunks);

        // 第二章的完整标题只出现在 chunk 2，chunk 0 的交叉引用不应被采信
        assertThat(anchors).containsEntry(2, 2);
    }

    @Test
    @DisplayName("回归：均分映射会漏掉章节开头，锚点法不会")
    void anchorMappingBeatsUniformMapping() {
        List<TocLine> lines = TocAnchorResolver.parseToc(TOC_TEXT);
        Map<Integer, Integer> anchors = TocAnchorResolver.resolveAnchors(lines, buildChunks());

        // 均分映射（修复前的算法）：总 chunks ÷ 目录行数
        double chunksPerChapter = (double) TOTAL_CHUNKS / lines.size();
        int uniformLo = (int) Math.max(0, (10 - 1) * chunksPerChapter);
        int uniformHi = (int) Math.min(TOTAL_CHUNKS - 1, 15 * chunksPerChapter);

        // 第一辑 自然 真实在 83..87
        assertThat(uniformLo).isGreaterThan(87);          // 均分映射漏掉整辑
        int[] anchored = TocAnchorResolver.resolveRange(anchors, lines, 10, 15, TOTAL_CHUNKS);
        assertThat(anchored[0]).isLessThanOrEqualTo(83);  // 锚点法命中
    }
}
