package com.kbook.service.note;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoteTagResponseParserTest {

    private static final List<String> VOCABULARY = List.of("认知", "决策", "情绪", "AI");

    @Test
    @DisplayName("解析纯 JSON 数组")
    void parsesPlainArray() {
        assertThat(NoteTagResponseParser.parse("[\"认知\",\"决策\"]", VOCABULARY, 3))
                .containsExactly("认知", "决策");
    }

    @Test
    @DisplayName("容忍代码围栏和前后解释")
    void toleratesFenceAndExplanation() {
        String raw = "```json\n[\"情绪\"]\n```";
        assertThat(NoteTagResponseParser.parse(raw, VOCABULARY, 3)).containsExactly("情绪");

        assertThat(NoteTagResponseParser.parse("我选择：[\"认知\"] 以上", VOCABULARY, 3))
                .containsExactly("认知");
    }

    @Test
    @DisplayName("词库外的标签一律丢弃")
    void dropsOutOfVocabularyTags() {
        assertThat(NoteTagResponseParser.parse("[\"认知\",\"模型编造的新标签\"]", VOCABULARY, 3))
                .containsExactly("认知");
    }

    @Test
    @DisplayName("英文标签大小写不敏感，返回词库原值")
    void matchesCaseInsensitively() {
        assertThat(NoteTagResponseParser.parse("[\"ai\"]", VOCABULARY, 3)).containsExactly("AI");
    }

    @Test
    @DisplayName("去重并限制数量")
    void deduplicatesAndLimits() {
        assertThat(NoteTagResponseParser.parse("[\"认知\",\"认知\",\"决策\",\"情绪\",\"AI\"]", VOCABULARY, 2))
                .containsExactly("认知", "决策");
    }

    @Test
    @DisplayName("空数组、非法 JSON、非文本项都安全降级")
    void returnsEmptyOnBadOutput() {
        assertThat(NoteTagResponseParser.parse("[]", VOCABULARY, 3)).isEmpty();
        assertThat(NoteTagResponseParser.parse("不是 JSON", VOCABULARY, 3)).isEmpty();
        assertThat(NoteTagResponseParser.parse("[1,2,3]", VOCABULARY, 3)).isEmpty();
        assertThat(NoteTagResponseParser.parse(null, VOCABULARY, 3)).isEmpty();
        assertThat(NoteTagResponseParser.parse("[\"认知\"]", List.of(), 3)).isEmpty();
        assertThat(NoteTagResponseParser.parse("[\"认知\"]", VOCABULARY, 0)).isEmpty();
    }
}
