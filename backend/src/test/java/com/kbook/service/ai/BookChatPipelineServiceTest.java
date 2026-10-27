package com.kbook.service.ai;

import com.kbook.service.ai.behavior.BehaviorProfileBuilder;
import com.kbook.service.ai.core.ChatModelManager;
import com.kbook.service.ai.outline.AnswerOutline;
import com.kbook.service.ai.outline.AnswerOutline.Item;
import com.kbook.service.ai.outline.AnswerOutlineService;
import com.kbook.service.ai.rag.AiRetrievalSnapshotService;
import com.kbook.service.ai.rag.ItemListRetriever;
import com.kbook.service.ai.rag.ListQueryDetector;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 列表流水线分段指令单测。
 * <p>
 * 重点：每节 prompt 必须带全篇条目清单、确定的标题契约和事实边界；
 * 缺材料时不能再鼓励"基于理解展开"。
 */
class BookChatPipelineServiceTest {

    private static final List<Item> ITEMS = List.of(
            new Item(1, "应变能力", "遇到突发状况时的处理", List.of("应变"), null),
            new Item(2, "沟通能力", "把话说到对方心里", List.of("沟通"), null),
            new Item(3, "复盘能力", "从经历里提炼经验", List.of("复盘"), null)
    );

    @Test
    @DisplayName("分段指令包含全篇条目、标题契约与事实边界")
    void segmentInstructionContainsOutlineHeadingAndFactBoundary() {
        String instruction = BookChatPipelineService.buildSegmentInstruction(
                2, 3, "三项能力", ITEMS, "沟通能力", "把话说到对方心里",
                "应变能力", "上一节结尾。", "这是第二节的参考材料。", "DEEP");

        assertThat(instruction)
                .contains("1) 应变能力")
                .contains("2) 沟通能力")
                .contains("3) 复盘能力");
        assertThat(instruction).contains("### 第 2 节 · 沟通能力");
        assertThat(instruction).contains("【事实边界】");
        assertThat(instruction).contains("不得凭记忆补充书中情节");
        assertThat(instruction).contains("【引用契约】");
        assertThat(instruction).contains("[[N]]");
        assertThat(instruction).contains("这是第二节的参考材料。");
        assertThat(instruction).doesNotContain("基于你对其主题的理解");
        assertThat(instruction).doesNotContain("谨慎展开");
    }

    @Test
    @DisplayName("缺材料的分段指令不鼓励书外知识外推")
    void blankMaterialInstructionDoesNotInviteOutsideKnowledge() {
        String instruction = BookChatPipelineService.buildSegmentInstruction(
                1, 3, "三项能力", ITEMS, "应变能力", "遇到突发状况时的处理",
                null, null, "  ", "DEEP");

        assertThat(instruction).contains("不要用书外知识补写");
        assertThat(instruction).doesNotContain("基于你对其主题的理解");
        assertThat(instruction).doesNotContain("谨慎展开");
    }

    @Test
    @DisplayName("标题补齐只在模型没写标题时发生")
    void ensureSectionHeadingPrependsOnlyWhenMissing() {
        assertThat(BookChatPipelineService.ensureSectionHeading("正文", 2, "沟通能力"))
                .isEqualTo("### 第 2 节 · 沟通能力\n\n正文");
        assertThat(BookChatPipelineService.ensureSectionHeading("### 一、沟通能力\n正文", 2, "沟通能力"))
                .isEqualTo("### 一、沟通能力\n正文");
        assertThat(BookChatPipelineService.ensureSectionHeading("  ", 3, "复盘能力"))
                .isEqualTo("### 第 3 节 · 复盘能力");
    }

    @Test
    @DisplayName("长列表进一步压缩每节预算")
    void longListUsesCompactBudget() {
        String compact = BookChatPipelineService.buildSegmentInstruction(
                1, 10, "三项能力", ITEMS, "应变能力", "gist", null, null, "材料", "DEEP");
        assertThat(compact).contains("200~300 字");

        String ultra = BookChatPipelineService.buildSegmentInstruction(
                1, 20, "三项能力", ITEMS, "应变能力", "gist", null, null, "材料", "DEEP");
        assertThat(ultra).contains("150~250 字");
    }

    @Test
    @DisplayName("首轮列表题：没有上轮回答时从检索材料提取 outline")
    void firstTurnListQuestionBuildsOutlineFromMatches() {
        AnswerOutlineService outlineService = mock(AnswerOutlineService.class);
        when(outlineService.buildFromContext(anyString(), anyString(), anyString())).thenReturn(
                new AnswerOutline("三项能力", List.of(
                        new Item(1, "条目一", "gist1", List.of("条目一"), null),
                        new Item(2, "条目二", "gist2", List.of("条目二"), null))));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            BookChatPipelineService service = new BookChatPipelineService(
                    outlineService, executor,
                    mock(ItemListRetriever.class),
                    mock(ChatModelManager.class),
                    mock(BehaviorProfileBuilder.class),
                    mock(BookChatSessionService.class),
                    mock(AiRetrievalSnapshotService.class));

            AnswerOutline result = service.resolvePipelineOutline(
                    ListQueryDetector.DetectionResult.of("三项能力"),
                    "书里三项能力分别是什么", 1L, "s1", mock(SseEmitter.class), null,
                    List.of(match(1, "条目一 的原文内容"), match(2, "条目二 的原文内容")),
                    null);

            assertThat(result).isNotNull();
            assertThat(result.size()).isEqualTo(2);
            assertThat(result.items().get(0).chunkAnchors()).contains(1);
            assertThat(result.items().get(1).chunkAnchors()).contains(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("首轮检索材料不含读者指名的章节时退回单次流式，不进流水线")
    void firstTurnOutlineOffTopicFallsBackToSingleStream() {
        AnswerOutlineService outlineService = mock(AnswerOutlineService.class);
        // 复刻 2026-10-26 实测事故：问「如何获得幸福和成功」一节要避开什么，
        // 检索只回到书前记材料，条目化于是从材料里挑出「私人书单」六辑
        when(outlineService.buildFromContext(anyString(), anyString(), anyString())).thenReturn(
                new AnswerOutline("芒格获得的40本私人书单", List.of(
                        new Item(1, "自然", "自然科学奠基之作", List.of("自然"), null),
                        new Item(2, "人类", "人类社会与认知", List.of("人类"), null))));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            BookChatPipelineService service = new BookChatPipelineService(
                    outlineService, executor,
                    mock(ItemListRetriever.class),
                    mock(ChatModelManager.class),
                    mock(BehaviorProfileBuilder.class),
                    mock(BookChatSessionService.class),
                    mock(AiRetrievalSnapshotService.class));

            AnswerOutline result = service.resolvePipelineOutline(
                    ListQueryDetector.DetectionResult.of("芒格避免的不幸事"),
                    "在“如何获得幸福和成功”一节里，芒格说要避免哪些事情？请逐条列出。",
                    1L, "s1", mock(SseEmitter.class), null,
                    List.of(match(0, "书单简介：分自然、人类、经济、投资、人生和处世六辑"),
                            match(1, "芒格获得的40本私人书单")),
                    null);

            assertThat(result).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("同一问题、材料里有该章节时闸门放行，流水线照常触发")
    void firstTurnGuardPassesWhenMaterialCoversChapter() {
        AnswerOutlineService outlineService = mock(AnswerOutlineService.class);
        when(outlineService.buildFromContext(anyString(), anyString(), anyString())).thenReturn(
                new AnswerOutline("如何获得幸福和成功要避免的事", List.of(
                        new Item(1, "过早死亡", null, List.of("过早死亡"), null),
                        new Item(2, "糟糕的婚姻", null, List.of("糟糕的婚姻"), null))));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            BookChatPipelineService service = new BookChatPipelineService(
                    outlineService, executor,
                    mock(ItemListRetriever.class),
                    mock(ChatModelManager.class),
                    mock(BehaviorProfileBuilder.class),
                    mock(BookChatSessionService.class),
                    mock(AiRetrievalSnapshotService.class));

            AnswerOutline result = service.resolvePipelineOutline(
                    ListQueryDetector.DetectionResult.of("芒格避免的不幸事"),
                    "在“如何获得幸福和成功”一节里，芒格说要避免哪些事情？请逐条列出。",
                    1L, "s2", mock(SseEmitter.class), null,
                    List.of(match(56, "如何获得幸福和成功 这一节里，芒格列出了他见过的主要愚蠢做法"),
                            match(57, "第一条是过早死亡，其次是糟糕的婚姻")),
                    null);

            assertThat(result).isNotNull();
            assertThat(result.size()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("闸门只认引号里的章节名：没有引号时不参与判定")
    void quotedPhrasesDriveTheGuard() {
        assertThat(com.kbook.service.ai.rag.TopicCoverageCheck.quotedPhrases(
                "在“如何获得幸福和成功”一节里，芒格说要避免哪些事情？"))
                .containsExactly("如何获得幸福和成功");
        assertThat(com.kbook.service.ai.rag.TopicCoverageCheck.quotedPhrases(
                "书里有哪几辑？请逐条列出。")).isEmpty();
    }

    /**
     * 真实目录（books.toc 原样，book 24689《穷查理宝典》珍藏本）。
     * 「下篇 芒格私人书单」下有六辑子行；「人类误判心理学」的 25 条不在目录里。
     */
    private static final String TOC = """
            穷查理宝典（珍藏本）：芒格智慧箴言与私人书单
              扉页
              序言 书中自有黄金屋
              上篇 芒格智慧箴言
                第一章 查理·芒格传略
              下篇 芒格私人书单
                第一辑 自然
                第二辑 人类
                第三辑 经济
                第四辑 投资
                第五辑 人生
                第六辑 处世
              版权页""";

    @Test
    @DisplayName("目录本身就是枚举表：条目直接取目录子行，不调模型条目化")
    void tocChildrenBecomeOutlineWithoutModel() {
        AnswerOutlineService outlineService = mock(AnswerOutlineService.class);
        when(outlineService.buildFromContext(anyString(), anyString(), anyString())).thenReturn(null);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            BookChatPipelineService service = PipelineFixtures.service(outlineService, executor);

            AnswerOutline result = service.resolvePipelineOutline(
                    ListQueryDetector.DetectionResult.of("芒格私人书单分类"),
                    "下篇“芒格私人书单”把芒格推荐的书分成哪几辑？请逐条列出。",
                    1L, "s1", mock(SseEmitter.class), null,
                    List.of(match(82, "下篇 芒格私人书单 芒格获得的 40 本书分成六辑"),
                            // 正文里的辑标题是「第一辑」换行「自然」——字面匹配必须去空白才命中
                            match(83, "第一辑\n\n自然\n\n收录生物学与物理学奠基之作")),
                    TOC);

            assertThat(result).isNotNull();
            assertThat(result.items()).extracting(Item::title)
                    .containsExactly("第一辑 自然", "第二辑 人类", "第三辑 经济",
                            "第四辑 投资", "第五辑 人生", "第六辑 处世");
            // 目录条目没有 gist，检索依据只能靠锚点/关键词；两者都空时整节会落成占位文本
            assertThat(result.items().get(0).chunkAnchors()).contains(83);
            assertThat(result.items()).allSatisfy(it -> assertThat(it.keywords())
                    .as("目录条目必须带检索关键词: %s", it.title())
                    .containsExactly(it.title()));
            // 关键断言：模型条目化根本没被调用——目录已是现成枚举，调它只会付 10~24s
            verify(outlineService, never()).buildFromContext(anyString(), anyString(), anyString());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("目录里没有这份清单 → 仍走模型条目化（目录子行不是唯一来源）")
    void listNotInTocStillUsesModelOutline() {
        AnswerOutlineService outlineService = mock(AnswerOutlineService.class);
        when(outlineService.buildFromContext(anyString(), anyString(), anyString())).thenReturn(
                new AnswerOutline("人类误判心理学的 25 条", List.of(
                        new Item(1, "激励超级反应倾向", null, List.of("激励"), null),
                        new Item(2, "厌恶和失误超级反应倾向", null, List.of("厌恶"), null))));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            BookChatPipelineService service = PipelineFixtures.service(outlineService, executor);

            AnswerOutline result = service.resolvePipelineOutline(
                    ListQueryDetector.DetectionResult.of("人类误判心理学心理倾向"),
                    "书中芒格总结的“人类误判心理学”共列出了多少条心理倾向？请逐条列出。",
                    1L, "s2", mock(SseEmitter.class), null,
                    List.of(match(120, "人类误判心理学 这一节讨论各种心理倾向"),
                            match(121, "第一条 激励超级反应倾向，第二条 厌恶和失误超级反应倾向")),
                    TOC);

            assertThat(result).isNotNull();
            assertThat(result.size()).isEqualTo(2);
            verify(outlineService).buildFromContext(anyString(), anyString(), anyString());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("长清单才给更长条目化预算：窗口大或编号密，短清单仍走 12s")
    void longListDetection() {
        // 25 条清单的档 2 窗口实测出 49 段
        assertThat(BookChatPipelineService.looksLikeLongList("随便一段材料", 49)).isTrue();
        assertThat(BookChatPipelineService.looksLikeLongList("随便一段材料", 5)).isFalse();

        StringBuilder dense = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            dense.append(i).append(". 第").append(i).append("条倾向\n");
        }
        assertThat(BookChatPipelineService.looksLikeLongList(dense.toString(), 6)).isTrue();

        StringBuilder sparse = new StringBuilder();
        for (int i = 1; i <= 11; i++) {
            sparse.append(i).append("、条目").append(i).append('\n');
        }
        assertThat(BookChatPipelineService.looksLikeLongList(sparse.toString(), 6)).isFalse();
        assertThat(BookChatPipelineService.looksLikeLongList(null, 3)).isFalse();
    }

    @Test
    @DisplayName("条目数少于材料写明的总数八成 → 判残缺，退回单次流式")
    void truncatedOutlineFallsBackToSingleStream() {
        AnswerOutlineService outlineService = mock(AnswerOutlineService.class);
        List<Item> eight = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            eight.add(new Item(i, "倾向" + i, null, List.of("倾向" + i), null));
        }
        when(outlineService.buildFromContext(anyString(), anyString(), anyString()))
                .thenReturn(new AnswerOutline("25条心理倾向", eight));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            BookChatPipelineService service = PipelineFixtures.service(outlineService, executor);
            AnswerOutline result = service.resolvePipelineOutline(
                    ListQueryDetector.DetectionResult.of("人类误判心理学心理倾向"),
                    "书中芒格总结的“人类误判心理学”共列出了多少条心理倾向？请逐条列出。",
                    1L, "s3", mock(SseEmitter.class), null,
                    List.of(match(210, "人类误判心理学 这一节共列出 25 条心理倾向"),
                            match(211, "1. 激励倾向 2. 爱倾向")),
                    null);
            assertThat(result).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("条目数与材料声明一致时照常进流水线")
    void completeOutlineProceeds() {
        AnswerOutlineService outlineService = mock(AnswerOutlineService.class);
        List<Item> all = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            all.add(new Item(i, "倾向" + i, null, List.of("倾向" + i), null));
        }
        when(outlineService.buildFromContext(anyString(), anyString(), anyString()))
                .thenReturn(new AnswerOutline("25条心理倾向", all));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            BookChatPipelineService service = PipelineFixtures.service(outlineService, executor);
            AnswerOutline result = service.resolvePipelineOutline(
                    ListQueryDetector.DetectionResult.of("人类误判心理学心理倾向"),
                    "书中芒格总结的“人类误判心理学”共列出了多少条心理倾向？请逐条列出。",
                    1L, "s4", mock(SseEmitter.class), null,
                    List.of(match(210, "人类误判心理学 这一节共列出 25 条心理倾向"),
                            match(211, "1. 激励倾向 2. 爱倾向")),
                    null);
            assertThat(result).isNotNull();
            assertThat(result.size()).isEqualTo(25);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("声明总数解析：只看「N 条」，没写明返回 0")
    void declaredCountParsing() {
        assertThat(BookChatPipelineService.declaredItemCount("这一节共列出 25 条心理倾向")).isEqualTo(25);
        assertThat(BookChatPipelineService.declaredItemCount("一共12条")).isEqualTo(12);
        assertThat(BookChatPipelineService.declaredItemCount("这里没有数字")).isZero();
        assertThat(BookChatPipelineService.declaredItemCount(null)).isZero();
        // 没写总数时不判残缺：8 条也可以是完整清单
        assertThat(BookChatPipelineService.outlineIsComplete(8, 0)).isTrue();
        assertThat(BookChatPipelineService.outlineIsComplete(8, 25)).isFalse();
        assertThat(BookChatPipelineService.outlineIsComplete(20, 25)).isTrue();
    }

    /** 构造函数参数长且重复，测试里抽一处集中构造。 */
    private static final class PipelineFixtures {
        static BookChatPipelineService service(AnswerOutlineService outlineService, ExecutorService executor) {
            return new BookChatPipelineService(
                    outlineService, executor,
                    mock(ItemListRetriever.class),
                    mock(ChatModelManager.class),
                    mock(BehaviorProfileBuilder.class),
                    mock(BookChatSessionService.class),
                    mock(AiRetrievalSnapshotService.class));
        }
    }

    private static EmbeddingMatch<TextSegment> match(int chunkIndex, String text) {
        Metadata metadata = new Metadata().put("chunkIndex", chunkIndex);
        return new EmbeddingMatch<>(0.9, String.valueOf(chunkIndex), null,
                TextSegment.from(text, metadata));
    }

    @Test
    @DisplayName("超过展开上限不影响流水线触发，交由清单节兜底")
    void outlineOverLimitStillEntersPipeline() {
        AnswerOutlineService outlineService = mock(AnswerOutlineService.class);
        List<Item> many = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            many.add(new Item(i, "条目" + i, "gist" + i, List.of("条目" + i), null));
        }
        when(outlineService.buildFromContext(anyString(), anyString(), anyString()))
                .thenReturn(new AnswerOutline("二十五条", many));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            BookChatPipelineService service = new BookChatPipelineService(
                    outlineService, executor,
                    mock(ItemListRetriever.class),
                    mock(ChatModelManager.class),
                    mock(BehaviorProfileBuilder.class),
                    mock(BookChatSessionService.class),
                    mock(AiRetrievalSnapshotService.class));

            AnswerOutline result = service.resolvePipelineOutline(
                    ListQueryDetector.DetectionResult.of("二十五条"),
                    "书里这二十五条分别是什么", 1L, "s1", mock(SseEmitter.class), null,
                    List.of(match(1, "条目1 的原文内容")), null);

            assertThat(result).isNotNull();
            assertThat(result.size()).isEqualTo(25);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("超上限条目输出确定性清单节，不调用模型")
    void overflowSectionListsRemainingTitles() {
        List<Item> overflow = List.of(
                new Item(21, "第二十一条", "要点甲", List.of(), null),
                new Item(22, "第二十二条", null, List.of(), null));

        String section = BookChatPipelineService.buildOverflowSection(20, overflow);

        assertThat(section).startsWith("### 其余条目");
        assertThat(section).contains("21) 第二十一条 —— 要点甲");
        assertThat(section).contains("22) 第二十二条");
        assertThat(section).endsWith("22) 第二十二条");
        assertThat(section).doesNotContain("23)");
        assertThat(section).contains("单轮最多逐条展开 20 条");
    }
}
