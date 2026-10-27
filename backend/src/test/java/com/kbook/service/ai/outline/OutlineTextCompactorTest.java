package com.kbook.service.ai.outline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 条目化输入压缩单测（分块专项 §2.3）。
 * <p>
 * 旧实现 {@code substring(0, 6000)} 会切在句中并丢掉尾部条目标题，
 * 这里验证：不超上限、列表保留全部条目标题、散文退到句子边界。
 */
class OutlineTextCompactorTest {

    @Test
    @DisplayName("未超上限时原样返回")
    void 未超上限原样返回() {
        String text = "短文本";
        assertThat(OutlineTextCompactor.compact(text, 100)).isEqualTo(text);
    }

    @Test
    @DisplayName("列表超长时保留全部条目标题，且总长不超过上限")
    void 列表超长保留全部条目标题() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            sb.append(i).append(". 第").append(i).append("条：")
                    .append("这是一段用于占位的正文内容，").append("用来把长度撑过上限。")
                    .append('\n');
        }
        String text = sb.toString();
        int max = 1200;

        String out = OutlineTextCompactor.compact(text, max);

        assertThat(out.length()).isLessThanOrEqualTo(max);
        assertThat(out).contains("其余条目");
        assertThat(out).contains("第60条");
        // 正文仍保留完整开头条目
        assertThat(out).startsWith("1. 第1条");
    }

    @Test
    @DisplayName("散文超长时切在句末，不出现半句结尾")
    void 散文切在句末() {
        String sentence = "这是一句完整的说明文字。";
        String text = sentence.repeat(200);
        int max = 500;

        String out = OutlineTextCompactor.compact(text, max);

        assertThat(out.length()).isLessThanOrEqualTo(max);
        String body = out.substring(0, out.indexOf(OutlineTextCompactor.TRUNCATION_NOTE));
        assertThat(body).endsWith("。");
    }

    @Test
    @DisplayName("无标点无换行时退到硬切，仍不超上限")
    void 无标点硬切不超上限() {
        String text = "字".repeat(2000);
        int max = 300;

        String out = OutlineTextCompactor.compact(text, max);

        assertThat(out.length()).isLessThanOrEqualTo(max);
        assertThat(out).endsWith(OutlineTextCompactor.TRUNCATION_NOTE);
    }

    @Test
    @DisplayName("空值安全")
    void 空值安全() {
        assertThat(OutlineTextCompactor.compact(null, 100)).isNull();
        assertThat(OutlineTextCompactor.compact("abc", 0)).isEqualTo("abc");
    }
}