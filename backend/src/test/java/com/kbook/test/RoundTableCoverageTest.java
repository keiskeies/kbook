package com.kbook.test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.util.CommonUtils;
import com.kbook.config.ChatModelFactory;
import com.kbook.config.properties.QdrantProperties;
import com.kbook.entity.Book;
import com.kbook.entity.RoundTableMessage;
import com.kbook.entity.RoundTableSession;
import com.kbook.repository.BookRepository;
import com.kbook.repository.RoundTableMessageRepository;
import com.kbook.repository.RoundTableSessionRepository;
import com.kbook.service.embedding.EmbeddingService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.kbook.common.util.QueryBuilder.eq;

/**
 * 圆桌派会话覆盖度评估工具
 * <p>
 * 根据圆桌派会话ID，加载所有顺序发言和图书信息，通过 4 种算法计算发言对图书内容的覆盖度：
 * <p>
 * 算法 1 - 内容块覆盖度 (Content Block Coverage) — 主要指标 (40%):
 *   把全书解构成「内容块」(基于TOC/LLM大纲/RAG分块三种策略)，逐块判断讨论覆盖深度。
 *   含概念标签覆盖度作为子指标。
 * <p>
 * 算法 2 - RAG 语义命中率 (RAG Semantic Hit Rate) — 技术补充 (15%):
 *   以讨论文本为RAG查询，检索图书内容分块，按相似度分布估算内容覆盖比例。
 * <p>
 * 算法 3 - LLM 综合评估 (LLM Assessment) — 定性评估 (30%):
 *   将图书摘要+讨论实录喂给LLM，让其从6维度评分。
 * <p>
 * 算法 4 - 概念标签覆盖度 (Concept Tag Coverage) — 元数据核查 (15%):
 *   以图书 conceptTags 为核查清单，直接匹配+LLM语义判断。
 * <p>
 * 使用方式：修改下方 SESSION_ID 为实际会话ID，运行本测试即可。
 */
@SpringBootTest
@ActiveProfiles("dev")
public class RoundTableCoverageTest {

    private static final Logger log = LoggerFactory.getLogger(RoundTableCoverageTest.class);

    // ==================== 输入配置 ====================
    /** 修改此处为实际的圆桌派会话 sessionId */
    private static final String SESSION_ID = "rt-20636-2c4a6b8f";

    // ==================== 注入依赖 ====================
    @Autowired
    private RoundTableSessionRepository sessionRepository;
    @Autowired
    private RoundTableMessageRepository messageRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private EmbeddingService embeddingService;
    @Autowired
    private ChatModelFactory chatModelFactory;
    @Autowired
    private QdrantProperties qdrantProps;
    @Autowired
    private ObjectMapper objectMapper;

    /** HTTP 客户端（用于 Qdrant REST API scroll） */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build();

    /** 每轮 RAG 查询返回数量 */
    private static final int RAG_TOP_K = 30;
    /** RAG 最低相似度阈值 */
    private static final double RAG_MIN_SCORE = 0.15;
    /** 内容块最大数量（防止书本太长导致过多LLM调用） */
    private static final int MAX_CONTENT_BLOCKS = 50;
    /** LLM 评估单块的最大讨论文本长度 */
    private static final int DISCUSSION_MAX_CHARS = 8000;

    // ==================== 主入口 ====================

    @Test
    public void calculateCoverage() throws Exception {


        System.out.println("============================================================");
        System.out.println("  圆桌派会话覆盖度评估");
        System.out.println("  会话 ID: " + SESSION_ID);
        System.out.println("============================================================");

        // ---- Step 1: 加载数据 ----
        System.out.println("\n[Step 1] 加载数据...");
        RoundTableSession session = sessionRepository.query()
                .where("sessionId", eq(SESSION_ID))
                .list(1).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + SESSION_ID));
        Book book = bookRepository.findById(session.getBookId())
                .orElseThrow(() -> new IllegalArgumentException("图书不存在: " + session.getBookId()));
        List<RoundTableMessage> messages = messageRepository.query()
                .where("sessionId", eq(SESSION_ID))
                .orderBy("id")
                .list();

        System.out.println("  图书: 《" + book.getTitle() + "》(" + book.getAuthor() + ")");
        System.out.println("  参与角色: " + session.getRoleKeys());
        System.out.println("  发言总数: " + messages.size() + " 条");
        if (messages.isEmpty()) {
            System.out.println("  [警告] 会话没有发言记录，无法计算覆盖度");
            return;
        }
        printRoleDistribution(messages);

        // ---- Step 2: 构建讨论全文和分析材料 ----
        System.out.println("\n[Step 2] 构建讨论数据...");
        String discussionText = buildDiscussionText(messages);
        Set<String> discussionKeywords = extractKeyTerms(discussionText);
        System.out.println("  讨论文本总长度: " + discussionText.length() + " 字符");
        System.out.println("  讨论关键词数: " + discussionKeywords.size());

        // ---- Step 3: 解析图书元数据 ----
        List<String> conceptTags = parseJsonArray(book.getConceptTags());
        List<String> readerNeedTags = parseJsonArray(book.getReaderNeedTags());
        List<String> formatTags = parseJsonArray(book.getFormatTags());
        System.out.println("  概念标签: " + conceptTags.size() + " 个");
        System.out.println("  读者需求标签: " + readerNeedTags.size() + " 个");
        System.out.println("  体裁标签: " + formatTags.size() + " 个");

        // ---- Step 4: 获取图书 RAG 全量分块 ----
        System.out.println("\n[Step 3] 获取图书 RAG 分块...");
        List<BookChunk> allChunks = fetchAllBookChunks(book.getId());
        System.out.println("  图书内容分块总数: " + allChunks.size() + " 块");
        boolean hasChunks = !allChunks.isEmpty();
        if (!hasChunks) {
            System.out.println("  [警告] 图书无内容分块，内容块算法将跳过RAG匹配层");
        }

        // ---- Step 5: 运行覆盖度算法 ----
        System.out.println("\n============================================================");
        System.out.println("  运行覆盖度算法");
        System.out.println("============================================================");

        CoverageReport report = new CoverageReport();
        report.setSession(session);
        report.setBook(book);
        report.setMessages(messages);
        report.setConceptTags(conceptTags);
        report.setReaderNeedTags(readerNeedTags);
        report.setTotalChunks(allChunks.size());

        // 算法 1: 内容块覆盖度（核心算法）
        runContentBlockCoverage(book, discussionText, discussionKeywords, messages, allChunks, conceptTags, report);

        // 算法 2: RAG 语义命中率
        runRagSemanticCoverage(discussionText, book, allChunks, report);

        // 算法 3: LLM 综合评估
        runLlmAssessment(discussionText, book, messages, report);

        // 算法 4: 概念标签覆盖度（作为辅助指标）
        runConceptTagCoverage(discussionText, conceptTags, report);

        // ---- Step 6: 综合评分 ----
        computeOverallScore(report);

        // ---- Step 7: 打印报告 ----
        printReport(report);
    }

    // ========================================================================
    //  算法 1: 内容块覆盖度 (Content Block Coverage) — 核心算法
    // ========================================================================
    //
    // 设计思路:
    //   1. 把全书解构成 N 个「内容块」（ContentBlock），每块有标题、摘要、原文片段、关键词
    //   2. 对每块评估讨论对其的覆盖深度（未覆盖/提及/部分讨论/深入讨论）
    //   3. 综合得出全书的覆盖比例
    //
    // 内容块构建策略（3 层回退）:
    //   Tier 1: TOC-based (最优) — 用 book.toc 的章节标题 + book.chapterSummary
    //   Tier 2: LLM-outline (TOC不足时) — 抽样RAG chunks → LLM生成主题大纲
    //   Tier 3: Dense-chunking (兜底) — 连续chunk合并
    //
    // 每块覆盖判断:
    //   方法A: 关键词重叠 (快速筛查)
    //   方法B: RAG反向检索 (语义匹配)
    //   方法C: LLM精确判定 (边界块)
    // ========================================================================

    /** 内容块 */
    @Data
    @Builder
    public static class ContentBlock {
        private String title;              // 块标题（章节名或主题名）
        private String summary;            // 块摘要
        private String representativeText; // 代表性原文片段（前300字）
        private int chunkCount;            // 关联的RAG chunk数
        private Set<String> keywords;      // 块的关键词集合
        /** 块来源: "toc" / "llm_outline" / "dense_chunking" */
        private String source;
    }

    /** 单块覆盖评估结果 */
    @Data
    @Builder
    public static class BlockCoverageDetail {
        private ContentBlock block;
        /** 覆盖等级: 0=未覆盖, 1=提及, 2=部分讨论, 3=深入讨论 */
        private int coverageLevel;
        /** 覆盖得分 0-100 */
        private double score;
        /** 关键词重叠率 0-1 */
        private double keywordOverlap;
        /** 使用的判断方法: "keyword" / "rag" / "llm" */
        private String judgeMethod;
        /** 判定证据简述 */
        private String evidence;

        public String levelLabel() {
            return switch (coverageLevel) {
                case 0 -> "未覆盖";
                case 1 -> "提及";
                case 2 -> "部分讨论";
                case 3 -> "深入讨论";
                default -> "未知";
            };
        }
    }

    /**
     * 核心算法：运行内容块覆盖度评估
     */
    private void runContentBlockCoverage(Book book, String discussionText, Set<String> discussionKeywords,
                                          List<RoundTableMessage> messages, List<BookChunk> allChunks,
                                          List<String> conceptTags, CoverageReport report) {
        System.out.println("\n--- 算法 1: 内容块覆盖度 (Content Block Coverage) ---");

        // ---- 1. 构建内容块 ----
        List<ContentBlock> blocks = buildContentBlocks(book, allChunks);
        if (blocks.isEmpty()) {
            System.out.println("  无法构建内容块，跳过");
            report.setBlockCoverage(new CoverageMetric("内容块覆盖度", 0, 1, 0));
            report.setBlockDetails(new ArrayList<>());
            return;
        }
        System.out.printf("  构建内容块: %d 个 (来源: %s)%n", blocks.size(), blocks.get(0).getSource());

        // 构建讨论的RAG embedding（一次性做完，供后续复用）
        boolean ragAvailable = embeddingService.isAvailable();

        // ---- 2. 逐块评估覆盖度 ----
        List<BlockCoverageDetail> details = new ArrayList<>();
        // 收集边界块（关键词重叠率在 5%-25% 之间的），用 LLM 做精确判定
        List<Integer> borderlineIndices = new ArrayList<>();

        for (int i = 0; i < blocks.size(); i++) {
            ContentBlock block = blocks.get(i);
            BlockCoverageDetail detail = evaluateSingleBlock(
                    block, i, discussionText, discussionKeywords, book, allChunks, ragAvailable);
            details.add(detail);

            if (detail.getKeywordOverlap() > 0.05 && detail.getKeywordOverlap() < 0.25
                    && detail.getCoverageLevel() == 1) {
                borderlineIndices.add(i);
            }
        }

        // ---- 3. 对边界块用 LLM 精确判定（batch 方式，减少 LLM 调用） ----
        if (!borderlineIndices.isEmpty()) {
            System.out.printf("  边界块判定: %d 个块需要 LLM 精确判定...%n", borderlineIndices.size());
            runLlmBorderlineJudgment(blocks, details, borderlineIndices, discussionText, book);
        }

        // ---- 4. 汇总统计 ----
        long coveredCount = details.stream().filter(d -> d.getCoverageLevel() >= 1).count();
        long deepCount = details.stream().filter(d -> d.getCoverageLevel() >= 2).count();
        double avgScore = details.stream().mapToDouble(BlockCoverageDetail::getScore).average().orElse(0);
        double coverageRate = (double) coveredCount / blocks.size() * 100;

        // 加权: 深入讨论的块权重更高
        double weightedScore = (deepCount * 1.0 + (coveredCount - deepCount) * 0.5) / blocks.size() * 100;

        System.out.printf("  覆盖统计: %d/%d 块被触及 (%.1f%%), 其中 %d 块深入讨论%n",
                coveredCount, blocks.size(), coverageRate, deepCount);
        System.out.printf("  平均覆盖得分: %.1f/100, 加权得分: %.1f/100%n", avgScore, weightedScore);

        // ---- 5. 打印每块的覆盖详情（仅展示有代表性的） ----
        System.out.println("\n  逐块覆盖详情（前20块）:");
        int printLimit = Math.min(blocks.size(), 20);
        for (int i = 0; i < printLimit; i++) {
            BlockCoverageDetail d = details.get(i);
            String icon = switch (d.getCoverageLevel()) {
                case 3 -> "█";
                case 2 -> "▓";
                case 1 -> "▒";
                default -> "░";
            };
            System.out.printf("    %s [%s] %s (得分:%.0f 重叠:%.0f%% 方法:%s)%n",
                    icon, d.levelLabel(), d.getBlock().getTitle(),
                    d.getScore(), d.getKeywordOverlap() * 100, d.getJudgeMethod());
            if (d.getCoverageLevel() == 0 && d.getEvidence() != null && !d.getEvidence().isBlank()) {
                System.out.printf("        → %s%n", d.getEvidence());
            }
        }
        if (blocks.size() > 20) {
            System.out.printf("    ... 还有 %d 块未展示%n", blocks.size() - 20);
        }

        // ---- 6. 同时计算概念标签覆盖度（作为内容块的子指标） ----
        List<String> matchedConcepts = new ArrayList<>();
        List<String> unmatchedConcepts = new ArrayList<>();
        for (String tag : conceptTags) {
            boolean foundInBlocks = details.stream().anyMatch(d ->
                    d.getCoverageLevel() >= 1 && (
                            d.getBlock().getTitle().contains(tag)
                            || (d.getBlock().getKeywords() != null && d.getBlock().getKeywords().contains(tag))
                    )
            );
            if (foundInBlocks || discussionText.contains(tag)) {
                matchedConcepts.add(tag);
            } else {
                unmatchedConcepts.add(tag);
            }
        }

        // ---- 7. 存储结果 ----
        report.setBlockCoverage(new CoverageMetric("内容块覆盖度",
                (int) coveredCount, blocks.size(), weightedScore));
        report.setBlockDetails(details);
        report.setBlocks(blocks);
        report.setCoveredConcepts(matchedConcepts);
        report.setMissedConcepts(unmatchedConcepts);
    }

    /**
     * 构建内容块 — 3 层回退策略
     */
    private List<ContentBlock> buildContentBlocks(Book book, List<BookChunk> allChunks) {
        // Tier 1: TOC-based
        List<ContentBlock> tocBlocks = buildBlocksFromToc(book, allChunks);
        if (tocBlocks.size() >= 3) {
            return tocBlocks;
        }

        // Tier 2: LLM-outline
        if (!allChunks.isEmpty()) {
            List<ContentBlock> llmBlocks = buildBlocksFromLlmOutline(book, allChunks);
            if (llmBlocks.size() >= 3) {
                return llmBlocks;
            }
        }

        // Tier 3: Dense chunking
        return buildBlocksFromDenseChunking(allChunks);
    }

    /**
     * Tier 1: 从 TOC 构建内容块
     */
    private List<ContentBlock> buildBlocksFromToc(Book book, List<BookChunk> allChunks) {
        String toc = book.getToc();
        if (toc == null || toc.isBlank()) return new ArrayList<>();

        // 解析章节标题
        String[] chapterLines = toc.split("\n");
        List<String> chapterTitles = Arrays.stream(chapterLines)
                .map(String::trim)
                .filter(line -> line.length() >= 2 && !line.matches("^[\\d\\s.]+$"))
                .distinct()
                .collect(Collectors.toList());

        if (chapterTitles.isEmpty()) return new ArrayList<>();

        // 聚类章节（最多 MAX_CONTENT_BLOCKS 块）
        if (chapterTitles.size() > MAX_CONTENT_BLOCKS) {
            chapterTitles = mergeChapters(chapterTitles, MAX_CONTENT_BLOCKS);
        }

        // 解析 chapterSummary
        Map<String, String> chapterSummaryMap = parseChapterSummary(book.getChapterSummary());

        // 将 RAG chunks 按章节匹配
        Map<String, List<BookChunk>> chapterChunks = new LinkedHashMap<>();
        for (String title : chapterTitles) {
            chapterChunks.put(title, new ArrayList<>());
        }

        if (!allChunks.isEmpty()) {
            String matchedChapter = null;
            for (BookChunk chunk : allChunks) {
                String matched = matchChunkToChapter(chunk.text(), chapterTitles, matchedChapter);
                if (matched != null) {
                    matchedChapter = matched;
                }
                if (matchedChapter != null && chapterChunks.containsKey(matchedChapter)) {
                    chapterChunks.get(matchedChapter).add(chunk);
                } else if (matchedChapter == null) {
                    // 匹配到第一个章节之前的内容，归到第一章
                    if (!chapterTitles.isEmpty()) {
                        chapterChunks.get(chapterTitles.get(0)).add(chunk);
                    }
                }
            }
        }

        // 构建 ContentBlock
        List<ContentBlock> blocks = new ArrayList<>();
        for (String title : chapterTitles) {
            List<BookChunk> relatedChunks = chapterChunks.getOrDefault(title, new ArrayList<>());
            String summary = chapterSummaryMap.getOrDefault(title, "");
            String representativeText = relatedChunks.isEmpty()
                    ? (summary.isEmpty() ? title : summary)
                    : relatedChunks.get(0).text();
            if (representativeText.length() > 300) {
                representativeText = representativeText.substring(0, 300);
            }

            Set<String> keywords = extractKeyTerms(title + " " + summary + " " + representativeText);

            blocks.add(ContentBlock.builder()
                    .title(title)
                    .summary(summary.length() > 200 ? summary.substring(0, 200) : summary)
                    .representativeText(representativeText)
                    .chunkCount(relatedChunks.size())
                    .keywords(keywords)
                    .source("toc")
                    .build());
        }

        log.info("TOC 内容块构建完成: {} 块 (从 {} 章节)", blocks.size(), chapterTitles.size());
        return blocks;
    }

    /** 章节标题太多时合并 */
    private List<String> mergeChapters(List<String> titles, int targetCount) {
        List<String> merged = new ArrayList<>();
        int groupSize = (int) Math.ceil((double) titles.size() / targetCount);
        for (int i = 0; i < titles.size(); i += groupSize) {
            int end = Math.min(i + groupSize, titles.size());
            if (end - i == 1) {
                merged.add(titles.get(i));
            } else {
                merged.add(titles.get(i) + " 等" + (end - i) + "节");
            }
        }
        return merged;
    }

    /** 解析 chapterSummary 为 Map<章节标题, 摘要> */
    private Map<String, String> parseChapterSummary(String chapterSummary) {
        Map<String, String> map = new LinkedHashMap<>();
        if (chapterSummary == null || chapterSummary.isBlank()) return map;

        // 尝试按常见格式解析: "第一章\n摘要...\n\n第二章\n摘要..."
        String[] parts = chapterSummary.split("\n(?=[第章节]|\\d+[.、])");
        String currentTitle = null;
        StringBuilder currentSummary = new StringBuilder();

        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            // 检查是否是章节标题行
            if (part.matches("^第[一二三四五六七八九十百千]+章.*")
                    || part.matches("^\\d+[.、].*")
                    || part.matches("^[A-Z][a-z]+.*")
                    || part.length() < 30) {
                if (currentTitle != null && currentSummary.length() > 0) {
                    map.put(currentTitle, currentSummary.toString().trim());
                }
                currentTitle = part.length() > 50 ? part.substring(0, 50) : part;
                currentSummary = new StringBuilder();
            } else {
                currentSummary.append(part).append("\n");
            }
        }
        if (currentTitle != null && currentSummary.length() > 0) {
            map.put(currentTitle, currentSummary.toString().trim());
        }

        return map;
    }

    /** 将 RAG chunk 匹配到章节 */
    private String matchChunkToChapter(String chunkText, List<String> chapterTitles, String lastMatched) {
        if (chunkText == null || chunkText.isBlank()) return null;
        for (String title : chapterTitles) {
            // 去除章节编号前缀，提取核心标题词
            String cleanTitle = title.replaceAll("^第[一二三四五六七八九十百千]+章\\s*", "")
                    .replaceAll("^\\d+[.、]\\s*", "")
                    .trim();
            if (cleanTitle.length() < 2) continue;

            // 检查 chunk 中是否包含标题的核心词（取前 2-4 个字）
            String[] keywords = cleanTitle.split("[，,。.、：:]");
            String primary = keywords[0].trim();
            if (primary.length() >= 2 && chunkText.contains(primary)) {
                return title;
            }
        }
        return null; // 沿用上一章节
    }

    /**
     * Tier 2: 从 LLM 大纲构建内容块
     */
    private List<ContentBlock> buildBlocksFromLlmOutline(Book book, List<BookChunk> allChunks) {
        if (allChunks.isEmpty()) return new ArrayList<>();

        // 抽样 chunks（最多 50 个）
        List<BookChunk> sampledChunks;
        if (allChunks.size() <= 50) {
            sampledChunks = new ArrayList<>(allChunks);
        } else {
            sampledChunks = new ArrayList<>();
            int step = allChunks.size() / 50;
            for (int i = 0; i < allChunks.size(); i += step) {
                sampledChunks.add(allChunks.get(i));
            }
        }

        // 构建 chunk 摘要（每条取前 100 字）
        StringBuilder chunkIndex = new StringBuilder();
        for (int i = 0; i < sampledChunks.size(); i++) {
            String preview = sampledChunks.get(i).text();
            preview = CommonUtils.truncateText(preview, 100);
            chunkIndex.append(i + 1).append(". ").append(preview).append("\n");
        }

        String bookInfo = "书名：《" + book.getTitle() + "》\n作者：" + (book.getAuthor() != null ? book.getAuthor() : "未知");
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            bookInfo += "\n简介：" + CommonUtils.truncateText(book.getDescription(), 300);
        }

        String prompt = """
                你是一个书籍内容分析专家。请根据以下图书内容和抽样片段，生成该书的内容大纲。

                要求：
                1. 将全书内容划分为 %d-%d 个主题块（不要太细，也不要太粗）
                2. 每个块要有「标题」和「一句话摘要」
                3. 标题要能反映该块的内容主题
                4. 输出格式为 JSON 数组，不要任何其他内容

                【图书信息】
                %s

                【内容抽样片段】
                %s

                输出格式：
                [
                  {"title": "标题1", "summary": "一句话摘要"},
                  {"title": "标题2", "summary": "一句话摘要"},
                  ...
                ]
                """.formatted(
                Math.max(5, Math.min(MAX_CONTENT_BLOCKS / 2, sampledChunks.size() / 5)),
                Math.min(MAX_CONTENT_BLOCKS, sampledChunks.size() / 3),
                bookInfo,
                chunkIndex.toString()
        );

        String result = callLlm(prompt);
        if (result == null) return new ArrayList<>();

        result = CommonUtils.stripCodeFence(result);
        List<ContentBlock> blocks = new ArrayList<>();
        try {
            var arr = objectMapper.readTree(result);
            if (arr != null && arr.isArray()) {
                for (var node : arr) {
                    String title = node.has("title") ? node.get("title").asText() : "未知主题";
                    String summary = node.has("summary") ? node.get("summary").asText() : "";

                    // 关联 RAG chunks（通过关键词匹配）
                    Set<String> keywords = extractKeyTerms(title + " " + summary);
                    List<BookChunk> relatedChunks = findRelatedChunks(keywords, allChunks);
                    String representativeText = relatedChunks.isEmpty()
                            ? summary : relatedChunks.get(0).text();
                    if (representativeText.length() > 300) {
                        representativeText = representativeText.substring(0, 300);
                    }

                    blocks.add(ContentBlock.builder()
                            .title(title.length() > 50 ? title.substring(0, 50) : title)
                            .summary(summary.length() > 200 ? summary.substring(0, 200) : summary)
                            .representativeText(representativeText)
                            .chunkCount(relatedChunks.size())
                            .keywords(keywords)
                            .source("llm_outline")
                            .build());
                }
            }
        } catch (Exception e) {
            log.warn("LLM 大纲解析失败: {}", e.getMessage());
        }

        log.info("LLM 大纲内容块构建完成: {} 块", blocks.size());
        return blocks;
    }

    /** 根据关键词在 chunks 中查找最相关的 */
    private List<BookChunk> findRelatedChunks(Set<String> keywords, List<BookChunk> allChunks) {
        if (keywords.isEmpty() || allChunks.isEmpty()) return new ArrayList<>();
        List<BookChunk> related = new ArrayList<>();
        for (BookChunk chunk : allChunks) {
            String text = chunk.text();
            long matchCount = keywords.stream().filter(text::contains).count();
            if (matchCount >= 2) {
                related.add(chunk);
            }
        }
        // 如果直接匹配不够，返回按关键词密度排序的前 5 个
        if (related.isEmpty()) {
            allChunks.stream()
                    .filter(c -> keywords.stream().filter(c.text()::contains).count() >= 1)
                    .limit(5)
                    .forEach(related::add);
        }
        return related;
    }

    /**
     * Tier 3: 密集分块（兜底）
     */
    private List<ContentBlock> buildBlocksFromDenseChunking(List<BookChunk> allChunks) {
        if (allChunks.isEmpty()) return new ArrayList<>();

        int chunksPerBlock = Math.max(1, (int) Math.ceil((double) allChunks.size() / MAX_CONTENT_BLOCKS));
        List<ContentBlock> blocks = new ArrayList<>();
        int blockNum = 0;

        for (int i = 0; i < allChunks.size(); i += chunksPerBlock) {
            blockNum++;
            int end = Math.min(i + chunksPerBlock, allChunks.size());
            StringBuilder combined = new StringBuilder();
            for (int j = i; j < end; j++) {
                combined.append(allChunks.get(j).text()).append(" ");
            }
            String text = combined.toString();
            String preview = text.length() > 300 ? text.substring(0, 300) : text;
            Set<String> keywords = extractKeyTerms(preview);

            blocks.add(ContentBlock.builder()
                    .title("内容区块 " + blockNum)
                    .summary("")
                    .representativeText(preview)
                    .chunkCount(end - i)
                    .keywords(keywords)
                    .source("dense_chunking")
                    .build());
        }

        log.info("密集分块完成: {} 块 (每块 {} chunks)", blocks.size(), chunksPerBlock);
        return blocks;
    }

    /**
     * 评估单个内容块的覆盖度
     * 使用多层方法: 关键词重叠 → RAG 语义 → 综合判定
     */
    private BlockCoverageDetail evaluateSingleBlock(ContentBlock block, int blockIndex,
                                                     String discussionText, Set<String> discussionKeywords,
                                                     Book book, List<BookChunk> allChunks,
                                                     boolean ragAvailable) {
        double keywordOverlap = 0;
        if (!block.getKeywords().isEmpty() && !discussionKeywords.isEmpty()) {
            long overlap = block.getKeywords().stream()
                    .filter(discussionKeywords::contains)
                    .count();
            keywordOverlap = (double) overlap / block.getKeywords().size();
        }

        // 方法A: 关键词判定
        if (keywordOverlap >= 0.25) {
            int level = keywordOverlap >= 0.40 ? 3 : (keywordOverlap >= 0.30 ? 2 : 1);
            double score = Math.min(100, keywordOverlap * 200);
            return BlockCoverageDetail.builder()
                    .block(block)
                    .coverageLevel(level)
                    .score(score)
                    .keywordOverlap(keywordOverlap)
                    .judgeMethod("keyword")
                    .evidence(String.format("关键词重叠率 %.0f%%", keywordOverlap * 100))
                    .build();
        }

        // 方法B: RAG 反向检索（用块文本查讨论）
        if (ragAvailable && block.getRepresentativeText() != null && block.getRepresentativeText().length() > 50) {
            try {
                // 用块的代表文本做RAG查询（查书中的相关片段）
                List<EmbeddingMatch<TextSegment>> matches = embeddingService.searchContent(
                        block.getRepresentativeText(), 3, book);
                if (!matches.isEmpty()) {
                    double avgScore = matches.stream().mapToDouble(EmbeddingMatch::score).average().orElse(0);
                    // 高相似度 + 低关键词重叠 → 语义匹配
                    if (avgScore > 0.6) {
                        int level = avgScore > 0.75 ? 3 : 2;
                        return BlockCoverageDetail.builder()
                                .block(block)
                                .coverageLevel(level)
                                .score(Math.min(100, avgScore * 120))
                                .keywordOverlap(keywordOverlap)
                                .judgeMethod("rag")
                                .evidence(String.format("RAG语义匹配: avgScore=%.3f", avgScore))
                                .build();
                    }
                }
            } catch (Exception e) {
                log.debug("RAG 反向检索失败: block={} - {}", block.getTitle(), e.getMessage());
            }
        }

        // 未达到关键词阈值的，根据关键词覆盖率给基础分
        if (keywordOverlap > 0) {
            int level = keywordOverlap > 0.10 ? 1 : 0;
            double score = keywordOverlap * 100;
            String evidence = level > 0
                    ? String.format("关键词部分重叠 %.0f%%", keywordOverlap * 100)
                    : String.format("关键词重叠不足 %.0f%%", keywordOverlap * 100);
            return BlockCoverageDetail.builder()
                    .block(block)
                    .coverageLevel(level)
                    .score(score)
                    .keywordOverlap(keywordOverlap)
                    .judgeMethod("keyword")
                    .evidence(evidence)
                    .build();
        }

        // 完全无重叠
        return BlockCoverageDetail.builder()
                .block(block)
                .coverageLevel(0)
                .score(0)
                .keywordOverlap(0)
                .judgeMethod("keyword")
                .evidence("无关键词重叠")
                .build();
    }

    /**
     * 对边界块使用 LLM 精确判定
     */
    private void runLlmBorderlineJudgment(List<ContentBlock> blocks, List<BlockCoverageDetail> details,
                                           List<Integer> borderlineIndices, String discussionText, Book book) {
        // 将边界块分批（每批最多 8 个，避免 prompt 太长）
        int batchSize = 8;
        for (int batchStart = 0; batchStart < borderlineIndices.size(); batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, borderlineIndices.size());
            List<Integer> batch = borderlineIndices.subList(batchStart, batchEnd);

            StringBuilder blockInfos = new StringBuilder();
            for (int idx : batch) {
                ContentBlock b = blocks.get(idx);
                blockInfos.append(idx).append(". 【").append(b.getTitle()).append("】\n");
                if (!b.getSummary().isBlank()) {
                    blockInfos.append("   摘要：").append(b.getSummary()).append("\n");
                }
                blockInfos.append("   原文片段：").append(b.getRepresentativeText()).append("\n\n");
            }

            String discussionPreview = CommonUtils.truncateText(discussionText, 4000);

            String prompt = """
                    你是一个图书讨论分析专家。给定的讨论是否覆盖了以下各个内容块？
                    
                    对每个块，判断讨论是否涉及了该块的核心内容。输出 JSON 数组，不要其他内容。
                    判定标准：
                    - YES: 讨论中明确提到了该块的主题、论点或内容
                    - PARTIAL: 讨论仅侧面提及，未深入核心内容
                    - NO: 讨论未涉及该块的内容
                    
                    【讨论内容】
                    %s
                    
                    【要判断的内容块】
                    %s
                    
                    输出格式：
                    [
                      {"index": 0, "judgment": "YES|PARTIAL|NO", "reason": "简短原因"},
                      {"index": 1, ...}
                    ]
                    """.formatted(discussionPreview, blockInfos.toString());

            String result = callLlm(prompt);
            if (result == null) continue;

            result = CommonUtils.stripCodeFence(result);
            try {
                var arr = objectMapper.readTree(result);
                if (arr != null && arr.isArray()) {
                    for (var item : arr) {
                        int idx = item.has("index") ? item.get("index").asInt() : -1;
                        if (idx < 0 && item.has("index") && item.get("index").isInt()) {
                            idx = item.get("index").asInt();
                        }
                        // 尝试用位置匹配
                        if (idx < 0 || idx >= details.size()) continue;

                        String judgment = item.has("judgment") ? item.get("judgment").asText().toUpperCase() : "NO";
                        String reason = item.has("reason") ? item.get("reason").asText() : "";

                        BlockCoverageDetail detail = details.get(idx);
                        switch (judgment) {
                            case "YES" -> {
                                detail.setCoverageLevel(2);
                                detail.setScore(75);
                                detail.setJudgeMethod("llm");
                                detail.setEvidence("LLM判定: " + reason);
                            }
                            case "PARTIAL" -> {
                                if (detail.getCoverageLevel() < 1) {
                                    detail.setCoverageLevel(1);
                                    detail.setScore(40);
                                    detail.setJudgeMethod("llm");
                                    detail.setEvidence("LLM判定(部分): " + reason);
                                }
                            }
                            default -> { /* 维持原判定 */ }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("LLM 边界块判定解析失败: {}", e.getMessage());
            }
        }
    }

    // ========================================================================
    //  算法 2: RAG 语义命中率
    // ========================================================================

    private void runRagSemanticCoverage(String discussionText, Book book,
                                         List<BookChunk> allChunks, CoverageReport report) {
        System.out.println("\n--- 算法 2: RAG 语义命中率 (RAG Semantic Hit Rate) ---");

        if (!embeddingService.isAvailable()) {
            System.out.println("  Embedding 不可用，跳过");
            report.setRagCoverage(new CoverageMetric("RAG语义命中率", 0, 1, 0));
            return;
        }

        try {
            String query = CommonUtils.truncateText(discussionText, 2000);
            List<EmbeddingMatch<TextSegment>> matches = embeddingService.searchContent(query, RAG_TOP_K, book);
            if (matches.isEmpty()) {
                System.out.println("  RAG 检索无结果");
                report.setRagCoverage(new CoverageMetric("RAG语义命中率", 0, 1, 0));
                return;
            }

            matches = matches.stream()
                    .filter(m -> m.score() >= RAG_MIN_SCORE)
                    .toList();

            double avgScore = matches.stream().mapToDouble(EmbeddingMatch::score).average().orElse(0);
            double maxScore = matches.stream().mapToDouble(EmbeddingMatch::score).max().orElse(0);

            Map<String, Integer> scoreBuckets = new LinkedHashMap<>();
            scoreBuckets.put("0.7-1.0", 0);
            scoreBuckets.put("0.5-0.7", 0);
            scoreBuckets.put("0.3-0.5", 0);
            scoreBuckets.put("0.15-0.3", 0);
            for (EmbeddingMatch<TextSegment> m : matches) {
                double s = m.score();
                if (s >= 0.7) scoreBuckets.put("0.7-1.0", scoreBuckets.get("0.7-1.0") + 1);
                else if (s >= 0.5) scoreBuckets.put("0.5-0.7", scoreBuckets.get("0.5-0.7") + 1);
                else if (s >= 0.3) scoreBuckets.put("0.3-0.5", scoreBuckets.get("0.3-0.5") + 1);
                else scoreBuckets.put("0.15-0.3", scoreBuckets.get("0.15-0.3") + 1);
            }

            System.out.printf("  RAG 检索命中 %d 个分块 (minScore=%.2f)%n", matches.size(), RAG_MIN_SCORE);
            System.out.printf("  相似度范围: %.3f ~ %.3f, 平均: %.3f%n",
                    matches.stream().mapToDouble(EmbeddingMatch::score).min().orElse(0),
                    maxScore, avgScore);
            System.out.println("  相似度分布:");
            scoreBuckets.forEach((bucket, count) -> {
                String bar = "█".repeat(Math.min(count, 20));
                System.out.printf("    %s: %2d %s%n", bucket, count, bar);
            });

            double ragHitRate = 0;
            if (!allChunks.isEmpty()) {
                Set<String> hitTexts = new HashSet<>();
                for (EmbeddingMatch<TextSegment> m : matches) {
                    if (m.embedded() != null && m.embedded().text() != null) {
                        String fp = m.embedded().text().substring(0, Math.min(50, m.embedded().text().length()));
                        hitTexts.add(fp);
                    }
                }
                ragHitRate = (double) hitTexts.size() / allChunks.size() * 100;
                System.out.printf("  唯一命中分块指纹: %d / %d (%.1f%%)%n",
                        hitTexts.size(), allChunks.size(), ragHitRate);
            }

            double finalScore = matches.isEmpty() ? 0 :
                    Math.min(100, (avgScore * 100 + ragHitRate) / 2);
            report.setRagCoverage(new CoverageMetric("RAG语义命中率",
                    matches.size(), Math.max(allChunks.size(), matches.size()), finalScore));

        } catch (Exception e) {
            log.warn("RAG 语义覆盖评估异常: {}", e.getMessage());
            report.setRagCoverage(new CoverageMetric("RAG语义命中率", 0, 1, 0));
        }
    }

    // ========================================================================
    //  算法 3: LLM 综合评估
    // ========================================================================

    private void runLlmAssessment(String discussionText, Book book,
                                   List<RoundTableMessage> messages, CoverageReport report) {
        System.out.println("\n--- 算法 3: LLM 综合评估 (LLM Assessment) ---");

        StringBuilder bookInfo = new StringBuilder();
        bookInfo.append("书名：《").append(book.getTitle()).append("》\n");
        bookInfo.append("作者：").append(book.getAuthor()).append("\n");
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            bookInfo.append("简介：").append(CommonUtils.truncateText(book.getDescription(), 500)).append("\n");
        }
        if (book.getConceptTags() != null && !book.getConceptTags().isBlank()) {
            String tags = book.getConceptTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            bookInfo.append("核心概念：").append(tags).append("\n");
        }
        if (book.getCompressedSummary() != null && !book.getCompressedSummary().isBlank()) {
            bookInfo.append("精炼摘要：").append(CommonUtils.truncateText(book.getCompressedSummary(), 1500)).append("\n");
        }

        String discussionSummary = buildDiscussionSummary(messages);

        if (messages.size() < 3) {
            System.out.println("  发言不足 3 条，跳过");
            report.setLlmAssessment(new CoverageMetric("LLM综合评估", 0, 1, 0));
            report.setLlmDimensions(new HashMap<>());
            return;
        }

        String prompt = """
                你是一位专业的书籍讨论评估专家。请评估以下圆桌派讨论对该书内容的覆盖程度。

                【图书信息】
                %s

                【讨论实录（摘要）】
                %s

                请从以下维度评分（每项 0-10 分）：
                1. 广度覆盖 (Breadth)：讨论触及了多少个书中的核心主题/概念？
                2. 深度挖掘 (Depth)：对已触及的主题，讨论的深度如何？是否只是表面提及？
                3. 观点碰撞 (Debate)：不同角色的观点是否形成有效碰撞和辩驳？
                4. 文本关联 (TextAnchor)：讨论是否紧密围绕书中的具体内容、案例或论述展开？
                5. 批判思辨 (CriticalThinking)：是否有对书中观点提出质疑、反思或延伸思考？
                6. 整体覆盖度 (Overall)：综合评估，讨论在多大程度上覆盖了此书的核心内容？

                请以 JSON 格式输出，不要任何其他内容：
                {
                  "dimensions": {
                    "广度覆盖": {"score": N, "reason": "..."},
                    "深度挖掘": {"score": N, "reason": "..."},
                    "观点碰撞": {"score": N, "reason": "..."},
                    "文本关联": {"score": N, "reason": "..."},
                    "批判思辨": {"score": N, "reason": "..."},
                    "整体覆盖度": {"score": N, "reason": "..."}
                  },
                  "强项": ["...", "..."],
                  "不足": ["...", "..."],
                  "改进建议": ["...", "..."]
                }
                """.formatted(bookInfo.toString(), discussionSummary);

        String result = callLlm(prompt);
        if (result == null) {
            System.out.println("  LLM 调用失败，跳过");
            report.setLlmAssessment(new CoverageMetric("LLM综合评估", 0, 1, 0));
            report.setLlmDimensions(new HashMap<>());
            return;
        }

        result = CommonUtils.stripCodeFence(result);
        try {
            var root = objectMapper.readTree(result);
            var dims = root.get("dimensions");
            if (dims != null) {
                Map<String, Double> dimensionScores = new LinkedHashMap<>();
                double totalScore = 0;
                int count = 0;
                for (var it = dims.fieldNames(); it.hasNext(); ) {
                    String dimName = it.next();
                    var dim = dims.get(dimName);
                    double score = dim.has("score") ? dim.get("score").asDouble() : 0;
                    String reason = dim.has("reason") ? dim.get("reason").asText() : "";
                    dimensionScores.put(dimName, score);
                    System.out.printf("  %s: %.1f/10 — %s%n", dimName, score, reason);
                    totalScore += score;
                    count++;
                }

                double overallScore = dimensionScores.getOrDefault("整体覆盖度",
                        count > 0 ? totalScore / count : 0);
                System.out.printf("  整体覆盖度: %.1f/10%n", overallScore);

                report.setLlmAssessment(new CoverageMetric("LLM综合评估",
                        (int) Math.round(overallScore), 10, overallScore * 10));
                report.setLlmDimensions(dimensionScores);
                report.setLlmStrengths(parseStringArray(root, "强项"));
                report.setLlmWeaknesses(parseStringArray(root, "不足"));
                report.setLlmSuggestions(parseStringArray(root, "改进建议"));
            }
        } catch (Exception e) {
            log.warn("LLM 评估结果解析失败: {}", e.getMessage());
            report.setLlmAssessment(new CoverageMetric("LLM综合评估", 0, 1, 0));
            report.setLlmDimensions(new HashMap<>());
        }
    }

    private List<String> parseStringArray(JsonNode root, String field) {
        List<String> result = new ArrayList<>();
        try {
            var arr = root.get(field);
            if (arr != null && arr.isArray()) {
                for (var item : arr) result.add(item.asText());
            }
        } catch (Exception ignored) {}
        return result;
    }

    // ========================================================================
    //  算法 4: 概念标签覆盖度（辅助指标）
    // ========================================================================

    private void runConceptTagCoverage(String discussionText, List<String> conceptTags, CoverageReport report) {
        System.out.println("\n--- 算法 4: 概念标签覆盖度 (Concept Tag Coverage) ---");

        if (conceptTags.isEmpty()) {
            System.out.println("  无概念标签，跳过");
            report.setConceptCoverage(new CoverageMetric("概念标签覆盖度", 0, 1, 0));
            return;
        }

        List<String> directMatched = new ArrayList<>();
        List<String> notMatched = new ArrayList<>();
        for (String tag : conceptTags) {
            if (discussionText.contains(tag)) {
                directMatched.add(tag);
            } else {
                notMatched.add(tag);
            }
        }
        System.out.printf("  [直接匹配] %d/%d (%.1f%%)%n",
                directMatched.size(), conceptTags.size(),
                (double) directMatched.size() / conceptTags.size() * 100);

        List<String> llmMatched = new ArrayList<>();
        if (!notMatched.isEmpty() && notMatched.size() <= 30) {
            String prompt = """
                    以下是一段关于某本书的圆桌派讨论。请判断讨论中是否隐含地涉及了以下各个概念。
                    对每个概念输出 YES 或 NO。即使概念词本身没出现，只要触及了实质内容也算涉及。
                    
                    【讨论内容】
                    %s
                    
                    【要检查的概念】
                    %s
                    
                    输出 JSON: {"结果": [{"概念": "概念名", "涉及": "YES"|"NO"}, ...]}
                    """.formatted(CommonUtils.truncateText(discussionText, 8000),
                    String.join("\n", notMatched));

            String result = callLlm(prompt);
            if (result != null) {
                result = CommonUtils.stripCodeFence(result);
                try {
                    var arr = objectMapper.readTree(result).get("结果");
                    if (arr != null && arr.isArray()) {
                        for (var item : arr) {
                            String concept = item.has("概念") ? item.get("概念").asText() : "";
                            String covered = item.has("涉及") ? item.get("涉及").asText().toUpperCase() : "NO";
                            if ("YES".equals(covered) && conceptTags.contains(concept) && !directMatched.contains(concept)) {
                                llmMatched.add(concept);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("LLM 标签解析失败: {}", e.getMessage());
                }
            }
            System.out.printf("  [LLM补充] %d/%d 概念被判定为语义涉及%n",
                    llmMatched.size(), notMatched.size());
        }

        Set<String> allCovered = new LinkedHashSet<>(directMatched);
        allCovered.addAll(llmMatched);
        double finalRate = (double) allCovered.size() / conceptTags.size() * 100;

        System.out.printf("  [综合] 覆盖 %d/%d → %.1f%%%n",
                allCovered.size(), conceptTags.size(), finalRate);
        System.out.println("  已覆盖: " + String.join(", ", allCovered));
        List<String> missed = conceptTags.stream().filter(t -> !allCovered.contains(t)).toList();
        if (!missed.isEmpty()) {
            System.out.println("  未覆盖: " + String.join(", ", missed));
        }

        report.setConceptCoverage(new CoverageMetric("概念标签覆盖度",
                allCovered.size(), conceptTags.size(), finalRate));
        report.setCoveredConcepts(new ArrayList<>(allCovered));
        report.setMissedConcepts(missed);
    }

    // ========================================================================
    //  综合评分
    // ========================================================================

    private void computeOverallScore(CoverageReport report) {
        System.out.println("\n============================================================");
        System.out.println("  综合评估");
        System.out.println("============================================================");

        double w1 = 0.40; // 内容块覆盖度（核心）
        double w2 = 0.15; // RAG 语义命中率（技术补充）
        double w3 = 0.30; // LLM 综合评估（定性评估）
        double w4 = 0.15; // 概念标签覆盖度（元数据核查）

        double score1 = report.getBlockCoverage() != null ? report.getBlockCoverage().getRate() : 0;
        double score2 = report.getRagCoverage() != null ? report.getRagCoverage().getRate() : 0;
        double score3 = report.getLlmAssessment() != null ? report.getLlmAssessment().getRate() : 0;
        double score4 = report.getConceptCoverage() != null ? report.getConceptCoverage().getRate() : 0;

        double overall = w1 * score1 + w2 * score2 + w3 * score3 + w4 * score4;

        // LLM 不可用时重新分配权重
        if (score3 == 0 && score1 > 0) {
            double adj = w1 + w3;
            overall = (w1 * score1 + w2 * score2 + w4 * score4) / adj * (adj / (adj));
        }

        overall = Math.min(100, Math.max(0, overall));
        report.setOverallScore(overall);

        String grade;
        if (overall >= 85) grade = "S (卓越)";
        else if (overall >= 70) grade = "A (优秀)";
        else if (overall >= 55) grade = "B (良好)";
        else if (overall >= 40) grade = "C (一般)";
        else if (overall >= 25) grade = "D (不足)";
        else grade = "F (严重不足)";
        report.setGrade(grade);

        System.out.printf("  ① 内容块覆盖度 (%.0f%%): %.1f%n", w1 * 100, score1);
        System.out.printf("  ② RAG 语义命中率 (%.0f%%): %.1f%n", w2 * 100, score2);
        System.out.printf("  ③ LLM 综合评估   (%.0f%%): %.1f%n", w3 * 100, score3);
        System.out.printf("  ④ 概念标签覆盖度 (%.0f%%): %.1f%n", w4 * 100, score4);
        System.out.printf("  ─────────────────────────────────────%n");
        System.out.printf("  综合覆盖度: %.1f / 100 → 等级: %s%n", overall, grade);
    }

    // ========================================================================
    //  报告打印
    // ========================================================================

    private void printReport(CoverageReport report) {
        System.out.println("\n\n");
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║              圆桌派会话覆盖度评估报告                        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("【基本信息】");
        System.out.println("  会话 ID:      " + report.getSession().getSessionId());
        System.out.println("  图书:         《" + report.getBook().getTitle() + "》");
        System.out.println("  作者:         " + report.getBook().getAuthor());
        System.out.println("  角色:         " + report.getSession().getRoleKeys());
        System.out.println("  发言总条数:   " + report.getMessages().size());
        System.out.println("  图书内容分块: " + report.getTotalChunks() + " 块");
        System.out.println();
        System.out.println("【覆盖度评分】");

        printMetric("① 内容块覆盖度", report.getBlockCoverage(), "40%");
        printMetric("② RAG 语义命中率", report.getRagCoverage(), "15%");
        printMetric("③ LLM 综合评估", report.getLlmAssessment(), "30%");
        printMetric("④ 概念标签覆盖度", report.getConceptCoverage(), "15%");

        System.out.println("  ────────────────────────────────────────");
        System.out.printf("  综合覆盖度:       %5.1f / 100   等级: %s%n",
                report.getOverallScore(), report.getGrade());

        // 内容块详情
        if (report.getBlocks() != null && !report.getBlocks().isEmpty()
                && report.getBlockDetails() != null) {
            System.out.println();
            System.out.println("【内容块覆盖详情】");
            System.out.printf("  %-4s %-10s %-6s %-8s %s%n", "序号", "覆盖等级", "得分", "判断方法", "块标题");
            for (int i = 0; i < report.getBlocks().size(); i++) {
                ContentBlock b = report.getBlocks().get(i);
                BlockCoverageDetail d = i < report.getBlockDetails().size()
                        ? report.getBlockDetails().get(i) : null;
                String level = d != null ? d.levelLabel() : "-";
                String score = d != null ? String.format("%.0f", d.getScore()) : "-";
                String method = d != null ? d.getJudgeMethod() : "-";
                System.out.printf("  %-4d %-10s %-6s %-8s %s%n",
                        i + 1, level, score, method, b.getTitle());
            }
        }

        // LLM 维度详情
        if (report.getLlmDimensions() != null && !report.getLlmDimensions().isEmpty()) {
            System.out.println();
            System.out.println("【LLM 评估维度详情】");
            report.getLlmDimensions().forEach((dim, score) -> {
                String bar = "▓".repeat((int) Math.round(score));
                String empty = "░".repeat(10 - (int) Math.round(score));
                System.out.printf("  %-10s %s%s %.1f/10%n", dim, bar, empty, score);
            });
        }

        if (report.getLlmStrengths() != null && !report.getLlmStrengths().isEmpty()) {
            System.out.println();
            System.out.println("【强项】");
            report.getLlmStrengths().forEach(s -> System.out.println("  ✓ " + s));
        }
        if (report.getLlmWeaknesses() != null && !report.getLlmWeaknesses().isEmpty()) {
            System.out.println();
            System.out.println("【不足】");
            report.getLlmWeaknesses().forEach(s -> System.out.println("  ✗ " + s));
        }
        if (report.getLlmSuggestions() != null && !report.getLlmSuggestions().isEmpty()) {
            System.out.println();
            System.out.println("【改进建议】");
            report.getLlmSuggestions().forEach(s -> System.out.println("  → " + s));
        }

        // 未覆盖的概念
        if (report.getMissedConcepts() != null && !report.getMissedConcepts().isEmpty()) {
            System.out.println();
            System.out.println("【未覆盖的概念标签】");
            report.getMissedConcepts().forEach(c -> System.out.println("  ○ " + c));
        }

        System.out.println();
        System.out.println("============================================================");
    }

    private void printMetric(String name, CoverageMetric metric, String weight) {
        if (metric == null) {
            System.out.printf("  %-18s  —  (跳过)%n", name);
            return;
        }
        String rateStr = metric.getTotal() > 0
                ? String.format("%.1f%% (%d/%d)", metric.getRate(), metric.getHit(), metric.getTotal())
                : "—";
        System.out.printf("  %-18s  %s     权重 %s%n", name, rateStr, weight);
    }

    // ========================================================================
    //  通用工具方法
    // ========================================================================

    private void printRoleDistribution(List<RoundTableMessage> messages) {
        Map<String, List<RoundTableMessage>> byRole = messages.stream()
                .collect(Collectors.groupingBy(RoundTableMessage::getRoleKey));
        System.out.println("  角色发言分布:");
        byRole.forEach((role, msgs) -> {
            String name = msgs.get(0).getRoleName();
            int totalChars = msgs.stream().mapToInt(m ->
                    (m.getCompressedContent() != null ? m.getCompressedContent() : m.getContent()).length()).sum();
            System.out.printf("    %-5s %s: %d条, %d字符%n", role, name, msgs.size(), totalChars);
        });
    }

    private String buildDiscussionText(List<RoundTableMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (RoundTableMessage msg : messages) {
            String content = msg.getCompressedContent() != null && !msg.getCompressedContent().isBlank()
                    ? msg.getCompressedContent() : msg.getContent();
            if (content != null && !content.isBlank()) {
                sb.append("【").append(msg.getRoleName()).append("】(第").append(msg.getRound()).append("轮)\n")
                        .append(content).append("\n\n");
            }
        }
        return sb.toString();
    }

    private String buildDiscussionSummary(List<RoundTableMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (RoundTableMessage msg : messages) {
            String content = msg.getCompressedContent() != null && !msg.getCompressedContent().isBlank()
                    ? msg.getCompressedContent() : msg.getContent();
            if (content != null && !content.isBlank()) {
                String truncated = content.length() > 200 ? content.substring(0, 200) + "…" : content;
                sb.append(msg.getRoleName()).append("：").append(truncated).append("\n");
                if (sb.length() > 6000) {
                    sb.setLength(6000);
                    sb.append("…（以下省略）");
                    break;
                }
            }
        }
        return sb.toString();
    }

    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Arrays.stream(json.replaceAll("[\\[\\]\"]", "").split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toList());
        }
    }

    /**
     * 从 Qdrant REST API scrolling 获取所有内容分块
     */
    private List<BookChunk> fetchAllBookChunks(Long bookId) {
        List<BookChunk> chunks = new ArrayList<>();
        String scrollUrl = "http://" + qdrantProps.getHost() + ":6333/collections/"
                + qdrantProps.getContentCollection() + "/points/scroll";

        try {
            ObjectMapper localMapper = new ObjectMapper();
            String offset = null;

            do {
                Map<String, Object> bodyMap = new LinkedHashMap<>();
                Map<String, Object> filterMap = new LinkedHashMap<>();
                List<Map<String, Object>> mustList = new ArrayList<>();
                Map<String, Object> fieldMap = new LinkedHashMap<>();
                Map<String, Object> matchMap = new LinkedHashMap<>();
                matchMap.put("value", bookId);
                fieldMap.put("key", "bookId");
                fieldMap.put("match", matchMap);
                Map<String, Object> conditionMap = new LinkedHashMap<>();
                conditionMap.put("field", fieldMap);
                mustList.add(conditionMap);
                filterMap.put("must", mustList);
                bodyMap.put("filter", filterMap);
                bodyMap.put("limit", 200);
                bodyMap.put("with_payload", true);
                bodyMap.put("with_vector", false);
                if (offset != null) {
                    bodyMap.put("offset", offset);
                }

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(scrollUrl))
                        .timeout(java.time.Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(localMapper.writeValueAsString(bodyMap)));
                if (qdrantProps.getApiKey() != null && !qdrantProps.getApiKey().isBlank()) {
                    reqBuilder.header("api-key", qdrantProps.getApiKey());
                }

                HttpResponse<String> response = httpClient.send(
                        reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    log.warn("Qdrant scroll 请求失败: status={}", response.statusCode());
                    break;
                }

                JsonNode result = localMapper.readTree(response.body()).get("result");
                if (result == null) break;

                JsonNode points = result.get("points");
                if (points != null && points.isArray()) {
                    for (JsonNode point : points) {
                        JsonNode payload = point.get("payload");
                        if (payload == null) continue;
                        String text = payload.has("text_segment") ? payload.get("text_segment").asText()
                                : payload.has("text") ? payload.get("text").asText() : "";
                        if (!text.isBlank()) {
                            chunks.add(new BookChunk(text, point.has("id") ? point.get("id").asLong() : 0));
                        }
                    }
                }

                JsonNode nextOffset = result.get("next_page_offset");
                offset = (nextOffset != null && !nextOffset.isNull()) ? nextOffset.asText() : null;
            } while (offset != null);

            log.info("从 Qdrant 获取图书 {} 的内容分块: {} 块", bookId, chunks.size());
        } catch (Exception e) {
            log.warn("从 Qdrant 获取内容分块失败: bookId={} - {}", bookId, e.getMessage());
        }
        return chunks;
    }

    /**
     * 安全调用 LLM
     */
    private String callLlm(String prompt) {
        try {
            ChatModel chatModel = chatModelFactory.buildToolChatModel();
            if (chatModel == null) {
                log.warn("AI 模型不可用");
                return null;
            }
            return chatModel.chat(prompt);
        } catch (Exception e) {
            log.warn("LLM 调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 提取文本中的关键术语（中文 2-6 字词，去停用词）
     */
    private Set<String> extractKeyTerms(String text) {
        if (text == null || text.isBlank()) return new HashSet<>();
        Set<String> terms = new HashSet<>();
        Pattern p = Pattern.compile("[\\u4e00-\\u9fa5]{2,6}");
        Matcher m = p.matcher(text);
        while (m.find()) {
            String word = m.group();
            if (!STOP_WORDS.contains(word)) terms.add(word);
        }
        return terms;
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "可以", "一个", "这个", "那个", "什么", "我们", "他们", "你们",
            "就是", "不是", "没有", "如果", "因为", "所以", "但是", "而且",
            "或者", "虽然", "然后", "这样", "那样", "怎么", "如何", "为什么",
            "时候", "方式", "东西", "事情", "地方", "问题", "关系", "情况",
            "部分", "方面", "能力", "水平", "程度", "特点", "开始", "继续",
            "可能", "应该", "需要", "能够", "已经", "正在", "将会",
            "这些", "那些", "这里", "那里", "这种", "那种",
            "这么", "那么", "一些", "很多", "大量", "主要", "基本", "重要",
            "不同", "一样", "完全", "非常", "比较", "相当", "特别", "甚至",
            "以及", "还有", "包括", "关于", "对于", "通过", "根据", "按照"
    );

    // ========================================================================
    //  内部数据类型
    // ========================================================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CoverageMetric {
        private String name;
        private int hit;
        private int total;
        private double rate; // 0-100
    }

    public record BookChunk(String text, long pointId) {}

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CoverageReport {
        private RoundTableSession session;
        private Book book;
        private List<RoundTableMessage> messages;
        private List<String> conceptTags;
        private List<String> readerNeedTags;
        private int totalChunks;
        private double overallScore;
        private String grade;

        // 算法1: 内容块覆盖度
        private CoverageMetric blockCoverage;
        private List<ContentBlock> blocks;
        private List<BlockCoverageDetail> blockDetails;

        // 算法2: RAG 语义
        private CoverageMetric ragCoverage;

        // 算法3: LLM 评估
        private CoverageMetric llmAssessment;
        private Map<String, Double> llmDimensions;
        private List<String> llmStrengths;
        private List<String> llmWeaknesses;
        private List<String> llmSuggestions;

        // 算法4: 概念标签
        private CoverageMetric conceptCoverage;
        private List<String> coveredConcepts;
        private List<String> missedConcepts;
    }
}
