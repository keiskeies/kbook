package com.kbook.service.note;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoteTitleGeneratorTest {

    @Test
    @DisplayName("空正文不生成标题")
    void blankContentHasNoTitle() {
        assertThat(NoteTitleGenerator.from(null)).isNull();
        assertThat(NoteTitleGenerator.from("   \n\n  ")).isNull();
        assertThat(NoteTitleGenerator.from("---\n```\n")).isNull();
    }

    @Test
    @DisplayName("取首个有效行并去掉标题/引用/列表前缀")
    void stripsBlockMarkdown() {
        assertThat(NoteTitleGenerator.from("# 今天读了《活着》\n正文")).isEqualTo("今天读了《活着》");
        assertThat(NoteTitleGenerator.from("> 人是为活着本身而活着")).isEqualTo("人是为活着本身而活着");
        assertThat(NoteTitleGenerator.from("- [ ] **明天**要做的事")).isEqualTo("明天要做的事");
        assertThat(NoteTitleGenerator.from("1. 第一件事\n2. 第二件事")).isEqualTo("第一件事");
        assertThat(NoteTitleGenerator.from("> - 今天读到一句话")).isEqualTo("今天读到一句话");
    }

    @Test
    @DisplayName("链接/图片保留可读文字，去掉行内标记")
    void stripsInlineMarkdown() {
        assertThat(NoteTitleGenerator.from("[链接](https://example.com) 后面的内容"))
                .isEqualTo("链接 后面的内容");
        assertThat(NoteTitleGenerator.from("![封面](cover.png) 图片说明"))
                .isEqualTo("封面 图片说明");
        assertThat(NoteTitleGenerator.from("`code` 和 ~~删除~~ 和 **重点**"))
                .isEqualTo("code 和 删除 和 重点");
    }

    @Test
    @DisplayName("跳过代码围栏行，正文行仍可用")
    void skipsFenceLine() {
        assertThat(NoteTitleGenerator.from("```java\nx = 1;")).isEqualTo("x = 1;");
    }

    @Test
    @DisplayName("超长标题截断到 20 字并加省略号")
    void truncatesLongTitle() {
        String content = "这是一个非常非常长的标题用来验证截断逻辑是否正确";
        String title = NoteTitleGenerator.from(content);
        assertThat(title).isEqualTo(content.substring(0, NoteTitleGenerator.MAX_TITLE_CHARS) + "…");
        assertThat(title).hasSize(NoteTitleGenerator.MAX_TITLE_CHARS + 1);
    }
}
