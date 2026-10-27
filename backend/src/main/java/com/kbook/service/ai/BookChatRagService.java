package com.kbook.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.service.ai.streaming.StreamingSseHandler;
import com.kbook.service.ai.behavior.BehaviorProfileBuilder;
import com.kbook.service.ai.behavior.UserBehaviorSignalEvent;
import com.kbook.service.user.UserService;
import com.kbook.service.book.BookParserService;
import com.kbook.service.book.BookService;
import com.kbook.service.embedding.RagHitStatisticsService;
import com.kbook.service.embedding.EmbeddingService;
import com.kbook.service.embedding.ContentQualityGate;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.config.ChatModelFactory;
import com.kbook.service.ai.core.ChatHistoryCompressor;
import com.kbook.service.ai.core.UserProfileBuilder;
import com.kbook.service.ai.behavior.BehaviorProfileBuilder;
import com.kbook.service.ai.behavior.BehaviorProfileService;
import com.kbook.service.ai.behavior.UserBehaviorSignalEvent;
import com.kbook.entity.UserBehaviorProfile;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.config.properties.QdrantProperties;
import com.kbook.config.ai.AiConfigProvider;
import com.kbook.dto.book.BookProjection;
import com.kbook.entity.AiConversation;
import com.kbook.entity.AiScene;
import com.kbook.entity.AiProviderConfig;
import com.kbook.entity.Book;
import com.kbook.entity.AiSession;
import com.kbook.entity.BookSuggestedQuestion;
import com.kbook.entity.User;
import com.kbook.entity.UserBehaviorProfile;
import com.kbook.repository.AiConversationRepository;
import com.kbook.repository.AiSessionRepository;
import com.kbook.repository.BookSuggestedQuestionRepository;
import com.kbook.repository.UserBehaviorProfileRepository;
import static com.kbook.common.util.QueryBuilder.*;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import com.kbook.service.ai.rag.ListQueryDetector;
import com.kbook.service.ai.rag.ListQueryRetriever;
import com.kbook.service.ai.rag.ListQueryStrategySelector;
import com.kbook.service.ai.rag.ItemListRetriever;
import com.kbook.service.ai.rag.RagPipelineComponents;
import com.kbook.service.ai.rag.RagRerankService;
import com.kbook.service.ai.core.ChatModelManager;
import com.kbook.service.ai.core.AiAssistantProvider;
import com.kbook.service.ai.core.AiSceneConfigService;
import com.kbook.service.ai.core.AiUsageContext;
import com.kbook.constants.ai.BookChatPrompts;
import com.kbook.constants.ai.RagPrompts;

/**
 * 图书问答 RAG 检索编排 — 常规管线/列表型三档策略/查询扩展/上下文配额。
 */
@Slf4j
@Service
@LogModule("图书问答")
public class BookChatRagService {

    private final EmbeddingService embeddingService;
    private final BookParserService bookParserService;
    private final ChatModelManager chatModelManager;
    private final ListQueryStrategySelector listQueryStrategySelector;
    private final ListQueryRetriever listQueryRetriever;
    private final RagHitStatisticsService ragHitStatisticsService;
    private final AiSceneConfigService aiSceneConfigService;
    private final RagRerankService ragRerankService;
    private final QdrantProperties qdrantProperties;
    private final Executor qaPrefetchExecutor;

    public BookChatRagService(
            EmbeddingService embeddingService,
            BookParserService bookParserService,
            ChatModelManager chatModelManager,
            ListQueryStrategySelector listQueryStrategySelector,
            ListQueryRetriever listQueryRetriever,
            RagHitStatisticsService ragHitStatisticsService,
            AiSceneConfigService aiSceneConfigService,
            RagRerankService ragRerankService,
            QdrantProperties qdrantProperties,
            @Qualifier("qaPrefetchExecutor") Executor qaPrefetchExecutor) {
        this.embeddingService = embeddingService;
        this.bookParserService = bookParserService;
        this.chatModelManager = chatModelManager;
        this.listQueryStrategySelector = listQueryStrategySelector;
        this.listQueryRetriever = listQueryRetriever;
        this.ragHitStatisticsService = ragHitStatisticsService;
        this.aiSceneConfigService = aiSceneConfigService;
        this.ragRerankService = ragRerankService;
        this.qdrantProperties = qdrantProperties;
        this.qaPrefetchExecutor = qaPrefetchExecutor;
    }

    /** 查询扩展子查询上限：过多子查询会放大噪声与检索耗时。 */
    private static final int MAX_SUB_QUERIES = 6;
    /** 单个子查询检索超时：失败只损失一路召回，不拖垮整轮。 */
    private static final long PER_QUERY_TIMEOUT_MS = 10_000L;


    /** 默认上下文长度（32K tokens，场景配置缺失时兜底） */
    private static final int DEFAULT_MAX_TOKENS = 32768;
    /** token → 中文字符换算比例 */
    private static final double TOKEN_TO_CHAR_RATIO = 1.5;

    /** 邻域扩展候选数量：前 1 后 2 */
    private static final int NEIGHBOR_PREV = 1;
    private static final int NEIGHBOR_NEXT = 2;

    /**
     * 阶段0诊断：提取一组 chunk 的索引分布概况（数量、最小/最大索引、跨度、是否覆盖全书中段）。
     * <p>
     * 用途：判断列表型问题"展开丢尾项"是否因检索材料只聚集在书中某一区域——
     * 若 span 很窄（例如全部落在前 1/5 区间），第 4、5 项根本没有原文可依据，
     * 这与"数据量够不够"无关，而是覆盖面的问题。
     *
     * @return 形如 {@code idx[n=6,min=12,max=38,span=26]} 的诊断串；无有效索引时返回 {@code idx[none]}
     */
    private static String describeChunkIndexSpan(List<EmbeddingMatch<TextSegment>> chunks) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int valid = 0;
        for (EmbeddingMatch<TextSegment> m : chunks) {
            int[] span = RagPipelineComponents.getChunkSpan(m);
            if (span[0] < 0) continue;
            valid++;
            min = Math.min(min, span[0]);
            max = Math.max(max, span[1]);
        }
        if (valid == 0) return "idx[none]";
        return "idx[n=" + valid + ",min=" + min + ",max=" + max + ",span=" + (max - min + 1) + "]";
    }

    /**
     * RAG 上下文最大字符数：maxTokens × 1.5 × 0.6（留 40% 给系统和对话）。
     * <p>
     * 这是「模型能吃下多少」的预算，不是「该注入多少材料」——后者由
     * {@code qdrant.rag-max-chars} 在常规管线里单独封顶。
     */
    int getRagMaxChars() {
        // 从 BOOK_QA 场景绑定配置读 maxTokens（跟随场景路由）
        Integer maxTokens = null;
        try {
            AiProviderConfig sceneConfig = aiSceneConfigService.resolveConfig(AiScene.BOOK_QA);
            if (sceneConfig != null) {
                maxTokens = sceneConfig.getMaxTokens();
            }
        } catch (Exception e) {
            log.warn("解析 BOOK_QA 场景配置失败，回退到默认 maxTokens: {}", e.getMessage());
        }
        int tokens = maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS;
        return (int) (tokens * TOKEN_TO_CHAR_RATIO * 0.6);
    }

    /**
     * 常规管线的上下文注入上限：模型窗口推导预算与 {@code qdrant.rag-max-chars} 取小者。
     * <p>
     * 列表型路径不适用这个上限——它的材料是逐条定向检索出来的，本来就不是「整本塞进去」，
     * 用全局上限反而会把长列表的条目截掉。
     */
    int getRegularRagMaxChars(int modelDerived) {
        return resolveRagMaxChars(modelDerived, qdrantProperties.getRagMaxChars());
    }

    /**
     * 取「模型窗口推导预算」与「配置上限」的较小值；配置上限 ≤ 0 表示不额外限制。
     */
    static int resolveRagMaxChars(int modelDerived, int configuredCap) {
        if (configuredCap <= 0) return modelDerived;
        return Math.min(modelDerived, configuredCap);
    }

    /**
     * RAG 检索结果：上下文字符串 + 列表型主题。
     * <p>
     * listTopic 非空 = 本轮问题被判定为列表展开型（无论列表专项检索成功还是退化为常规 RAG）。
     * 主链路据此在问题消息末尾附加"覆盖度契约"，要求逐条展开、禁止省略后几项
     * （阶段1 止血项：主干路径下"5 点只展开 3 点"的直接对策之一）。
     */
    record RagRetrievalResult(String ragContext, String listTopic,
                                      List<EmbeddingMatch<TextSegment>> matches,
                                      List<EmbeddingMatch<TextSegment>> includedMatches) {
        /** 未区分"候选 matches"与"实际入选"时的兼容构造：默认两者相同。 */
        RagRetrievalResult(String ragContext, String listTopic,
                           List<EmbeddingMatch<TextSegment>> matches) {
            this(ragContext, listTopic, matches, matches);
        }

        static final RagRetrievalResult EMPTY = new RagRetrievalResult(null, null, null, null);
    }

    /**
     * 列表型问题 RAG 优化入口。
     * <p>
     * 流程：
     * 1. LLM 检测问题是否为列表型（含列表主题提取）
     * 2. 若是列表型 → 选档策略 → 执行对应检索流程
     * 3. 拼接 RAG 上下文返回
     * <p>
     * ragContext 为 null 表示：
     * - 不是列表型问题 → 走常规 RAG
     * - 是列表型但所有档位都失败 → 走常规 RAG（兜底）
     * 但 listTopic 在"是列表型"时始终保留，供回退路径的覆盖度契约使用。
     * <p>
     * matches：策略检索命中的向量匹配片段（含 chunkIndex 元数据），
     * 供流水线 §3.1③ 把条目 chunkAnchors 对齐到这些片段；检索失败/兜底时为 null。
     *
     * @return 检索结果（context + listTopic + matches）；非列表型或失败时 ragContext 为 null
     */
    private RagRetrievalResult tryListQueryRagRetrieval(Book book, String question, String lastAiAnswer,
                                            int ragMaxChars, SseEmitter emitter,
                                            ListQueryDetector.DetectionResult detection) {
        // 1. 检测列表型问题（检测已提前到检索入口，本处复用结果，不再重复调 LLM）
        if (!detection.isListQuery()) {
            return RagRetrievalResult.EMPTY; // 非列表型，走常规 RAG
        }

        String listTopic = detection.listTopic();
        log.info("[ListQuery] 检测到列表型问题: bookId={}, topic={}, question={}",
                book.getId(), listTopic, question);

        try {
            emitter.send(SseEmitter.event().name("thinking_content")
                    .data("这是个列表型问题，我用专门策略找找\"" + listTopic + "\"的具体内容…\n"));
        } catch (Exception ignored) {
        }

        // 2. 选档策略
        ListQueryStrategySelector.Strategy strategy = listQueryStrategySelector.selectStrategy(book);

        // 3. 执行对应检索
        ListQueryRetriever.RetrievalResult result;
        try {
            result = switch (strategy) {
                case FULL_SCAN -> listQueryRetriever.fullScanAndRefine(book, listTopic, ragMaxChars);
                case TOC_RANGE -> listQueryRetriever.tocRangeRetrieve(book, listTopic, question, ragMaxChars);
                case CLUSTER_EXPAND -> listQueryRetriever.clusterExpandRetrieve(book, listTopic, question, ragMaxChars);
            };
        } catch (Exception e) {
            log.warn("[ListQuery] 策略 {} 执行异常，退化为常规 RAG: {}", strategy, e.getMessage());
            return new RagRetrievalResult(null, listTopic, null);
        }

        // 3.5 档 2 的前提是「主题落在目录的某一行」。目录不完整、或 LLM 挑错行时，
        // 窗口会落到别的章节（实测「如何获得幸福和成功」不在目录里 → 窗口落到「第三讲」）。
        // 这时改用档 3（向量召回 + 簇扩展），而不是拿错误章节硬答。
        if (strategy == ListQueryStrategySelector.Strategy.TOC_RANGE && result.matches().isEmpty()) {
            log.warn("[ListQuery] 档2 未产出可用材料，改用档3: bookId={}, topic={}, log={}",
                    book.getId(), listTopic, result.strategyLog());
            result = listQueryRetriever.clusterExpandRetrieve(book, listTopic, question, ragMaxChars);
        }

        // 4. 检查结果
        if (result.matches().isEmpty()) {
            log.warn("[ListQuery] 策略 {} 无结果，退化为常规 RAG: {}", strategy, result.strategyLog());
            return new RagRetrievalResult(null, listTopic, null);
        }

        // 5. 拼接 RAG 上下文
        StringBuilder sb = new StringBuilder();
        int totalLen = 0;
        int charsIn = 0;
        List<EmbeddingMatch<TextSegment>> included = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            String chunkText = match.embedded() != null ? match.embedded().text() : "";
            if (chunkText.isBlank()) continue;
            // 与常规路径同一条闸门：乱码块不进上下文
            if (ContentQualityGate.isGarbled(chunkText)) {
                continue;
            }
            charsIn += chunkText.length();
            String label = RagPipelineComponents.fragmentLabel(match, included.size() + 1) + "\n";
            if (totalLen + label.length() + chunkText.length() > ragMaxChars) break;
            sb.append(label).append(chunkText).append("\n\n");
            totalLen += label.length() + chunkText.length();
            included.add(match);
        }

        String ragContext = sb.toString();
        if (ragContext.isBlank()) {
            log.warn("[ListQuery] 策略 {} 拼接后上下文为空", strategy);
            return new RagRetrievalResult(null, listTopic, null);
        }

        log.info("[ListQuery] 检索成功: bookId={}, strategy={}, matches={}, chars={}, log={}",
                book.getId(), strategy, result.matches().size(), ragContext.length(), result.strategyLog());
        // 阶段0诊断：chunkIndex 跨度 + 填充率。charsAvail≫maxChars 且 fillRate 低 = 截断在咬（H5）；
        // span 窄 = 检索材料集中在某一区域、后几项无原文可依（H4）
        log.info("[RAG诊断/列表型] bookId={} | strategy={} matches={} charsAvail={} filled={} maxChars={} fillRate={} {}",
                book.getId(), strategy, result.matches().size(), charsIn, totalLen, ragMaxChars,
                ragMaxChars > 0 ? String.format("%.1f%%", ragContext.length() * 100.0 / ragMaxChars) : "n/a",
                describeChunkIndexSpan(result.matches()));

        try {
            emitter.send(SseEmitter.event().name("thinking_content")
                    .data("找到 " + result.matches().size() + " 条相关内容（列表型优化），我整理一下思路…\n"));
        } catch (Exception ignored) {
        }

        return new RagRetrievalResult(ragContext, listTopic, result.matches(), included);
    }

    /**
     * RAG 检索核心逻辑：查询扩展 → 多查询检索 → 去重 → 合并相邻片段 → 组装上下文。
     * <p>
     * 列表型问题优化：先检测是否为列表型问题，若是则走三档优化策略
     * （全量 scroll / toc 范围 / 簇扩展 + LLM 精筛），失败时退化为常规流程。
     *
     * @return 检索结果（context + listTopic）；不可用时 ragContext 为 null
     */
    RagRetrievalResult doRagRetrieval(Book book, String question, String lastAiAnswer, int ragTopK, int ragMaxChars,
                                   SseEmitter emitter, ListQueryDetector.DetectionResult detection) {
        try {
            emitter.send(SseEmitter.event().name("thinking_content")
                    .data("我好好分析一下这个问题…\n"));
        } catch (Exception ignored) {
        }

        // ===== 列表型问题 RAG 优化分支 =====
        RagRetrievalResult listResult = tryListQueryRagRetrieval(book, question, lastAiAnswer, ragMaxChars, emitter, detection);
        if (listResult.ragContext() != null) {
            return listResult;
        }
        // 列表型分支未触发或失败 → 走常规 RAG 流程（兜底；listTopic 若存在则保留给覆盖度契约）

        List<String> subQueries = expandQuery(question, lastAiAnswer, book);
        try {
            emitter.send(SseEmitter.event().name("thinking_content")
                    .data("找找 [" + String.join("，", subQueries) + "] 相关内容…\n"));
        } catch (Exception ignored) {
        }

        // ===== 新 RAG 检索管线 =====
        // 1. 向量查询：每个子查询取 ragTopK × 2（宽松取，后续由全局 topN 截断）
        // 2. 按 chunkIndex 去重（替代前 80 字符 key）
        // 3. 全局 score 排序 + topN 截断
        // 4. 软阈值过滤（砍明显噪声）
        // 5. 自适应邻域扩展（前 1 后 2 候选，按 score 决定保留）
        // 6. 合并相邻 + 按 bestScore 重排
        // 7. ragMaxChars 截断填充
        // 每个子查询统一取 ragTopK × 2，不再用固定公式
        int perQueryLimit = Math.max(ragTopK, ragTopK * 2);

        // 1. 并行向量查询：第一条保留书籍领域上下文，其余子查询已语义化，不再重复拼简介
        SubQueryHits hits = searchSubQueries(book, subQueries, perQueryLimit);
        int rawCount = hits.rawCount();
        int rawChars = hits.rawChars();
        Map<Integer, EmbeddingMatch<TextSegment>> dedupedByIndex = dedupeByIndex(hits.matches());

        // 2. 无结果时触发向量重建（保留原兜底逻辑）
        if (dedupedByIndex.isEmpty()) {
            log.debug("RAG 检索无结果: bookId={}, question={}",
                    book.getId(), question.substring(0, Math.min(30, question.length())));
            Boolean reEmbedResult = bookParserService.forceReEmbedIfMissing(book.getId());
            if (reEmbedResult != null && reEmbedResult) {
                log.info("内容向量重建成功，重新执行 RAG 检索: bookId={}", book.getId());
                hits = searchSubQueries(book, subQueries, perQueryLimit);
                rawCount = hits.rawCount();
                rawChars = hits.rawChars();
                dedupedByIndex = dedupeByIndex(hits.matches());
            }
        }

        // 3. 全局按 score 降序排序 + topN 截断
        List<EmbeddingMatch<TextSegment>> sortedByScore = new ArrayList<>(dedupedByIndex.values());
        sortedByScore.sort((a, b) -> Double.compare(b.score(), a.score()));
        int dedupCount = sortedByScore.size();
        int dedupChars = sortedByScore.stream()
                .mapToInt(m -> m.embedded() != null ? m.embedded().text().length() : 0).sum();

        List<EmbeddingMatch<TextSegment>> topN = sortedByScore.stream()
                .limit(ragTopK)
                .collect(Collectors.toCollection(ArrayList::new));

        // 4. 软阈值过滤：只砍明显噪声（score < max_score × 0.3）
        if (!topN.isEmpty()) {
            double maxScore = topN.get(0).score();
            double noiseThreshold = maxScore * 0.3;
            int beforeFilter = topN.size();
            topN = topN.stream()
                    .filter(m -> m.score() >= noiseThreshold)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (topN.size() < beforeFilter) {
                log.debug("软阈值过滤: maxScore={}, threshold={}, 砍掉 {} 条噪声",
                        String.format("%.4f", maxScore), String.format("%.4f", noiseThreshold),
                        beforeFilter - topN.size());
            }
        }

        // 5. 自适应邻域扩展
        List<EmbeddingMatch<TextSegment>> expanded = RagPipelineComponents.adaptiveNeighborExpand(
                topN, book.getId(), NEIGHBOR_PREV, NEIGHBOR_NEXT,
                (bookId, idx) -> embeddingService.searchContentByChunkIndex(bookId, idx));

        // 6. 合并相邻 + 按 bestScore 重排
        List<EmbeddingMatch<TextSegment>> merged = RagPipelineComponents.mergeAdjacentChunks(expanded);
        // 关键改造：合并后按 bestScore 降序（高相关片段优先填满 ragMaxChars）
        merged.sort((a, b) -> Double.compare(b.score(), a.score()));
        // 可选 LLM rerank（默认关闭）：只改顺序，不改向量分；rerankScore 写入 metadata 供快照记录
        merged = ragRerankService.rerank(question, merged);
        // 乱码块过滤：乱码 chunk 进了上下文就会被模型当作"书中原文"引用，比丢材料更糟。
        // 用与入库闸门同一套判定，保证"进了索引的脏块"在检索侧还有第二道拦截。
        int beforeGarbledFilter = merged.size();
        merged = merged.stream()
                .filter(m -> {
                    String t = m.embedded() != null ? m.embedded().text() : "";
                    return !ContentQualityGate.isGarbled(t);
                })
                .collect(Collectors.toCollection(ArrayList::new));
        if (merged.size() < beforeGarbledFilter) {
            log.warn("[编码诊断] 过滤乱码 chunk: bookId={}, 丢弃={}/{}",
                    book.getId(), beforeGarbledFilter - merged.size(), beforeGarbledFilter);
            ragHitStatisticsService.recordMiss(book.getId());
        }
        int mergeChars = merged.stream()
                .mapToInt(m -> m.embedded() != null ? m.embedded().text().length() : 0).sum();

        // 7. ragMaxChars 截断填充（同时拿到真正进入上下文的 matches，供检索快照使用）
        // 常规管线额外受 qdrant.rag-max-chars 封顶：模型窗口大不等于该把整本书塞进提示词
        int regularBudget = getRegularRagMaxChars(ragMaxChars);
        RagPipelineComponents.Selection selection =
                RagPipelineComponents.selectWithinBudget(merged, regularBudget, true);
        String ragContext = selection.text();
        log.info("RAG检索 bookId={} | 原始{}条{}字 → 去重{}条{}字 → topN={} → 邻域扩展={} → 合并{}条{}字 → 最终{}字",
                book.getId(), rawCount, rawChars,
                dedupCount, dedupChars,
                Math.min(ragTopK, dedupCount), expanded.size(),
                merged.size(), mergeChars, ragContext.length());
        // 阶段0诊断：填充率 + chunkIndex 跨度 + topScore。span 远小于全书 chunk 数 = 材料集中在某一区域（H4 证据）；
        // fillRate 低 = merged 总量本就不足或首条即超配额（H5 咬人信号）；topScore 用于绝对阈值标定。
        double topScore = merged.stream().mapToDouble(EmbeddingMatch::score).max().orElse(0.0);
        log.info("[RAG诊断/常规] bookId={} | chunksIn={} charsIn={} maxChars={} fillRate={} topScore={} {} | question={}",
                book.getId(), merged.size(), mergeChars, regularBudget,
                regularBudget > 0 ? String.format("%.1f%%", ragContext.length() * 100.0 / regularBudget) : "n/a",
                String.format("%.4f", topScore),
                describeChunkIndexSpan(merged),
                question.length() > 40 ? question.substring(0, 40) : question);

        long questionMarkCount = ragContext.chars().filter(c -> c == '?').count();
        if (questionMarkCount > ragContext.length() * 0.1) {
            log.warn("[编码诊断] 截断后上下文仍疑似乱码（单块未超阈值但拼接占比高）: bookId={}, 问号占比={}/{}, 建议重新解析该书",
                    book.getId(), questionMarkCount, ragContext.length());
            ragHitStatisticsService.recordMiss(book.getId());
        }

        String thinkingText = (!merged.isEmpty())
                ? "找到 " + merged.size() + " 条相关内容, 我整理一下思路…\n"
                : "没找到直接相关的内容，我确认一下…\n";
        try {
            emitter.send(SseEmitter.event().name("thinking_content").data(thinkingText));
        } catch (Exception ignored) {
        }
        return new RagRetrievalResult(ragContext, listResult.listTopic(), merged, selection.matches());
    }

    private record SubQueryHits(List<EmbeddingMatch<TextSegment>> matches, int rawCount, int rawChars) {
    }

    /**
     * 并行执行子查询检索；单条超时/失败只损失该路召回。
     * 第一条保留书籍领域上下文，其余子查询不再重复拼简介。
     */
    private SubQueryHits searchSubQueries(Book book, List<String> subQueries, int perQueryLimit) {
        List<CompletableFuture<List<EmbeddingMatch<TextSegment>>>> futures =
                new ArrayList<>(subQueries.size());
        for (int i = 0; i < subQueries.size(); i++) {
            final String subQuery = subQueries.get(i);
            final boolean addDomainContext = i == 0;
            try {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return embeddingService.searchContent(subQuery, perQueryLimit, book, addDomainContext);
                    } catch (Exception e) {
                        log.debug("子查询检索失败: subQuery={} - {}", subQuery, e.getMessage());
                        return List.<EmbeddingMatch<TextSegment>>of();
                    }
                }, qaPrefetchExecutor)
                        .orTimeout(PER_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .exceptionally(e -> {
                            log.debug("子查询检索超时: subQuery={}", subQuery);
                            return List.<EmbeddingMatch<TextSegment>>of();
                        }));
            } catch (Exception e) {
                log.debug("子查询任务提交失败: subQuery={} - {}", subQuery, e.getMessage());
                futures.add(CompletableFuture.completedFuture(List.of()));
            }
        }

        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        int rawCount = 0;
        int rawChars = 0;
        for (CompletableFuture<List<EmbeddingMatch<TextSegment>>> future : futures) {
            List<EmbeddingMatch<TextSegment>> hits;
            try {
                hits = future.join();
            } catch (Exception e) {
                continue;
            }
            for (EmbeddingMatch<TextSegment> match : hits) {
                String text = match.embedded() != null ? match.embedded().text() : "";
                if (text.isBlank()) continue;
                rawCount++;
                rawChars += text.length();
                matches.add(match);
            }
        }
        return new SubQueryHits(matches, rawCount, rawChars);
    }

    private static Map<Integer, EmbeddingMatch<TextSegment>> dedupeByIndex(
            List<EmbeddingMatch<TextSegment>> matches) {
        Map<Integer, EmbeddingMatch<TextSegment>> deduped = new LinkedHashMap<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            int idx = RagPipelineComponents.getChunkIndex(match);
            deduped.merge(idx, match,
                    (existing, incoming) -> incoming.score() > existing.score() ? incoming : existing);
        }
        return deduped;
    }

    /**
     * RAG 查询扩展，根据问题类型生成多组检索查询以提高召回率。
     *
     * @param question     用户原始问题
     * @param lastAiAnswer 上一轮 AI 回答摘要（可为 null，用于追问场景）
     * @param book         书籍
     * @return 扩展后的查询列表（最多 9 个），包含原始查询
     */
    public List<String> expandQuery(String question, String lastAiAnswer, Book book) {
        // 初始化查询列表
        List<String> queries = new ArrayList<>();

        // 原始查询：如果超过15字，提取关键词作为首个查询
        String primaryQuery = question.length() > 15 ? extractKeywords(question) : question;
        queries.add(primaryQuery);

        try {
            // 构建静态书籍信息（书名、作者、目录）— 同书复用 KV Cache
            String bookContext = BookContentUtils.buildSpeedReadContent(book);

            // 固定指令作为 SystemMessage（与动态内容分离，复用 KV Cache 前缀）
            String systemPrompt = RagPrompts.EXPAND_QUERY_SYSTEM_PROMPT;

            // 消息顺序：SystemMessage(固定指令) → UserMessage(图书信息) → UserMessage(上轮回答,可空) → UserMessage(用户问题)
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(systemPrompt));
            messages.add(UserMessage.from("【书籍信息】\n" + bookContext.trim()));
            if (lastAiAnswer != null && !lastAiAnswer.isBlank()) {
                messages.add(UserMessage.from("【上轮AI回答】\n" + lastAiAnswer.trim()));
            }
            messages.add(UserMessage.from("【用户问题】\n" + question));

            String aiText = chatModelManager.callAiForScene(AiScene.QUERY_EXPAND,
                    "RAG查询扩展",
                    String.format("原始: %s", question),
                    messages);
            if (aiText != null) {
                for (String line : aiText.split("\n")) {
                    line = line.trim();
                    // 过滤：非空、不过长、不重复
                    if (!line.isBlank() && line.length() <= 20 && !queries.contains(line)) {
                        queries.add(line);
                    }
                }
            }

            log.debug("[RAG查询扩展] 原始: {} → 扩展后: {}", question, queries);
        } catch (Exception e) {
            log.warn("[RAG查询扩展] 失败，使用原始查询: {}", e.getMessage());
        }

        return limitQueries(queries);
    }

    /** 限制子查询数量，保留原始查询在首位；过多子查询会放大噪声与检索耗时。 */
    static List<String> limitQueries(List<String> queries) {
        if (queries == null) {
            return List.of();
        }
        if (queries.size() <= MAX_SUB_QUERIES) {
            return queries;
        }
        log.info("[RAG查询扩展] 子查询 {} 条超上限 {}，截断", queries.size(), MAX_SUB_QUERIES);
        return new ArrayList<>(queries.subList(0, MAX_SUB_QUERIES));
    }

    /**
     * 从长问题中提取关键词，用于向量检索。
     *
     * @param question 用户原始问题
     * @return 提取的关键词短语（≤15字）
     */
    private static String extractKeywords(String question) {
        // 去除口语化前缀和标点
        String cleaned = question
                .replaceAll("^(既然|那么|如果|请问|我想问|请告诉我|你能告诉我)", "")
                .replaceAll("[？?!！。，,、\"“”‘’（）()\\[\\]【】]", " ")
                .trim();

        // 按空格/标点分割，取核心词
        String[] words = cleaned.split("\\s+");
        StringBuilder keywords = new StringBuilder();
        for (String word : words) {
            if (word.length() >= 2 && keywords.length() + word.length() <= 15) {
                if (!keywords.isEmpty()) keywords.append(" ");
                keywords.append(word);
            }
        }

        String result = keywords.toString().trim();
        return result.isEmpty() ? question.substring(0, Math.min(15, question.length())) : result;
    }
}
