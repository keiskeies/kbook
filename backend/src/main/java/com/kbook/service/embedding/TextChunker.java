package com.kbook.service.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构感知分块器：优先在段落、句子边界切分，避免把句子/段落从中间截断。
 * <p>
 * 与旧实现相比：
 * <ul>
 *   <li>句子边界优先于换行边界（PDF/OCR 常见中句硬换行）；</li>
 *   <li>单句超过上限才硬切，并标记 {@link CutReason#HARD}；</li>
 *   <li>保留原文起始/结束偏移，供后续章节映射、引用与合并去重；</li>
 *   <li>末块过短时并回前一块。</li>
 * </ul>
 */
public final class TextChunker {

    /** 单块最小字符数（末块低于此值尝试并回前块）。 */
    public static final int MIN_CHUNK_CHARS = 200;
    /** 单块最大字符数（单句超限时才允许硬切到这里）。 */
    public static final int MAX_CHUNK_CHARS = 1200;

    public enum CutReason {
        PARAGRAPH, SENTENCE, LINE, CLAUSE, WORD, HARD, END
    }

    public record Chunk(String text, int startChar, int endChar, CutReason reason) {
        public boolean hardCut() {
            return reason == CutReason.HARD;
        }
    }

    private record Boundary(int index, CutReason reason) {
    }

    private TextChunker() {
    }

    public static List<Chunk> split(String content, int targetSize, int overlap) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        int target = Math.max(MIN_CHUNK_CHARS, targetSize);
        int maxSize = Math.max(target, MAX_CHUNK_CHARS);
        int overlapSize = Math.max(0, Math.min(overlap, target / 2));
        int len = content.length();

        List<Chunk> chunks = new ArrayList<>();
        int pos = 0;
        while (pos < len) {
            while (pos < len && Character.isWhitespace(content.charAt(pos))) {
                pos++;
            }
            if (pos >= len) {
                break;
            }

            int idealEnd = Math.min(len, pos + target);
            int maxEnd = Math.min(len, pos + maxSize);
            Boundary boundary = findBoundary(content, pos, idealEnd, maxEnd);
            int end = Math.min(boundary.index(), len);
            if (end <= pos) {
                end = idealEnd;
            }

            String text = content.substring(pos, end).trim();
            if (!text.isEmpty()) {
                chunks.add(new Chunk(text, pos, end, boundary.reason()));
            }
            if (end >= len) {
                break;
            }

            int next = end - overlapSize;
            if (next <= pos || next >= end) {
                // 块太短或重叠会吃掉整块：放弃重叠，从块尾继续
                next = end;
            } else {
                next = snapOverlapStart(content, pos, next, end, overlapSize);
            }
            pos = next;
        }
        return mergeTinyTail(content, chunks);
    }

    private static Boundary findBoundary(String content, int pos, int idealEnd, int maxEnd) {
        if (idealEnd >= content.length()) {
            return new Boundary(content.length(), CutReason.END);
        }
        int minEnd = Math.min(idealEnd, pos + MIN_CHUNK_CHARS);

        // 1. 向后找段落边界（允许略长，换取完整段落）
        for (int i = idealEnd; i < maxEnd - 1; i++) {
            if (isParagraphBreak(content, i)) {
                return new Boundary(i + 2, CutReason.PARAGRAPH);
            }
        }
        // 2. 向前找句子边界（优先于换行，避免 PDF/OCR 中句换行把句子切断）
        for (int i = idealEnd; i < maxEnd; i++) {
            if (isSentenceEnd(content.charAt(i))) {
                return new Boundary(consumeClosing(content, i + 1), CutReason.SENTENCE);
            }
        }
        // 3. 向后找段落边界
        for (int i = Math.min(idealEnd, maxEnd) - 2; i >= minEnd; i--) {
            if (isParagraphBreak(content, i)) {
                return new Boundary(i + 2, CutReason.PARAGRAPH);
            }
        }
        // 4. 向后找句子边界
        for (int i = Math.min(idealEnd, maxEnd) - 1; i >= minEnd; i--) {
            if (isSentenceEnd(content.charAt(i))) {
                return new Boundary(consumeClosing(content, i + 1), CutReason.SENTENCE);
            }
        }
        // 5. 向后找换行
        for (int i = Math.min(idealEnd, maxEnd) - 1; i >= minEnd; i--) {
            if (content.charAt(i) == '\n') {
                return new Boundary(i + 1, CutReason.LINE);
            }
        }
        // 6. 向后找分句标点
        for (int i = Math.min(idealEnd, maxEnd) - 1; i >= minEnd; i--) {
            if (isClauseEnd(content.charAt(i))) {
                return new Boundary(i + 1, CutReason.CLAUSE);
            }
        }
        // 7. 向后找词边界（英文/空格分隔文本）
        for (int i = Math.min(idealEnd, maxEnd) - 1; i >= minEnd; i--) {
            if (Character.isWhitespace(content.charAt(i))) {
                return new Boundary(i + 1, CutReason.WORD);
            }
        }
        // 8. 单句/无标点超长：硬切到上限并标记
        return new Boundary(Math.min(maxEnd, Math.max(idealEnd, pos + MIN_CHUNK_CHARS)), CutReason.HARD);
    }

    private static int snapOverlapStart(String content, int pos, int next, int end, int overlapSize) {
        int candidate = Math.max(pos + 1, next);
        int lowerBound = Math.max(pos + 1, candidate - overlapSize / 2);
        for (int i = candidate; i > lowerBound; i--) {
            if (isSentenceEnd(content.charAt(i - 1))) {
                int snapped = consumeClosing(content, i);
                return snapped < end ? snapped : candidate;
            }
            if (content.charAt(i - 1) == '\n') {
                return i;
            }
        }
        return candidate;
    }

    private static List<Chunk> mergeTinyTail(String content, List<Chunk> chunks) {
        if (chunks.size() < 2) {
            return chunks;
        }
        Chunk last = chunks.get(chunks.size() - 1);
        if (last.text().length() >= MIN_CHUNK_CHARS) {
            return chunks;
        }
        Chunk previous = chunks.get(chunks.size() - 2);
        int combinedEnd = last.endChar();
        if (combinedEnd - previous.startChar() > MAX_CHUNK_CHARS) {
            return chunks;
        }
        String text = content.substring(previous.startChar(), combinedEnd).trim();
        List<Chunk> merged = new ArrayList<>(chunks.subList(0, chunks.size() - 2));
        merged.add(new Chunk(text, previous.startChar(), combinedEnd, previous.reason()));
        return merged;
    }

    private static boolean isParagraphBreak(String content, int index) {
        return index >= 0 && index + 1 < content.length()
                && content.charAt(index) == '\n' && content.charAt(index + 1) == '\n';
    }

    private static boolean isSentenceEnd(char c) {
        return c == '。' || c == '！' || c == '？' || c == '…'
                || c == '.' || c == '!' || c == '?';
    }

    private static boolean isClauseEnd(char c) {
        return c == '，' || c == '、' || c == ',' || c == ';' || c == '；' || c == ':' || c == '：';
    }

    private static int consumeClosing(String content, int end) {
        int i = end;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (c == '”' || c == '’' || c == '」' || c == '』' || c == '）' || c == ')'
                    || c == '】' || c == ']' || c == '"' || c == '\'') {
                i++;
            } else {
                break;
            }
        }
        return i;
    }
}
