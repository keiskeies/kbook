package com.kbook.service.book;

import com.kbook.service.book.TextStructureExtractor.Chapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 章节结构提取单测：标题行、偏移、无标题回退、正文句子不误判。
 */
class TextStructureExtractorTest {

    @Test
    @DisplayName("识别中文章节标题并保留偏移")
    void extractsChineseChapters() {
        String text = "前言内容\n\n第一章 起点\n" + "甲".repeat(400)
                + "\n\n第二章 转折\n" + "乙".repeat(400);

        List<Chapter> chapters = TextStructureExtractor.extract(text);

        assertThat(chapters).hasSize(3);
        assertThat(chapters.get(0).title()).isNull();
        assertThat(chapters.get(1).title()).isEqualTo("第一章 起点");
        assertThat(chapters.get(2).title()).isEqualTo("第二章 转折");
        assertThat(chapters).allSatisfy(c -> {
            assertThat(c.startChar()).isGreaterThanOrEqualTo(0);
            assertThat(c.endChar()).isLessThanOrEqualTo(text.length());
            assertThat(c.endChar()).isGreaterThan(c.startChar());
        });
        assertThat(TextStructureExtractor.findChapter(chapters, 10).title()).isEqualTo("第一章 起点");
        assertThat(TextStructureExtractor.findChapter(chapters, text.length() - 1).title()).isEqualTo("第二章 转折");
    }

    @Test
    @DisplayName("英文 Chapter 标题也能识别")
    void extractsEnglishChapter() {
        String text = "Chapter 1\n" + "A".repeat(400);

        List<Chapter> chapters = TextStructureExtractor.extract(text);

        assertThat(chapters).hasSize(1);
        assertThat(chapters.get(0).title()).isEqualTo("Chapter 1");
    }

    @Test
    @DisplayName("无标题时回退为单个全书区间")
    void fallsBackToWholeBook() {
        String text = "这是一段没有章节标题的正文。";

        List<Chapter> chapters = TextStructureExtractor.extract(text);

        assertThat(chapters).hasSize(1);
        assertThat(chapters.get(0).title()).isNull();
        assertThat(chapters.get(0).startChar()).isZero();
        assertThat(chapters.get(0).endChar()).isEqualTo(text.length());
    }

    @Test
    @DisplayName("正文中的“第一章……”句子不当作标题")
    void doesNotTreatBodySentenceAsHeading() {
        String text = "正文开始\n\n第一章讲了什么。\n更多正文";

        List<Chapter> chapters = TextStructureExtractor.extract(text);

        assertThat(chapters).hasSize(1);
        assertThat(chapters.get(0).title()).isNull();
    }
}
