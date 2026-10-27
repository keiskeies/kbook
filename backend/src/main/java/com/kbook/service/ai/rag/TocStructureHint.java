package com.kbook.service.ai.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 从目录里挑出与问题相关的子树，作为「条目枚举」的证据。
 * <p>
 * 条目化（outline）只吃检索材料的<b>前 6000 字</b>，而窗口里每个章节的开头块彼此相隔上千字：
 * 「芒格私人书单」窗口覆盖了六辑正文（chunk 83/88/99/112/123/136），
 * 但 6000 字只够看到前两辑的标题 → 条目化只提取出 2 条 → 答案只展开 2 辑（实测 coverage 0.5）。
 * <p>
 * 目录本身就是书里的「枚举表」：读者问「分成哪几辑」，答案就是目录的那几行。
 * 这里用纯确定性匹配（读者引号里点名的章节名优先，其次列表主题）定位目录节点，
 * 取它的内容块（节点 + 子节点）作为条目化提示——不调模型、不臆造，只把书自己的目录摆到模型眼前。
 */
public final class TocStructureHint {

    /** 提示最多包含多少行目录（超长目录只取前若干行）。 */
    private static final int MAX_HINT_LINES = 24;

    /** 目录子行（条目枚举）的硬上限：只防异常目录，不参与正常清单截断。 */
    private static final int MAX_CHILD_LINES = 60;

    /** 提示最多多少字符。 */
    private static final int MAX_HINT_CHARS = 1200;

    /** 公共子串短于此长度不算「同一个东西」。 */
    private static final int MIN_COMMON_LEN = 5;

    /** 短语短于此长度不做匹配（避免「节」「篇」这类单字误命中）。 */
    private static final int MIN_PHRASE_LEN = 3;

    private TocStructureHint() {
    }

    /**
     * @param toc       书籍目录原文（保留缩进）
     * @param question  读者原问题（取引号里点名的章节名）
     * @param listTopic 列表主题（LLM 从问题里抽出来的）
     * @return 与问题相关的目录子树文本；没有可用结构时返回空串
     */
    public static String select(String toc, String question, String listTopic) {
        if (toc == null || toc.isBlank()) {
            return "";
        }
        List<TocAnchorResolver.TocLine> lines = TocAnchorResolver.parseToc(toc);
        if (lines.isEmpty()) {
            return "";
        }

        TocAnchorResolver.TocLine matched = matchByQuotedPhrase(lines, question);
        if (matched == null) {
            matched = matchByTopic(lines, listTopic);
        }
        if (matched == null) {
            return "";
        }
        // 叶子节点给不出枚举结构（没有子行 = 没有条目清单）
        if (matched.blockEnd() <= matched.number()) {
            return "";
        }
        return render(lines, matched);
    }

    /**
     * 同一棵子树的「直接子行」列表 —— 书自己的条目枚举。
     * <p>条目化本来要靠模型从检索材料里提条目，但目录已经是现成的枚举表：
     * 模型只是把目录行抄一遍，却要付 10~24s 的生成时间（实测条目化平均 10.95s，
     * 输出越长越慢：1291 tok 平均 23.5s），首轮 12s 预算下 25 条清单必然超时退回单次流式。
     * 目录有子行时直接用目录行当条目，零模型调用。
     *
     * @return 直接子行（缩进更深的那些行）；匹配不到节点或该节点没有子行时返回空列表
     */
    public static List<String> childTitles(String toc, String question, String listTopic) {
        if (toc == null || toc.isBlank()) {
            return List.of();
        }
        List<TocAnchorResolver.TocLine> lines = TocAnchorResolver.parseToc(toc);
        if (lines.isEmpty()) {
            return List.of();
        }
        TocAnchorResolver.TocLine matched = matchByQuotedPhrase(lines, question);
        if (matched == null) {
            matched = matchByTopic(lines, listTopic);
        }
        if (matched == null || matched.blockEnd() <= matched.number()) {
            return List.of();
        }
        List<String> children = new ArrayList<>();
        // 子行不受 MAX_HINT_LINES 限制：那是「喂给模型的提示」的体积上限，
        // 目录条目本身要全（实测「人类误判心理学」25 条，截到 24 就少一条）。
        int end = Math.min(matched.blockEnd(), matched.number() + MAX_CHILD_LINES);
        for (int number = matched.number() + 1; number <= end; number++) {
            TocAnchorResolver.TocLine line = findByNumber(lines, number);
            if (line == null || line.indent() <= matched.indent()) {
                continue;
            }
            String title = line.trimmed();
            if (!title.isBlank()) {
                children.add(title);
            }
        }
        return children;
    }

    /** 读者引号里点名的章节是权威信号：命中就用它，不再看主题。 */
    private static TocAnchorResolver.TocLine matchByQuotedPhrase(List<TocAnchorResolver.TocLine> lines,
                                                                 String question) {
        for (String phrase : TopicCoverageCheck.quotedPhrases(question)) {
            TocAnchorResolver.TocLine line = bestMatch(lines, phrase);
            if (line != null) {
                return line;
            }
        }
        return null;
    }

    /** 主题措辞常被 LLM 泛化（"芒格私人书单分类"），用公共子串兜住。 */
    private static TocAnchorResolver.TocLine matchByTopic(List<TocAnchorResolver.TocLine> lines,
                                                           String listTopic) {
        if (listTopic == null || listTopic.isBlank()) {
            return null;
        }
        return bestMatch(lines, listTopic);
    }

    /** 找出与短语最相关的目录行（公共子串最长者胜出，取最具体的那一行）。 */
    private static TocAnchorResolver.TocLine bestMatch(List<TocAnchorResolver.TocLine> lines, String phrase) {
        String np = TopicCoverageCheck.normalize(phrase);
        if (np.length() < MIN_PHRASE_LEN) {
            return null;
        }
        TocAnchorResolver.TocLine best = null;
        int bestScore = 0;
        for (TocAnchorResolver.TocLine line : lines) {
            String nl = TopicCoverageCheck.normalize(line.trimmed());
            if (nl.isEmpty()) {
                continue;
            }
            int score = commonLength(np, nl);
            if (score <= bestScore || !related(np, nl)) {
                continue;
            }
            bestScore = score;
            best = line;
        }
        return best;
    }

    /** 公共子串长度达到阈值才算相关：既要是子串，也要覆盖较短一方的一半以上。 */
    private static boolean related(String a, String b) {
        int common = commonLength(a, b);
        return common >= MIN_COMMON_LEN && common * 2 >= Math.min(a.length(), b.length());
    }

    /** 最长公共子串长度。 */
    private static int commonLength(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.contains(b) || b.contains(a)) {
            return Math.min(a.length(), b.length());
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        int max = 0;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    cur[j] = prev[j - 1] + 1;
                    if (cur[j] > max) {
                        max = cur[j];
                    }
                } else {
                    cur[j] = 0;
                }
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return max;
    }

    /** 渲染节点 + 内容块（子节点），缩进按匹配节点归零后保留层级。 */
    private static String render(List<TocAnchorResolver.TocLine> lines, TocAnchorResolver.TocLine matched) {
        StringBuilder sb = new StringBuilder(256);
        int end = Math.min(matched.blockEnd(), matched.number() + MAX_HINT_LINES - 1);
        for (int number = matched.number(); number <= end; number++) {
            TocAnchorResolver.TocLine line = findByNumber(lines, number);
            if (line == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            int relativeIndent = Math.max(0, line.indent() - matched.indent());
            sb.append("  ".repeat(relativeIndent / 2)).append(line.trimmed());
            if (sb.length() >= MAX_HINT_CHARS) {
                break;
            }
        }
        return sb.toString();
    }

    private static TocAnchorResolver.TocLine findByNumber(List<TocAnchorResolver.TocLine> lines, int number) {
        for (TocAnchorResolver.TocLine line : lines) {
            if (line.number() == number) {
                return line;
            }
        }
        return null;
    }
}
