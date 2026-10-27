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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.kbook.service.ai.rag.ListQueryDetector;
import com.kbook.service.ai.rag.ListQueryRetriever;
import com.kbook.service.ai.rag.ListQueryStrategySelector;
import com.kbook.service.ai.rag.ItemListRetriever;
import com.kbook.service.ai.rag.RagPipelineComponents;
import com.kbook.service.ai.rag.CitationValidator;
import com.kbook.service.ai.core.ChatModelManager;
import com.kbook.service.ai.core.AiAssistantProvider;
import com.kbook.service.ai.core.AiSceneConfigService;
import com.kbook.service.ai.core.AiUsageContext;
import com.kbook.constants.ai.BookChatPrompts;
import com.kbook.constants.ai.RagPrompts;

/**
 * 图书问答列表展开流水线 — outline 对象化/逐条定向检索/分节串行生成/聚合落库。
 */
@Slf4j
@Service
@LogModule("图书问答")
public class BookChatPipelineService {

    private final com.kbook.service.ai.outline.AnswerOutlineService answerOutlineService;
    private final @Qualifier("qaOutlineExecutor") ExecutorService qaOutlineExecutor;
    private final ItemListRetriever itemListRetriever;
    private final ChatModelManager chatModelManager;
    private final BehaviorProfileBuilder behaviorProfileBuilder;
    private final BookChatSessionService sessionService;
    private final com.kbook.service.ai.rag.AiRetrievalSnapshotService retrievalSnapshotService;

    public BookChatPipelineService(
            com.kbook.service.ai.outline.AnswerOutlineService answerOutlineService,
            @Qualifier("qaOutlineExecutor") ExecutorService qaOutlineExecutor,
            ItemListRetriever itemListRetriever,
            ChatModelManager chatModelManager,
            BehaviorProfileBuilder behaviorProfileBuilder,
            BookChatSessionService sessionService,
            com.kbook.service.ai.rag.AiRetrievalSnapshotService retrievalSnapshotService) {
        this.answerOutlineService = answerOutlineService;
        this.qaOutlineExecutor = qaOutlineExecutor;
        this.itemListRetriever = itemListRetriever;
        this.chatModelManager = chatModelManager;
        this.behaviorProfileBuilder = behaviorProfileBuilder;
        this.sessionService = sessionService;
        this.retrievalSnapshotService = retrievalSnapshotService;
    }

    // ====================================================================
    // 列表展开流水线（阶段2，§3.1–3.4 / §4.4–4.7）
    //
    // 把"必须回答哪些条目"从模型的临场记忆变成 Java 循环的已知常量：
    //   outline 对象化 → 逐条配额定向检索（治 H4 覆盖集中化）
    //   → 逐段 streamSegment 串行生成、按序写同一条 SSE 流的 message 事件
    //   →（节数=N 由循环结构保证）→ 聚合单行落库。前端协议零改动。
    // ====================================================================

    /** 单轮逐节展开的条目上限（§3.4/§10）：超出部分由末尾确定性清单节兜底，不再整轮回退。 */
    private static final int MAX_PIPELINE_ITEMS = 20;
    /** 超过该条数时压缩每节预算，避免总时长与 token 失控。 */
    private static final int COMPACT_BUDGET_THRESHOLD = 8;
    /** 超过该条数时进一步压缩预算（长列表优先保证每条都覆盖）。 */
    private static final int ULTRA_COMPACT_BUDGET_THRESHOLD = 16;
    /** 单节生成超时（§4.7）：120s/节（Q7 定） */
    private static final long SEGMENT_TIMEOUT_MS = 120_000L;
    /** 逐条预取整体超时：并行执行，15s 覆盖 P99 */
    private static final long PREFETCH_TIMEOUT_MS = 15_000L;
    /** 现场条目化超时：1.2s 硬上限（§4.7：超即退回单次流式，用户无感） */
    private static final long ONSITE_OUTLINE_TIMEOUT_MS = 1_200L;
    /** 流水线内历史注入上限（§4.5：outline 已携带清单，历史可截） */
    private static final int PIPELINE_HISTORY_LIMIT = 6;
    /** §3.1 第 1 轮③ 对齐产物：chunkAnchors 已写入的 outline（alignChunkAnchors 设置、
     *  streamItemListPipeline 消费）。ThreadLocal 避免并发串轮。 */
    private static final ThreadLocal<com.kbook.service.ai.outline.AnswerOutline> ALIGNED_OUTLINE =
            new ThreadLocal<>();

    /**
     * 流水线触发判定（§3.4）：检测为列表型 && outline 可得 && items≥2 && items≤8。
     * <p>outline 两级获取对抗异步落库竞态：① DB（上一轮 assistant 的 answer_outline 列）；
     * ② 缺失即现场条目化（输入=上轮回答原文，1.2s 硬超时）并回填 DB。</p>
     * <p>命中且本轮 RAG 有 matches 时，把上轮 RAG 材料里的 chunkIndex 对齐写入 outline 的
     * chunkAnchors（§3.1 第 1 轮③，字面命中率、不用 LLM），供流水线逐条锚点检索；
     * 对齐失败留空，检索阶段走关键词向量兜底（§4.6 第 2 级）。</p>
     *
     * @param lastAnswer 调用方已加载的上轮回答（避免重复查库）
     * @param ragMatches  本轮 RAG 匹配片段（含 chunkIndex），可为 null（无对齐，走关键词兜底）
     * @return 可用的 outline（已尽量对齐锚点）；不触发时 null（调用方走单次流式回退路径）
     */
    /**
     * 首轮列表题：从问题 + 当前检索材料提取条目的超时（比现场条目化宽松）。
     * <p>
     * 2026-10-26 运行时实测：同一网关的查询扩展单次调用约 3.7s，3s 预算下首轮条目化
     * 必然超时、流水线从未被触发（日志反复出现「首轮列表题条目化超时（3000ms）」）。
     * <p>
     * 放宽到 12s：实测条目化（问题 + 6000 字材料）单次耗时 6.2s / &gt;8s / ≤8s，
     * 8s 预算正好卡在分布边缘——约一半的首轮列表题走不到流水线。12s 覆盖实测分布，
     * 失败也只是退回单次流式（回答仍然完整），而成功则解锁逐条展开。
     */
    private static final long FIRST_TURN_OUTLINE_TIMEOUT_MS = 12_000L;

    /**
     * 长清单的条目化预算。条目化耗时与输出长度正相关（`ai_usage_log` scene=ANSWER_OUTLINE：
     * 1291 输出 tokens 平均 23.5s、67 tokens 只要 3.3s），25 条清单在 12s 下会超时退回单次流式
     * （同一后端两轮评测实测超时 4 次）。只有"确实是长清单"时才放宽，短清单仍用 12s。
     */
    private static final long FIRST_TURN_OUTLINE_LONG_MS = 25_000L;

    /** 材料片段数达到这个量级才按长清单给预算（实测 25 条清单窗口出 49 段，短清单 5~7 段）。 */
    private static final int LONG_LIST_MATCHES = 40;

    /** 材料里行首编号达到这么多行，同样按长清单给预算（片段少但条目密的情况）。 */
    private static final int LONG_LIST_ENUM_LINES = 12;

    private static final Pattern ENUM_LINE =
            Pattern.compile("^\\s*(?:\\d{1,2}\\s*[.、)）]|第[〇零一二三四五六七八九十百0-9]{1,4}[条项点])");

    /** 材料里写明的条目总数（「共25条」「一共 25 条心理倾向」）。 */
    private static final Pattern DECLARED_COUNT = Pattern.compile("(?:共|一共|总共)?\\s*(\\d{2,3})\\s*条");

    /** 条目数不足声明总数的这个比例，就认定清单残缺（8/25 = 32%，25/25 = 100%）。 */
    private static final int COMPLETE_RATIO_NUM = 8;
    private static final int COMPLETE_RATIO_DEN = 10;

    com.kbook.service.ai.outline.AnswerOutline resolvePipelineOutline(
            ListQueryDetector.DetectionResult detection, String question,
            Long userId, String sessionId, SseEmitter emitter,
            String lastAnswer, List<EmbeddingMatch<TextSegment>> ragMatches, String toc) {
        if (!detection.isListQuery()) {
            return null;
        }
        if (lastAnswer == null || lastAnswer.isBlank()) {
            // 首轮列表题：没有上轮回答，从问题 + 当前检索材料现场提取条目
            return buildFirstTurnOutline(question, ragMatches, emitter, toc, detection.listTopic());
        }

        // ① DB 优先
        AiConversation lastRow = sessionService.findLastAssistantRow(userId, sessionId);
        com.kbook.service.ai.outline.AnswerOutline outline = lastRow != null
                ? answerOutlineService.parseFromDb(lastRow.getAnswerOutline()) : null;
        String source = "db";

        // ② 现场条目化（回退），1.2s 硬超时
        if (outline == null) {
            try {
                emitter.send(SseEmitter.event().name("thinking_content")
                        .data("我把上一条回答整理成清单，逐条展开讲给你…\n"));
            } catch (Exception ignored) {
            }
            // 输入优先原文 content（压缩版可能丢列表结构——H3 教训），缺失才回退压缩文本
            final String answerForOutline = (lastRow != null && lastRow.getContent() != null
                    && !lastRow.getContent().isBlank()) ? lastRow.getContent() : lastAnswer;
            java.util.concurrent.Future<com.kbook.service.ai.outline.AnswerOutline> buildFuture =
                    qaOutlineExecutor.submit(() -> answerOutlineService.build(answerForOutline));
            try {
                outline = buildFuture.get(ONSITE_OUTLINE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                source = "onsite";
            } catch (TimeoutException te) {
                // 超时仅让等待方降级。用 ThreadPoolExecutor.submit 拿 Future（非 CompletableFuture），
                // cancel(true) 会真正中断 worker 线程——langchain4j 阻塞调用响应中断时提前返回，
                // 避免 qaOutlineExecutor（2 线程）被跑满 30s 的僵尸任务堵死导致后续流水线全灭。
                buildFuture.cancel(true);
                log.info("[Pipeline] 现场条目化超时（{}ms），已中断后台任务，退回单次流式路径", ONSITE_OUTLINE_TIMEOUT_MS);
                return null;
            } catch (Exception e) {
                log.info("[Pipeline] 现场条目化失败，退回单次流式路径: {}", e.getMessage());
                return null;
            }
            // 回填 DB（异步，不阻塞当前回答）：现场条目化的输入已是上轮回答，
            // 回填后下次追问直接命中 DB 路径
            if (outline != null && lastRow != null) {
                final String json = answerOutlineService.toJson(outline);
                if (json != null) {
                    sessionService.updateOutlineById(lastRow.getId(), json);
                }
            }
        }

        if (outline == null || outline.size() < 2) {
            return null;
        }
        if (outline.size() > MAX_PIPELINE_ITEMS) {
            // 不再整轮退回：展开前 MAX_PIPELINE_ITEMS 条，其余条目由流水线末尾的
            // 确定性清单节兜底（见 streamItemListPipeline），保证"一条不落"
            log.info("[Pipeline] 条目数 {} 超展开上限 {}，前 {} 条逐节展开、其余走清单节",
                    outline.size(), MAX_PIPELINE_ITEMS, MAX_PIPELINE_ITEMS);
        }

        // ③ 对齐 chunkAnchors（§3.1 第 1 轮③）：把上轮 RAG 材料片段中的 chunkIndex
        // 关联到各条目（条目关键词字面命中率粗排，不用 LLM），对齐后写 ALIGNED_OUTLINE
        // 供流水线逐条锚点检索；对齐失败/无可对齐时留空，检索阶段走关键词向量兜底
        alignChunkAnchors(outline, ragMatches);
        com.kbook.service.ai.outline.AnswerOutline aligned = ALIGNED_OUTLINE.get();
        ALIGNED_OUTLINE.remove();
        if (aligned != null) {
            log.info("[Pipeline] 触发: source={}, items={}, topic={}, alignedAnchors=true",
                    source, aligned.size(), aligned.topic());
            return aligned;
        }
        log.info("[Pipeline] 触发: source={}, items={}, topic={}", source, outline.size(), outline.topic());
        return outline;
    }

    /**
     * 目录子行直接当条目清单：书自己的枚举（「下篇 芒格私人书单」下的六辑）。
     * <p>只在目录确实给出枚举结构时用——没有子行（如「人类误判心理学」的 25 条不在目录里）
     * 仍走模型条目化。子行标题原样保留（含「第一辑」这类序数前缀），条目顺序＝目录顺序。
     *
     * @return items ≥ 2 的 outline；目录无子行时 null
     */
    com.kbook.service.ai.outline.AnswerOutline outlineFromTocChildren(
            String toc, String question, String listTopic) {
        List<String> children = com.kbook.service.ai.rag.TocStructureHint.childTitles(toc, question, listTopic);
        if (children.size() < 2) {
            return null;
        }
        List<com.kbook.service.ai.outline.AnswerOutline.Item> items = new ArrayList<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            // 标题同时当检索关键词：逐条取材料先按锚点、再按 keywords 向量召回，
            // 两者都空时整节会直接落成「材料没检索到」占位（实测 6/6 节全空）。
            items.add(new com.kbook.service.ai.outline.AnswerOutline.Item(
                    i + 1, children.get(i), null, List.of(children.get(i)), null));
        }
        return new com.kbook.service.ai.outline.AnswerOutline(listTopic, items);
    }

    /**
     * 材料里写明的条目总数；没写明返回 0。
     * <p>模型条目化偶尔只抄一部分（实测 25 条清单某一轮只给出 8 条），
     * 少于声明总数八成时按清单残缺处理，退回单次流式——八节的半份清单比散文答案更糟。
     */
    static int declaredItemCount(String context) {
        if (context == null || context.isEmpty()) {
            return 0;
        }
        java.util.regex.Matcher m = DECLARED_COUNT.matcher(context);
        if (!m.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 条目数够不够撑起一份完整清单（材料没写总数时不判残缺）。 */
    static boolean outlineIsComplete(int itemCount, int declaredCount) {
        return declaredCount < 5 || (long) itemCount * COMPLETE_RATIO_DEN >= (long) declaredCount * COMPLETE_RATIO_NUM;
    }

    /**
     * 这份材料是不是长清单：片段多（长章节窗口）或材料里行首编号多（一条一行的小清单）。
     * 只看"是否值得给更长预算"，不参与内容判断——判错的代价只是多等一会儿。
     */
    static boolean looksLikeLongList(String context, int matchCount) {
        if (matchCount >= LONG_LIST_MATCHES) {
            return true;
        }
        if (context == null || context.isEmpty()) {
            return false;
        }
        int enumerated = 0;
        for (String line : context.split("\n")) {
            if (ENUM_LINE.matcher(line).find() && ++enumerated >= LONG_LIST_ENUM_LINES) {
                return true;
            }
        }
        return false;
    }

    /**
     * 首轮列表题：没有上轮回答时，用"问题 + 当前检索材料"提取条目。
     * <p>只取参考材料中明确出现的条目；超时/无条目/超上限一律退回单次流式。
     * <p>条目化只吃材料前 6000 字：窗口里各章节开头块彼此相隔上千字，靠后的条目
     * 连标题都进不了输入（实测「芒格私人书单」六辑只提取出前两辑）。
     * 因此把书自己的目录子树一并喂进去当枚举表。
     */
    private com.kbook.service.ai.outline.AnswerOutline buildFirstTurnOutline(
            String question, List<EmbeddingMatch<TextSegment>> ragMatches, SseEmitter emitter,
            String toc, String listTopic) {
        if (question == null || question.isBlank() || ragMatches == null || ragMatches.isEmpty()) {
            return null;
        }
        StringBuilder context = new StringBuilder();
        for (EmbeddingMatch<TextSegment> match : ragMatches) {
            String text = match.embedded() != null ? match.embedded().text() : "";
            if (text == null || text.isBlank()) continue;
            if (context.length() + text.length() > 6000) break;
            context.append(text).append("\n\n");
        }
        if (context.isEmpty()) {
            return null;
        }
        try {
            emitter.send(SseEmitter.event().name("thinking_content")
                    .data("我把这个问题整理成清单，逐条讲…\n"));
        } catch (Exception ignored) {
        }

        String tocHint = com.kbook.service.ai.rag.TocStructureHint.select(toc, question, listTopic);
        if (!tocHint.isEmpty()) {
            log.info("[Pipeline] 首轮条目化带目录提示: topic={}, hint={}", listTopic,
                    tocHint.replace("\n", " / "));
        }
        // 条目化之前先确认"喂给条目化的材料里真的有读者指名的那一节"。
        // 材料取错时条目化一定仍能产出清单（它挑材料里唯一可见的那份），于是流水线会拿着
        // 合法引用去回答另一个主题（实测「如何获得幸福和成功」被写成「私人书单六辑」6 节，
        // 引用合法、答案最长）。判定只看引号里的章节名——与 TopicCoverageCheck 同一口径；
        // 不用 listTopic 兜底：它是泛化措辞（「芒格避免的不幸事」正文里就没有），
        // 拿它判定会把正常用例误退回单次流式。
        List<String> asked = com.kbook.service.ai.rag.TopicCoverageCheck.quotedPhrases(question);
        if (!asked.isEmpty()
                && !com.kbook.service.ai.rag.TopicCoverageCheck.coveredPhrases(asked, context.toString())) {
            log.warn("[Pipeline] 首轮检索材料未覆盖提问指名的章节，退回单次流式: asked={}, phrases={}",
                    listTopic, asked);
            return null;
        }

        // 目录已经是现成的枚举表时，条目不用再让模型抄一遍。
        // 实测条目化平均 10.95s、输出越长越慢（1291 tok 平均 23.5s，67 tok 只要 3.3s），
        // 首轮 12s 预算下长清单必然超时退回单次流式；而目录子行是零成本、零漂移的确定性来源。
        com.kbook.service.ai.outline.AnswerOutline fromToc =
                outlineFromTocChildren(toc, question, listTopic);
        if (fromToc != null) {
            alignChunkAnchors(fromToc, ragMatches);
            com.kbook.service.ai.outline.AnswerOutline alignedToc = ALIGNED_OUTLINE.get();
            ALIGNED_OUTLINE.remove();
            com.kbook.service.ai.outline.AnswerOutline tocOutline =
                    alignedToc != null ? alignedToc : fromToc;
            log.info("[Pipeline] 首轮条目化直接取目录子行（不调模型）: items={}, topic={}, alignedAnchors={}",
                    tocOutline.size(), tocOutline.topic(), alignedToc != null);
            return tocOutline;
        }

        Future<com.kbook.service.ai.outline.AnswerOutline> future =
                qaOutlineExecutor.submit(() ->
                        answerOutlineService.buildFromContext(question, context.toString(), tocHint));
        long outlineTimeout = looksLikeLongList(context.toString(), ragMatches.size())
                ? FIRST_TURN_OUTLINE_LONG_MS : FIRST_TURN_OUTLINE_TIMEOUT_MS;
        com.kbook.service.ai.outline.AnswerOutline outline;
        try {
            outline = future.get(outlineTimeout, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            log.info("[Pipeline] 首轮列表题条目化超时（{}ms），退回单次流式", outlineTimeout);
            return null;
        } catch (Exception e) {
            log.info("[Pipeline] 首轮列表题条目化失败，退回单次流式: {}", e.getMessage());
            return null;
        }

        if (outline == null || outline.size() < 2) {
            return null;
        }
        int declared = declaredItemCount(context.toString());
        if (!outlineIsComplete(outline.size(), declared)) {
            log.warn("[Pipeline] 条目化只给出 {}/{} 条，清单残缺，退回单次流式", outline.size(), declared);
            return null;
        }
        alignChunkAnchors(outline, ragMatches);
        com.kbook.service.ai.outline.AnswerOutline aligned = ALIGNED_OUTLINE.get();
        ALIGNED_OUTLINE.remove();
        if (aligned != null) {
            log.info("[Pipeline] 首轮触发: items={}, topic={}, alignedAnchors=true",
                    aligned.size(), aligned.topic());
            return aligned;
        }
        log.info("[Pipeline] 首轮触发: items={}, topic={}", outline.size(), outline.topic());
        return outline;
    }

    /**
     * 列表展开流水线主流程（§3.1）。
     * <p>严格 for i=1..N 串行生成（循环计数器，不依赖闭包 state——A2 教训）：
     * 节数 = 条目数恒成立，漏条在结构上不可能发生。每节失败重试一次，仍失败落占位节继续，
     * 整轮绝不因单节失败而报废（Q5）。N 节全部完成后聚合为单行 assistant 落库。</p>
     */
    void streamItemListPipeline(
            Book book, String question,
            com.kbook.service.ai.outline.AnswerOutline outline,
            String bookInfoPrompt, StreamingChatModel fallbackModel,
            Long userId, String sessionId, SseEmitter emitter, boolean manual,
            List<EmbeddingMatch<TextSegment>> initialMatches, String promptVersion, boolean regenerate) {

        List<com.kbook.service.ai.outline.AnswerOutline.Item> allItems = outline.items();
        // 条目超过展开上限时只逐节展开前 MAX_PIPELINE_ITEMS 条，其余由末尾清单节确定性兜底——
        // 整轮退回单次流式会让长列表直接失去"逐条覆盖"的保证，尾部条目更容易被模型跳过
        List<com.kbook.service.ai.outline.AnswerOutline.Item> items =
                allItems.size() > MAX_PIPELINE_ITEMS
                        ? new ArrayList<>(allItems.subList(0, MAX_PIPELINE_ITEMS))
                        : allItems;
        List<com.kbook.service.ai.outline.AnswerOutline.Item> overflowItems =
                allItems.size() > MAX_PIPELINE_ITEMS
                        ? new ArrayList<>(allItems.subList(MAX_PIPELINE_ITEMS, allItems.size()))
                        : List.of();
        final int n = items.size();
        // 长列表缩短单节超时：20 节 × 2 次重试也不至于顶穿 1h SSE 上限
        final long segmentTimeoutMs = n > ULTRA_COMPACT_BUDGET_THRESHOLD
                ? 60_000L : SEGMENT_TIMEOUT_MS;

        // 单节模型：BOOK_QA_SEGMENT（QA_WITHOUT_THINKING 未绑定回退 QA 配置 + thinking 关，Q6）
        StreamingChatModel segmentModel = fallbackModel;
        var segLogContext = chatModelManager.buildLogContext(AiScene.BOOK_QA);
        try {
            StreamingChatModel m = chatModelManager.getStreamingModelForScene(AiScene.BOOK_QA_SEGMENT);
            if (m != null) {
                segmentModel = m;
                segLogContext = chatModelManager.buildLogContext(AiScene.BOOK_QA_SEGMENT);
            }
        } catch (Exception e) {
            log.warn("[Pipeline] 分节模型解析失败，沿用 BOOK_QA 模型: {}", e.getMessage());
        }

        // 1. 逐条定向预取（§4.6）：提交即返回，生成第 i 节前只等第 i 条——
        // 第一节不必等全部条目检索完；单条超时/异常只影响该条。
        // 注意：传入的 outline 已含 §3.1③ 对齐后的 chunkAnchors（retrieveByAnchors 消费）
        Map<Integer, java.util.concurrent.CompletableFuture<ItemListRetriever.Material>> materialFutures =
                itemListRetriever.prefetchAllAsync(book, items);
        int anchorHit = (int) items.stream()
                .filter(it -> it.chunkAnchors() != null && !it.chunkAnchors().isEmpty()).count();
        log.info("[Pipeline] 预取已提交: bookId={}, items={}, 有锚点={}/{}",
                book.getId(), n, anchorHit, n);

        // 2. 消息组装：静态前缀 + 历史 + 本轮专属材料 + 单节任务（§3.3）
        List<ChatMessage> baseMessages = buildBaseMessages(userId, sessionId, bookInfoPrompt);
        String style = sessionService.getChatStyleForUser(userId);

        StringBuilder answer = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        final SseEmitter finalEmitter = emitter;
        StreamingSseHandler.Callback noopCallback = new StreamingSseHandler.Callback() {
            @Override
            public String getOperationName() {
                return "图书问答-列表分节";
            }

            @Override
            public void onThinkingToken(String thinkingText, SseEmitter e) {
                // 分节模型默认 thinking 关；解析失败回退 BOOK_QA（thinking 开）时转发给前端
                thinking.append(thinkingText);
                SseHelper.safeSendEvent(finalEmitter, "thinking_content", thinkingText);
            }

            @Override
            public void onComplete(String fullResponse, ChatResponse completeResponse) {
                // 流水线自己聚合落库，段级不使用 onComplete
            }
        };

        List<String> sectionFinishReasons = new ArrayList<>(n);
        boolean interrupted = false;
        int materialHit = 0;
        PipelineFragmentRegistry fragments = new PipelineFragmentRegistry();

        for (int i = 0; i < n; i++) {
            com.kbook.service.ai.outline.AnswerOutline.Item item = items.get(i);
            int id = item.id() != null ? item.id() : i + 1;
            String prevTitle = i > 0 ? items.get(i - 1).title() : null;
            String prevTail = i > 0 ? tailSentences(answer.toString(), 2) : null;
            ItemListRetriever.Material material = awaitMaterial(materialFutures.get(id));
            String materialText = fragments.label(material);
            if (!materialText.isBlank()) {
                materialHit++;
            }
            String instruction = buildSegmentInstruction(i + 1, n, outline.topic(), items, item.title(),
                    item.gist(), prevTitle, prevTail, materialText, style);

            List<ChatMessage> segMessages = new ArrayList<>(baseMessages.size() + 2);
            segMessages.addAll(baseMessages);
            segMessages.add(UserMessage.from(instruction));

            // 节间分隔：先于本节流式内容发送（streamSegment 直接推 message），
            // 前端拼接出的全文与聚合落库文本一致；空节/占位节时只是多一个空行，Markdown 渲染无害
            if (answer.length() > 0) {
                answer.append("\n\n");
                if (!SseHelper.safeSendEvent(emitter, "message", "\n\n")) {
                    interrupted = true;
                    sectionFinishReasons.add("DISCONNECTED");
                    break;
                }
            }

            // 该条材料没检索到 → 不调用模型。预写占位节既保证 N 节完整，也避免模型用书外知识外推。
            if (materialText == null || materialText.isBlank()) {
                String placeholder = buildMaterialPlaceholder(i + 1, item.title());
                appendSection(answer, placeholder);
                SseHelper.safeSendEvent(emitter, "message", placeholder);
                sectionFinishReasons.add("NO_MATERIAL");
                log.info("[Pipeline] 第 {}/{} 节无材料，输出预写占位: {}", i + 1, n, item.title());
                continue;
            }

            StreamingSseHandler.SegmentResult seg = StreamingSseHandler.streamSegment(
                    segmentModel, segMessages, emitter, noopCallback, segLogContext, segmentTimeoutMs);

            if (seg.connectionClosed()) {
                // 客户端断开/任务取消：本节已流出部分并入聚合（与前端显示一致），部分落库，不发 done（§7 验收）
                appendSection(answer, seg.text());
                interrupted = true;
                sectionFinishReasons.add("DISCONNECTED");
                break;
            }
            // 失败（ERROR/TIMEOUT）且未流出任何内容时重试一次（Q5）；
            // 已有部分输出则直接接受（前端已实时流出，重试会造成重复内容）
            boolean sectionFailed = seg.failed() || "TIMEOUT".equals(seg.finishReason());
            if (sectionFailed && seg.text().isBlank()) {
                log.warn("[Pipeline] 第 {}/{} 节生成失败且无输出，重试一次: {}", i + 1, n, seg.error());
                seg = StreamingSseHandler.streamSegment(
                        segmentModel, segMessages, emitter, noopCallback, segLogContext, segmentTimeoutMs);
                if (seg.connectionClosed()) {
                    appendSection(answer, seg.text());
                    interrupted = true;
                    sectionFinishReasons.add("DISCONNECTED");
                    break;
                }
                sectionFailed = seg.failed() || "TIMEOUT".equals(seg.finishReason());
            }

            String sectionText = seg.text() != null ? seg.text().trim() : "";
            if (sectionFailed && sectionText.isBlank()) {
                // 占位节 + 继续——"输出节数 = N 恒成立"不允许被单节失败破坏（Q5）
                log.warn("[Pipeline] 第 {}/{} 节无有效输出，已落占位节: {}", i + 1, n, seg.error());
                // 占位节是本地生成的文本（未走 streamSegment，前端从未收到）→ 按 message 事件补发，
                // 保证前端显示与落库文本一致；模型部分输出已实时流出，不补发
                sectionText = buildFailurePlaceholder(i + 1, item.title());
                SseHelper.safeSendEvent(emitter, "message", sectionText);
            } else {
                sectionText = ensureSectionHeading(sectionText, i + 1, item.title());
            }
            sectionFinishReasons.add(seg.finishReason() != null ? seg.finishReason() : "UNKNOWN");
            appendSection(answer, sectionText);
        }

        if (interrupted && answer.isEmpty()) {
            log.info("[Pipeline] 断开于首节之前，无内容可落库");
            return;
        }

        // 超出展开上限的条目：用确定性清单兜底（A3 预写），保证长列表不因上限而"凭空少条"。
        // 不调模型：这里没有逐条材料，交给模型只会让它用书外知识编。
        if (!overflowItems.isEmpty() && !interrupted) {
            String overflowSection = buildOverflowSection(n, overflowItems);
            answer.append("\n\n");
            if (SseHelper.safeSendEvent(emitter, "message", "\n\n" + overflowSection)) {
                appendSection(answer, overflowSection);
                sectionFinishReasons.add("OVERFLOW_LIST");
                log.info("[Pipeline] 超上限条目已输出清单节: 展开={}, 清单={}", n, overflowItems.size());
            } else {
                interrupted = true;
            }
        }

        log.info("[Pipeline] 分节完成: bookId={}, sections={}/{}, 有材料={}/{}",
                book.getId(), sectionFinishReasons.size(), n, materialHit, n);

        // 3. 聚合落库（单行 assistant，§3.2）
        String aggregated = answer.toString().trim();
        String joinedFr = sectionFinishReasons.stream().distinct()
                .collect(java.util.stream.Collectors.joining(","));
        // finish_reason 列 length=20；多节拼接（如 "STOP,LENGTH,TIMEOUT"）会超 20 → 截断，
        // 否则 MySQL 数据截断导致整轮落库失败（§3.2 单行助手语义被破坏）
        if (joinedFr.length() > 20) joinedFr = joinedFr.substring(0, 20);

        // 输出审查 + 引用校验在落库前完成，保证 DB 与前端显示一致
        String safeAggregated = CommonUtils.sanitizeAiOutput(aggregated);
        CitationValidator.Result citationResult =
                CitationValidator.validate(safeAggregated, fragments.size());
        String finalAggregated = citationResult.answer();

        String outlineJson = answerOutlineService.toJson(outline);
        AiConversation pipelineRow = persistPipelineResult(userId, sessionId, book.getId(), question, finalAggregated,
                thinking.isEmpty() ? null : thinking.toString(), joinedFr, outlineJson, manual, regenerate);
        if (pipelineRow != null) {
            if (citationResult.citationJson() != null) {
                sessionService.updateCitationById(pipelineRow.getId(), citationResult.citationJson());
            }
            // 优先记录逐条材料实际入选的 chunk；全为空时退回本轮列表检索的初始 matches
            List<EmbeddingMatch<TextSegment>> snapshotMatches =
                    fragments.all().isEmpty() ? initialMatches : fragments.all();
            retrievalSnapshotService.save(pipelineRow.getId(), book.getId(), snapshotMatches, promptVersion);
        }

        if (interrupted) {
            log.warn("[Pipeline] 中途断开，按部分输出落库: chars={}, sectionsDone={}/{}",
                    finalAggregated.length(), sectionFinishReasons.size() - 1, n);
            return;
        }

        // §3.1④ 二级条目化：流水线的展开结果本身也是"分节列表"，再异步条目化一次，
        // 支持"第 3 条再详细点"三级钻取（下一轮 resolvePipelineOutline 能直读这份 outline）
        scheduleOutline(pipelineRow, finalAggregated, true);

        // 4. 结束：replace（sanitize/引用校验有改动时）→ done → complete
        if (!finalAggregated.equals(aggregated)) {
            try {
                emitter.send(SseEmitter.event().name("replace").data(finalAggregated));
                log.warn("[Pipeline] sanitize/引用校验命中，已发 replace 覆盖: sessionId={}", sessionId);
            } catch (Exception ignored) {
            }
        }
        try {
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
        } catch (Exception e) {
            log.debug("[Pipeline] done/complete 发送失败（连接已断）: {}", e.getMessage());
        }
        log.info("[Pipeline] 流水线完成: bookId={}, sections={}, totalChars={}, finishReasons={}",
                book.getId(), n, finalAggregated.length(), joinedFr);
    }

    /**
     * 流水线单节指令（§3.3 组装规则 + §4.2 预算）。
     */
    static String buildSegmentInstruction(int i, int n, String topic,
                                   List<com.kbook.service.ai.outline.AnswerOutline.Item> items,
                                   String title, String gist,
                                   String prevTitle, String prevTail, String material, String style) {
        StringBuilder sb = new StringBuilder();
        sb.append("【本轮任务 = 第 ").append(i).append('/').append(n).append(" 节】\n");
        sb.append("总任务：把「").append(topic != null && !topic.isBlank() ? topic : "上一条回答").append("」逐条展开，一条不落。\n");
        sb.append("【全篇条目】\n").append(buildOutlineList(items)).append("\n");
        sb.append("本节只写第 ").append(i).append(" 项「").append(title).append("」");
        if (gist != null && !gist.isBlank()) {
            sb.append("（要点：").append(gist).append("）");
        }
        sb.append("，不要提及其他条目。\n");

        sb.append("【输出格式】本节第一行必须是「### 第 ").append(i).append(" 节 · ").append(title)
          .append("」，不要另起总标题、不要重复编号列表。\n");

        sb.append("【衔接】");
        if (i == 1) {
            sb.append("你是首节：用一句话点明主题后立刻进入本节内容；禁止开场客套、禁止复述全列表、禁止加总结。\n");
        } else {
            sb.append("上一节为「").append(prevTitle).append("」，其结尾是「")
              .append(prevTail != null && !prevTail.isBlank() ? prevTail : "……").append("」。");
            sb.append("不要重复上一节内容，换一条自然的衔接句进入本节。\n");
        }

        sb.append("【本节预算】约 ").append(segmentBudgetText(style, n)).append("；写满即收，禁止拖成两节的内容。\n");

        if (i == n) {
            sb.append("【本节是末节】写完后允许一句收束，除此之外不要加总结。\n");
        }

        if (material != null && !material.isBlank()) {
            sb.append("【事实边界】本节事实只以【本节专属参考材料】为准；材料没写到的内容不要补充，")
              .append("不得凭记忆补充书中情节、人物、数据。\n");
            sb.append("【引用契约】本节材料以【片段 N】分隔。")
              .append("每个书中事实断言后用 [[N]] 标注来源（如 [[3]]）；")
              .append("N 只能使用本节材料里出现的编号，不要编造；没有依据的判断不标注。\n");
            sb.append("【本节专属参考材料（只含第 ").append(i).append(" 项）】\n").append(material).append("\n");
        } else {
            sb.append("【材料说明】原书中该项的直接材料没检索到。不要用书外知识补写，")
              .append("直接说明这一节材料不足。\n");
        }

        sb.append("【续写契约】直接输出本节正文。严禁输出「（未完）」「（待续）」等提示性文字；")
          .append("严禁向用户提问或询问是否继续；严禁重复编号列表结构以外的整段内容。");
        return sb.toString();
    }

    private static String buildOutlineList(List<com.kbook.service.ai.outline.AnswerOutline.Item> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(i + 1).append(") ").append(items.get(i).title());
        }
        return sb.toString();
    }

    /** 保证每节都有确定的 markdown 标题：模型没写就补，写了就不动。 */
    static String ensureSectionHeading(String text, int index, String title) {
        String heading = "### 第 " + index + " 节 · " + title;
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) return heading;
        if (trimmed.startsWith("#")) return trimmed;
        return heading + "\n\n" + trimmed;
    }

    /** 只等本节这一条材料；future 自带 8s 超时，这里再加一层兜底。 */
    private static ItemListRetriever.Material awaitMaterial(
            java.util.concurrent.CompletableFuture<ItemListRetriever.Material> future) {
        if (future == null) {
            return ItemListRetriever.Material.empty();
        }
        try {
            ItemListRetriever.Material material = future.get(
                    PREFETCH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            return material != null ? material : ItemListRetriever.Material.empty();
        } catch (Exception e) {
            return ItemListRetriever.Material.empty();
        }
    }

    private static String buildMaterialPlaceholder(int index, String title) {
        return "### 第 " + index + " 节 · " + title
                + "\n\n这一节的原书材料没检索到，我先不展开，避免说错。你可以单独问我这一条。";
    }

    private static String buildFailurePlaceholder(int index, String title) {
        return "### 第 " + index + " 节 · " + title
                + "\n\n这一节暂时没法展开，材料不足，先记着，回头我单独补给你。";
    }

    /**
     * 超出逐节展开上限时，剩余条目的确定性清单节（A3 预写）。
     * <p>不调用模型：这里没有逐条检索材料，模型只能靠书外知识补写。
     * 清单明确告知"未展开"，用户可对单条追问，由下一轮流水线单独展开。
     */
    static String buildOverflowSection(int expandedCount,
                                       List<com.kbook.service.ai.outline.AnswerOutline.Item> overflowItems) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 其余条目\n\n");
        sb.append("单轮最多逐条展开 ").append(expandedCount).append(" 条，以下 ")
          .append(overflowItems.size()).append(" 条这轮没有展开（避免篇幅和时长失控）。")
          .append("你可以直接说条目名，我下一轮单独展开：\n\n");
        int index = expandedCount;
        for (com.kbook.service.ai.outline.AnswerOutline.Item item : overflowItems) {
            index++;
            sb.append(index).append(") ").append(item.title());
            if (item.gist() != null && !item.gist().isBlank()) {
                sb.append(" —— ").append(item.gist());
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 按风格取单节预算字符数（§3.3/§4.2：DEEP 400–500 / CASUAL 250–350 / WITTY 300–400 / CONCISE 100–200）。
     */
    private static long segmentBudgetForStyle(String style) {
        if (style == null) return 450;
        return switch (style.toUpperCase()) {
            case "CONCISE" -> 250;
            case "CASUAL" -> 350;
            case "WITTY" -> 400;
            default -> 500; // DEEP
        };
    }

    /** 预算文案（进指令用，给模型区间而非定值） */
    /** 预算文案（进指令用，给模型区间而非定值）；条目多时压缩，控制总时长与 token。 */
    private static String segmentBudgetText(String style, int n) {
        if (n > ULTRA_COMPACT_BUDGET_THRESHOLD) {
            return "150~250 字";
        }
        if (n > COMPACT_BUDGET_THRESHOLD) {
            return "200~300 字";
        }
        return segmentBudgetText(style);
    }

    private static String segmentBudgetText(String style) {
        if (style == null) return "400~500 字";
        return switch (style.toUpperCase()) {
            case "CONCISE" -> "100~200 字";
            case "CASUAL" -> "250~350 字";
            case "WITTY" -> "300~400 字";
            default -> "400~500 字"; // DEEP
        };
    }

    /**
     * 流水线静态消息前缀（§3.3）：SystemMessage → UserMessage(bookInfo) → 截断历史。
     * 不含 RAG 与问题消息——本轮专属材料逐节注入。
     * 历史最多 {@link PIPELINE_HISTORY_LIMIT} 条且预留空间不足时进一步砍（outline 已携带清单）。
     */
    private List<ChatMessage> buildBaseMessages(Long userId, String sessionId, String bookInfoPrompt) {
        List<ChatMessage> messages = new ArrayList<>();
        String style = sessionService.getChatStyleForUser(userId);
        String systemPrompt = sessionService.getSystemPromptForStyle(style);
        messages.add(SystemMessage.from(systemPrompt));

        if (bookInfoPrompt != null && !bookInfoPrompt.isBlank()) {
            messages.add(UserMessage.from(bookInfoPrompt));
        }

        try {
            // 保守预留：N 节 × 每节预算 ×（1.2 指令/材料放大）
            int overhead = systemPrompt.length()
                    + (bookInfoPrompt != null ? bookInfoPrompt.length() : 0)
                    + (int) (MAX_PIPELINE_ITEMS * segmentBudgetForStyle(style) * 1.2) + 2000;
            List<AiConversation> history = sessionService.loadAndCompressHistory(userId, sessionId, overhead);
            if (!history.isEmpty()) {
                // 从最新往回取最多 PIPELINE_HISTORY_LIMIT 条，触顶即停（保证"最近的历史"优先留下）
                int estimated = messagesChars(messages);
                int contextLimit = sessionService.contextCharLimit();
                LinkedList<ChatMessage> picked = new LinkedList<>();
                int taken = 0;
                for (int i = history.size() - 1; i >= 0 && taken < PIPELINE_HISTORY_LIMIT; i--) {
                    AiConversation conv = history.get(i);
                    String content = conv.getCompressedContent();
                    if (content == null || content.isBlank()) continue;
                    if (estimated + content.length() > contextLimit) {
                        log.info("[Pipeline] 历史注入触顶，停止追加");
                        break;
                    }
                    if ("user".equals(conv.getRole())) {
                        picked.addFirst(UserMessage.from(content));
                    } else if ("assistant".equals(conv.getRole())) {
                        picked.addFirst(AiMessage.from(content));
                    } else {
                        continue;
                    }
                    estimated += content.length();
                    taken++;
                }
                messages.addAll(picked);
            }
        } catch (Exception e) {
            log.warn("[Pipeline] 加载历史失败，流水线继续无历史: {}", e.getMessage());
        }

        // 行为画像（与主路径一致，静态前缀的一部分）
        UserBehaviorProfile behaviorProfile = sessionService.loadBehaviorProfile(userId);
        if (behaviorProfile != null) {
            String behaviorDesc = behaviorProfileBuilder.buildSummary(behaviorProfile);
            if (!behaviorDesc.isBlank()) {
                messages.add(UserMessage.from("【读者近期画像】\n" + behaviorDesc
                        + "\n回答时可在相关处自然贴合，不要生硬提及读者画像。"));
            }
        }
        return messages;
    }

    /** 流水线聚合结果落库：单行 assistant（含 finishReason / outline），与追问历史兼容（§3.2）。 */
    private AiConversation persistPipelineResult(Long userId, String sessionId, Long bookId, String question,
                                       String aggregated, String thinkingText,
                                       String finishReason, String outlineJson, boolean manual,
                                       boolean regenerate) {
        try {
            sessionService.ensureSession(userId, sessionId, question, bookId);
            if (!regenerate || !sessionService.isLastUserMessage(userId, sessionId, question)) {
                sessionService.saveMessage(userId, sessionId, "user", question, bookId, null);
            }

            String safe = CommonUtils.sanitizeAiOutput(aggregated);
            AiConversation row = sessionService.saveMessage(userId, sessionId, "assistant", safe,
                    bookId, thinkingText, finishReason);
            if (row != null && outlineJson != null) {
                sessionService.updateOutlineById(row.getId(), outlineJson);
            }
            sessionService.updateSessionTimestamp(sessionId);
            sessionService.publishUserSignal(userId, sessionId, bookId, question, manual);
            return row;
        } catch (Exception e) {
            log.warn("[Pipeline] 聚合落库失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * §4.5：异步条目化回写。fire-and-forget，Rejected/异常全部吞掉，不影响主流程。
     *
     * @param row         刚落库的 assistant 行（null 则跳过）
     * @param answer      回答全文
     * @param isListQuery 本轮检测是否列表型（true 时无论结果都写，false 仅含枚举结构时写）
     */
    void scheduleOutline(AiConversation row, String answer, boolean isListQuery) {
        if (row == null || row.getId() == null || answer == null || answer.isBlank()) {
            return;
        }
        Long rowId = row.getId();
        // 无枚举结构直接跳过（省一次 TOOL 调用）；列表型问题即使文本无标号也跑条目化（可能用散文列举）
        if (!isListQuery && !hasEnumerativeStructure(answer)) {
            return;
        }
        try {
            qaOutlineExecutor.execute(() -> {
                try {
                    var outline = answerOutlineService.build(answer);
                    if (outline == null) return;
                    String json = answerOutlineService.toJson(outline);
                    if (json != null) {
                        sessionService.updateOutlineById(rowId, json);
                    }
                } catch (Exception e) {
                    log.warn("[Outline] 异步条目化失败: rowId={} - {}", rowId, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[Outline] 异步任务提交被拒（不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * §3.1 第 1 轮③：把上一轮 RAG 检索结果里的 chunkIndex 关联到 outline 各条目（chunkAnchors）。
     * <p>对齐依据：条目（标题+要点+关键词）在材料片段中的<b>字面命中率</b>——不用 LLM，
     * 流水线触发前同步执行、开销可忽略。每条目取命中率 ≥ 0.3 的片段中最多 3 个 chunkIndex；
     * 无可靠对齐则留空——检索阶段由关键词向量检索兜底（§4.6 第 2 级）。
     * <p>对齐后的 outline 写入 {@link #ALIGNED_OUTLINE}，由 {@code streamItemListPipeline} 消费；
     * 落库保持 keywords 版（锚点是本轮检索产物，不污染 DB 直读路径）。
     */
    private void alignChunkAnchors(com.kbook.service.ai.outline.AnswerOutline outline,
                                   List<EmbeddingMatch<TextSegment>> ragMatches) {
        if (outline == null || outline.size() < 2 || ragMatches == null || ragMatches.isEmpty()) {
            return;
        }
        try {
            List<List<Integer>> anchorLists = new ArrayList<>(outline.size());
            int aligned = 0;
            for (com.kbook.service.ai.outline.AnswerOutline.Item item : outline.items()) {
                List<Integer> anchors = new ArrayList<>();
                for (int i = 0; i < ragMatches.size() && anchors.size() < 3; i++) {
                    if (literalHit(item, ragMatches.get(i)) < 0.3f) continue;
                    int idx = RagPipelineComponents.getChunkIndex(ragMatches.get(i));
                    if (idx >= 0 && !anchors.contains(idx)) {
                        anchors.add(idx);
                    }
                }
                if (!anchors.isEmpty()) aligned++;
                anchorLists.add(anchors);
            }
            List<com.kbook.service.ai.outline.AnswerOutline.Item> newItems = new ArrayList<>(outline.size());
            for (int i = 0; i < outline.size(); i++) {
                com.kbook.service.ai.outline.AnswerOutline.Item it = outline.items().get(i);
                newItems.add(new com.kbook.service.ai.outline.AnswerOutline.Item(
                        it.id(), it.title(), it.gist(), it.keywords(),
                        anchorLists.get(i).isEmpty() ? null : anchorLists.get(i)));
            }
            log.info("[Pipeline] chunkAnchors 对齐: items={}, 有锚点={}/{}",
                    outline.size(), aligned, outline.size());
            ALIGNED_OUTLINE.set(new com.kbook.service.ai.outline.AnswerOutline(outline.topic(), newItems));
        } catch (Exception e) {
            log.debug("[Pipeline] chunkAnchors 对齐失败（留空走关键词兜底）: {}", e.getMessage());
        }
    }

    /** 条目关键词（标题+要点+keywords 字段）在材料片段中的字面命中率（0~1）。 */
    private static float literalHit(com.kbook.service.ai.outline.AnswerOutline.Item item,
                                    EmbeddingMatch<TextSegment> match) {
        String text = match.embedded() != null ? match.embedded().text() : "";
        if (text.isBlank()) return 0;
        Set<String> terms = new LinkedHashSet<>();
        if (item.title() != null && !item.title().isBlank()) terms.add(item.title().trim());
        if (item.gist() != null && !item.gist().isBlank()) terms.add(item.gist().trim());
        if (item.keywords() != null) item.keywords().stream()
                .filter(k -> k != null && !k.isBlank()).forEach(k -> terms.add(k.trim()));
        if (terms.isEmpty()) return 0;
        // 目录标题在正文里常被拆成两行（「第一辑」换行「自然」），字面 contains 会全部落空；
        // 比较前统一去空白，只影响「能不能找到材料」，不影响条目内容。
        String squashedText = squash(text);
        int hit = 0;
        for (String t : terms) {
            if (t.length() >= 2 && squashedText.contains(squash(t))) hit++;
        }
        return (float) hit / terms.size();
    }

    private static String squash(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) sb.append(c);
        }
        return sb.toString();
    }

    /** 粗略枚举结构信号：≥2 行行首编号/项目符号。命中才值得花一次 TOOL 调用条目化。 */
    private static boolean hasEnumerativeStructure(String answer) {
        int hits = 0;
        for (String line : answer.split("\n")) {
            String t = line.trim();
            if (t.matches("^\\s*(\\d+[.、)）]|[-*•·]|[一二三四五六七八九十]+[、.）])\\s*.*")) {
                hits++;
            }
        }
        return hits >= 2;
    }

    /**
     * 按行 ID 定向更新 answer_outline（不整行 save，避免与压缩等字段写入互相覆盖）。
     */
    /** 会话内最近一条 assistant 行（取 outline 用）；无则 null。 */
    /** 节文本并入聚合（trim 后非空才追加分隔+内容，保证与前端 message 流一致）。 */
    private static void appendSection(StringBuilder answer, String sectionText) {
        if (sectionText == null) return;
        String s = sectionText.trim();
        if (s.isEmpty()) return;
        if (answer.length() > 0 && answer.charAt(answer.length() - 1) != '\n') {
            answer.append("\n\n");
        }
        answer.append(s);
    }

    /** 取文本末尾 k 句（流水线衔接用，§3.3：仅上一节标题 + 结尾 1~2 句）。 */
    private static String tailSentences(String text, int k) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();
        int count = 0;
        int pos = t.length();
        for (int i = t.length() - 1; i >= 0; i--) {
            char c = t.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '…') {
                count++;
                if (count >= k) {
                    pos = i + 1;
                    break;
                }
                // 跳过句末连续标点
                int j = i - 1;
                while (j >= 0 && (t.charAt(j) == '”' || t.charAt(j) == '』' || t.charAt(j) == '）')) {
                    i = j;
                    j--;
                }
            }
        }
        String tail = t.substring(Math.min(pos, t.length())).trim();
        return tail.length() > 160 ? tail.substring(tail.length() - 160) : tail;
    }

    /** 上下文预算（字符）：场景配置 maxTokens × 1.5；解析失败按 32K tokens 估。 */
    private static int messagesChars(List<ChatMessage> messages) {
        int sum = 0;
        for (ChatMessage m : messages) {
            if (m instanceof SystemMessage s) sum += s.text().length();
            else if (m instanceof UserMessage u) sum += u.singleText().length();
            else if (m instanceof AiMessage a) sum += a.text().length();
        }
        return sum;
    }
}
