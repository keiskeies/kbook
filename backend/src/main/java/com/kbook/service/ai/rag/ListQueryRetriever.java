package com.kbook.service.ai.rag;

import com.kbook.common.util.CommonUtils;
import com.kbook.config.ChatModelFactory;
import com.kbook.entity.AiScene;
import com.kbook.entity.Book;
import com.kbook.service.embedding.EmbeddingIndexService;
import com.kbook.service.embedding.EmbeddingIndexService.ChunkInfo;
import com.kbook.service.embedding.EmbeddingService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 列表型问题检索器 — 实现三档检索策略。
 * <p>
 * 三档策略共用 LLM 精筛方法，差异在候选 chunks 的来源：
 * <ul>
 *   <li>档 1（FULL_SCAN）：全量 scroll → LLM 精筛</li>
 *   <li>档 2（TOC_RANGE）：toc 范围 scroll → LLM 精筛（toc 已过滤，可选精筛）</li>
 *   <li>档 3（CLUSTER_EXPAND）：向量召回 → 分簇 → 邻域扩展 scroll → LLM 精筛</li>
 * </ul>
 * <p>
 * 所有档位失败时返回空列表，由调用方退化为常规 RAG 流程。
 */
@Slf4j
@Service
public class ListQueryRetriever {

    /** 全量 scroll 上限（防止极端大书 OOM） */
    private static final int MAX_FULL_SCAN_CHUNKS = 500;
    /** LLM 精筛单批最大 chunks 数 */
    private static final int LLM_REFINE_BATCH_SIZE = 15;
    /** LLM 精筛整体超时（毫秒）——超时按「保守保留该批全部」处理，不丢内容 */
    private static final long LLM_REFINE_TIMEOUT_MS = 45_000L;
    /** 簇扩展后总 chunks 上限 */
    private static final int MAX_CLUSTER_EXPAND_CHUNKS = 50;
    /** 档 3 向量召回 topN */
    private static final int CLUSTER_RECALL_TOP_N = 15;
    /** 锚点法需要 scroll 全书 chunk 做标题定位；超过这个体量回退均分映射 */
    private static final int MAX_ANCHOR_SCAN_CHUNKS = 3000;
    /** 正文标题定位的窗口：标题所在块向前/向后各扩多少块 */
    private static final int TITLE_WINDOW_BEFORE = 1;
    private static final int TITLE_WINDOW_AFTER = 3;
    // 注意：档 3 向量召回复用 EmbeddingService.searchContent，其内部阈值固定为 0.2。
    // 邻域扩展的核心价值是绕过向量相似度——即使向量召回只命中少量 chunks，
    // 邻域扩展会按 chunkIndex 范围拉取周围 chunks，再由 LLM 精筛。

    private final EmbeddingService embeddingService;
    private final ChatModelFactory chatModelFactory;
    private final ExecutorService refineExecutor;

    public ListQueryRetriever(EmbeddingService embeddingService,
                              ChatModelFactory chatModelFactory,
                              @org.springframework.beans.factory.annotation.Qualifier("listRefineExecutor")
                              ExecutorService refineExecutor) {
        this.embeddingService = embeddingService;
        this.chatModelFactory = chatModelFactory;
        this.refineExecutor = refineExecutor;
    }

    /**
     * 检索结果 — 包含 chunks 和策略日志。
     */
    public record RetrievalResult(
            /** 精筛后的 chunks（按 chunkIndex 升序） */
            List<EmbeddingMatch<TextSegment>> matches,
            /** 策略执行日志（用于诊断） */
            String strategyLog
    ) {
        public static RetrievalResult empty(String reason) {
            return new RetrievalResult(List.of(), reason);
        }
    }

    // ================================================================
    // 档 1：全量 scroll + LLM 精筛
    // ================================================================

    /**
     * 档 1：全量 scroll 所有 chunks → 分批 LLM 精筛。
     * 适用：小书（chunks < 200）或 toc 完整的小书。
     *
     * @param book       书籍
     * @param listTopic  列表主题（如"11项能力培养"）
     * @param ragMaxChars RAG 上下文最大字符数
     * @return 检索结果
     */
    public RetrievalResult fullScanAndRefine(Book book, String listTopic, int ragMaxChars) {
        long bookId = book.getId();
        String logPrefix = "[档1-FullScan] bookId=" + bookId;

        try {
            List<ChunkInfo> allChunks = embeddingService.scrollAllContentChunks(bookId);
            if (allChunks.isEmpty()) {
                return RetrievalResult.empty(logPrefix + " 全量 scroll 无结果");
            }

            // 截断极端大书，防止 OOM
            if (allChunks.size() > MAX_FULL_SCAN_CHUNKS) {
                log.warn("{} 全量 chunks 数={} 超过上限，截断到 {}",
                        logPrefix, allChunks.size(), MAX_FULL_SCAN_CHUNKS);
                allChunks = allChunks.subList(0, MAX_FULL_SCAN_CHUNKS);
            }

            log.info("{} 全量 scroll 完成: chunks={}", logPrefix, allChunks.size());

            List<ChunkInfo> refined = refineByLlm(allChunks, listTopic, logPrefix);
            if (refined.isEmpty()) {
                return RetrievalResult.empty(logPrefix + " LLM 精筛后无结果");
            }

            List<EmbeddingMatch<TextSegment>> matches = toMatches(refined, bookId, ragMaxChars);
            return new RetrievalResult(matches,
                    logPrefix + " chunks=" + allChunks.size() + "→精筛=" + refined.size() + "→输出=" + matches.size());

        } catch (Exception e) {
            log.warn("{} 检索失败: {}", logPrefix, e.getMessage());
            return RetrievalResult.empty(logPrefix + " 异常: " + e.getMessage());
        }
    }

    // ================================================================
    // 档 2：toc 范围补拉
    // ================================================================

    /**
     * 档 2：LLM 从 toc 找列表所在章节范围 → 按 chunkIndex 范围 scroll → 可选 LLM 精筛。
     * 适用：toc 完整/中等的中大书。
     *
     * @param book       书籍
     * @param listTopic  列表主题
     * @param ragMaxChars RAG 上下文最大字符数
     * @return 检索结果
     */
    public RetrievalResult tocRangeRetrieve(Book book, String listTopic, String question, int ragMaxChars) {
        long bookId = book.getId();
        String logPrefix = "[档2-TocRange] bookId=" + bookId;

        try {
            // 1. LLM 从 toc 找列表章节范围
            ChapterRange range = findChapterRangeByToc(book, listTopic, logPrefix);
            List<ChunkInfo> chunks = range == null
                    ? List.of()
                    : embeddingService.scrollChunksByIndexRange(bookId, range.lo(), range.hi());

            // 2.5 窗口必须真的覆盖主题：目录不完整时 LLM 只能挑一个「最像的行」，
            // 窗口会切到别的章节（实测「如何获得幸福和成功」不在目录里 → 窗口落到「第三讲」）。
            if (!TopicCoverageCheck.covered(question, listTopic, textsOf(chunks))) {
                // 目录指不到那一节时，先在正文里按「引号里的章节名出现在块开头」定位它——
                // 这一步不看目录、不调模型，专治「那一节根本不在目录里」。
                ChapterRange byTitle = findChapterRangeByTitle(book, question, logPrefix);
                if (byTitle != null) {
                    range = byTitle;
                    chunks = embeddingService.scrollChunksByIndexRange(bookId, range.lo(), range.hi());
                }
                // 两条确定性路径都不覆盖主题就交回上层换策略，绝不拿错误章节硬答
                if (!TopicCoverageCheck.covered(question, listTopic, textsOf(chunks))) {
                    log.warn("{} 目录窗口与正文标题都未覆盖主题「{}」，交回上层换检索策略", logPrefix, listTopic);
                    return RetrievalResult.empty(logPrefix + " 目录窗口未覆盖主题");
                }
            }

            int minIdx = range.lo();
            int maxIdx = range.hi();
            log.info("{} 最终章节范围: chunkIndex=[{}-{}], chunks={}", logPrefix, minIdx, maxIdx, chunks.size());

            // 3. 范围内 chunks 较多时仍需 LLM 精筛（toc 范围可能包含无关内容）
            List<ChunkInfo> refined = chunks.size() > LLM_REFINE_BATCH_SIZE
                    ? refineByLlm(chunks, listTopic, logPrefix) : chunks;

            // 3.5 窗口内各章节的开头块必须保留：精筛是 LLM 挑的，容易只挑第一个子章节
            // （实测「芒格私人书单」只留下「第一辑 自然」，六辑丢了五辑）。
            refined = mergeAnchorHeads(refined, chunks, range.anchorChunks());

            List<EmbeddingMatch<TextSegment>> matches = toMatches(refined, bookId, ragMaxChars);
            return new RetrievalResult(matches,
                    logPrefix + " range=[" + minIdx + "-" + maxIdx + "], chunks=" +
                            chunks.size() + "→精筛=" + refined.size() + "→输出=" + matches.size());

        } catch (Exception e) {
            log.warn("{} 检索失败: {}", logPrefix, e.getMessage());
            return RetrievalResult.empty(logPrefix + " 异常: " + e.getMessage());
        }
    }

    /**
     * 正文标题定位：读者用引号指名的那一节，若在正文里以「块开头」出现，就以它为中心开窗口。
     * <p>目录本身可能缺这一节（实测《穷查理宝典》目录里没有「如何获得幸福和成功」，
     * 但 chunk 57 开头正是这个标题），此时 LLM 只能从目录里挑「最像的行」，窗口必然切错。
     * 本方法完全不依赖目录、也不调模型：在正文里找该标题，命中后向前 1 块、向后 3 块开窗
     * （标题所在块通常就是清单/论述的起点，前一块承接上一节结尾，向后覆盖该节正文）。
     * <p>只认「出现在块开头附近」的命中：标题在正文中间被提及（引用、小结）不该当成章节起点。
     *
     * @return 定位到的窗口；标题没出现在任何块开头时返回 null
     */
    private ChapterRange findChapterRangeByTitle(Book book, String question, String logPrefix) {
        List<String> titles = TopicCoverageCheck.quotedPhrases(question);
        if (titles.isEmpty() || book == null || book.getId() == null) {
            return null;
        }
        long totalChunks = embeddingService.countContentChunks(book.getId());
        if (totalChunks <= 0 || totalChunks > MAX_ANCHOR_SCAN_CHUNKS) {
            return null;
        }
        List<ChunkInfo> all = embeddingService.scrollAllContentChunks(book.getId());
        if (all.isEmpty()) {
            return null;
        }
        all.sort(Comparator.comparingInt(ChunkInfo::chunkIndex));

        int hit = -1;
        String matched = null;
        for (String title : titles) {
            for (ChunkInfo chunk : all) {
                if (TopicCoverageCheck.startsWithTitle(chunk.text(), title)) {
                    hit = chunk.chunkIndex();
                    matched = title;
                    break;
                }
            }
            if (hit >= 0) break;
        }
        if (hit < 0) {
            log.info("{} 正文标题定位未命中: titles={}", logPrefix, titles);
            return null;
        }
        int lo = Math.max(0, hit - TITLE_WINDOW_BEFORE);
        int hi = (int) Math.min(totalChunks - 1, hit + TITLE_WINDOW_AFTER);
        log.info("{} 正文标题定位命中: title={}, 起始chunkIndex={}, 窗口=[{}-{}]",
                logPrefix, matched, hit, lo, hi);
        return new ChapterRange(lo, hi, List.of(hit));
    }

    /** 块文本列表，供 TopicCoverageCheck 做窗口覆盖判定 */
    private static List<String> textsOf(List<ChunkInfo> chunks) {
        List<String> texts = new ArrayList<>(chunks.size());
        for (ChunkInfo chunk : chunks) {
            texts.add(chunk.text());
        }
        return texts;
    }

    /**
     * 把窗口内各章节的开头块（锚点）补回精筛结果，并排在最前面。
     * <p>
     * 锚点块是"这一章从哪开始"的证据：列表题要逐条展开时，每一章的开头正是条目清单所在处。
     * LLM 精筛只按主题挑块，容易只留下第一个子章节（实测六辑只剩一辑），
     * 所以这里强制把每章开头补回来，并给它们最高的排名（预算截断时优先保留）。
     */
    private List<ChunkInfo> mergeAnchorHeads(List<ChunkInfo> refined, List<ChunkInfo> window,
                                              List<Integer> anchorChunks) {
        if (anchorChunks == null || anchorChunks.isEmpty()) {
            return refined;
        }
        Set<Integer> headIndexes = new HashSet<>(anchorChunks);
        List<ChunkInfo> heads = new ArrayList<>();
        for (ChunkInfo chunk : window) {
            if (headIndexes.contains(chunk.chunkIndex())) {
                heads.add(chunk);
            }
        }
        if (heads.isEmpty()) {
            return refined;
        }
        List<ChunkInfo> merged = new ArrayList<>(heads.size() + refined.size());
        merged.addAll(heads);
        for (ChunkInfo chunk : refined) {
            if (!headIndexes.contains(chunk.chunkIndex())) {
                merged.add(chunk);
            }
        }
        return merged;
    }

    // ================================================================
    // 档 3：簇扩展 + LLM 精筛
    // ================================================================

    /**
     * 档 3：向量召回 → 按 chunkIndex 分簇 → 邻域扩展 scroll → LLM 精筛。
     * 适用：toc 不完整的中大书。
     *
     * @param book       书籍
     * @param listTopic  列表主题
     * @param question   原始问题（用于向量召回）
     * @param ragMaxChars RAG 上下文最大字符数
     * @return 检索结果
     */
    public RetrievalResult clusterExpandRetrieve(Book book, String listTopic,
                                                String question, int ragMaxChars) {
        long bookId = book.getId();
        String logPrefix = "[档3-ClusterExpand] bookId=" + bookId;

        try {
            // 1. 向量召回 top-N（阈值放宽到 0.1）
            List<EmbeddingMatch<TextSegment>> rawMatches = vectorRecall(book, question, logPrefix);
            if (rawMatches.isEmpty()) {
                return RetrievalResult.empty(logPrefix + " 向量召回无结果");
            }

            log.info("{} 向量召回: hits={}", logPrefix, rawMatches.size());

            // 2. 提取 chunkIndex 集合
            List<Integer> hitIndices = rawMatches.stream()
                    .map(m -> {
                        Long idx = m.embedded() != null && m.embedded().metadata() != null
                                ? m.embedded().metadata().getLong("chunkIndex") : null;
                        return idx != null ? idx.intValue() : -1;
                    })
                    .filter(idx -> idx >= 0)
                    .distinct()
                    .sorted()
                    .toList();

            if (hitIndices.isEmpty()) {
                return RetrievalResult.empty(logPrefix + " 命中 chunks 无有效 chunkIndex");
            }

            // 3. 分簇 + 邻域扩展
            List<int[]> expandedRanges = expandClusterRanges(hitIndices, logPrefix);
            log.info("{} 簇扩展: 命中={}, 簇数={}, 扩展范围数={}",
                    logPrefix, hitIndices.size(), countClusters(hitIndices), expandedRanges.size());

            // 4. 范围 scroll 拉取候选 chunks
            List<ChunkInfo> candidates = new ArrayList<>();
            for (int[] range : expandedRanges) {
                candidates.addAll(embeddingService.scrollChunksByIndexRange(bookId, range[0], range[1]));
            }

            // 去重（不同簇范围可能重叠）
            candidates = candidates.stream()
                    .collect(Collectors.toMap(
                            ChunkInfo::chunkIndex,
                            c -> c,
                            (a, b) -> a))
                    .values()
                    .stream()
                    .sorted(Comparator.comparingInt(ChunkInfo::chunkIndex))
                    .toList();

            // 截断到上限
            if (candidates.size() > MAX_CLUSTER_EXPAND_CHUNKS) {
                log.warn("{} 扩展后 chunks={} 超过上限，截断到 {}",
                        logPrefix, candidates.size(), MAX_CLUSTER_EXPAND_CHUNKS);
                candidates = candidates.subList(0, MAX_CLUSTER_EXPAND_CHUNKS);
            }

            log.info("{} 扩展后候选 chunks: {}", logPrefix, candidates.size());

            // 5. LLM 精筛
            List<ChunkInfo> refined = refineByLlm(candidates, listTopic, logPrefix);
            if (refined.isEmpty()) {
                // 兜底：精筛失败时退化为原始向量召回结果
                log.warn("{} LLM 精筛后无结果，退化为原始向量召回", logPrefix);
                return new RetrievalResult(
                        truncateMatches(rawMatches, ragMaxChars),
                        logPrefix + " chunks=" + candidates.size() + "→精筛=0→退化向量召回=" + rawMatches.size());
            }

            List<EmbeddingMatch<TextSegment>> matches = toMatches(refined, bookId, ragMaxChars);
            return new RetrievalResult(matches,
                    logPrefix + " 召回=" + rawMatches.size() + "→候选=" + candidates.size() +
                            "→精筛=" + refined.size() + "→输出=" + matches.size());

        } catch (Exception e) {
            log.warn("{} 检索失败: {}", logPrefix, e.getMessage());
            return RetrievalResult.empty(logPrefix + " 异常: " + e.getMessage());
        }
    }

    // ================================================================
    // 通用辅助方法
    // ================================================================

    /**
     * 向量召回（复用 EmbeddingService.searchContent，阈值 0.2 + 5 层降级）。
     * 阈值不需要放宽——邻域扩展会按 chunkIndex 拉取周围 chunks，绕过向量相似度限制。
     */
    private List<EmbeddingMatch<TextSegment>> vectorRecall(Book book, String question, String logPrefix) {
        try {
            return embeddingService.searchContent(question, CLUSTER_RECALL_TOP_N, book);
        } catch (Exception e) {
            log.warn("{} 向量召回失败: {}", logPrefix, e.getMessage());
            return List.of();
        }
    }

    /**
     * 分簇 + 邻域扩展计算。
     * 簇定义：chunkIndex 连续的命中点形成一个簇。
     * 扩展量：簇宽度 × 2（孤点 ±2，宽簇 ±4）。
     */
    private List<int[]> expandClusterRanges(List<Integer> hitIndices, String logPrefix) {
        List<int[]> ranges = new ArrayList<>();
        if (hitIndices.isEmpty()) return ranges;

        int clusterStart = hitIndices.get(0);
        int clusterEnd = hitIndices.get(0);

        for (int i = 1; i < hitIndices.size(); i++) {
            int idx = hitIndices.get(i);
            // 连续性判断：idx 与 clusterEnd 相差 ≤ 1 视为同簇
            if (idx <= clusterEnd + 1) {
                clusterEnd = idx;
            } else {
                // 簇结束，计算扩展范围
                ranges.add(expandCluster(clusterStart, clusterEnd));
                clusterStart = idx;
                clusterEnd = idx;
            }
        }
        // 最后一个簇
        ranges.add(expandCluster(clusterStart, clusterEnd));

        return ranges;
    }

    /**
     * 单簇扩展：宽度 × 2，下限 0。
     */
    private int[] expandCluster(int clusterStart, int clusterEnd) {
        int width = clusterEnd - clusterStart;
        int expand = Math.max(2, width * 2);
        return new int[]{Math.max(0, clusterStart - expand), clusterEnd + expand};
    }

    /**
     * 统计簇数（用于日志）。
     */
    private int countClusters(List<Integer> hitIndices) {
        if (hitIndices.isEmpty()) return 0;
        int count = 1;
        int prev = hitIndices.get(0);
        for (int i = 1; i < hitIndices.size(); i++) {
            if (hitIndices.get(i) > prev + 1) count++;
            prev = hitIndices.get(i);
        }
        return count;
    }

    /**
     * LLM 精筛：分批调用 LLM，输出相关 chunkIndex 列表。
     * <p>
     * 各批并行执行：精筛是纯 LLM IO，串行时耗时 = 批数 × 单批耗时
     * （2026-10-26 运行时实测：212 chunks → 15 批串行共 97.5s，是列表型问答的首要延迟来源）。
     * <p>
     * 结果顺序与串行版一致：按批次序拼接，批内保持 LLM 的选择顺序。
     */
    private List<ChunkInfo> refineByLlm(List<ChunkInfo> candidates, String listTopic, String logPrefix) {
        List<ChunkInfo> refined = new ArrayList<>();
        if (candidates.isEmpty()) return refined;

        int batchCount = (candidates.size() + LLM_REFINE_BATCH_SIZE - 1) / LLM_REFINE_BATCH_SIZE;
        List<List<ChunkInfo>> batches = new ArrayList<>(batchCount);
        List<Future<List<ChunkInfo>>> futures = new ArrayList<>(batchCount);
        for (int batch = 0; batch < batchCount; batch++) {
            int from = batch * LLM_REFINE_BATCH_SIZE;
            int to = Math.min(from + LLM_REFINE_BATCH_SIZE, candidates.size());
            List<ChunkInfo> batchChunks = new ArrayList<>(candidates.subList(from, to));
            batches.add(batchChunks);
            try {
                futures.add(refineExecutor.submit(() -> refineBatch(batchChunks, listTopic)));
            } catch (RejectedExecutionException re) {
                // 线程池满 → 保守保留该批全部（与 LLM 调用失败同策略），不丢内容
                log.warn("{} LLM 精筛线程池已满，批次 {} 直接保留全部", logPrefix, batch + 1);
                futures.add(CompletableFuture.completedFuture(batchChunks));
            }
        }

        // 统一截止时间：避免每批各等一轮把总耗时放大成 批数 × 超时
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LLM_REFINE_TIMEOUT_MS);
        for (int batch = 0; batch < batchCount; batch++) {
            List<ChunkInfo> batchChunks = batches.get(batch);
            Future<List<ChunkInfo>> future = futures.get(batch);
            long remain = deadline - System.nanoTime();
            try {
                if (remain <= 0) throw new TimeoutException("deadline exceeded");
                refined.addAll(future.get(remain, TimeUnit.NANOSECONDS));
            } catch (TimeoutException te) {
                future.cancel(true);
                log.warn("{} LLM 精筛批次 {} 超时（{}ms），保留该批全部",
                        logPrefix, batch + 1, LLM_REFINE_TIMEOUT_MS);
                refined.addAll(batchChunks);
            } catch (Exception e) {
                log.warn("{} LLM 精筛批次 {} 失败: {}", logPrefix, batch + 1, e.getMessage());
                refined.addAll(batchChunks);
            }
        }

        return refined;
    }

    /**
     * 单批 LLM 精筛。LLM 失败/异常时保守保留该批全部，避免漏召回。
     */
    private List<ChunkInfo> refineBatch(List<ChunkInfo> batchChunks, String listTopic) {
        try {
            List<Integer> selectedIndices = callLlmRefine(batchChunks, listTopic);
            if (selectedIndices == null) {
                // LLM 调用失败/模型未配置 → 保留全部（保守，避免漏召回）
                return batchChunks;
            }
            if (selectedIndices.isEmpty()) {
                // LLM 判断这批无相关内容 → 丢弃
                return List.of();
            }
            // 保留 LLM 的选择顺序（相关性信号），后续按 rank 截断
            return ChunkRanking.orderSelected(batchChunks, selectedIndices);
        } catch (Exception e) {
            log.warn("LLM 精筛批次失败: {}", e.getMessage());
            // 失败时保留该批次全部（保守策略，避免漏召回）
            return batchChunks;
        }
    }

    /**
     * LLM 精筛单次调用。
     */
    private List<Integer> callLlmRefine(List<ChunkInfo> chunks, String listTopic) {
        var model = chatModelFactory.buildForScene(AiScene.LIST_QUERY_REFINE);
        if (model == null) {
            log.warn("TOOL 模型未配置，跳过 LLM 精筛");
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("你正在帮用户从一本书中查找\"").append(listTopic).append("\"的具体内容。\n\n");
        sb.append("【候选 chunks（按 chunkIndex 排序）】\n");
        for (ChunkInfo c : chunks) {
            String preview = c.text().length() > 300 ? c.text().substring(0, 300) : c.text();
            sb.append("[chunkIndex=").append(c.chunkIndex()).append("] ")
              .append(preview.replaceAll("\\n", " ")).append("\n");
        }
        sb.append("\n【任务】\n");
        sb.append("1. 找出所有\"具体讲解").append(listTopic).append("中某一项\"的 chunks\n");
        sb.append("2. 同时保留\"列表总览/概述\"的 chunks\n");
        sb.append("3. 排除明显属于其他章节的 chunks\n");
        sb.append("4. 只输出 chunkIndex，逗号分隔，无其他文字\n");
        sb.append("5. 例：51,52,53,55,56,57\n");

        try {
            var response = model.chat(List.of(
                    dev.langchain4j.data.message.UserMessage.from(sb.toString())));
            String text = response.aiMessage().text();
            if (text == null || text.isBlank()) return null;

            // 解析 chunkIndex 列表
            List<Integer> indices = new ArrayList<>();
            for (String token : text.split("[,，\\s]+")) {
                try {
                    indices.add(Integer.parseInt(token.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
            return indices;
        } catch (Exception e) {
            log.warn("LLM 精筛调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 目录行 → chunkIndex 区间，以及落在区间内的各章节锚点（章节开头块）。
     *
     * @param lo           区间起始 chunkIndex（含）
     * @param hi           区间结束 chunkIndex（含）
     * @param anchorChunks 区间内每个目录行对应的正文锚点；均分映射时为空
     */
    private record ChapterRange(int lo, int hi, List<Integer> anchorChunks) {
        static ChapterRange none() {
            return new ChapterRange(-1, -1, List.of());
        }
    }

    /**
     * LLM 从 toc 找列表章节范围（档 2 专用）。
     * 返回 [minChunkIndex, maxChunkIndex]，找不到返回 null。
     */
    private ChapterRange findChapterRangeByToc(Book book, String listTopic, String logPrefix) {
        if (book.getToc() == null || book.getToc().isBlank()) {
            return null;
        }

        var model = chatModelFactory.buildForScene(AiScene.LIST_QUERY_CHAPTER_RANGE);
        if (model == null) {
            log.warn("{} TOOL 模型未配置，无法推断章节范围", logPrefix);
            return null;
        }

        // 估算总 chunks 数（用于把章节序号映射到 chunkIndex 范围）
        long totalChunks = embeddingService.countContentChunks(book.getId());
        if (totalChunks <= 0) return null;

        List<TocAnchorResolver.TocLine> tocLines = TocAnchorResolver.parseToc(book.getToc());
        if (tocLines.isEmpty()) {
            log.warn("{} toc 解析后无有效行", logPrefix);
            return null;
        }

        String prompt = "你正在帮用户定位书中\"" + listTopic + "\"对应的章节范围。\n\n" +
                "【目录】（共 " + tocLines.size() + " 行，括号内是行号，引用时用行号）\n" +
                buildNumberedToc(tocLines) + "\n\n" +
                "【任务】\n" +
                "1. 从目录中找出最可能包含\"" + listTopic + "\"的行号范围\n" +
                "2. 返回起始行号和结束行号（就是上面括号里的编号，1-based）\n" +
                "3. 严格按以下行式 KV 格式输出，不要 JSON、不要代码块围栏：\n" +
                "START_CHAPTER: 3\n" +
                "END_CHAPTER: 5\n" +
                "4. 找不到时返回：\n" +
                "START_CHAPTER: null\n" +
                "END_CHAPTER: null";

        try {
            var response = model.chat(List.of(
                    dev.langchain4j.data.message.UserMessage.from(prompt)));
            String text = response.aiMessage().text();
            if (text == null || text.isBlank()) return null;

            // 行式 KV 解析（兼容 LLM 偶发输出 JSON）
            text = CommonUtils.stripCodeFence(text);
            Integer startCh = parseChapterKv(text, "START_CHAPTER");
            Integer endCh = parseChapterKv(text, "END_CHAPTER");

            // 边界校验：1-based 行号，且不能超过 toc 总行数
            if (startCh != null && endCh != null && startCh > 0 && endCh >= startCh) {
                startCh = Math.min(startCh, tocLines.size());
                endCh = Math.min(endCh, tocLines.size());

                ChapterRange anchored = resolveAnchoredRange(book, tocLines, startCh, endCh, totalChunks, logPrefix);
                if (anchored != null) {
                    return anchored;
                }
                // 锚点不可用 → 回退均分映射
                // 章节 → chunkIndex 范围映射
                // 假设章节均匀分布：章节 i 对应 chunkIndex 范围 [(i-1)*chunksPerChapter, i*chunksPerChapter)
                double chunksPerChapter = (double) totalChunks / tocLines.size();
                int minIdx = (int) Math.max(0, (startCh - 1) * chunksPerChapter);
                int maxIdx = (int) Math.min(totalChunks - 1, endCh * chunksPerChapter);
                // 扩展 1 个章节的余量，应对映射误差
                int expand = (int) Math.max(2, chunksPerChapter);
                return new ChapterRange(Math.max(0, minIdx - expand / 2), maxIdx + expand / 2, List.of());
            }
        } catch (Exception e) {
            log.warn("{} LLM 章节范围推断失败: {}", logPrefix, e.getMessage());
        }
        return null;
    }

    /**
     * 锚点法：目录行号 → chunkIndex 区间。
     * <p>
     * 目录行 ≠ 章节（混着书名行、扉页、版权页、「上篇/下篇」容器行），且真实章节长度极不均匀，
     * 按行均分会让窗口整体错位、每个章节的开头都被跳过。改为用「序数标记 + 标题」在正文里
     * 定位每行的真实起点（见 {@link TocAnchorResolver}）。
     *
     * @return {minIdx, maxIdx}；锚点不可用时返回 null，由调用方回退均分映射
     */
    private ChapterRange resolveAnchoredRange(Book book, List<TocAnchorResolver.TocLine> tocLines,
                                              int startCh, int endCh, long totalChunks, String logPrefix) {
        if (totalChunks > MAX_ANCHOR_SCAN_CHUNKS) {
            log.info("{} 全书 {} chunks 超过锚点扫描上限 {}，回退均分映射",
                    logPrefix, totalChunks, MAX_ANCHOR_SCAN_CHUNKS);
            return null;
        }
        List<ChunkInfo> allChunks = embeddingService.scrollAllContentChunks(book.getId());
        if (allChunks.isEmpty()) {
            log.info("{} 全书 scroll 无结果，回退均分映射", logPrefix);
            return null;
        }
        Map<Integer, Integer> anchors = TocAnchorResolver.resolveAnchors(tocLines, allChunks);
        if (anchors.isEmpty()) {
            log.info("{} 目录标题在正文中未找到锚点，回退均分映射", logPrefix);
            return null;
        }
        int[] range = TocAnchorResolver.resolveRange(anchors, tocLines, startCh, endCh, (int) totalChunks);
        if (range == null) {
            // LLM 可能只数了「真实章节」（把书名行/扉页/版权页/容器行排除后重新编号）
            List<Integer> anchoredLines = new ArrayList<>(anchors.keySet());
            int fromIdx = startCh - 1;
            int toIdx = endCh - 1;
            if (fromIdx >= 0 && toIdx < anchoredLines.size() && fromIdx <= toIdx) {
                range = TocAnchorResolver.resolveRange(anchors, tocLines,
                        anchoredLines.get(fromIdx), anchoredLines.get(toIdx), (int) totalChunks);
            }
        }
        if (range == null) {
            log.info("{} 目录行 {}-{} 无对应锚点，回退均分映射", logPrefix, startCh, endCh);
            return null;
        }
        final int[] resolvedRange = range;
        List<Integer> inside = anchors.values().stream()
                .filter(v -> v >= resolvedRange[0] && v <= resolvedRange[1])
                .sorted()
                .toList();
        log.info("{} 锚点映射: 目录行 {}-{} → chunkIndex=[{}-{}], 锚点数={}, 窗口内锚点={}",
                logPrefix, startCh, endCh, resolvedRange[0], resolvedRange[1], anchors.size(), inside);
        return new ChapterRange(resolvedRange[0], resolvedRange[1], inside);
    }

    /** 目录行加行号，避免 LLM 把「章序号」和「行序号」混着数 */
    private static String buildNumberedToc(List<TocAnchorResolver.TocLine> tocLines) {
        StringBuilder sb = new StringBuilder(1024);
        for (TocAnchorResolver.TocLine line : tocLines) {
            sb.append('[').append(line.number()).append("] ").append(line.trimmed()).append('\n');
        }
        return sb.toString();
    }

    /** 解析章节 KV 字段（START_CHAPTER / END_CHAPTER），支持 null 标记 */
    private Integer parseChapterKv(String text, String key) {
        if (text == null) return null;
        Pattern p = Pattern.compile("(?im)^\\s*" + Pattern.quote(key) + "\\s*[:：]\\s*(.+)$");
        Matcher m = p.matcher(text);
        if (!m.find()) return null;
        String v = m.group(1).trim();
        if (v.isEmpty() || "null".equalsIgnoreCase(v)) return null;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            // 兼容 LLM 偶发输出 JSON 数字（如 "3,"）
            String digits = v.replaceAll("[^0-9].*$", "");
            if (digits.isEmpty()) return null;
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    /**
     * ChunkInfo 列表转 EmbeddingMatch：按精筛顺序截断，再按文档顺序输出。
     */
    private List<EmbeddingMatch<TextSegment>> toMatches(List<ChunkInfo> chunks, long bookId, int ragMaxChars) {
        return ChunkRanking.toMatches(chunks, bookId, ragMaxChars);
    }

    /**
     * 截断 EmbeddingMatch 列表到 ragMaxChars。
     */
    private List<EmbeddingMatch<TextSegment>> truncateMatches(
            List<EmbeddingMatch<TextSegment>> matches, int ragMaxChars) {
        List<EmbeddingMatch<TextSegment>> result = new ArrayList<>();
        int totalLen = 0;
        for (EmbeddingMatch<TextSegment> m : matches) {
            String text = m.embedded() != null ? m.embedded().text() : "";
            if (text.isBlank()) continue;
            if (totalLen + text.length() > ragMaxChars) break;
            result.add(m);
            totalLen += text.length();
        }
        return result;
    }
}
