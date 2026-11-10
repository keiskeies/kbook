package com.kbook.service.ai.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 合规第 3 道防线：答案侧连续原文重合检测，必须精确、不改无关内容、无哈希误判。 */
class AnswerOverlapGuardTest {

    private static final String BOOK =
            "第一章 多元思维模型\n"
            + "复利效应是长期投资的核心机制，它要求投资者把时间当作朋友而不是敌人，"
            + "并且在每一次决策里都留出安全边际，避免被单次极端结果击穿。"
            + "这一段完整的话就是我们要检测的原文片段，长度明显超过三十个汉字，"
            + "用来验证答案里整段照抄时能够被可靠地识别出来并替换成从略标记。\n"
            + "第二章 逆向思考\n逆向思考要求先问怎样会失败，再问怎样能成功。";

    @Test
    void shortAnswerOrMissingSourceIsNoOp() {
        AnswerOverlapGuard.Result r = AnswerOverlapGuard.check("一句短评", BOOK, 30);
        assertFalse(r.hasOverlap());
        assertEquals("一句短评", r.redactedAnswer());
        assertFalse(AnswerOverlapGuard.check("很长的一段回答", null, 30).hasOverlap());
        assertFalse(AnswerOverlapGuard.check(null, BOOK, 30).hasOverlap());
    }

    @Test
    void verbatimPassageIsRedacted() {
        String quoted = BOOK.substring(60, 60 + 48);
        String answer = "我的看法是：" + quoted + "。所以结论是应当长期持有。";

        AnswerOverlapGuard.Result r = AnswerOverlapGuard.check(answer, BOOK, 30);
        assertTrue(r.hasOverlap());
        assertEquals(1, r.hits());
        assertTrue(r.maxOverlap() >= 48);
        assertTrue(r.redactedAnswer().contains(AnswerOverlapGuard.REDACTION_MARK));
        assertFalse(r.redactedAnswer().contains(quoted), "原文连续片段不应留在最终答案里");
        assertTrue(r.redactedAnswer().startsWith("我的看法是："));
        assertTrue(r.redactedAnswer().endsWith("。所以结论是应当长期持有。"));
    }

    @Test
    void shortQuoteIsAllowed() {
        String shortQuote = BOOK.substring(60, 60 + 12);
        String answer = "可以引用「" + shortQuote + "」这个说法。";
        AnswerOverlapGuard.Result r = AnswerOverlapGuard.check(answer, BOOK, 30);
        assertFalse(r.hasOverlap());
        assertEquals(answer, r.redactedAnswer());
    }

    @Test
    void multipleSeparateQuotesAreAllRedacted() {
        String q1 = BOOK.substring(20, 20 + 40);
        String q2 = BOOK.substring(120, 120 + 40);
        String answer = "先引用：" + q1 + "；再引用：" + q2 + "；以上。";

        AnswerOverlapGuard.Result r = AnswerOverlapGuard.check(answer, BOOK, 30);
        assertEquals(2, r.hits());
        assertEquals(2, r.spans().size());
        assertEquals(2, countOccurrences(r.redactedAnswer(), AnswerOverlapGuard.REDACTION_MARK));
        assertTrue(r.redactedAnswer().endsWith("；以上。"));
    }

    @Test
    void ownWordsAreNeverFlagged() {
        String answer = "这本书强调的是跨学科思考框架，作者用多个学科的基础概念去审视同一个决策问题，"
                + "我认为最有价值的地方在于它把「避免愚蠢」放在「追求聪明」之前，"
                + "这与常见的投资入门书在取向上有明显区别，值得反复读。";
        AnswerOverlapGuard.Result r = AnswerOverlapGuard.check(answer, BOOK, 30);
        assertFalse(r.hasOverlap());
        assertEquals(answer, r.redactedAnswer());
    }

    @Test
    void rollingHashMatchesPrefixHashAtEveryPosition() {
        String s = "复利效应与安全边际，逆向思考、多元思维模型 A B C 12345";
        int n = 8;
        long[] windows = AnswerOverlapGuard.windowHashes(s, n);
        long[] prefix = AnswerOverlapGuard.prefixHash(s);
        long power = AnswerOverlapGuard.power(n);
        assertEquals(s.length() - n + 1, windows.length);
        for (int i = 0; i + n <= s.length(); i++) {
            assertEquals(AnswerOverlapGuard.hashAt(prefix, i, n, power), windows[i],
                    "位置 " + i + " 的滚动哈希必须与前缀哈希一致");
        }
    }

    @Test
    void detectsQuoteFromMiddleOfLongSource() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2000; i++) big.append("铺垫文字用于把源文本撑长到数万字。");
        String quote = "这一段是藏在长文中部的完整原文，专门用来验证哈希窗口不只在开头生效，"
                + "长度也已经明显超过三十字了。";
        big.insert(15000, quote);
        String answer = "回答里夹带了：" + quote + " 后面是评论。";

        AnswerOverlapGuard.Result r = AnswerOverlapGuard.check(answer, big.toString(), 30);
        assertTrue(r.hasOverlap());
        assertTrue(r.maxOverlap() >= quote.length() - 2);
    }

    private static int countOccurrences(String s, String token) {
        int count = 0, at = 0;
        while ((at = s.indexOf(token, at)) >= 0) { count++; at += token.length(); }
        return count;
    }
}
