package com.kbook.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 问题消息契约单测：有参考片段时才要求 [[N]] 引用；覆盖度契约保持原样。
 */
class BookChatServicePromptTest {

    @Test
    @DisplayName("有片段时追加引用契约")
    void citationContractOnlyWhenContextExists() {
        String withContext = BookChatService.buildQuestionMessage("主角为什么离开", null, 2);

        assertThat(withContext).contains("【引用契约】");
        assertThat(withContext).contains("[[N]]");
        assertThat(withContext).contains("不要编造");

        String withoutContext = BookChatService.buildQuestionMessage("主角为什么离开", null, 0);
        assertThat(withoutContext).doesNotContain("【引用契约】");
    }

    @Test
    @DisplayName("列表覆盖度契约仍然保留")
    void coverageContractStillPresent() {
        String message = BookChatService.buildQuestionMessage("把这些展开", "三项能力", 3);

        assertThat(message).contains("【覆盖度契约】");
        assertThat(message).contains("三项能力");
        assertThat(message).contains("【引用契约】");
    }
}
