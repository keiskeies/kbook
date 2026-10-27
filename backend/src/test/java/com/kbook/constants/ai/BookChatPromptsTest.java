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
