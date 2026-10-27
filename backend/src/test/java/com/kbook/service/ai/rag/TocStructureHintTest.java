package com.kbook.service.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目录结构提示单测（首轮条目化的「枚举表」来源）。
 * <p>
 * 回归背景：2026-10-26 真实评测，问「下篇"芒格私人书单"分成哪几辑」时，档 2 窗口
 * 已覆盖六辑正文（chunk 83/88/99/112/123/136），但条目化只吃材料前 6000 字，
 * 只看到前两辑标题 → outline 只 2 条 → 答案只展开 2 辑（coverage 0.5）。
 * 目录本身就写着六辑，这里把它作为提示递给条目化。
 */
class TocStructureHintTest {

    /** book 24689《穷查理宝典》珍藏本的真实目录（books.toc 原样，含缩进）。 */
    private static final String TOC = """
            穷查理宝典（珍藏本）：芒格智慧箴言与私人书单
              扉页
              序言 书中自有黄金屋
              上篇 芒格智慧箴言
                第一章 查理·芒格传略
                第二章 歌颂长者：芒格论晚年
                第三章 忆念：晚辈谈芒格
                第四章 芒格主义：查理的即席谈话
              下篇 芒格私人书单
                第一辑 自然
                第二辑 人类
                第三辑 经济
                第四辑 投资
                第五辑 人生
                第六辑 处世
              版权页
            穷查理宝典（珍藏本）：芒格最重要的三场演讲
              扉页
              第一讲 最有用的商业智慧有哪些？
              第二讲 如何获得精彩人生？
              第三讲 聪明人为什么会犯错？
              版权页""";

    @Test
    @DisplayName("读者引号点名的章节在目录里 → 给出它和全部子行（六辑一个不少）")
    void quotedSectionYieldsItsChildren() {
        String hint = TocStructureHint.select(TOC,
                "下篇“芒格私人书单”把芒格在不同场合推荐的书分成哪几辑？请逐条列出。",
                "芒格私人书单分类");

        assertThat(hint).startsWith("下篇 芒格私人书单");
        assertThat(hint).contains("第一辑 自然", "第二辑 人类", "第三辑 经济",
                "第四辑 投资", "第五辑 人生", "第六辑 处世");
        // 窗口外的章节不该混进来
        assertThat(hint).doesNotContain("第一章", "第一讲");
    }

    @Test
    @DisplayName("没有引号时靠列表主题命中（主题被 LLM 泛化过也能认）")
    void topicStillMatchesWithoutQuotes() {
        String hint = TocStructureHint.select(TOC,
                "芒格私人书单分成哪几辑？请逐条列出。",
                "芒格私人书单分类");

        assertThat(hint).contains("第一辑 自然", "第六辑 处世");
    }

    @Test
    @DisplayName("问题指向的内容不在目录里 → 不给提示（不拿无关章节凑枚举）")
    void offTopicQuestionGetsNoHint() {
        assertThat(TocStructureHint.select(TOC,
                "书中芒格总结的“人类误判心理学”共列出了多少条心理倾向？",
                "人类误判心理学心理倾向")).isEmpty();
    }

    @Test
    @DisplayName("命中叶子节点 → 不给提示：叶子没有子行，给不出枚举")
    void leafMatchGivesNoHint() {
        assertThat(TocStructureHint.select(TOC,
                "在“第二讲 如何获得精彩人生”这一讲里芒格讲了什么？请逐条列出。",
                "精彩人生的做法")).isEmpty();
    }

    @Test
    @DisplayName("目录缺失 → 空提示，不影响条目化")
    void missingTocGivesEmptyHint() {
        assertThat(TocStructureHint.select(null, "问题", "主题")).isEmpty();
        assertThat(TocStructureHint.select("  ", "问题", "主题")).isEmpty();
    }

    @Test
    @DisplayName("过短的短语不参与匹配（避免「辑」这类单字命中一整片目录）")
    void shortPhraseIgnored() {
        assertThat(TocStructureHint.select(TOC, "“辑”", null)).isEmpty();
        assertThat(TocStructureHint.childTitles(TOC, "“辑”", null)).isEmpty();
    }

    @Test
    @DisplayName("childTitles：目录有子行时给出全部条目（六辑一个不少，含序数前缀）")
    void childTitlesReturnsEveryChild() {
        assertThat(TocStructureHint.childTitles(TOC,
                "下篇“芒格私人书单”把芒格在不同场合推荐的书分成哪几辑？请逐条列出。",
                "芒格私人书单分类"))
                .containsExactly("第一辑 自然", "第二辑 人类", "第三辑 经济",
                        "第四辑 投资", "第五辑 人生", "第六辑 处世");
    }

    @Test
    @DisplayName("childTitles：清单不在目录里 → 空列表（不能拿目录行冒充条目）")
    void childTitlesEmptyWhenListNotInToc() {
        assertThat(TocStructureHint.childTitles(TOC,
                "书中芒格总结的“人类误判心理学”共列出了多少条心理倾向？",
                "人类误判心理学心理倾向")).isEmpty();
    }

    @Test
    @DisplayName("childTitles 不受提示行数上限约束：25 条子行给全，不截到 24")
    void childTitlesNotCappedByHintLineLimit() {
        StringBuilder toc = new StringBuilder("演讲三 人类误判心理学\n");
        for (int i = 1; i <= 25; i++) {
            toc.append("  第").append(i).append("条 倾向").append(i).append('\n');
        }
        List<String> children = TocStructureHint.childTitles(toc.toString(),
                "“人类误判心理学”列出的心理倾向", null);
        assertThat(children).hasSize(25);
        assertThat(children).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, 25)
                        .mapToObj(i -> "第" + i + "条 倾向" + i).toList());
    }
}
