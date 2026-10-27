package com.kbook.service.embedding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 入库质量闸门（入库计划 §3.1 第 5 条）。
 * <p>
 * 分块后、嵌入前的最后一道确定性问题过滤：空块、极短块、乱码、纯符号、完全重复块
 * 一律不进向量库。乱码块进了索引不只是浪费存储——它会被检索命中并当成"书中原文"
 * 喂给模型，直接污染答案。
 * <p>
 * 纯函数、无 IO，便于单测与统计。
 */
public final class ContentQualityGate {

    /** 低于此长度且不是全文唯一块时视为噪声（目录残行、页眉碎片）。 */
    public static final int MIN_ACCEPT_CHARS = 30;
    /** 替换符/问号占比超过此值判为乱码。 */
    public static final double MAX_GARBLED_RATIO = 0.02;
    /** 汉字/字母/数字之外字符占比超过此值判为噪声（排版符号墙）。 */
    public static final double MAX_SYMBOL_RATIO = 0.85;
    /**
     * 仅对不超过此长度的块做"全书完全重复"去重。
     * <p>重复页眉/页脚/免责声明都是短块；长段落全书逐字重复更可能是书本身的合法重复内容
     * （练习题、引用整段原文），整块删掉属于误伤。
     */
    public static final int MAX_DEDUP_CHARS = 600;

    public enum RejectReason {
        BLANK, TOO_SHORT, GARBLED, SYMBOL_ONLY, DUPLICATE
    }

    public record Decision(boolean accepted, RejectReason reason) {
        static Decision pass() {
            return new Decision(true, null);
        }

        static Decision reject(RejectReason reason) {
            return new Decision(false, reason);
        }
    }

    public record Result(List<TextChunker.Chunk> accepted, Map<RejectReason, Integer> rejectedCounts) {
        public int rejected() {
            return rejectedCounts.values().stream().mapToInt(Integer::intValue).sum();
        }

        public boolean garbledHeavy() {
            int total = accepted.size() + rejected();
            return total > 0 && rejectedCounts.getOrDefault(RejectReason.GARBLED, 0) * 10 > total;
        }
    }

    private ContentQualityGate() {
    }

    /**
     * 批量过滤；重复判定在本次调用范围内生效（重复页眉、目录与正文重复段落）。
     * <p>文档本身极短（全文不足 {@link #MIN_ACCEPT_CHARS}）时不做最短长度限制，避免把短文档整本拒掉。
     */
    public static Result filter(List<TextChunker.Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return new Result(List.of(), Map.of());
        }
        int docLen = chunks.stream().mapToInt(c -> c.text() == null ? 0 : c.text().length()).sum();
        boolean shortDoc = docLen < MIN_ACCEPT_CHARS;

        List<TextChunker.Chunk> accepted = new ArrayList<>(chunks.size());
        Map<RejectReason, Integer> rejected = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();

        for (TextChunker.Chunk chunk : chunks) {
            Decision d = evaluate(chunk.text(), seen, shortDoc);
            if (d.accepted()) {
                accepted.add(chunk);
            } else {
                rejected.merge(d.reason(), 1, Integer::sum);
            }
        }
        return new Result(accepted, rejected);
    }

    static Decision evaluate(String rawText, Set<String> seenSignatures, boolean shortDoc) {
        if (rawText == null || rawText.isBlank()) {
            return Decision.reject(RejectReason.BLANK);
        }
        String text = rawText.strip();
        if (!shortDoc && text.length() < MIN_ACCEPT_CHARS) {
            return Decision.reject(RejectReason.TOO_SHORT);
        }
        if (garbledRatio(text) > MAX_GARBLED_RATIO) {
            return Decision.reject(RejectReason.GARBLED);
        }
        if (symbolRatio(text) > MAX_SYMBOL_RATIO) {
            return Decision.reject(RejectReason.SYMBOL_ONLY);
        }
        String signature = signature(text);
        if (signature.length() <= MAX_DEDUP_CHARS && !seenSignatures.add(signature)) {
            return Decision.reject(RejectReason.DUPLICATE);
        }
        return Decision.pass();
    }

    /** 检索侧同样适用的乱码判定：与入库闸门同一阈值，避免"入库拦了、检索又放进来"。 */
    public static boolean isGarbled(String text) {
        return garbledRatio(text) > MAX_GARBLED_RATIO;
    }

    /** 替换符（U+FFFD）与问号占非空白字符的比例。 */
    public static double garbledRatio(String text) {
        CharStats stats = charStats(text);
        return stats.ratio();
    }

    /** 非空白字符数与其中的坏字符数（替换符 U+FFFD、空框 U+25A1、问号）。 */
    public record CharStats(int total, int bad) {
        public double ratio() {
            return total == 0 ? 0 : (double) bad / total;
        }
    }

    /** 坏字符计数入口：乱码判定与「入库后字符级乱码率」共用同一套坏字符定义。 */
    public static CharStats charStats(String text) {
        int total = 0;
        int bad = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            total++;
            if (c == '\uFFFD' || c == '\u25A1' || c == '?') {
                bad++;
            }
        }
        return new CharStats(total, bad);
    }

    /** 非文字符号占非空白字符的比例（汉字、字母、数字、常用标点视为正常）。 */
    public static double symbolRatio(String text) {
        int total = 0;
        int symbols = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            total++;
            if (!Character.isLetterOrDigit(c) && ",.!?;:'\"()[]{}<>-—–…、。，；：！？（）《》【】「」『』·".indexOf(c) < 0) {
                symbols++;
            }
        }
        return total == 0 ? 0 : (double) symbols / total;
    }

    /** 归一化签名：折叠空白，用于识别逐字重复的块。 */
    static String signature(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean prevSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!prevSpace) {
                    sb.append(' ');
                }
                prevSpace = true;
            } else {
                sb.append(Character.toLowerCase(c));
                prevSpace = false;
            }
        }
        return sb.toString().strip();
    }
}