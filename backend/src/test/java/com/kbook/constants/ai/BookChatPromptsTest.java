package com.kbook.constants.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图书问答提示词组装单测。
 * <p>
 * 重点：外部 ai-config.json 的风格词只能改语气/结构，事实边界必须由组装层强制注入；
 * 代码风格常量已含边界，组装必须幂等，不能重复拼接。
 */
class BookChatPromptsTest {

    private static int countOccurrences(String text, String marker) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(marker, idx)) >= 0) {
            count++;
            idx += marker.length();
        }
        return count;
    }

    @Test
    @DisplayName("外部风格词会强制加上事实边界，且只加一次")
    void withConstraintsAddsBoundaryOnce() {
        String external = "你是 KBook 的「图书伴读」AI，名叫「小K」。你说话像电报。";

        String assembled = BookChatPrompts.withConstraints(external);

        assertThat(assembled).startsWith(BookChatPrompts.BOOK_CHAT_CONSTRAINTS_PREFIX);
        assertThat(assembled).endsWith(external);
        assertThat(countOccurrences(assembled, BookChatPrompts.CONSTRAINT_MARKER)).isEqualTo(1);
        assertThat(assembled).contains("不要凭空标注章节号");
    }

    @Test
    @DisplayName("代码风格常量已含事实边界，组装幂等")
    void withConstraintsIsIdempotentForCodeStyles() {
        assertThat(BookChatPrompts.withConstraints(BookChatPrompts.BOOK_CHAT_STYLE_CASUAL))
                .isEqualTo(BookChatPrompts.BOOK_CHAT_STYLE_CASUAL);
        assertThat(BookChatPrompts.withConstraints(BookChatPrompts.BOOK_CHAT_STYLE_DEEP))
                .isEqualTo(BookChatPrompts.BOOK_CHAT_STYLE_DEEP);
        assertThat(BookChatPrompts.withConstraints(BookChatPrompts.BOOK_CHAT_STYLE_CONCISE))
                .isEqualTo(BookChatPrompts.BOOK_CHAT_STYLE_CONCISE);
        assertThat(BookChatPrompts.withConstraints(BookChatPrompts.BOOK_CHAT_STYLE_WITTY))
                .isEqualTo(BookChatPrompts.BOOK_CHAT_STYLE_WITTY);
    }

    @Test
    @DisplayName("null/空白风格词回退为纯事实边界")
    void withConstraintsHandlesBlank() {
        assertThat(BookChatPrompts.withConstraints(null))
                .isEqualTo(BookChatPrompts.BOOK_CHAT_CONSTRAINTS_PREFIX);
        assertThat(BookChatPrompts.withConstraints("   "))
                .isEqualTo(BookChatPrompts.BOOK_CHAT_CONSTRAINTS_PREFIX);
    }

    @Test
    @DisplayName("仓库内 ai-config.json 的 4 个风格都能被事实边界覆盖，且不再要求标注章节")
    void externalConfigStylesAreCovered() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config/ai-config.json")) {
            assertThat(in).as("classpath config/ai-config.json 必须存在").isNotNull();
            JsonNode styles = new ObjectMapper().readTree(in).at("/bookChat/styles");

            assertThat(styles.isArray()).isTrue();
            assertThat(styles.size()).isEqualTo(4);

            for (JsonNode style : styles) {
                String key = style.path("key").asText();
                String prompt = style.path("prompt").asText();

                assertThat(prompt)
                        .as("外部风格 %s 不应自带事实边界（由组装层统一注入）", key)
                        .doesNotContain(BookChatPrompts.CONSTRAINT_MARKER);
                assertThat(prompt)
                        .as("外部风格 %s 不应再要求标注章节号", key)
                        .doesNotContain("标注章节位置");

                String assembled = BookChatPrompts.withConstraints(prompt);
                assertThat(countOccurrences(assembled, BookChatPrompts.CONSTRAINT_MARKER))
                        .as("风格 %s 组装后应恰好包含一次事实边界", key)
                        .isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("关闭线性口语时提示词逐字节等于旧版，A/B 基线可复现")
    void linearSpeechDisabledKeepsLegacyPromptByteForByte() {
        String external = "你是 KBook 的「图书伴读」AI，名叫「小K」。";

        assertThat(BookChatPrompts.withConstraints(external, false))
                .isEqualTo(BookChatPrompts.withConstraints(external));
        assertThat(BookChatPrompts.withConstraints(BookChatPrompts.BOOK_CHAT_STYLE_DEEP, false))
                .isEqualTo(BookChatPrompts.BOOK_CHAT_STYLE_DEEP);
        assertThat(BookChatPrompts.versionOf(BookChatPrompts.withConstraints(external, false)))
                .isEqualTo(BookChatPrompts.versionOf(BookChatPrompts.withConstraints(external)));
    }

    @Test
    @DisplayName("开启线性口语时追加到风格锚点之后，且重复组装不会叠加")
    void linearSpeechIsAppendedOnceAtTheEnd() {
        String assembled = BookChatPrompts.withConstraints(BookChatPrompts.BOOK_CHAT_STYLE_DEEP, true);

        assertThat(assembled).startsWith(BookChatPrompts.BOOK_CHAT_STYLE_DEEP);
        assertThat(assembled).endsWith(BookChatPrompts.BOOK_CHAT_LINEAR_SPEECH);
        assertThat(countOccurrences(assembled, BookChatPrompts.LINEAR_SPEECH_MARKER)).isEqualTo(1);
        assertThat(countOccurrences(assembled, BookChatPrompts.CONSTRAINT_MARKER)).isEqualTo(1);
        assertThat(BookChatPrompts.withConstraints(assembled, true)).isEqualTo(assembled);
    }

    @Test
    @DisplayName("4 个风格常量与空风格词都能被线性口语层覆盖")
    void linearSpeechCoversAllStyles() {
        for (String style : new String[]{
                BookChatPrompts.BOOK_CHAT_STYLE_CASUAL,
                BookChatPrompts.BOOK_CHAT_STYLE_DEEP,
                BookChatPrompts.BOOK_CHAT_STYLE_CONCISE,
                BookChatPrompts.BOOK_CHAT_STYLE_WITTY,
                null}) {
            String assembled = BookChatPrompts.withConstraints(style, true);
            assertThat(assembled).contains(BookChatPrompts.LINEAR_SPEECH_MARKER);
            assertThat(assembled).contains("安全边界");
        }
    }

    @Test
    @DisplayName("线性口语层写明覆盖三段式、保留安全边界与列表豁免")
    void linearSpeechCarriesOverrideAndExceptionRules() {
        String speech = BookChatPrompts.BOOK_CHAT_LINEAR_SPEECH;

        assertThat(speech).contains("从结论到证据再到意义的写法");
        assertThat(speech).contains("【安全边界】");
        assertThat(speech).contains("【列表展开豁免】");
        assertThat(speech).contains("不用加粗，不用小标题，不用编号列表");
        // 列表流水线的「### 第 N 节」是硬解析契约，不能被「不要小标题」误伤
        assertThat(speech).contains("【本轮任务 = 第 X/Y 节】");
        assertThat(speech).contains("### 第 N 节");
        assertThat(BookChatPrompts.versionOf(speech)).hasSize(16);
    }

    @Test
    @DisplayName("两层节奏：分块数量由内容决定，块内只写可判定的推导关系")
    void linearSpeechDescribesTwoLevelRhythm() {
        String speech = BookChatPrompts.BOOK_CHAT_LINEAR_SPEECH;

        // 分几块不能硬编码；用户举的「5 个」只是举例，不是要写进提示词的上限
        assertThat(speech).contains("该分几块就分几块");
        assertThat(speech).contains("不设上限也不设下限");
        assertThat(speech).doesNotContain("分成两三个大类");
        assertThat(speech).doesNotContain("两三个");

        // 句间写的是关系类型和判定标准，不是把用户举的关联词抄成清单
        assertThat(speech).contains("后一句要能从上一句推出来");
        assertThat(speech).contains("可以是补充，可以是转折，可以是因果");
        assertThat(speech).contains("能顺着你的话复现");
        assertThat(speech).contains("能不能说出后一句是从前一句哪儿来的");
        assertThat(speech).doesNotContain("而且\"\"并且\"\"但是\"");
        assertThat(speech).doesNotContain("接下来说说");
        assertThat(speech).doesNotContain("这也带出了下一点");
        assertThat(speech).doesNotContain("不用每句都挂一个");

        // 换块只要求交代理由，不指定过渡句式
        assertThat(speech).contains("换块时让读者知道为什么要换");
        assertThat(speech).contains("不要套固定句式");

        // 真正要禁的是块内的论据排队
        assertThat(speech).contains("并排放几条互不相干的理由");
        assertThat(speech).doesNotContain("一件事没说完别开下一件");
        assertThat(speech).doesNotContain("不用\"第一、第二、第三\"");
    }

    @Test
    @DisplayName("解释要重说一遍，不塞进括号引号；提示词自身也不做坏的示范")
    void linearSpeechBansParentheticalExplanationAndAllowsRestatement() {
        String speech = BookChatPrompts.BOOK_CHAT_LINEAR_SPEECH;

        // 核心：怕没讲清就换个说法再说一遍，重说不算啰嗦
        assertThat(speech).contains("别把解释塞进括号或者引号里");
        assertThat(speech).contains("换个说法再说一遍");
        assertThat(speech).contains("说两遍不算啰嗦");
        assertThat(speech).contains("比喻之后还得落回直说");
        assertThat(speech).contains("要补就补在意思上");
        assertThat(speech).contains("有就把它拿出来，正经说一句");

        // 提示词自己不能再拿引号/括号包例子，否则模型照着学
        assertThat(speech).doesNotContain("\"这里涉及的核心机制\"");
        assertThat(speech).doesNotContain("\"这个我也拿不准\"");
        assertThat(speech).doesNotContain("（随和、认真、简短、调侃）");
        assertThat(speech).doesNotContain("\"综上所述\"");

        // 逐节流水线的硬契约仍在，但必须说明那不是给正文模仿的排版
        assertThat(speech).contains("【本轮任务 = 第 X/Y 节】");
        assertThat(speech).contains("### 第 N 节");
        assertThat(speech).contains("[[N]]");
        assertThat(speech).contains("不是让你在正文里学着用括号");
        // 公共前缀无法改（改了就破坏关闭开关时的逐字节回滚），
        // 只能显式声明前缀里的括号引号不构成排版示范
        assertThat(speech).contains("都是指令自己的排版，不是让你在回答里照着用的写法");
    }

    @Test
    @DisplayName("提示词版本稳定且对内容敏感")
    void versionOfIsStableAndSensitive() {
        String v1 = BookChatPrompts.versionOf("abc");

        assertThat(v1).hasSize(16);
        assertThat(BookChatPrompts.versionOf("abc")).isEqualTo(v1);
        assertThat(BookChatPrompts.versionOf("abd")).isNotEqualTo(v1);
        assertThat(BookChatPrompts.versionOf(null)).isEqualTo("none");
        assertThat(BookChatPrompts.versionOf("  ")).isEqualTo("none");
    }
}
