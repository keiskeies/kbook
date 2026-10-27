package com.kbook.service.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目录窗口覆盖度判定单测。
 * <p>
 * 回归背景：2026-10-26 真实评测发现，问「在"如何获得幸福和成功"一节里……」时，
 * 该节根本不在目录里（正文 chunk 56/57），LLM 只能从目录里挑一个"最像"的行
 * （「第三讲 聪明人为什么会犯错？」），窗口落到无关章节，答案对着错误内容写。
 */
class TopicCoverageCheckTest {

    @Test
    @DisplayName("问题里引用的章节名出现在窗口里 → 覆盖")
    void quotedSectionNameFoundInWindow() {
        List<String> window = List.of(
                "第三讲 聪明人为什么会犯错？\n人类误判心理学共列 25 条倾向",
                "展开第一条：奖励超级反应倾向");
        assertThat(TopicCoverageCheck.covered(
                "书中芒格总结的“人类误判心理学”共列出了多少条心理倾向？",
                "人类误判心理学",
                window)).isTrue();
    }

    @Test
    @DisplayName("引用的章节名不在窗口里 → 不覆盖（交给上层换策略）")
    void quotedSectionNameMissingFromWindow() {
        List<String> window = List.of(
                "第三讲 聪明人为什么会犯错？\n第一讲 最有用的商业智慧有哪些？",
                "戴夫·麦克德米德提出了人类误判心理学的 25 条倾向");
        assertThat(TopicCoverageCheck.covered(
                "在“如何获得幸福和成功”一节里，芒格说要避免哪些事情？",
                "如何获得幸福和成功要避免的事",
                window)).isFalse();
    }

    @Test
    @DisplayName("引号里点名的章节不在窗口 → 不覆盖，哪怕泛化主题短语命中")
    void genericTopicCannotMaskMissingQuotedSection() {
        List<String> window = List.of(
                "第三讲 聪明人为什么会犯错？",
                "这里讲的是要避免的事情有哪些");
        assertThat(TopicCoverageCheck.covered(
                "在“如何获得幸福和成功”一节里，芒格说要避免哪些事情？",
                "要避免的事情",
                window)).isFalse();
    }

    @Test
    @DisplayName("主题本身出现在窗口里 → 覆盖（没有引号也能判）")
    void topicLiteralFoundInWindow() {
        assertThat(TopicCoverageCheck.covered(
                "芒格私人书单怎么分的？",
                "芒格私人书单",
                List.of("下篇 芒格私人书单\n第一辑 自然"))).isTrue();
    }

    @Test
    @DisplayName("标题跨行/带空格也算命中（空白全部去掉后比较）")
    void matchIgnoresWhitespace() {
        assertThat(TopicCoverageCheck.covered(
                "“芒格私人书单”",
                null,
                List.of("芒格私人\n书单\n第一辑 自然"))).isTrue();
    }

    @Test
    @DisplayName("窗口为空 → 不覆盖")
    void emptyWindowNotCovered() {
        assertThat(TopicCoverageCheck.covered("“随便什么”", "随便什么", List.of())).isFalse();
    }

    @Test
    @DisplayName("过短的短语不参与匹配（避免单字误命中）")
    void shortPhraseIgnored() {
        assertThat(TopicCoverageCheck.covered("“节”", "节", List.of("这一节讲的是别的"))).isFalse();
    }

    // ================================================================
    // startsWithTitle：正文标题定位（档 2 目录缺该节时的确定性兜底）
    // 两段文本取自《穷查理宝典》真实 chunk：57 是该节正文开头，56 只在该块中段列出小节名
    // ================================================================

    /** 真实 chunk 57 的开头（去空白后标题位于第 0 字） */
    private static final String CHUNK_57_HEAD =
            "如何获得幸福和成功 如果你在生活中唯一的成功就是通过买股票发财，那么这是一种失败的生活。";

    /** 真实 chunk 56 的开头片段：小节名只出现在该块中段（去空白后偏移约 571） */
    private static final String CHUNK_56_HEAD =
            "如果乌龟能够吸取它那些最棒前辈的已经被实践所证明的洞见，有时候它也能跑赢那些追求独创性的兔子。"
                    + "在需要制裁和不需要制裁的事情之间应该有一条巨大的鸿沟。 如何经营好自己的生活 "
                    + "如何获得幸福和成功 如果你在生活中唯一的成功就是通过买股票发财";

    @Test
    @DisplayName("章节正文开头块 → 判为标题起点（真实 chunk 57）")
    void chapterOpeningChunkCountsAsTitleStart() {
        assertThat(TopicCoverageCheck.startsWithTitle(CHUNK_57_HEAD, "如何获得幸福和成功")).isTrue();
    }

    @Test
    @DisplayName("标题只出现在块中段 → 不算起点（真实 chunk 56，覆盖判定却是 true）")
    void titleMidChunkIsNotAStart() {
        // 这一对断言是关键：covered() 对同一块返回 true，若定位沿用 covered() 就会把窗口起点
        // 错钉在 56（正文里只是列了一次小节名），所以定位必须额外看位置
        assertThat(TopicCoverageCheck.covered(
                "“如何获得幸福和成功”", null, List.of(CHUNK_56_HEAD))).isTrue();
        assertThat(TopicCoverageCheck.startsWithTitle(CHUNK_56_HEAD, "如何获得幸福和成功")).isFalse();
    }

    @Test
    @DisplayName("开头有序号前缀仍算起点（「第三讲 …」）")
    void ordinalPrefixBeforeTitleStillCounts() {
        assertThat(TopicCoverageCheck.startsWithTitle(
                "第三讲\n如何获得幸福和成功\n生活和生意上的大多数成功来自你知道应该避免什么",
                "如何获得幸福和成功")).isTrue();
    }

    @Test
    @DisplayName("空白/换行不影响起点判定（标题跨行）")
    void startsWithTitleIgnoresWhitespace() {
        assertThat(TopicCoverageCheck.startsWithTitle(
                "芒格私人\n书单\n第一辑 自然", "芒格私人书单")).isTrue();
        assertThat(TopicCoverageCheck.startsWithTitle(null, "芒格私人书单")).isFalse();
        assertThat(TopicCoverageCheck.startsWithTitle("正文里没有这个", "芒格私人书单")).isFalse();
    }
}
