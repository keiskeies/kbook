package com.kbook.service.book;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 入库前文本净化：把 HTML / PDF / OCR / TXT 的原始文本变成适合分块与检索的纯文本。
 * <p>
 * 只做确定性清洗，不改写内容：
 * <ul>
 *   <li>HTML：块级标签转换行、实体解码、剔除 script/style/nav；</li>
 *   <li>PDF/OCR：去独立页码行、英文断词续行、空白归一；</li>
 *   <li>TXT：BOM/UTF-8/GB18030 编码探测。</li>
 * </ul>
 */
public final class TextNormalizer {

    private static final Pattern DROP_BLOCK =
            Pattern.compile("(?is)<(script|style|nav|head)[^>]*>.*?</\\1\\s*>");
    private static final Pattern BLOCK_TAG =
            Pattern.compile("(?i)</?(p|div|h[1-6]|li|ul|ol|tr|table|blockquote|section|article|header|footer|pre|br)(\\s[^>]*)?/?>");
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ENTITY =
            Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);");
    private static final Pattern PAGE_NUMBER_LINE = Pattern.compile(
            "(?m)^[ \\t]*(?:[-—–]?[ \\t]*\\d{1,4}[ \\t]*[-—–]?|第[ \\t]*\\d+[ \\t]*页(?:[ \\t]*/[ \\t]*共[ \\t]*\\d+[ \\t]*页)?)[ \\t]*$\\n?");
    private static final Pattern DEHYPHEN =
            Pattern.compile("(\\p{L})-[ \\t]*\\n[ \\t]*(\\p{Ll})");
    private static final Pattern HORIZONTAL_SPACE = Pattern.compile("[\\t\\x0B\\f ]+");
    private static final Pattern MULTI_NEWLINE = Pattern.compile("\\n{3,}");

    private TextNormalizer() {
    }

    /** HTML → 纯文本：块级边界保留为换行，实体解码，剔除不可见内容。 */
    public static String htmlToText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String s = DROP_BLOCK.matcher(html).replaceAll("");
        s = BLOCK_TAG.matcher(s).replaceAll("\n");
        s = ANY_TAG.matcher(s).replaceAll("");
        s = decodeEntities(s);
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        s = HORIZONTAL_SPACE.matcher(s).replaceAll(" ");
        s = MULTI_NEWLINE.matcher(s).replaceAll("\n\n");
        return s.trim();
    }

    /** PDF/OCR/TXT 抽取文本的通用净化：去页码行、英文断词、空白归一。 */
    public static String normalizeExtractedText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String s = text.replace("\r\n", "\n").replace('\r', '\n');
        s = PAGE_NUMBER_LINE.matcher(s).replaceAll("");
        s = DEHYPHEN.matcher(s).replaceAll("$1$2");
        s = HORIZONTAL_SPACE.matcher(s).replaceAll(" ");
        s = MULTI_NEWLINE.matcher(s).replaceAll("\n\n");
        return s.trim();
    }

    /**
     * 读取文本文件并做编码探测：BOM → UTF-8 严格解码 → GB18030 回退。
     */
    public static String readText(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String text = decode(bytes);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return normalizeExtractedText(text);
    }

    static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            // 中文 TXT 常见 GBK/GB18030，UTF-8 严格解码失败时回退
            return new String(bytes, Charset.forName("GB18030"));
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按页净化：去掉在多页顶部/底部重复出现的页眉、页脚（如书名、章节名、站点名）。
     * <p>只把"短行 + 非句末标点 + 出现于 ≥ max(3, 50% 页)"的页边行视为页眉页脚；
     * 单页/双页文档不启用，避免误删正文。页码行由 {@link #normalizeExtractedText} 另行清理。
     */
    public static String cleanPagedText(List<String> pages) {
        if (pages == null || pages.isEmpty()) {
            return "";
        }
        List<List<String>> pageLines = new ArrayList<>(pages.size());
        Map<String, Integer> headCounts = new HashMap<>();
        Map<String, Integer> tailCounts = new HashMap<>();
        for (String page : pages) {
            List<String> lines = nonBlankLines(page);
            pageLines.add(lines);
            addEdgeCandidates(lines, true, headCounts);
            addEdgeCandidates(lines, false, tailCounts);
        }

        int threshold = Math.max(3, (int) Math.ceil(pages.size() * 0.5));
        Set<String> running = new HashSet<>();
        headCounts.forEach((line, count) -> {
            if (count >= threshold) running.add(line);
        });
        tailCounts.forEach((line, count) -> {
            if (count >= threshold) running.add(line);
        });
        if (running.isEmpty()) {
            return normalizeExtractedText(String.join("\n\n", pages));
        }

        StringBuilder sb = new StringBuilder();
        for (List<String> lines : pageLines) {
            boolean first = true;
            for (String line : lines) {
                if (running.contains(normalizeLine(line))) continue;
                if (!first) sb.append('\n');
                sb.append(line);
                first = false;
            }
            sb.append("\n\n");
        }
        return normalizeExtractedText(sb.toString());
    }

    private static List<String> nonBlankLines(String page) {
        List<String> lines = new ArrayList<>();
        if (page == null) return lines;
        for (String line : page.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) lines.add(trimmed);
        }
        return lines;
    }

    private static void addEdgeCandidates(List<String> lines, boolean head, Map<String, Integer> counts) {
        int edge = Math.min(2, lines.size());
        Set<String> seenOnPage = new HashSet<>();
        for (int i = 0; i < edge; i++) {
            String line = head ? lines.get(i) : lines.get(lines.size() - 1 - i);
            String key = normalizeLine(line);
            if (key.isEmpty() || key.length() > 40) continue;
            if (key.endsWith("。") || key.endsWith("！") || key.endsWith("？")
                    || key.endsWith(".") || key.endsWith("!") || key.endsWith("?")) {
                continue;
            }
            if (seenOnPage.add(key)) {
                counts.merge(key, 1, Integer::sum);
            }
        }
    }

    private static String normalizeLine(String line) {
        return line == null ? "" : line.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private static String decodeEntities(String text) {
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String entity = matcher.group(1);
            String replacement = entityReplacement(entity);
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(replacement != null ? replacement : matcher.group()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String entityReplacement(String entity) {
        if (entity.startsWith("#x") || entity.startsWith("#X")) {
            return codePoint(entity.substring(2), 16);
        }
        if (entity.startsWith("#")) {
            return codePoint(entity.substring(1), 10);
        }
        return switch (entity.toLowerCase()) {
            case "nbsp" -> " ";
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            case "ldquo" -> "“";
            case "rdquo" -> "”";
            case "lsquo" -> "‘";
            case "rsquo" -> "’";
            case "mdash" -> "—";
            case "ndash" -> "–";
            case "hellip" -> "…";
            default -> null;
        };
    }

    private static String codePoint(String value, int radix) {
        try {
            int cp = Integer.parseInt(value, radix);
            if (cp <= 0 || cp > Character.MAX_CODE_POINT) {
                return "";
            }
            return new String(Character.toChars(cp));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
