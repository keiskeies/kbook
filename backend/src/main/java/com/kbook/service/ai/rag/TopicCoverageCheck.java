package com.kbook.service.ai.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 目录窗口是否真的覆盖了提问指向的主题。
 * <p>
 * 档 2（TOC_RANGE）的前提是「主题落在目录的某一行」。但目录并不完整：读者问的
 * 「如何获得幸福和成功」这一节根本不在目录里（实测 chunk 56/57，目录里只有「第二讲 如何获得精彩人生？」），
 * LLM 只能从目录里挑一个「最像的行」，窗口于是切到别的章节，答案对着错误的内容写。
 * <p>
 * 判定只用确定性的字符串匹配，不额外调模型：问题里被引号引起来的章节名（读者引的通常就是那一节的标题）
 * 与列表主题本身，只要有一个能在窗口正文里原样找到，就算窗口覆盖了主题；都找不到 → 窗口不可信，
 * 交给上层换检索策略，而不是拿着错误章节硬答。
 */
public final class TopicCoverageCheck {

    /** 成对引号：中文直角引号、书名号外的弯引号、直引号。 */
    private static final Pattern QUOTED =
            Pattern.compile("[「『“\"]([^」』”\"]{2,40})[」』”\"]");

    /** 短语短于此长度不做匹配（避免「节」「篇」这类单字误命中）。 */
    private static final int MIN_PHRASE_LEN = 3;

    /** 标题允许出现在块开头多少字以内才算"这一块就是这一节的起点"。 */
    private static final int TITLE_HEAD_WINDOW_CHARS = 40;

    private TopicCoverageCheck() {
    }

    /**
     * @param question    读者原问题（用于取引号里的章节名）
     * @param listTopic   列表主题（LLM 从问题里抽出来的）
     * @param windowTexts 目录窗口内的正文
     * @return 窗口是否覆盖了主题
     */
    public static boolean covered(String question, String listTopic, List<String> windowTexts) {
        if (windowTexts == null || windowTexts.isEmpty()) {
            return false;
        }
        List<String> normalizedTexts = new ArrayList<>(windowTexts.size());
        for (String text : windowTexts) {
            normalizedTexts.add(normalize(text));
        }

        // 读者引号里指名的章节是权威信号：主题短语往往是泛化措辞，
        // 拿它兜底会让"窗口切错章节"被误判成覆盖。
        List<String> quoted = quotedPhrases(question);
        if (!quoted.isEmpty()) {
            return anyFound(quoted, normalizedTexts);
        }
        if (listTopic != null && !listTopic.isBlank()) {
            return anyFound(List.of(listTopic.trim()), normalizedTexts);
        }
        return false;
    }

    /**
     * 给定短语是否出现在任一文本里（调用方已从问题里取好短语时使用）。
     * <p>与 {@link #covered} 同一匹配口径，只是不再从 question 重新取引号。
     */
    public static boolean coveredPhrases(List<String> phrases, String... texts) {
        if (phrases == null || phrases.isEmpty() || texts == null || texts.length == 0) {
            return false;
        }
        List<String> normalizedTexts = new ArrayList<>(texts.length);
        for (String text : texts) {
            normalizedTexts.add(normalize(text));
        }
        return anyFound(phrases, normalizedTexts);
    }

    /**
     * 标题是否出现在文本的**开头附近**（用来判定「这一块的正文就是从这一节开始」）。
     * <p>与 {@link #covered} 的区别：covered 只问"有没有提到"，会命中正文中的引用与小结；
     * 定位章节起点必须看位置。允许开头有空白、页眉残留或「第三讲」这类序号前缀。
     *
     * @param text        块正文
     * @param title       章节名（读者引号里的措辞）
     * @param headWindowChars 允许标题出现在开头多少字以内
     */
    public static boolean startsWithTitle(String text, String title, int headWindowChars) {
        String normalizedText = normalize(text);
        String normalizedTitle = normalize(title);
        if (normalizedText.isEmpty() || normalizedTitle.length() < MIN_PHRASE_LEN) {
            return false;
        }
        int at = normalizedText.indexOf(normalizedTitle);
        return at >= 0 && at <= headWindowChars;
    }

    /** 默认开头窗口：够容纳「第 X 讲」这类序号前缀与残留空白，又不会把正文中段的引用算成标题。 */
    public static boolean startsWithTitle(String text, String title) {
        return startsWithTitle(text, title, TITLE_HEAD_WINDOW_CHARS);
    }

    private static boolean anyFound(List<String> phrases, List<String> normalizedTexts) {        for (String phrase : phrases) {
            String normalized = normalize(phrase);
            if (normalized.length() < MIN_PHRASE_LEN) {
                continue;
            }
            for (String text : normalizedTexts) {
                if (text.contains(normalized)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 问题里引号引起来的内容。 */
    public static List<String> quotedPhrases(String question) {
        List<String> phrases = new ArrayList<>();
        if (question != null) {
            Matcher matcher = QUOTED.matcher(question);
            while (matcher.find()) {
                phrases.add(matcher.group(1).trim());
            }
        }
        return phrases;
    }

    /** 去掉全部空白：正文里的换行、空格、全角空格不该影响章节名的匹配。 */
    public static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }
}
