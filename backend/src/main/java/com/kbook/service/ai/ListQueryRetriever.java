package com.kbook.service.ai;

import com.kbook.common.util.CommonUtils;
import com.kbook.config.ChatModelFactory;
import com.kbook.entity.AiScene;
import com.kbook.entity.Book;
import com.kbook.service.embedding.EmbeddingService;
import com.kbook.service.embedding.EmbeddingService.ChunkInfo;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    /** 簇扩展后总 chunks 上限 */
    private static final int MAX_CLUSTER_EXPAND_CHUNKS = 50;
    /** 档 3 向量召回 topN */
    private static final int CLUSTER_RECALL_TOP_N = 15;
    // 注意：档 3 向量召回复用 EmbeddingService.searchContent，其内部阈值固定为 0.2。
    // 邻域扩展的核心价值是绕过向量相似度——即使向量召回只命中少量 chunks，
    // 邻域扩展会按 chunkIndex 范围拉取周围 chunks，再由 LLM 精筛。

    private final EmbeddingService embeddingService;
    private final ChatModelFactory chatModelFactory;

    public ListQueryRetriever(EmbeddingService embeddingService,
                              ChatModelFactory chatModelFactory) {
        this.embeddingService = embeddingService;
        this.chatModelFactory = chatModelFactory;
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
    public RetrievalResult tocRangeRetrieve(Book book, String listTopic, int ragMaxChars) {
        long bookId = book.getId();
        String logPrefix = "[档2-TocRange] bookId=" + bookId;

        try {
            // 1. LLM 从 toc 找列表章节范围
            int[] range = findChapterRangeByToc(book, listTopic, logPrefix);
            if (range == null) {
                return RetrievalResult.empty(logPrefix + " LLM 未找到章节范围");
            }

            int minIdx = range[0];
            int maxIdx = range[1];
            log.info("{} LLM 推断章节范围: chunkIndex=[{}-{}]", logPrefix, minIdx, maxIdx);

            // 2. 按 chunkIndex 范围 scroll
            List<ChunkInfo> chunks = embeddingService.scrollChunksByIndexRange(bookId, minIdx, maxIdx);
            if (chunks.isEmpty()) {
                return RetrievalResult.empty(logPrefix + " 范围 scroll 无结果");
            }

            log.info("{} 范围 scroll 完成: chunks={}", logPrefix, chunks.size());

            // 3. 范围内 chunks 较多时仍需 LLM 精筛（toc 范围可能包含无关内容）
            List<ChunkInfo> refined = chunks.size() > LLM_REFINE_BATCH_SIZE
                    ? refineByLlm(chunks, listTopic, logPrefix) : chunks;

            List<EmbeddingMatch<TextSegment>> matches = toMatches(refined, bookId, ragMaxChars);
            return new RetrievalResult(matches,
                    logPrefix + " range=[" + minIdx + "-" + maxIdx + "], chunks=" +
                            chunks.size() + "→精筛=" + refined.size() + "→输出=" + matches.size());

        } catch (Exception e) {
            log.warn("{} 检索失败: {}", logPrefix, e.getMessage());
            return RetrievalResult.empty(logPrefix + " 异常: " + e.getMessage());
        }
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
     */
    private List<ChunkInfo> refineByLlm(List<ChunkInfo> candidates, String listTopic, String logPrefix) {
        List<ChunkInfo> refined = new ArrayList<>();
        if (candidates.isEmpty()) return refined;

        // 分批处理
        int batchCount = (candidates.size() + LLM_REFINE_BATCH_SIZE - 1) / LLM_REFINE_BATCH_SIZE;
        for (int batch = 0; batch < batchCount; batch++) {
            int from = batch * LLM_REFINE_BATCH_SIZE;
            int to = Math.min(from + LLM_REFINE_BATCH_SIZE, candidates.size());
            List<ChunkInfo> batchChunks = candidates.subList(from, to);

            try {
                List<Integer> selectedIndices = callLlmRefine(batchChunks, listTopic);
                if (selectedIndices == null) {
                    // LLM 调用失败/模型未配置 → 保留全部（保守，避免漏召回）
                    refined.addAll(batchChunks);
                } else if (!selectedIndices.isEmpty()) {
                    // LLM 选出相关 chunks → 按 list 过滤
                    for (ChunkInfo c : batchChunks) {
                        if (selectedIndices.contains(c.chunkIndex())) {
                            refined.add(c);
                        }
                    }
                }
                // else: selectedIndices 为空列表 → LLM 判断这批无相关内容 → 丢弃
            } catch (Exception e) {
                log.warn("{} LLM 精筛批次 {} 失败: {}", logPrefix, batch + 1, e.getMessage());
                // 失败时保留该批次全部（保守策略，避免漏召回）
                refined.addAll(batchChunks);
            }
        }

        return refined;
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
     * LLM 从 toc 找列表章节范围（档 2 专用）。
     * 返回 [minChunkIndex, maxChunkIndex]，找不到返回 null。
     */
    private int[] findChapterRangeByToc(Book book, String listTopic, String logPrefix) {
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

        List<String> tocLines = book.getToc().lines()
                .map(String::trim)
                .filter(l -> !l.isBlank())
                .toList();
        if (tocLines.isEmpty()) {
            log.warn("{} toc 解析后无有效行", logPrefix);
            return null;
        }

        String prompt = "你正在帮用户定位书中\"" + listTopic + "\"对应的章节范围。\n\n" +
                "【目录】（共 " + tocLines.size() + " 章）\n" +
                String.join("\n", tocLines) + "\n\n" +
                "【全书总 chunks 数】" + totalChunks + "\n\n" +
                "【任务】\n" +
                "1. 从目录中找出最可能包含\"" + listTopic + "\"的章节范围\n" +
                "2. 返回起始章节序号和结束章节序号（1-based）\n" +
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

            // 边界校验：1-based 章节序号，且不能超过 toc 总章数
            if (startCh != null && endCh != null && startCh > 0 && endCh >= startCh) {
                startCh = Math.min(startCh, tocLines.size());
                endCh = Math.min(endCh, tocLines.size());
                // 章节 → chunkIndex 范围映射
                // 假设章节均匀分布：章节 i 对应 chunkIndex 范围 [(i-1)*chunksPerChapter, i*chunksPerChapter)
                double chunksPerChapter = (double) totalChunks / tocLines.size();
                int minIdx = (int) Math.max(0, (startCh - 1) * chunksPerChapter);
                int maxIdx = (int) Math.min(totalChunks - 1, endCh * chunksPerChapter);
                // 扩展 1 个章节的余量，应对映射误差
                int expand = (int) Math.max(2, chunksPerChapter);
                return new int[]{Math.max(0, minIdx - expand / 2), maxIdx + expand / 2};
            }
        } catch (Exception e) {
            log.warn("{} LLM 章节范围推断失败: {}", logPrefix, e.getMessage());
        }
        return null;
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
     * ChunkInfo 列表转 EmbeddingMatch 列表（截断到 ragMaxChars）。
     */
    private List<EmbeddingMatch<TextSegment>> toMatches(List<ChunkInfo> chunks, long bookId, int ragMaxChars) {
        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        int totalLen = 0;
        for (ChunkInfo c : chunks) {
            if (totalLen + c.text().length() > ragMaxChars) break;
            Metadata metadata = new Metadata().put("bookId", bookId).put("chunkIndex", c.chunkIndex());
            TextSegment segment = TextSegment.from(c.text(), metadata);
            // score 用 1.0 表示精筛通过（不影响后续合并逻辑）
            matches.add(new EmbeddingMatch<>(1.0, String.valueOf(c.pointId()), null, segment));
            totalLen += c.text().length();
        }
        return matches;
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
