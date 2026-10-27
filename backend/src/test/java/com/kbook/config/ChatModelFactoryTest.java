package com.kbook.config;

import com.kbook.entity.AiProviderConfig;
import com.kbook.entity.AiScene;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图书问答输出上限兜底单测：只在未显式配置时生效，且不能超过上下文的一半。
 */
class ChatModelFactoryTest {

    @Test
    @DisplayName("BOOK_QA 未配置时兜底 16384（25 条清单题实测 4096 处被腰斩）")
    void bookQaDefaultsTo16384() {
        AiProviderConfig config = AiProviderConfig.builder().maxTokens(32768).build();

        assertThat(ChatModelFactory.effectiveMaxOutputTokens(AiScene.BOOK_QA, config)).isEqualTo(16384);
    }

    @Test
    @DisplayName("BOOK_QA 兜底仍受上下文一半约束")
    void bookQaRespectsContextCap() {
        AiProviderConfig small = AiProviderConfig.builder().maxTokens(4000).build();

        assertThat(ChatModelFactory.effectiveMaxOutputTokens(AiScene.BOOK_QA, small)).isEqualTo(2000);
    }

    @Test
    @DisplayName("显式配置优先，不做兜底")
    void explicitConfigWins() {
        AiProviderConfig config = AiProviderConfig.builder()
                .maxTokens(32768)
                .maxOutputTokens(1000)
                .build();

        assertThat(ChatModelFactory.effectiveMaxOutputTokens(AiScene.BOOK_QA, config)).isEqualTo(1000);
    }

    @Test
    @DisplayName("BOOK_QA_SEGMENT 兜底 2048，且受上下文一半约束")
    void segmentDefaultsAndContextCap() {
        AiProviderConfig big = AiProviderConfig.builder().maxTokens(32768).build();
        AiProviderConfig small = AiProviderConfig.builder().maxTokens(2000).build();

        assertThat(ChatModelFactory.effectiveMaxOutputTokens(AiScene.BOOK_QA_SEGMENT, big)).isEqualTo(2048);
        assertThat(ChatModelFactory.effectiveMaxOutputTokens(AiScene.BOOK_QA_SEGMENT, small)).isEqualTo(1000);
    }

    @Test
    @DisplayName("其他场景保持 null，不改变行为")
    void otherScenesStayNull() {
        AiProviderConfig config = AiProviderConfig.builder().maxTokens(32768).build();

        assertThat(ChatModelFactory.effectiveMaxOutputTokens(AiScene.AI_ASSISTANT, config)).isNull();
        assertThat(ChatModelFactory.effectiveMaxOutputTokens(AiScene.BOOK_QA, null)).isNull();
    }
}
