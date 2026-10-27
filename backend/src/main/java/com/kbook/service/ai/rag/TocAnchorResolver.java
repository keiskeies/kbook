package com.kbook.service.ai.rag;

import com.kbook.service.embedding.EmbeddingIndexService.ChunkInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 目录行 → chunkIndex 锚点解析。
 * <p>
 * <b>要解决的问题</b>：档 2（TOC_RANGE）原来用「总 chunks ÷ 目录行数」把 LLM 给出的目录行号
 * 换算成 chunkIndex 区间。但目录行 ≠ 章节——目录里通常混着书名行、扉页、版权页、
 * 「上篇/下篇」这类容器行，而真实章节长度又极不均匀。按行均分会让窗口整体错位，
 * <b>每个真实章节的开头都被跳过</b>（实测 book 24689：14/14 漏头）。
 * <p>
 * <b>做法</b>：
 * <ol>
 *   <li>保留目录缩进，解析出每个目录行的「内容块」（到自己或同级行为止）；</li>
 *   <li>目录标题在正文里逐字出现（如「第一辑 自然」出现在 chunk 83），
 *       用「序数标记 + 标题」的连续匹配在 chunk 流里定位每行的真实起点；</li>
 *   <li>区间 = 范围内首个锚点 → 下一个锚点的前一块。</li>
 * </ol>
 * 书末尾常带一份内嵌目录（电子书排版留下的 Table of Contents），它会让每行都"命中"，
 * 因此含有 ≥{@value #TOC_TAIL_MIN_LINES} 个目录行的 chunk 一律不作为锚点目标。
 */
public final class TocAnchorResolver {

    /** 序数标记：第一章 / 第一辑 / 第一讲 / 第一部 … */
    private static final Pattern ORDINAL = Pattern.compile("^(第[〇零一二三四五六七八九十百千0-9]+[章辑讲部回篇卷])");

    /** chunk 内嵌目录行数达到此值即视为"书末尾目录块"，不作为锚点目标 */
    private static final int TOC_TAIL_MIN_LINES = 3;

    /** 非序数标题参与唯一匹配的最短长度（太短的通用词如「扉页」容易误命中） */
    private static final int MIN_TITLE_MATCH_LEN = 3;

    /** 制表符按 4 列计 */
    private static final int TAB_WIDTH = 4;

    private TocAnchorResolver() {
    }

    /**
     * 一行目录。
     *
     * @param number   行号（1-based）
     * @param trimmed  去首尾空白后的文字
     * @param indent   缩进列数（用来判断层级）
     * @param blockEnd 内容块的最后一行号（含）；自己即叶子节点
     */
    public record TocLine(int number, String trimmed, int indent, int blockEnd) {
    }

    /**
     * 解析目录文本 → 带层级信息的目录行。
     *
     * @param toc 原始目录文本（保留缩进）
     */
    public static List<TocLine> parseToc(String toc) {
        List<TocLine> lines = new ArrayList<>();
        if (toc == null || toc.isBlank()) return lines;

        List<String> raw = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        for (String s : toc.split("\\R")) {
            if (s.isBlank()) continue;
            raw.add(s.trim());
            indents.add(indentOf(s));
        }
        int n = raw.size();
        if (n == 0) return lines;

        // 内容块：向下延伸到第一个缩进 ≤ 自己的行之前
        int[] blockEnd = new int[n];
        Deque<Integer> stack = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            while (!stack.isEmpty() && indents.get(stack.peek()) >= indents.get(i)) {
                blockEnd[stack.pop()] = i - 1;
            }
            stack.push(i);
        }
        while (!stack.isEmpty()) {
            blockEnd[stack.pop()] = n - 1;
        }

        for (int i = 0; i < n; i++) {
            lines.add(new TocLine(i + 1, raw.get(i), indents.get(i), blockEnd[i] + 1));
        }
        return lines;
    }

    /**
     * 解析「目录行号 → 起始 chunkIndex」的锚点表。
     *
     * @param tocLines 目录行（{@link #parseToc} 的产物）
     * @param chunks   全书内容分块（内部按 chunkIndex 升序处理）
     * @return 行号（1-based）→ 起始 chunkIndex；无锚点的行不出现在表中
     */
    public static Map<Integer, Integer> resolveAnchors(List<TocLine> tocLines, List<ChunkInfo> chunks) {
        Map<Integer, Integer> anchors = new LinkedHashMap<>();
        if (tocLines == null || tocLines.isEmpty() || chunks == null || chunks.isEmpty()) {
            return anchors;
        }

        List<ChunkInfo> ordered = new ArrayList<>(chunks);
        ordered.removeIf(c -> c == null || c.chunkIndex() < 0 || c.text() == null || c.text().isBlank());
        if (ordered.isEmpty()) {
            return anchors;
        }
        ordered.sort(Comparator.comparingInt(ChunkInfo::chunkIndex));

        List<String> normalizedLines = new ArrayList<>(tocLines.size());
        for (TocLine line : tocLines) {
            normalizedLines.add(normalize(line.trimmed()));
        }
        List<Integer> tocTailChunks = findTocTailChunks(ordered, normalizedLines);

        int lastAnchor = -1;
        for (TocLine line : tocLines) {
            int anchor = -1;
            Matcher m = ORDINAL.matcher(line.trimmed());
            if (m.find()) {
                String marker = m.group(1);
                String title = line.trimmed().substring(marker.length()).trim();
                if (!title.isEmpty()) {
                    anchor = findAnchor(ordered, tocTailChunks, normalize(marker + title), -1);
                    if (anchor < 0) {
                        // 弱匹配：正文里标题与序数标记不连续。只接受晚于上一行锚点的命中，
                        // 避免正文里的交叉引用（「见第三章」）把锚点拉到章节之前。
                        anchor = findAnchor(ordered, tocTailChunks, normalize(marker), lastAnchor);
                    }
                }
            } else if (line.blockEnd() == line.number() && line.trimmed().length() >= MIN_TITLE_MATCH_LEN) {
                // 无序数标记的叶子行（如「序言 书中自有黄金屋」）：标题全文唯一命中才采信
                anchor = findUniqueAnchor(ordered, tocTailChunks, normalize(line.trimmed()), lastAnchor);
            }
            if (anchor < 0) continue;

            anchors.put(line.number(), anchor);
            lastAnchor = anchor;
        }
        return anchors;
    }

    /**
     * 由锚点表计算 chunkIndex 区间。
     * <p>
     * 范围会沿目录层级向右扩展到所选行的内容块末尾（选「上篇」这类容器行时，
     * 它自己未必有锚点，真正有用的是它下面的章节）。
     *
     * @param anchors    锚点表（行号 → 起始 chunkIndex）
     * @param tocLines   目录行（用于取内容块）
     * @param startLine  起始目录行号（1-based，闭区间）
     * @param endLine    结束目录行号（1-based，闭区间）
     * @param totalChunks 全书 chunk 数
     * @return {minIdx, maxIdx}；范围内没有任何锚点时返回 null（调用方回退到均分映射）
     */
    public static int[] resolveRange(Map<Integer, Integer> anchors, List<TocLine> tocLines,
                                     int startLine, int endLine, int totalChunks) {
        if (anchors == null || anchors.isEmpty() || tocLines == null || tocLines.isEmpty()) {
            return null;
        }
        if (startLine < 1 || endLine < startLine) return null;

        int effectiveEnd = endLine;
        for (TocLine line : tocLines) {
            if (line.number() < startLine || line.number() > endLine) continue;
            effectiveEnd = Math.max(effectiveEnd, line.blockEnd());
        }

        Integer first = null;
        Integer next = null;
        for (Map.Entry<Integer, Integer> e : anchors.entrySet()) {
            int number = e.getKey();
            if (number < startLine) continue;
            if (number > effectiveEnd) {
                if (next == null) next = e.getValue();
                continue;
            }
            if (first == null) first = e.getValue();
        }
        if (first == null) return null;

        int lo = Math.max(0, first - 1);
        int hi = next != null ? next - 1 : Math.max(0, totalChunks - 1);
        if (hi < lo) hi = lo;
        return new int[]{lo, hi};
    }

    /**
     * 找出"书末尾内嵌目录"所在的 chunk：正文里出现 ≥{@value #TOC_TAIL_MIN_LINES} 个目录行的块。
     */
    private static List<Integer> findTocTailChunks(List<ChunkInfo> ordered, List<String> normalizedLines) {
        List<Integer> tail = new ArrayList<>();
        for (ChunkInfo c : ordered) {
            String norm = normalize(c.text());
            int hit = 0;
            for (String line : normalizedLines) {
                if (!line.isEmpty() && norm.contains(line)) {
                    hit++;
                    if (hit >= TOC_TAIL_MIN_LINES) break;
                }
            }
            if (hit >= TOC_TAIL_MIN_LINES) {
                tail.add(c.chunkIndex());
            }
        }
        return tail;
    }

    /**
     * 在 chunk 流里找第一个包含 {@code key} 的块（跳过内嵌目录块）。
     *
     * @param minChunkIndex 允许命中的最小 chunkIndex（弱匹配时用来保证单调）
     */
    private static int findAnchor(List<ChunkInfo> ordered, List<Integer> tocTailChunks,
                                  String key, int minChunkIndex) {
        if (key.isEmpty()) return -1;
        for (ChunkInfo c : ordered) {
            if (c.chunkIndex() < minChunkIndex) continue;
            if (tocTailChunks.contains(c.chunkIndex())) continue;
            if (normalize(c.text()).contains(key)) {
                return c.chunkIndex();
            }
        }
        return -1;
    }

    /**
     * 唯一命中才算数：标题在正文里出现多次时无法确定是哪一处，放弃锚点。
     */
    private static int findUniqueAnchor(List<ChunkInfo> ordered, List<Integer> tocTailChunks,
                                        String key, int minChunkIndex) {
        if (key.isEmpty()) return -1;
        int hit = -1;
        for (ChunkInfo c : ordered) {
            if (c.chunkIndex() < minChunkIndex) continue;
            if (tocTailChunks.contains(c.chunkIndex())) continue;
            if (normalize(c.text()).contains(key)) {
                if (hit >= 0) return -1;   // 不唯一
                hit = c.chunkIndex();
            }
        }
        return hit;
    }

    /** 缩进列数（制表符按 4 列计） */
    private static int indentOf(String s) {
        int indent = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') indent += 1;
            else if (c == '\t') indent += TAB_WIDTH;
            else break;
        }
        return indent;
    }

    /** 去掉所有空白后比较，避免正文换行/空格导致标题对不上 */
    static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) sb.append(c);
        }
        return sb.toString();
    }
}
