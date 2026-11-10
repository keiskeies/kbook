package com.kbook.service.ai.rag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 合规三道防线第 3 道：答案侧「连续原文重合」检测与脱敏。
 *
 * <p>前两道分别是 System Prompt 的定性约束（禁止复述原文连续 20 字以上）与
 * {@code PayloadTextCodec} 的向量库脱敏。约束写得再硬也只是提示词，模型仍可能
 * 整段照抄；这里在答案落库/回传前做一次确定性校验：把答案与本轮真正进入上下文的
 * 书籍文本比对，找出长度 ≥ {@code minChars} 的逐字重合片段并替换成从略标记。
 *
 * <p><b>性能</b>：全文档静态语料可达数十万字，不能用 {@code Set<String>} 存所有窗口
 * （会产生几十万个字符串对象）。这里对源文本的定长窗口算多项式滚动哈希，存进
 * 原始 {@code long[]} 排序后用二分查找过滤；只有哈希命中时才做一次
 * {@code String.indexOf} 精确验证，兼顾速度与零误判。
 *
 * <p><b>边界</b>：只处理逐字重合，不做语义相似度判断；短引用、专有名词、
 * 章节标题不会被命中。不做归一化（空白差异会漏检），这是刻意取舍——
 * 归一化需要位置映射，会把改写后的答案切成碎片，反而伤可读性。
 */
public final class AnswerOverlapGuard {

    /** 默认阈值：与 System Prompt「禁止复述原文连续 20 字以上」留一档缓冲 */
    public static final int DEFAULT_MIN_CHARS = 30;
    public static final String REDACTION_MARK = "〔原文从略〕";

    private static final long BASE = 131L;

    private AnswerOverlapGuard() {}

    /** 检测结果：命中数量、最长重合长度、被替换的区间 [start,end)。 */
    public record Result(int hits, int maxOverlap, List<int[]> spans, String redactedAnswer) {
        public boolean hasOverlap() { return hits > 0; }
    }

    /**
     * 检查并生成脱敏后的答案。
     *
     * @param answer    模型原始回答（已过 sanitize）
     * @param sourceText 本轮真正进入上下文的书籍文本（RAG 上下文 + 全文/整章静态语料）
     * @param minChars  触发替换的最小连续重合长度
     */
    public static Result check(String answer, String sourceText, int minChars) {
        int n = Math.max(1, minChars);
        if (answer == null || answer.length() < n
                || sourceText == null || sourceText.length() < n) {
            return new Result(0, 0, List.of(), answer);
        }

        long[] sourceHashes = windowHashes(sourceText, n);
        Arrays.sort(sourceHashes);
        long[] answerPrefix = prefixHash(answer);
        long power = power(n);

        List<int[]> spans = new ArrayList<>();
        int maxOverlap = 0;
        int cursor = 0; // 已处理到的答案位置，避免重复替换同一段
        for (int i = 0; i + n <= answer.length(); i++) {
            if (i < cursor) continue;
            long h = hashAt(answerPrefix, i, n, power);
            if (Arrays.binarySearch(sourceHashes, h) < 0) continue;

            String window = answer.substring(i, i + n);
            int srcStart = sourceText.indexOf(window);
            if (srcStart < 0) continue; // 哈希碰撞，精确验证挡掉

            // 向左右扩展成最大重合区间
            int left = i;
            int s = srcStart;
            while (left > cursor && s > 0 && answer.charAt(left - 1) == sourceText.charAt(s - 1)) {
                left--;
                s--;
            }
            int right = i + n;
            int srcEnd = s + (right - left);
            while (right < answer.length() && srcEnd < sourceText.length()
                    && answer.charAt(right) == sourceText.charAt(srcEnd)) {
                right++;
                srcEnd++;
            }
            if (right - left < n) continue;
            spans.add(new int[]{left, right});
            maxOverlap = Math.max(maxOverlap, right - left);
            cursor = right; // 坐标始终基于原始 answer，不能混进替换标记的长度
            i = right - 1;
        }
        if (spans.isEmpty()) return new Result(0, 0, List.of(), answer);

        StringBuilder sb = new StringBuilder(answer.length());
        int at = 0;
        for (int[] span : spans) {
            sb.append(answer, at, span[0]).append(REDACTION_MARK);
            at = span[1];
        }
        sb.append(answer, at, answer.length());
        return new Result(spans.size(), maxOverlap, List.copyOf(spans), sb.toString());
    }

    /** 定长窗口哈希数组（未排序），长度 = len - n + 1。 */
    static long[] windowHashes(String s, int n) {
        long[] out = new long[Math.max(0, s.length() - n + 1)];
        long rolling = 0;
        long power = power(n);
        for (int i = 0; i < s.length(); i++) {
            rolling = rolling * BASE + s.charAt(i);
            if (i >= n) rolling -= (long) s.charAt(i - n) * power;
            if (i >= n - 1) out[i - n + 1] = rolling;
        }
        return out;
    }

    static long[] prefixHash(String s) {
        long[] p = new long[s.length() + 1];
        for (int i = 0; i < s.length(); i++) p[i + 1] = p[i] * BASE + s.charAt(i);
        return p;
    }

    static long hashAt(long[] prefix, int start, int n, long power) {
        return prefix[start + n] - prefix[start] * power;
    }

    static long power(int n) {
        long p = 1;
        for (int i = 0; i < n; i++) p *= BASE;
        return p;
    }
}
