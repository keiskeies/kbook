package com.kbook.service.ai.rag;

import com.kbook.common.util.CommonUtils;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 图书问答的可答性守卫。
 * <p>
 * 检索结果为空时，决定"能不能不靠材料回答"：
 * <ul>
 *   <li>元问题（这本书讲了什么/值得读吗/作者是谁）→ 允许用图书信息回答；</li>
 *   <li>闲聊（你好/谢谢）→ 允许；</li>
 *   <li>书内事实问题（章节/人物/情节/观点/方法/为什么）→ 无材料时拒答，不猜。</li>
 * </ul>
 * 规则优先、纯函数、无外部依赖；分类不确定时默认按内容问题处理（严格）。
 */
public final class BookAnswerGuard {

    public enum QuestionType {
        /** 关于书本身的元问题，允许使用目录/摘要/元数据回答 */
        META,
        /** 书内事实问题，必须有检索材料 */
        CONTENT,
        /** 寒暄/情绪，不要求材料，也不得引入书中事实 */
        CHITCHAT
    }

    private static final Pattern CHAPTER_PATTERN =
            Pattern.compile("第[一二三四五六七八九十百千\\d]+\\s*[章节卷部篇]");

    private static final List<String> CONTENT_SIGNALS = List.of(
            "主角", "人物", "情节", "故事", "结局", "桥段", "例子", "案例",
            "观点", "论点", "方法", "步骤", "技巧", "写作", "文笔", "手法",
            "为什么", "怎么", "如何", "说过", "写道", "提到", "原文",
            "细节", "分析", "解释", "意思", "区别", "对比"
    );

    private static final List<String> META_SIGNALS = List.of(
            "这本书", "本书", "这书", "讲了什么", "讲什么", "主要讲", "主要内容",
            "核心观点", "主题", "结构", "目录", "值得读", "值得看",
            "适合谁", "适合什么人", "评价", "好看吗", "推荐吗", "怎么样",
            "评分", "作者", "谁写的", "多少页"
    );

    private static final List<String> CHITCHAT_SIGNALS = List.of(
            "你好", "您好", "在吗", "谢谢", "多谢", "再见", "拜拜",
            "哈哈", "呵呵", "嗯", "好的", "ok", "辛苦了", "晚安", "早安"
    );

    private BookAnswerGuard() {
    }

    public static QuestionType classify(String question) {
        if (question == null || question.isBlank()) {
            return QuestionType.CHITCHAT;
        }
        String q = question.trim().toLowerCase(Locale.ROOT);

        // 章节/正文类信号优先：避免"这本书"把"第三章讲了什么"误判成元问题
        if (CHAPTER_PATTERN.matcher(q).find()) {
            return QuestionType.CONTENT;
        }
        if (containsAny(q, CONTENT_SIGNALS)) {
            return QuestionType.CONTENT;
        }
        if (containsAny(q, META_SIGNALS)) {
            return QuestionType.META;
        }
        if (containsAny(q, CHITCHAT_SIGNALS)) {
            return QuestionType.CHITCHAT;
        }
        // 默认严格：没有依据时宁可拒答，不猜
        return QuestionType.CONTENT;
    }

    public static boolean shouldRefuse(String question) {
        return classify(question) == QuestionType.CONTENT;
    }

    /** 本轮入选片段的最高相似度分（无片段为 0）。 */
    public static double topScore(List<EmbeddingMatch<TextSegment>> matches) {
        if (matches == null || matches.isEmpty()) {
            return 0.0;
        }
        return matches.stream().mapToDouble(EmbeddingMatch::score).max().orElse(0.0);
    }

    /**
     * 常规检索是否低于绝对阈值。列表型不适用（其分值是 rank 序数分，不可与余弦分比较）。
     * 阈值 0 = 关闭；必须由黄金集标定后再开启。
     */
    public static boolean isLowScore(List<EmbeddingMatch<TextSegment>> matches,
                                     double minScore, boolean listQuery) {
        return minScore > 0 && !listQuery && topScore(matches) < minScore;
    }

    /** 预写拒答话术（不调用 LLM，不出现"参考内容"等元描述）。 */
    public static String refusalMessage(String question) {
        return "这本书里我没找到关于「" + extractTopic(question)
                + "」的内容。你可以换个说法，或者问我书里的其他部分。";
    }

    static String extractTopic(String question) {
        if (question == null) {
            return "这个问题";
        }
        String s = question
                .replaceAll("^(请问|请|帮我|我想问|想问|能不能|可以|麻烦)", "")
                .replaceAll("[？?！!。，,、；;：:\"“”‘’（）()\\[\\]【】\\s]", " ")
                .trim();
        if (s.length() < 2) {
            return "这个问题";
        }
        return CommonUtils.truncateText(s, 20);
    }

    private static boolean containsAny(String text, List<String> signals) {
        for (String signal : signals) {
            if (text.contains(signal)) {
                return true;
            }
        }
        return false;
    }
}
