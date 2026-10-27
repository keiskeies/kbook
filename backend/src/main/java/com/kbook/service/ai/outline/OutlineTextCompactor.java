package com.kbook.service.ai.outline;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 条目化输入的边界感知压缩（分块专项 §2.3）。
 * <p>
 * 旧的 {@code substring(0, 6000)} 会切在句中，且长列表的尾部条目连标题都进不了条目化，
 * 直接导致 outline 少项 → 流水线节数变少 → 漏答。这里做两件事：
 * <ol>
 *   <li>列表型文本：只保留"完整条目"，被截掉的条目以<b>标题清单</b>附在末尾，
 *       条目化模型仍能看到全部条目名；</li>
 *   <li>非列表文本：退到段落 → 换行 → 句末 → 分句边界，都找不到才硬切。</li>
 * </ol>
 * 仅在喂给模型前使用；入库 chunk 文本保持完整。
 */
final class OutlineTextCompactor {

    /** 续文标记：明确告知模型"后面还有正文但未提供"，避免它把截断当成全文结束 */
    static final String TRUNCATION_NOTE = "\n\n【注：后续内容因长度上限未提供】";

    static final String TAIL_HEADER = "\n\n【其余条目（仅标题，正文因长度上限未提供）】\n";

    private static final Pattern ITEM_START = Pattern.compile(
            "(?m)^[ \\t]*(?:[0-9]{1,3}[.、)）]|[（(][0-9]{1,3}[)）]|[-*•·][ \\t]+|[一二三四五六七八九十百]{1,3}[、.])");

    private static final Pattern MARKER_PREFIX = Pattern.compile(
            "^[ \\t]*(?:[0-9]{1,3}[.、)）]|[（(][0-9]{1,3}[)）]|[-*•·][ \\t]+|[一二三四五六七八九十百]{1,3}[、.])[ \\t]*");

    private static final String SENTENCE_ENDS = "。！？…!?";
    private static final String TRAILING_CLOSERS = "\"'”’）)】」』";
    private static final String CLAUSE_ENDS = "，、；;：:,";
    /** 标题候选的截断符：条目行常是「标题：正文」，冒号前即标题 */
    private static final String TITLE_ENDS = "：:，,。；;、!！?？—–-";
    private static final int TAIL_TITLE_MAX = 24;

    private OutlineTextCompactor() {
    }

    /**
     * @param text     原始输入
     * @param maxChars 允许的最大长度（含追加的说明文字）
     * @return 不超过 maxChars 的压缩结果
     */
    static String compact(String text, int maxChars) {
        if (text == null || maxChars <= 0) {
            return text;
        }
        if (text.length() <= maxChars) {
            return text;
        }
        int hardLimit = maxChars - TRUNCATION_NOTE.length();
        if (hardLimit <= 0) {
            return text.substring(0, maxChars);
        }

        List<Integer> starts = itemStarts(text);
        if (starts.size() >= 3) {
            // 目标：正文尽量多，且"被截掉的条目"一个不漏地以标题出现在清单里。
            // 升序尝试切点：head 变长、标题清单变短；保留最后一个两者都放得下的 k。
            int bestK = -1;
            String bestTail = null;
            for (int k = 1; k < starts.size(); k++) {
                int headLen = starts.get(k);
                if (headLen > hardLimit) {
                    break;
                }
                String tail = tailTitles(text, starts, k);
                if (headLen + tail.length() > maxChars) {
                    continue;
                }
                bestK = k;
                bestTail = tail;
            }
            if (bestK > 0) {
                String head = text.substring(0, starts.get(bestK)).stripTrailing();
                return head + bestTail;
            }
        }
        return truncateAtBoundary(text, hardLimit);
    }

    static String truncateAtBoundary(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        int minKeep = Math.max(1, (int) (limit * 0.6));
        int cut = -1;

        int idx = text.lastIndexOf("\n\n", limit - 1);
        if (idx >= minKeep) {
            cut = idx;
        }
        if (cut < 0) {
            idx = text.lastIndexOf('\n', limit - 1);
            if (idx >= minKeep) {
                cut = idx;
            }
        }
        if (cut < 0) {
            cut = lastIndexOfAny(text, limit, minKeep, SENTENCE_ENDS, true);
        }
        if (cut < 0) {
            cut = lastIndexOfAny(text, limit, minKeep, CLAUSE_ENDS, false);
        }
        if (cut < 0) {
            // 连分句标点都没有：只能硬切，且仍保证总长不超过 limit
            cut = Math.max(1, limit - TRUNCATION_NOTE.length());
        }
        String head = text.substring(0, Math.min(cut, text.length())).stripTrailing();
        return head + TRUNCATION_NOTE;
    }

    private static int lastIndexOfAny(String text, int limit, int minKeep, String chars, boolean extendClosers) {
        for (int i = Math.min(limit, text.length()) - 1; i >= minKeep; i--) {
            if (chars.indexOf(text.charAt(i)) < 0) {
                continue;
            }
            int end = i + 1;
            if (extendClosers) {
                while (end < text.length() && TRAILING_CLOSERS.indexOf(text.charAt(end)) >= 0) {
                    end++;
                }
            }
            return end;
        }
        return -1;
    }

    private static List<Integer> itemStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        Matcher m = ITEM_START.matcher(text);
        while (m.find()) {
            starts.add(m.start());
        }
        return starts;
    }

    private static String tailTitles(String text, List<Integer> starts, int from) {
        StringBuilder sb = new StringBuilder(TAIL_HEADER);
        for (int i = from; i < starts.size(); i++) {
            String line = firstLine(text, starts.get(i));
            String title = MARKER_PREFIX.matcher(line).replaceFirst("").strip();
            title = shortenTitle(title);
            if (title.isEmpty()) {
                continue;
            }
            sb.append("- ").append(title).append('\n');
        }
        return sb.toString();
    }

    /**
     * 条目行常是「标题：正文」或「标题，正文」；取第一个标点前的一段当标题，避免把整行正文塞进清单。
     */
    private static String shortenTitle(String raw) {
        if (raw.isEmpty()) {
            return raw;
        }
        int cut = -1;
        for (int i = 0; i < raw.length(); i++) {
            if (TITLE_ENDS.indexOf(raw.charAt(i)) >= 0) {
                cut = i;
                break;
            }
        }
        String title = cut > 0 ? raw.substring(0, cut) : raw;
        title = title.strip();
        if (title.length() > TAIL_TITLE_MAX) {
            title = title.substring(0, TAIL_TITLE_MAX) + "…";
        }
        return title;
    }

    private static String firstLine(String text, int start) {
        int end = text.indexOf('\n', start);
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }
}