package com.kbook.common.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 圆桌派发言文本工具 — 输出清洗 + 相似度。
 * <p>
 * 弱指令跟随模型会顺着「角色名：内容」的历史格式续写，产出三类脏数据：
 * <ol>
 *   <li>名字前缀 —— "刘强东：特朗普，你拿关税……"</li>
 *   <li>串台 —— 说完自己又接着替下一位嘉宾发言（"……\n\n马化腾：你那套……"）</li>
 *   <li>第三人称旁白 —— "乔布斯对李彦宏和雷军的观点表示强烈反对，认为……"</li>
 * </ol>
 * 这里在落库前统一修掉，保证历史、覆盖度、报告、导出都不被污染。
 * <p>
 * 抽成静态工具类而不是留在 Service 里，是为了能脱离 Spring 上下文用真实脏数据验证。
 */
public final class SpeechSanitizer {

    /** 单条发言的最大保留长度（防止模型跑飞写成长文） */
    private static final int MAX_SPEECH_CHARS = 600;

    /** 剥离开头角色标签的最大层数（"**雷军**：雷军：……"） */
    private static final int MAX_LABEL_LAYERS = 3;

    private SpeechSanitizer() {
    }

    /**
     * 清洗一条角色发言。
     *
     * @param raw         模型原始输出
     * @param roleName    本条发言的角色名（用于识别旁白和串台）
     * @param allRoleNames 会话内全部角色名（用于剥前缀、认串台）
     */
    public static String sanitize(String raw, String roleName, List<String> allRoleNames) {
        if (raw == null || raw.isBlank()) return "";
        String text = raw.replace("\r\n", "\n").replace('\u00A0', ' ').trim();

        // 1. 去掉模型自作主张的收尾语
        text = text.replaceAll("(?m)^\\s*(?:以上|以上就是|我说完了|完毕)[。！]?\\s*$", "").trim();

        // 2. 剥离开头的角色标签
        text = stripLeadingRoleLabels(text, allRoleNames);

        // 2.5 剥掉"自己的名字 + 逗号"的自我称呼（模型把接力棒当成回应自己）
        text = stripSelfAddress(text, roleName);

        // 3. 截断串台
        text = truncateAtOtherSpeaker(text, roleName, allRoleNames);

        // 4. 修第三人称旁白开头
        text = fixNarrationOpening(text, roleName);

        // 5. 规整空白、去掉 Markdown 粗体
        text = text.replace("**", "").replaceAll("\n{3,}", "\n\n").trim();

        // 6. 超长截断（按句号收口，避免半句）
        if (text.length() > MAX_SPEECH_CHARS) {
            text = cutAtSentenceBoundary(text, MAX_SPEECH_CHARS);
        }
        return text;
    }

    /**
     * 剥离开头的「角色名：」「角色名说：」「**角色名**：」「【角色名】」等标签。
     * 长名优先匹配，避免"马化腾"被拆成"马化"这种半截匹配。
     */
    static String stripLeadingRoleLabels(String text, List<String> names) {
        String alternation = buildNameAlternation(names);
        if (alternation.isEmpty()) return text;

        Pattern p = Pattern.compile("^\\s*(?:\\*\\*)?\\s*【?\\s*(?:" + alternation
                + ")\\s*】?\\s*(?:说|表示|认为|指出|强调)?\\s*(?:\\*\\*)?\\s*[：:]\\s*");
        String out = text;
        for (int i = 0; i < MAX_LABEL_LAYERS; i++) {
            String next = p.matcher(out).replaceFirst("");
            if (next.equals(out)) break;
            out = next;
        }
        return out.trim();
    }

    /**
     * 剥掉"自己的名字 + 逗号"这种自我称呼开头。
     * <p>模型会把自己当成"刚被点到的人"来接话，输出"刘强东，你老把……"。
     * 例："刘强东，你老把隐性知识挂在嘴边" → "你老把隐性知识挂在嘴边"。
     */
    static String stripSelfAddress(String text, String roleName) {
        if (roleName == null || roleName.isBlank()) return text;
        Pattern p = Pattern.compile("^\\s*(?:\\*\\*)?\\s*【?\\s*" + Pattern.quote(roleName)
                + "\\s*】?\\s*(?:说|认为|表示)?\\s*[，,]\\s*");
        return p.matcher(text).replaceFirst("").trim();
    }

    /**
     * 截断串台：模型如果开始写"另一位嘉宾：……"，把后面整段砍掉。
     * <p>只处理出现在行首、且位置靠后（&gt;10 字符）的名字冒号，避免误伤正文里正常的点名。
     */
    static String truncateAtOtherSpeaker(String text, String roleName, List<String> names) {
        if (names == null || names.isEmpty()) return text;
        for (String name : names) {
            if (name == null || name.isBlank() || name.equals(roleName)) continue;
            Pattern p = Pattern.compile("(?m)^\\s*(?:\\*\\*)?\\s*【?\\s*" + Pattern.quote(name)
                    + "\\s*】?\\s*(?:说|表示|认为)?\\s*(?:\\*\\*)?\\s*[：:]");
            Matcher m = p.matcher(text);
            if (m.find() && m.start() > 10) {
                text = text.substring(0, m.start()).trim();
            }
        }
        return text;
    }

    /**
     * 修第三人称旁白开头：把"乔布斯对……表示反对，认为X"改写成"X"。
     */
    static String fixNarrationOpening(String text, String roleName) {
        if (roleName == null || roleName.isBlank()) return text;
        Pattern p = Pattern.compile("^\\s*" + Pattern.quote(roleName)
                + "\\s*(?:对|向|就|针对|表示|认为|指出|强调|回应)");
        if (!p.matcher(text).find()) return text;
        int cut = text.indexOf('，');
        if (cut < 0 || cut > 40) return text;
        String rest = text.substring(cut + 1).trim();
        rest = rest.replaceFirst("^(?:认为|指出|强调|表示|他觉得|她认为|他认为)[，,]?", "").trim();
        return rest.isEmpty() ? text : rest;
    }

    static String cutAtSentenceBoundary(String text, int max) {
        String head = text.substring(0, max);
        int last = Math.max(head.lastIndexOf('。'), Math.max(head.lastIndexOf('！'), head.lastIndexOf('？')));
        return last > max / 2 ? head.substring(0, last + 1) : head;
    }

    private static String buildNameAlternation(List<String> names) {
        if (names == null || names.isEmpty()) return "";
        return names.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(n -> !n.isEmpty())
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
    }

    // ==================== 相似度（复读 / 原地踏步检测） ====================

    /**
     * 字符二元组 Jaccard 相似度。中文短文本上比编辑距离稳定，且 O(n) 可接受。
     */
    public static double similarity(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return 0;
        Set<String> ga = bigrams(a);
        Set<String> gb = bigrams(b);
        if (ga.isEmpty() || gb.isEmpty()) return 0;
        int inter = 0;
        for (String g : ga) {
            if (gb.contains(g)) inter++;
        }
        return (double) inter / (ga.size() + gb.size() - inter);
    }

    static Set<String> bigrams(String s) {
        String t = s.replaceAll("[\\s\\p{Punct}，。！？、；：“”‘’（）《》…—·]", "");
        Set<String> set = new HashSet<>();
        for (int i = 0; i + 1 < t.length(); i++) {
            set.add(t.substring(i, i + 2));
        }
        return set;
    }

    /**
     * 判断两个名字列表是否有交集（供调用方拼装角色名时去重用）。
     */
    public static List<String> mergeNames(List<String> base, List<String> extra) {
        List<String> out = new ArrayList<>(base == null ? List.of() : base);
        if (extra != null) {
            for (String n : extra) {
                if (n != null && !n.isBlank() && !out.contains(n)) out.add(n);
            }
        }
        return out;
    }
}
