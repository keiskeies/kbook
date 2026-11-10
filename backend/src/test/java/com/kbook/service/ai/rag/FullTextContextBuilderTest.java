package com.kbook.service.ai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.config.properties.BookQaProperties;
import com.kbook.entity.Book;
import com.kbook.service.book.BookStructureService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullTextContextBuilderTest {

    private final BookQaProperties properties = new BookQaProperties();
    private final FullTextContextBuilder builder =
            new FullTextContextBuilder(properties, new BookStructureService(new ObjectMapper()));

    private static Book book(String text) {
        return Book.builder().id(9L).title("测试书").fullText(text).build();
    }

    private static String twoChapters() {
        // TextStructureExtractor 要求标题行前有一个空行，避免把正文句子误判成标题
        return "第一章 开场\n" + "开场内容".repeat(500)
                + "\n\n第二章 正文\n" + "正文内容".repeat(500);
    }

    @Test
    void smallCorpusContainsWholeBookText() {
        String text = "原文".repeat(100);
        String corpus = builder.buildSmallCorpus("【书籍】", book(text));
        assertTrue(corpus.startsWith("【书籍】"));
        assertTrue(corpus.contains(text));
        assertTrue(corpus.contains("【全书原文】"));
    }

    @Test
    void explicitChapterNumberIsLocated() {
        FullTextContextBuilder.ChapterContext chapter =
                builder.resolveChapter(book(twoChapters()), "第二章讲了什么", 100_000);
        assertNotNull(chapter);
        assertEquals("第二章 正文", chapter.title());
        assertTrue(chapter.text().contains("正文内容"));
    }

    @Test
    void chapterTitlePhraseIsMatched() {
        FullTextContextBuilder.ChapterContext chapter =
                builder.resolveChapter(book(twoChapters()), "请讲讲正文章节的内容", 100_000);
        assertNotNull(chapter);
        assertTrue(chapter.title().contains("正文"));
    }

    @Test
    void chapterExceedingBudgetFallsBackToNull() {
        properties.setMediumChapterMaxChars(100);
        assertNull(builder.resolveChapter(book(twoChapters()), "第二章讲了什么", 100_000));
    }

    @Test
    void bookWithoutChapterStructureCannotUseMedium() {
        assertNull(builder.resolveChapter(book("没有章节结构的连续文本".repeat(500)),
                "讲了什么", 100_000));
    }

    @Test
    void tocFallbackLocatesChapterWithoutBlankLines() {
        // 正文章节没有前置空行 → TextStructureExtractor 识别不出结构，
        // 应回退用 books.toc 的标题在全文里定位
        String text = "第一章 开场\n" + "开场内容".repeat(500)
                + "\n第二章 正文\n" + "正文内容".repeat(500);
        Book b = Book.builder().id(9L).title("测试书")
                .toc("第一章 开场\n第二章 正文")
                .fullText(text).build();
        FullTextContextBuilder.ChapterContext chapter =
                builder.resolveChapter(b, "请讲讲正文章节的内容", 100_000);
        assertNotNull(chapter);
        assertTrue(chapter.text().contains("正文内容"));
    }

    @Test
    void nullFullTextIsSafe() {
        assertNull(builder.resolveChapter(book(null), "讲了什么", 100_000));
    }
}
