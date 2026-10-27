package com.kbook.service.ai;

import com.kbook.entity.AiConversation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多轮历史策略单测：最近一轮不被压缩，检测/扩展开使用原始回答。
 */
class BookChatSessionServiceCompressionTest {

    private static AiConversation conv(long id, String role, String content, String compressed) {
        return AiConversation.builder()
                .id(id)
                .role(role)
                .content(content)
                .compressedContent(compressed)
                .build();
    }

    @Test
    @DisplayName("多轮时跳过最近一轮 assistant")
    void protectsLatestAssistant() {
        List<AiConversation> history = List.of(
                conv(1, "user", "问题1", "问题1"),
                conv(2, "assistant", "回答1", "回答1"),
                conv(3, "user", "问题2", "问题2"),
                conv(4, "assistant", "回答2", "回答2"));

        List<AiConversation> compressible = BookChatSessionService.selectCompressible(history);

        assertThat(compressible).extracting(AiConversation::getId).containsExactly(2L);
        assertThat(BookChatSessionService.pickLastAnswerText(history)).isEqualTo("回答2");
    }

    @Test
    @DisplayName("只有一条 assistant 时仍允许压缩，避免单一长回答顶爆上下文")
    void singleAssistantIsStillCompressible() {
        List<AiConversation> history = List.of(
                conv(1, "user", "问题", "问题"),
                conv(2, "assistant", "回答", "回答"));

        assertThat(BookChatSessionService.selectCompressible(history))
                .extracting(AiConversation::getId)
                .containsExactly(2L);
    }

    @Test
    @DisplayName("已压缩的消息不重复压缩，最近一轮仍受保护")
    void alreadyCompressedIsExcluded() {
        List<AiConversation> history = List.of(
                conv(1, "assistant", "原始回答1", "摘要1"),
                conv(2, "assistant", "原始回答2", "原始回答2"),
                conv(3, "assistant", "原始回答3", "原始回答3"));

        assertThat(BookChatSessionService.selectCompressible(history))
                .extracting(AiConversation::getId)
                .containsExactly(2L);
    }

    @Test
    @DisplayName("取上轮回答优先原始文本，缺失回退压缩文本")
    void pickLastAnswerPrefersOriginal() {
        assertThat(BookChatSessionService.pickLastAnswerText(List.of(
                conv(1, "assistant", "原始回答", "压缩回答"))))
                .isEqualTo("原始回答");

        assertThat(BookChatSessionService.pickLastAnswerText(List.of(
                conv(1, "assistant", null, "压缩回答"))))
                .isEqualTo("压缩回答");

        assertThat(BookChatSessionService.pickLastAnswerText(List.of(
                conv(1, "user", "问题", "问题"))))
                .isNull();
    }

    @Test
    @DisplayName("超长上轮回答截到 6000 字")
    void longAnswerIsTruncated() {
        String longAnswer = "字".repeat(7000);

        assertThat(BookChatSessionService.pickLastAnswerText(List.of(
                conv(1, "assistant", longAnswer, longAnswer)))).hasSize(6000);
    }

    @Test
    @DisplayName("重新生成时识别最后一条用户消息，避免重复保存")
    void detectsLastUserQuestion() {
        List<AiConversation> history = List.of(
                conv(1, "user", "问题1", "问题1"),
                conv(2, "assistant", "回答1", "回答1"),
                conv(3, "user", "问题2", "问题2"));

        assertThat(BookChatSessionService.isLastUserQuestion(history, "问题2")).isTrue();
        assertThat(BookChatSessionService.isLastUserQuestion(history, "问题1")).isFalse();
        assertThat(BookChatSessionService.isLastUserQuestion(
                List.of(conv(1, "assistant", "回答", "回答")), "问题")).isFalse();
        assertThat(BookChatSessionService.isLastUserQuestion(null, "问题")).isFalse();
    }
}
