package com.kbook.service.note;

import java.util.regex.Pattern;

/**
 * 从笔记正文生成一个够用的标题：取首个有效行，去掉 Markdown 标记后截断。
 * <p>
 * 刻意不用 LLM：标题只需要「大概有个」，确定性规则零延迟、零幻觉，也避免保存链路依赖模型可用性。
 */
public final class NoteTitleGenerator {

    public static final int MAX_TITLE_CHARS = 20;

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s*");
    private static final Pattern QUOTE = Pattern.compile("^>+\\s*");
    private static final Pattern TASK_LIST = Pattern.compile("^[-*+]\\s+\\[[ xX]]\\s*");
    private static final Pattern ORDERED_LIST = Pattern.compile("^\\d+[.)]\\s+");
    private static final Pattern BULLET = Pattern.compile("^[-*+]\\s+");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^[-*_]{3,}$");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^]]*)]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^]]*)]\\([^)]*\\)");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern INLINE_MARK = Pattern.compile("[*_~`]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private NoteTitleGenerator() {
    }

    /**
     * @param contentMd 笔记正文（Markdown）
     * @return 1-20 字标题；正文没有有效内容时返回 null
     */
    public static String from(String contentMd) {
        if (contentMd == null || contentMd.isBlank()) {
            return null;
        }
        for (String rawLine : contentMd.split("\\R")) {
            String line = normalizeLine(rawLine);
            if (line.isEmpty()) {
                continue;
            }
            return line.length() > MAX_TITLE_CHARS
                    ? line.substring(0, MAX_TITLE_CHARS) + "…"
                    : line;
        }
        return null;
    }

    private static String normalizeLine(String rawLine) {
        String line = rawLine.strip();
        if (line.isEmpty() || line.startsWith("```") || HORIZONTAL_RULE.matcher(line).matches()) {
            return "";
        }
        // 逐层剥掉可能叠加的块级前缀，如 "> - 今天读到..."
        String previous;
        do {
            previous = line;
            line = HEADING.matcher(line).replaceFirst("");
            line = QUOTE.matcher(line).replaceFirst("");
            line = TASK_LIST.matcher(line).replaceFirst("");
            line = ORDERED_LIST.matcher(line).replaceFirst("");
            line = BULLET.matcher(line).replaceFirst("");
            line = line.strip();
        } while (!line.isEmpty() && !line.equals(previous));

        line = IMAGE.matcher(line).replaceAll("$1");
        line = LINK.matcher(line).replaceAll("$1");
        line = HTML_TAG.matcher(line).replaceAll("");
        line = INLINE_MARK.matcher(line).replaceAll("");
        return WHITESPACE.matcher(line).replaceAll(" ").strip();
    }
}
