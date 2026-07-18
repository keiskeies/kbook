package com.kbook.service.book;
import com.kbook.service.ai.ChatModelManager;

import com.kbook.service.embedding.EmbeddingService;

import com.kbook.common.api.PageResult;
import com.kbook.common.util.CommonUtils;
import com.kbook.constants.AiPromptConstants;
import com.kbook.document.BookDocument;
import com.kbook.dto.book.BookProjection;
import com.kbook.entity.AiScene;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.repository.BookSearchRepository;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 图书搜索服务 — Qdrant 向量 + ES 全文检索 + JPA 降级，动态加权融合排序
 * <p>
 * 搜索流程：
 * 1. 查询意图分析 — 根据查询文本特征（长度/语义关键词）确定初始权重
 * 2. 向量召回 — Qdrant kbook_books 语义相似度（理解"适合失恋看的治愈系书籍"）
 * 3. 关键词召回 — ES 全文检索 title/author/description（精确匹配"三体""金庸"）
 * 4. 召回自适应 — 根据两路召回结果质量微调权重（置信度高的一方获得更多权重）
 * 5. 融合排序 — vw×向量分 + kw×关键词分，两路互补
 * 6. 降级 — ES 不可用 → MySQL LIKE；Qdrant 不可用 → 仅关键词
 * <p>
 * 双写策略：图书写入时同步更新 ES 索引
 */
@Slf4j
@Service
public class BookSearchService {

    private final BookSearchRepository searchRepository;
    private final BookRepository bookRepository;
    private final EmbeddingService embeddingService;
    private final ChatModelManager chatModelManager;

    /** ES 是否可用标志 */
    private volatile boolean esAvailable = true;

    /** 各路召回上限 */
    private static final int RECALL_SIZE = 100;
    /** 向量相似度阈值 — 对话场景需要跨概念召回，但 0.5 太宽松会召回不相关书 */
    private static final double MIN_VECTOR_SCORE = 0.55;
    /** 召回不足时的降级阈值 */
    private static final double FALLBACK_VECTOR_SCORE = 0.35;
    /** 召回不足的判定数量 */
    private static final int MIN_RECALL_FOR_FALLBACK = 5;
    /** ES 结果不足此数时，触发向量兜底 */
    private static final int VECTOR_FALLBACK_THRESHOLD = 5;
    /** 质量门槛 — 仅过滤评分数据缺失或极低的书，不做质量筛选（评分主观性强） */
    private static final double MIN_RATING_THRESHOLD = 2.0;
    /** 扩展词参与关键词召回的最大数量（控制 ES 查询次数） */
    private static final int MAX_EXPANSION_FOR_KEYWORD = 5;

    private record SearchWeights(double vectorWeight, double keywordWeight) {
        static SearchWeights of(double vw, double kw) {
            return new SearchWeights(vw, kw);
        }
    }

    public BookSearchService(BookSearchRepository searchRepository,
                             BookRepository bookRepository,
                             @Lazy EmbeddingService embeddingService,
                             ChatModelManager chatModelManager) {
        this.searchRepository = searchRepository;
        this.bookRepository = bookRepository;
        this.embeddingService = embeddingService;
        this.chatModelManager = chatModelManager;
    }

    // ================================================================
    // 向量查询扩展 AI 调用
    // ================================================================

    /**
     * 向量搜索查询扩展，将口语化搜索词转化为多维度检索关键词。
     *
     * <p>核心思路：不改写用户原话，而是推断用户真正的阅读需求，从不同方向生成关键词短语，
     * 提高图书推荐的匹配精度。生成的关键词应是书籍标签、分类或简介中可能出现的短语。</p>
     *
     * @param query 用户口语化搜索词
     * @return 扩展后的关键词列表（3-5 个），失败时返回原始查询
     */
    public List<String> expandVectorSearchQuery(String query) {
        try {
            // 系统提示词（固定，可复用 KV Cache）
            String systemPrompt = AiPromptConstants.EXPAND_VECTOR_SEARCH_SYSTEM_PROMPT;

            // 动态内容（用户查询）作为 UserMessage
            List<ChatMessage> chatMessages = List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(query));

            // 调用 AI 生成扩展关键词
            String result = chatModelManager.callAiForScene(AiScene.VECTOR_QUERY_EXPAND,
                    "向量查询扩展",
                    String.format("q=%s", query.substring(0, Math.min(20, query.length()))),
                    chatMessages);
            if (result != null) {
                // 解析 AI 响应，按行分割并过滤无效内容
                List<String> raw = Arrays.stream(result.split("\n"))
                        .map(String::trim)
                        .filter(line -> !line.isBlank() && line.length() <= 20)
                        .filter(line -> line.length() >= 2)  // 单字无意义
                        .distinct()
                        .toList();

                // 去除与原词高度相似的扩展词（包含关系），避免重复查询
                List<String> expanded = raw.stream()
                        .filter(line -> !isSimilarToQuery(line, query))
                        .limit(18)
                        .collect(Collectors.toList());

                if (!expanded.isEmpty()) {
                    log.debug("向量查询扩展: '{}' → {}", query, expanded);
                    return expanded;
                }
            }
        } catch (Exception e) {
            // 扩展失败时使用原始查询，不影响搜索功能
            log.warn("向量查询扩展失败，使用原始查询: {}", e.getMessage());
        }
        // 默认返回原始查询
        return List.of(query);
    }

    /**
     * 判断扩展词与原词是否高度相似（包含关系），相似则跳过避免重复查询。
     */
    private boolean isSimilarToQuery(String expansion, String query) {
        String e = expansion.trim();
        String q = query.trim();
        if (e.equals(q)) return true;
        // 包含关系：短词是长词的子串，且短词长度≥2
        if (e.length() >= 2 && q.length() >= 2) {
            return q.contains(e) || e.contains(q);
        }
        return false;
    }

    // ==================== 混合搜索（对外主入口） ====================

    /**
     * 关键词优先搜索（用于前端 /api/books/search）
     * 优先返回 ES 书名/作者匹配的书籍，适合用户精确查找
     * <p>
     * 安全措施：keyword 经过 sanitizeSearchKeyword 清理（长度限制、控制字符移除、
     * JSON 特殊字符转义、SQL 注入模式过滤），防止 ES JSON 查询注入和 SQL 注入。
     */
    public PageResult<BookDocument> keywordSearch(String keyword, String tag, int page, int size) {
        // 安全清理：防止 ES JSON 注入 + SQL 注入模式
        String sanitizedKeyword = CommonUtils.sanitizeSearchKeyword(keyword);
        if (sanitizedKeyword == null || sanitizedKeyword.isBlank()) {
            if (tag != null && !tag.isBlank()) {
                return searchByTag(tag, page, size);
            }
            return PageResult.of(List.of(), 0L, page, size);
        }

        // 1. 尝试 ES 搜索
        if (esAvailable) {
            try {
                return esKeywordSearch(sanitizedKeyword, tag, page, size);
            } catch (Exception e) {
                log.warn("ES 关键词搜索异常，降级到 MySQL: {}", e.getMessage());
                esAvailable = false;
            }
        }

        // 2. 降级 MySQL
        return mysqlKeywordSearch(sanitizedKeyword, tag, page, size);
    }

    /**
     * ES 关键词搜索实现
     */
    private PageResult<BookDocument> esKeywordSearch(String keyword, String tag, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<BookDocument> result = searchRepository.searchWithHighlight(keyword, pageable);

        List<BookDocument> docs = result.getContent();

        // 标签过滤
        if (tag != null && !tag.isBlank()) {
            docs = docs.stream()
                    .filter(doc -> doc.getFormatTags() != null && doc.getFormatTags().contains(tag))
                    .toList();
        }

        esAvailable = true;
        return PageResult.of(docs, result.getTotalElements(), page, size);
    }

    /**
     * MySQL 关键词搜索实现
     * 按 rating 降序，避免 readCount 热门偏见
     */
    private PageResult<BookDocument> mysqlKeywordSearch(String keyword, String tag, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "rating"));
        Page<BookProjection> jpaResult = bookRepository.searchProjectedBooks(keyword, pageable);
        List<BookProjection> books = jpaResult.getContent();

        // 转换为 BookDocument
        List<BookDocument> docs = books.stream().map(this::toDocument).toList();

        // 标签过滤
        if (tag != null && !tag.isBlank()) {
            docs = docs.stream()
                    .filter(doc -> doc.getFormatTags() != null && doc.getFormatTags().contains(tag))
                    .toList();
        }

        return PageResult.of(docs, jpaResult.getTotalElements(), page, size);
    }

    /**
     * 混合搜索：Qdrant 向量语义召回 + ES/MySQL 关键词召回，加权融合排序
     * <p>
     * 对话场景专用入口（前端搜索走 keywordSearch）。ES 为主路、向量降级为补充，
     * 查询扩展词（同义+反义方向）喂 ES 多字段匹配，ES 结果不足时向量跨概念兜底。
     *
     * @param keyword       检索词（LLM 提炼，长短不限）
     * @param tag           标签筛选，可为 null
     * @param excludeBookIds 需排除的书籍ID（如已推荐的），可为 null
     */
    public PageResult<BookDocument> hybridSearch(String keyword, String tag,
                                                  List<Long> excludeBookIds, int page, int size) {
        // 安全清理：防止 ES JSON 注入 + SQL 注入模式
        String sanitizedKeyword = CommonUtils.sanitizeSearchKeyword(keyword);
        if (sanitizedKeyword == null || sanitizedKeyword.isBlank()) {
            if (tag != null && !tag.isBlank()) {
                return searchByTag(tag, page, size);
            }
            return PageResult.of(List.of(), 0L, page, size);
        }

        long startTime = System.currentTimeMillis();
        Set<Long> exclude = excludeBookIds != null ? new HashSet<>(excludeBookIds) : Set.of();

        // 查询扩展（同义+反义方向），一次调用两路共用
        List<String> expandedQueries = expandVectorSearchQuery(sanitizedKeyword);

        // ES 关键词召回（主路，扩展词用真实排名+折扣）
        Map<Long, Integer> keywordRanks = keywordRecall(sanitizedKeyword, expandedQueries, exclude);

        // 向量召回降级为补充：ES 结果不足时才触发，跨概念兜底
        Map<Long, Double> vectorScores = Map.of();
        if (keywordRanks.size() < VECTOR_FALLBACK_THRESHOLD) {
            log.debug("ES 召回不足({}本)，触发向量兜底", keywordRanks.size());
            vectorScores = vectorRecall(sanitizedKeyword, expandedQueries, exclude);
        }

        // 权重：ES 主(0.7)，向量辅(0.3)
        SearchWeights weights = SearchWeights.of(0.30, 0.70);

        Set<Long> allIds = new LinkedHashSet<>();
        allIds.addAll(keywordRanks.keySet());
        allIds.addAll(vectorScores.keySet());

        if (allIds.isEmpty()) {
            return PageResult.of(List.of(), 0L, page, size);
        }

        double vw = weights.vectorWeight();
        double kw = weights.keywordWeight();

        List<ScoredBook> scored = new ArrayList<>();
        for (Long bookId : allIds) {
            boolean hasVector = vectorScores.containsKey(bookId);
            boolean hasKeyword = keywordRanks.containsKey(bookId);

            double vs = vectorScores.getOrDefault(bookId, 0.0);
            double ks = rankToScore(keywordRanks.get(bookId));

            double fusionScore;
            if (hasVector && hasKeyword) {
                fusionScore = vw * vs + kw * ks;
            } else if (hasVector) {
                fusionScore = vs * vw;
            } else {
                fusionScore = ks * kw;
            }

            scored.add(new ScoredBook(bookId, fusionScore, vs, ks));
        }

        scored.sort((a, b) -> Double.compare(b.fusionScore, a.fusionScore));

        // 质量门槛：过滤评分过低的书（阈值降低后可能召回烂书）
        List<Long> candidateIds = scored.stream().map(s -> s.bookId).toList();
        Map<Long, BookProjection> bookMap = bookRepository.findProjectedByIdIn(candidateIds).stream()
                .collect(Collectors.toMap(BookProjection::getId, b -> b, (a, b) -> a));

        scored = scored.stream()
                .filter(sb -> {
                    BookProjection book = bookMap.get(sb.bookId);
                    return book != null
                            && (book.getRating() == null || book.getRating() >= MIN_RATING_THRESHOLD);
                })
                .toList();

        // 分页（基于过滤后的列表）
        int start = Math.min((page - 1) * size, scored.size());
        int end = Math.min(page * size, scored.size());
        List<ScoredBook> paged = scored.subList(start, end);

        List<Long> pagedIds = paged.stream().map(s -> s.bookId).toList();
        Map<Long, BookProjection> pagedBookMap = bookMap; // 复用已加载的 map

        List<BookDocument> docs = paged.stream()
                .map(sb -> {
                    BookProjection book = pagedBookMap.get(sb.bookId);
                    if (book == null) return null;
                    return toDocument(book);
                })
                .filter(Objects::nonNull)
                .toList();

        if (tag != null && !tag.isBlank()) {
            docs = docs.stream()
                    .filter(doc -> doc.getFormatTags() != null && doc.getFormatTags().contains(tag))
                    .toList();
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("混合搜索完成: keyword='{}', weights=({},{}), vectorHits={}, keywordHits={}, fused={}, returned={}, excluded={}, elapsed={}ms",
                CommonUtils.truncateText(keyword, 20),
                String.format("%.2f", vw), String.format("%.2f", kw),
                vectorScores.size(), keywordRanks.size(), scored.size(), docs.size(),
                exclude.size(), elapsed);

        return PageResult.of(docs, (long) scored.size(), page, size);
    }

    /** 向后兼容重载（无排除） */
    public PageResult<BookDocument> hybridSearch(String keyword, String tag, int page, int size) {
        return hybridSearch(keyword, tag, null, page, size);
    }

    // ==================== 向量召回：Qdrant kbook_books ====================

    /**
     * 通过 Qdrant kbook_books 做语义向量召回
     * <p>
     * 自适应阈值：先用 MIN_VECTOR_SCORE 召回，不足 MIN_RECALL_FOR_FALLBACK 本时
     * 用 FALLBACK_VECTOR_SCORE 重试，避免跨概念语义相关书被硬阈值卡掉。
     *
     * @param queryText       原始查询（主查询）
     * @param expandedQueries 查询扩展词列表
     * @param exclude         需排除的书籍ID
     */
    private Map<Long, Double> vectorRecall(String queryText, List<String> expandedQueries, Set<Long> exclude) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        if (!embeddingService.isAvailable()) {
            log.debug("向量召回跳过: EmbeddingService 不可用");
            return scores;
        }
        try {
            List<String> queries = new ArrayList<>();
            queries.add(queryText);
            queries.addAll(expandedQueries);

            // size 分配：原始查询拿 50%，扩展词平分剩余 50%
            int primarySize = RECALL_SIZE / 2;
            int expansionSize = queries.size() > 1 ? (RECALL_SIZE / 2) / (queries.size() - 1) : 0;
            List<Long> excludeList = new ArrayList<>(exclude);

            // 第一轮：默认阈值
            for (int i = 0; i < queries.size(); i++) {
                String query = queries.get(i);
                int size = (i == 0) ? primarySize : expansionSize;
                if (size <= 0) continue;
                List<EmbeddingMatch<TextSegment>> matches =
                        embeddingService.searchSimilarBooks(query, size, MIN_VECTOR_SCORE, excludeList);
                for (EmbeddingMatch<TextSegment> match : matches) {
                    if (match.embedded() != null && match.embedded().metadata() != null) {
                        Long bookId = match.embedded().metadata().getLong("bookId");
                        if (bookId != null) {
                            scores.merge(bookId, match.score(), Math::max);
                        }
                    }
                }
            }

            // 召回不足时降阈值重试（跨概念语义推荐需要）
            if (scores.size() < MIN_RECALL_FOR_FALLBACK) {
                log.debug("向量召回不足({}本)，降阈值重试: {}", scores.size(), FALLBACK_VECTOR_SCORE);
                for (int i = 0; i < queries.size(); i++) {
                    String query = queries.get(i);
                    int size = (i == 0) ? primarySize : expansionSize;
                    if (size <= 0) continue;
                    List<EmbeddingMatch<TextSegment>> matches =
                            embeddingService.searchSimilarBooks(query, size, FALLBACK_VECTOR_SCORE, excludeList);
                    for (EmbeddingMatch<TextSegment> match : matches) {
                        if (match.embedded() != null && match.embedded().metadata() != null) {
                            Long bookId = match.embedded().metadata().getLong("bookId");
                            if (bookId != null) {
                                scores.merge(bookId, match.score(), Math::max);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("向量召回异常: {}", e.getMessage());
        }
        return scores;
    }

    // ==================== 关键词召回：ES / MySQL ====================

    /**
     * 关键词召回：ES 优先，降级 MySQL，返回 bookId → rank（1-based，越小越相关）
     * <p>
     * 原始词命中的书按真实排名；扩展词命中的书（原始词未命中）给基础低 rank，
     * 不抢原始词排名但保留在候选池中，实现"扩展词喂关键词路"。
     *
     * @param keyword         原始检索词
     * @param expandedQueries 查询扩展词列表
     * @param exclude         需排除的书籍ID
     */
    private Map<Long, Integer> keywordRecall(String keyword, List<String> expandedQueries, Set<Long> exclude) {
        Map<Long, Integer> ranks = new LinkedHashMap<>();
        if (esAvailable) {
            try {
                // 原始词召回（真实排名）
                ranks.putAll(esKeywordRecall(keyword));

                // 扩展词召回：用真实 ES 排名 + 偏移，让扩展词命中的书有质量区分
                // 偏移量 = 原始词召回数量，确保扩展词命中的书排在原始词后面但不被埋
                // 过滤与原词高度相似的扩展词（包含关系），避免重复查询
                List<String> expansions = expandedQueries.stream()
                        .filter(q -> !q.equals(keyword)
                                && !(q.length() >= 2 && keyword.length() >= 2
                                    && (keyword.contains(q) || q.contains(keyword))))
                        .limit(MAX_EXPANSION_FOR_KEYWORD)
                        .toList();
                int baseOffset = ranks.size();  // 原始词召回数量作为偏移
                for (String eq : expansions) {
                    Map<Long, Integer> expRanks = esKeywordRecall(eq);
                    int expRank = 1;
                    for (Long bookId : expRanks.keySet()) {
                        if (!ranks.containsKey(bookId) && !exclude.contains(bookId)) {
                            // 扩展词真实排名 + 偏移，rankToScore 会指数衰减
                            ranks.put(bookId, baseOffset + expRank);
                        }
                        expRank++;
                    }
                }
                return ranks;
            } catch (Exception e) {
                log.warn("ES 关键词召回异常，降级到 MySQL: {}", e.getMessage());
                esAvailable = false;
            }
        }
        // MySQL 降级：只查原始词，不增强扩展词
        ranks.putAll(mysqlKeywordRecall(keyword));
        return ranks;
    }

    /**
     * ES 关键词召回，返回 bookId → rank
     * 使用 searchForRecall（function_score 加权评分+阅读量），使得高分/热门书籍排名更靠前
     */
    private Map<Long, Integer> esKeywordRecall(String keyword) {
        Map<Long, Integer> ranks = new LinkedHashMap<>();
        Pageable pageable = PageRequest.of(0, RECALL_SIZE);
        Page<BookDocument> result = searchRepository.searchForRecall(keyword, pageable);

        List<BookDocument> docs = result.getContent();
        for (int i = 0; i < docs.size(); i++) {
            ranks.putIfAbsent(docs.get(i).getId(), i + 1);
        }
        esAvailable = true;
        return ranks;
    }

    /**
     * MySQL LIKE 关键词召回（ES 降级方案），返回 bookId → rank
     * 按 rating 降序，避免 readCount 热门偏见
     */
    private Map<Long, Integer> mysqlKeywordRecall(String keyword) {
        Map<Long, Integer> ranks = new LinkedHashMap<>();
        Pageable pageable = PageRequest.of(0, RECALL_SIZE, Sort.by(Sort.Direction.DESC, "rating"));
        Page<Book> jpaResult = bookRepository.searchBooks(keyword, pageable);
        List<Book> books = jpaResult.getContent();
        for (int i = 0; i < books.size(); i++) {
            ranks.putIfAbsent(books.get(i).getId(), i + 1);
        }
        return ranks;
    }

    /**
     * 排名 → 得分：指数衰减，top 排名书籍获得更高权重
     * 前3名给额外加成，体现书名/标签精确匹配的强信号，避免被向量路弱相关书淹没
     */
    private double rankToScore(Integer rank) {
        if (rank == null) return 0.0;
        // 更陡的衰减：rank 1=1.0, 2=0.7, 3=0.49, 5=0.24
        return Math.pow(0.7, rank - 1);
    }

    // ==================== 动态权重 ====================

    /**
     * 根据召回结果质量动态调整权重
     * <p>
     * 自适应调整策略：
     * 1. 某一路召回为空时，大幅提高另一路权重
     * 2. 计算两路召回的置信度（基于最高分和召回数量）
     * 3. 置信度高的一方获得更多权重
     * 4. 两路结果重叠度高时，减少调整幅度（避免过度偏移）
     *
     * @param prior         初始权重
     * @param vectorScores  向量召回结果（bookId → 相似度分数）
     * @param keywordRanks  关键词召回结果（bookId → 排名）
     * @return 调整后的权重
     */
    private SearchWeights adjustWeightsByRecall(SearchWeights prior,
                                                 Map<Long, Double> vectorScores,
                                                 Map<Long, Integer> keywordRanks) {
        double vw = prior.vectorWeight();
        double kw = prior.keywordWeight();

        boolean vectorEmpty = vectorScores.isEmpty();
        boolean keywordEmpty = keywordRanks.isEmpty();

        if (vectorEmpty && keywordEmpty) {
            return prior;
        }
        if (vectorEmpty) {
            return SearchWeights.of(0.2, 0.8);
        }
        if (keywordEmpty) {
            return SearchWeights.of(0.8, 0.2);
        }

        double topVectorScore = vectorScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double vectorConfidence = topVectorScore * 0.6 + Math.min(vectorScores.size() / 20.0, 1.0) * 0.4;

        int topKeywordRank = keywordRanks.values().stream().mapToInt(Integer::intValue).min().orElse(RECALL_SIZE);
        double keywordConfidence = rankToScore(topKeywordRank) * 0.6 + Math.min(keywordRanks.size() / 20.0, 1.0) * 0.4;

        Set<Long> vectorIds = vectorScores.keySet();
        Set<Long> keywordIds = keywordRanks.keySet();
        long overlap = vectorIds.stream().filter(keywordIds::contains).count();
        double overlapRatio = (double) overlap / Math.min(vectorIds.size(), keywordIds.size());

        double confidenceDiff = vectorConfidence - keywordConfidence;
        double adjustment = Math.max(-0.15, Math.min(0.15, confidenceDiff * 0.2));

        if (overlapRatio > 0.3) {
            adjustment *= 0.5;
        }

        double newVw = Math.max(0.2, Math.min(0.9, vw + adjustment));
        double newKw = 1.0 - newVw;

        return SearchWeights.of(newVw, newKw);
    }

    /**
     * 按标签筛选（无关键词时使用）
     * 按 rating 降序，避免 readCount 热门偏见
     */
    private PageResult<BookDocument> searchByTag(String tag, int page, int size) {
        // MySQL 标签筛选（formatTags 存储的是 JSON 字符串，用 LIKE 匹配）
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "rating"));
        Page<BookProjection> jpaResult = bookRepository.findProjectedByTag(tag, pageable);
        List<BookDocument> docs = jpaResult.getContent().stream()
                .map(this::toDocument)
                .collect(Collectors.toList());
        return PageResult.of(docs, jpaResult.getTotalElements(), page, size);
    }

    /**
     * 搜索建议（前缀匹配）
     */
    public List<String> suggest(String keyword) {
        String sanitized = CommonUtils.sanitizeSearchKeyword(keyword);
        if (!esAvailable || sanitized == null || sanitized.isBlank()) {
            return List.of();
        }
        try {
            Pageable limit = PageRequest.of(0, 8);
            List<BookDocument> docs = searchRepository.suggestByTitle(sanitized, limit);
            return docs.stream().map(BookDocument::getTitle).distinct().collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("ES 搜索建议异常: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 同步单本图书到 ES
     */
    public void indexBook(Book book) {
        if (!esAvailable) return;
        try {
            searchRepository.save(toDocument(book));
        } catch (Exception e) {
            log.warn("ES 索引失败 bookId={}: {}", book.getId(), e.getMessage());
            esAvailable = false;
        }
    }

    /**
     * 从 ES 删除图书
     */
    public void deleteIndex(Long bookId) {
        if (!esAvailable) return;
        try {
            searchRepository.deleteById(bookId);
        } catch (Exception e) {
            log.warn("ES 删除索引失败 bookId={}: {}", bookId, e.getMessage());
        }
    }

    /**
     * 全量重建索引
     */
    @Transactional(readOnly = true)
    public long rebuildIndex() {
        int pageSize = 500;
        int page = 0;
        long total = 0;
        esAvailable = true;

        while (true) {
            Pageable pageable = PageRequest.of(page++, pageSize);
            List<BookProjection> books = bookRepository.findAllProjectedByOrderByIdAsc(pageable).getContent();
            if (books.isEmpty()) break;

            List<BookDocument> docs = books.stream()
                    .map(this::toDocument)
                    .collect(Collectors.toList());
            searchRepository.saveAll(docs);
            total += docs.size();
        }

        log.info("ES 索引重建完成，共 {} 条", total);
        return total;
    }

    /**
     * 全量重建索引 — SSE 流式推送进度
     */
    @Transactional(readOnly = true)
    public long rebuildIndexWithProgress(java.util.function.BiConsumer<Integer, Integer> onProgress) {
        long totalBooks = bookRepository.count();
        int pageSize = 500;
        int page = 0;
        int processed = 0;

        esAvailable = true;
        log.info("ES 索引重建开始，共 {} 条", totalBooks);

        while (true) {
            Pageable pageable = PageRequest.of(page++, pageSize);
            List<BookProjection> books = bookRepository.findAllProjectedByOrderByIdAsc(pageable).getContent();
            if (books.isEmpty()) break;

            List<BookDocument> docs = books.stream()
                    .map(this::toDocument)
                    .collect(Collectors.toList());
            searchRepository.saveAll(docs);
            processed += books.size();
            if (onProgress != null) {
                onProgress.accept(processed, (int) totalBooks);
            }
        }

        log.info("ES 索引重建完成，共 {} 条", processed);
        return processed;
    }

    // ==================== 内部类 ====================

    /**
     * 融合打分中间结果
     */
    private record ScoredBook(Long bookId, double fusionScore, double vectorScore, double keywordScore) {
    }

    /**
     * Entity → Document
     */
    private BookDocument toDocument(Book book) {
        return BookDocument.builder()
                .id(book.getId())
                .title(book.getTitle())
                .author(book.getAuthor())
                .description(book.getDescription())
                .format(book.getFormat())
                .formatTags(book.getFormatTags())
                .coverUrl(book.getCoverUrl())
                .fileSize(book.getFileSize())
                .readCount(book.getReadCount())
                .rating(book.getRating())
                .totalUnits(book.getTotalUnits())
                .fileUrl(book.getFileUrl())
                .conceptTags(book.getConceptTags())
                .readerNeedTags(book.getReaderNeedTags())
                .targetReaderTags(book.getTargetReaderTags())
                .createdAt(book.getCreatedAt() != null ? book.getCreatedAt().toEpochSecond(java.time.ZoneOffset.of("+8")) : 0L)
                .build();
    }

    private BookDocument toDocument(BookProjection book) {
        return BookDocument.builder()
                .id(book.getId())
                .title(book.getTitle())
                .author(book.getAuthor())
                .description(book.getDescription())
                .format(book.getFormat())
                .formatTags(book.getFormatTags())
                .coverUrl(book.getCoverUrl())
                .fileSize(book.getFileSize())
                .readCount(book.getReadCount())
                .rating(book.getRating())
                .totalUnits(book.getTotalUnits())
                .fileUrl(book.getFileUrl())
                .conceptTags(book.getConceptTags())
                .readerNeedTags(book.getReaderNeedTags())
                .targetReaderTags(book.getTargetReaderTags())
                .createdAt(book.getCreatedAt() != null ? book.getCreatedAt().toEpochSecond(java.time.ZoneOffset.of("+8")) : 0L)
                .build();
    }
}
