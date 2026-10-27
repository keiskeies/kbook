package com.kbook.service.book;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入库文本净化单测：HTML 结构、实体、页码/断词、TXT 编码探测。
 */
class TextNormalizerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("HTML 块级标签转换行，实体解码")
    void htmlToTextSeparatesBlocksAndDecodesEntities() {
        String html = "<html><body><p>第一段&amp;内容</p><p>第二段&nbsp;结束</p></body></html>";

        String text = TextNormalizer.htmlToText(html);

        assertThat(text).contains("第一段&内容").contains("第二段 结束");
        assertThat(text).doesNotContain("<p>").doesNotContain("&amp;");
    }

    @Test
    @DisplayName("剔除 script/style/nav")
    void htmlToTextRemovesInvisibleBlocks() {
        String html = "<p>A</p><script>alert(1)</script><style>.x{}</style><nav>目录</nav><p>B</p>";

        String text = TextNormalizer.htmlToText(html);

        assertThat(text).contains("A").contains("B");
        assertThat(text).doesNotContain("alert").doesNotContain(".x").doesNotContain("目录");
    }

    @Test
    @DisplayName("数字实体按十进制/十六进制解码")
    void htmlToTextDecodesNumericEntities() {
        assertThat(TextNormalizer.htmlToText("<p>&#20013;&#25991; &#x4e2d;</p>"))
                .isEqualTo("中文 中");
    }

    @Test
    @DisplayName("PDF 文本去页码行、英文断词续行、折叠空行")
    void normalizeExtractedTextCleansPdfArtifacts() {
        String raw = "hello-\nworld\n  12  \nnext line\n\n\n\nend";

        String text = TextNormalizer.normalizeExtractedText(raw);

        assertThat(text).contains("helloworld").contains("next line").contains("\n\nend");
        assertThat(text).doesNotContain("  12  ");
    }

    @Test
    @DisplayName("TXT 编码探测：UTF-8 / GB18030 / BOM")
    void readTextDetectsEncoding() throws Exception {
        Path utf8 = tempDir.resolve("utf8.txt");
        Files.writeString(utf8, "中文内容", StandardCharsets.UTF_8);
        assertThat(TextNormalizer.readText(utf8)).isEqualTo("中文内容");

        Path gbk = tempDir.resolve("gbk.txt");
        Files.write(gbk, "中文内容".getBytes(Charset.forName("GB18030")));
        assertThat(TextNormalizer.readText(gbk)).isEqualTo("中文内容");

        Path bom = tempDir.resolve("bom.txt");
        byte[] body = "中文".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        Files.write(bom, withBom);
        assertThat(TextNormalizer.readText(bom)).isEqualTo("中文");
    }

    @Test
    @DisplayName("逐页净化去掉重复页眉，保留正文")
    void cleanPagedTextRemovesRunningHeader() {
        String cleaned = TextNormalizer.cleanPagedText(List.of(
                "《书名》\n正文A\n1",
                "《书名》\n正文B\n2",
                "《书名》\n正文C\n3",
                "《书名》\n正文D\n4"));

        assertThat(cleaned).contains("正文A").contains("正文B")
                .contains("正文C").contains("正文D");
        assertThat(cleaned).doesNotContain("《书名》");
    }

    @Test
    @DisplayName("逐页净化去掉重复页脚")
    void cleanPagedTextRemovesRunningFooter() {
        String cleaned = TextNormalizer.cleanPagedText(List.of(
                "正文A\n页脚",
                "正文B\n页脚",
                "正文C\n页脚",
                "正文D\n页脚"));

        assertThat(cleaned).contains("正文A").contains("正文D");
        assertThat(cleaned).doesNotContain("页脚");
    }

    @Test
    @DisplayName("页数不足时不误删重复行")
    void cleanPagedTextKeepsShortDocuments() {
        String cleaned = TextNormalizer.cleanPagedText(List.of(
                "页眉\n正文A",
                "页眉\n正文B"));

        assertThat(cleaned).contains("页眉").contains("正文A").contains("正文B");
    }

    @Test
    @DisplayName("空白内容返回空串")
    void blankTextReturnsEmpty() {
        assertThat(TextNormalizer.htmlToText(null)).isEmpty();
        assertThat(TextNormalizer.normalizeExtractedText("   ")).isEmpty();
        assertThat(TextNormalizer.decode(new byte[0])).isEmpty();
        assertThat(TextNormalizer.cleanPagedText(null)).isEmpty();
    }
}
