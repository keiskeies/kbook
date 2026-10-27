package com.kbook.service.book;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 章节结构提取：从净化后的全文里识别标题行，得到章节标题与字符偏移。
 * <p>
 * 不依赖 TOC（TOC 只有标题、没有偏移）；通过“独立成行 + 前置空行 + 章节样式”识别。
 * 识别不到时返回一个标题为 null 的全书区间——章节是可选元数据，不影响检索主流程。
 */
public final class TextStructureExtractor {

    /** 章节区间短于此值时视为目录条目/误判，不写标题。 */
    public static final int MIN_CHAPTER_CHARS = 300;

    private static final Pattern HEADING = Pattern.compile(
            "^(第[一二三四五六七八九十百千万零\\d]+[章节卷部篇]|Chapter\\s+\\d+).{0,60}$",
            Pattern.CASE_INSENSITIVE);

    public record Chapter(int index, String title, int startChar, int endChar) {
    }

    private TextStructureExtractor() {
    }

    public static List<Chapter> extract(String text) {
        List<Chapter> chapters = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chapters;
        }

        int len = text.length();
        int currentStart = 0;
        String currentTitle = null;
        int index = 0;

        int lineStart = 0;
        while (lineStart < len) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = len;
            }
            String line = text.substring(lineStart, lineEnd).trim();
            if (isHeading(text, lineStart, line)) {
                if (lineStart > currentStart) {
                    chapters.add(new Chapter(index++, currentTitle, currentStart, lineStart));
                }
                currentStart = lineStart;
                currentTitle = line;
            }
            lineStart = lineEnd + 1;
        }

        if (currentStart < len) {
            chapters.add(new Chapter(index, currentTitle, currentStart, len));
        }
        if (chapters.isEmpty()) {
            chapters.add(new Chapter(0, null, 0, len));
        }
        return chapters;
    }

    /** 找到包含指定字符偏移的章节；越界时返回最后一章。 */
    public static Chapter findChapter(List<Chapter> chapters, int offset) {
        if (chapters == null || chapters.isEmpty()) {
            return null;
        }
        for (Chapter chapter : chapters) {
            if (offset >= chapter.startChar() && offset < chapter.endChar()) {
                return chapter;
            }
        }
        return chapters.get(chapters.size() - 1);
    }

    private static boolean isHeading(String text, int lineStart, String line) {
        if (line.length() < 2 || line.length() > 80) {
            return false;
        }
        if (line.endsWith("。") || line.endsWith("，") || line.endsWith("；")) {
            return false;
        }
        if (!HEADING.matcher(line).matches()) {
            return false;
        }
        // 要求前置空行（或文件开头），避免把正文中的“第一章……”句子当标题
        return lineStart == 0 || (lineStart >= 2
                && text.charAt(lineStart - 1) == '\n'
                && text.charAt(lineStart - 2) == '\n');
    }
}
