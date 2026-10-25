package com.kbook.service.ai.streaming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * &lt;thought&gt; 标签流式解析器单测（阶段 2 第 1 批 — 纯状态机，零依赖）。
 * <p>
 * 重点覆盖：标签跨 chunk 边界、未闭合标签、多段交替。
 */
class ThoughtTagParserTest {

    @Test
    @DisplayName("纯文本全部进 message 通道")
    void plainTextGoesToMessage() {
        ThoughtTagParser parser = new ThoughtTagParser();
        ThoughtTagParser.Result result = parser.process("你好，世界");
        assertThat(result.message()).isEqualTo("你好，世界");
        assertThat(result.thinking()).isEmpty();
        assertThat(parser.isInThought()).isFalse();
    }

    @Test
    @DisplayName("完整思考标签内容进 thinking 通道")
    void fullThoughtTagGoesToThinking() {
        ThoughtTagParser parser = new ThoughtTagParser();
        ThoughtTagParser.Result result = parser.process("<thought>让我想想</thought>");
        assertThat(result.thinking()).isEqualTo("让我想想");
        assertThat(result.message()).isEmpty();
    }

    @Test
    @DisplayName("标签前后的文本归 message，标签内归 thinking")
    void mixedContentSplitAcrossChannels() {
        ThoughtTagParser parser = new ThoughtTagParser();
        ThoughtTagParser.Result result = parser.process("前文<thought>思考中</thought>后文");
        assertThat(result.message()).isEqualTo("前文后文");
        assertThat(result.thinking()).isEqualTo("思考中");
    }

    @Test
    @DisplayName("多段思考交替分离")
    void multipleThoughtBlocks() {
        ThoughtTagParser parser = new ThoughtTagParser();
        ThoughtTagParser.Result result = parser.process("a<thought>t1</thought>b<thought>t2</thought>c");
        assertThat(result.message()).isEqualTo("abc");
        assertThat(result.thinking()).isEqualTo("t1t2");
    }

    @Test
    @DisplayName("开标签跨 chunk 边界：确定内容即时输出，前缀缓存后在下一 chunk 补全")
    void openTagSplitAcrossChunks() {
        ThoughtTagParser parser = new ThoughtTagParser();

        ThoughtTagParser.Result first = parser.process("答案前<tho");
        // "答案前" 是确定内容，本 chunk 即输出；"<tho" 是不完整开标签 → 缓存
        assertThat(first.message()).isEqualTo("答案前");
        assertThat(first.thinking()).isEmpty();

        ThoughtTagParser.Result second = parser.process("ught>推理过程</thought>结论");
        assertThat(second.thinking()).isEqualTo("推理过程");
        assertThat(second.message()).isEqualTo("结论");
    }

    @Test
    @DisplayName("闭标签跨 chunk 边界")
    void closeTagSplitAcrossChunks() {
        ThoughtTagParser parser = new ThoughtTagParser();
        parser.process("<thought>推理");
        ThoughtTagParser.Result result = parser.process("内容</thou");
        // 本 chunk 只输出确定部分 "内容"；"</thou" 是不完整闭标签 → 缓存
        assertThat(result.thinking()).isEqualTo("内容");

        ThoughtTagParser.Result done = parser.process("ght>答案");
        assertThat(done.message()).isEqualTo("答案");
        assertThat(parser.isInThought()).isFalse();
    }

    @Test
    @DisplayName("流结束时未闭合的 thinking 状态可被检测")
    void unclosedThoughtDetectable() {
        ThoughtTagParser parser = new ThoughtTagParser();
        ThoughtTagParser.Result result = parser.process("<thought>只写了一半");
        assertThat(result.thinking()).isEqualTo("只写了一半");
        assertThat(parser.isInThought()).isTrue();
    }

    @Test
    @DisplayName("正文里恰好出现标签前缀字符不会被误吞")
    void tagLikeTextNotSwallowed() {
        ThoughtTagParser parser = new ThoughtTagParser();
        // "<" 后跟的内容构不成标签前缀 → 原样输出
        ThoughtTagParser.Result result = parser.process("1 < 2 且 a<b");
        assertThat(result.message()).isEqualTo("1 < 2 且 a<b");
        assertThat(parser.isInThought()).isFalse();
    }

    @Test
    @DisplayName("null 与空 chunk 返回空结果不抛异常")
    void nullAndEmptyChunkSafe() {
        ThoughtTagParser parser = new ThoughtTagParser();
        assertThat(parser.process(null).hasMessage()).isFalse();
        assertThat(parser.process("").hasThinking()).isFalse();
    }

    @Test
    @DisplayName("reset 后解析器可复用且无残留状态")
    void resetClearsState() {
        ThoughtTagParser parser = new ThoughtTagParser();
        parser.process("<thought>没写完");
        assertThat(parser.isInThought()).isTrue();

        parser.reset();
        assertThat(parser.isInThought()).isFalse();

        // 复用后正常分离，未闭合状态的 thinking 不会泄漏到新一轮
        ThoughtTagParser.Result result = parser.process("新消息");
        assertThat(result.message()).isEqualTo("新消息");
        assertThat(result.thinking()).isEmpty();
    }
}
