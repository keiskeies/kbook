package com.kbook.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 查询扩展收敛单测：子查询数量上限，原始查询保留在首位。
 */
class BookChatRagServiceTest {

    @Test
    @DisplayName("子查询超过 6 条时截断，保留前 6 条")
    void capsSubQueries() {
        List<String> queries = List.of("原始问题", "扩展1", "扩展2", "扩展3", "扩展4", "扩展5", "扩展6");

        List<String> limited = BookChatRagService.limitQueries(queries);

        assertThat(limited).hasSize(6);
        assertThat(limited.get(0)).isEqualTo("原始问题");
        assertThat(limited).doesNotContain("扩展6");
    }

    @Test
    @DisplayName("未超限时原样返回，null 安全")
    void keepsSmallListsAndHandlesNull() {
        List<String> small = List.of("原始问题", "扩展1");

        assertThat(BookChatRagService.limitQueries(small)).isSameAs(small);
        assertThat(BookChatRagService.limitQueries(null)).isEmpty();
    }

    @Test
    @DisplayName("RAG 预算取模型窗口推导值与配置上限的较小者")
    void resolvesRagMaxChars() {
        // 不设上限（0）→ 沿用模型窗口推导值
        assertThat(BookChatRagService.resolveRagMaxChars(471_859, 0)).isEqualTo(471_859);
        // 配置上限生效 → 整本书不再整本进上下文
        assertThat(BookChatRagService.resolveRagMaxChars(471_859, 20_000)).isEqualTo(20_000);
        // 配置上限比模型窗口还大 → 不放大预算
        assertThat(BookChatRagService.resolveRagMaxChars(1_000, 20_000)).isEqualTo(1_000);
    }
}
