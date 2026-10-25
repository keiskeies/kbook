package com.kbook.service.roundtable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.exception.BusinessException;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SpeechSanitizer;
import com.kbook.common.util.SseHelper;
import com.kbook.config.ai.AiConfig;
import com.kbook.config.ai.AiConfigProvider;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.config.annotation.RedisLock;
import com.kbook.dto.roundtable.NextSpeakerResult;
import com.kbook.dto.roundtable.RoleVO;
import com.kbook.dto.roundtable.RoundTableSessionFeedVO;
import com.kbook.dto.roundtable.SpeakRequest;
import com.kbook.entity.AiScene;
import com.kbook.entity.AiProviderConfig;
import com.kbook.entity.Book;
import com.kbook.entity.RoundTableCoverage;
import com.kbook.entity.RoundTableMessage;
import com.kbook.entity.RoundTableSession;
import com.kbook.repository.BookRepository;
import com.kbook.repository.RoundTableCoverageRepository;
import com.kbook.repository.RoundTableMessageRepository;
import com.kbook.repository.RoundTableSessionRepository;
import com.kbook.service.ai.core.ChatHistoryCompressor;
import com.kbook.service.ai.core.ExternalKnowledgeGenerator;
import com.kbook.service.ai.streaming.StreamingSseHandler;
import com.kbook.service.book.BookParserService;
import com.kbook.service.book.BookService;
import com.kbook.service.embedding.EmbeddingService;
import com.kbook.service.progress.ReadingProgressService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import static com.kbook.common.util.QueryBuilder.eq;
import static com.kbook.common.util.QueryBuilder.in;
import com.kbook.service.ai.rag.RagPipelineComponents;
import com.kbook.service.ai.core.ChatModelManager;
import com.kbook.service.ai.core.AiProviderConfigService;
import com.kbook.service.ai.core.AiSceneConfigService;
import com.kbook.service.ai.core.AiUsageContext;
import com.kbook.constants.ai.RoundTablePrompts;

/**
 * 圆桌派发言人选择服务 — 纯 LLM 选人 / 机制兜底（轮数上限、跑题、停滞、垄断）/ 响应解析。
 */
@Slf4j
@Service
@LogModule("圆桌派")
public class RoundTableSpeakerService {

    private final BookService bookService;
    private final ChatModelManager chatModelManager;
    private final RoundTableCoverageService coverageService;
    private final RoundTableSessionRepository sessionRepository;
    private final RoundTableMessageRepository messageRepository;
    private final AiConfigProvider aiConfigProvider;
    private final ObjectMapper objectMapper;
    private final RoundTableRoleService roleService;
    private final RoundTableSpeechService speechService;

    public RoundTableSpeakerService(
            BookService bookService,
            ChatModelManager chatModelManager,
            RoundTableCoverageService coverageService,
            RoundTableSessionRepository sessionRepository,
            RoundTableMessageRepository messageRepository,
            AiConfigProvider aiConfigProvider,
            ObjectMapper objectMapper,
            RoundTableRoleService roleService,
            RoundTableSpeechService speechService) {
        this.bookService = bookService;
        this.chatModelManager = chatModelManager;
        this.coverageService = coverageService;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.aiConfigProvider = aiConfigProvider;
        this.objectMapper = objectMapper;
        this.roleService = roleService;
        this.speechService = speechService;
    }

    /**
     * 覆盖度触发谢幕阈值：overallScore（0-100）达到此值时强制 shouldEnd=true。
     * 替代旧的 60 轮硬上限——改为基于讨论质量（覆盖度）判断结束，而非机械轮次。
     * 阈值 85 对应 S 级（讨论已充分覆盖书籍核心内容）。
     * 兜底说明：若书籍无概念标签且无内容块，coverage 恒为 0，此时依赖 LLM 结束判断窗口自然结束。
     */
    private static final double COVERAGE_FORCE_END_THRESHOLD = 85.0;
    /**
     * 结束判断频控：轮次是此值的倍数时打开"结束判断窗口"，让 LLM 输出 shouldEnd 字段。
     * 避免每轮都让 LLM 思考"该不该结束"，浪费 token。
     */
    private static final int END_CHECK_INTERVAL_ROUNDS = 3;
    /**
     * 结束判断预热：轮次小于 角色数 × 此值 时不判断结束（讨论还在预热阶段）。
     */
    private static final int END_CHECK_WARMUP_ROLE_MULTIPLIER = 3;

    // ==================== 机制兜底常量（不依赖 LLM 判断） ====================

    /**
     * 硬性轮数上限。覆盖度在「目录块 + 空摘要」的书上恒为 0（块 keywords 是章节标题碎片，
     * 讨论永远命中不了），此时 END 的唯一门槛如果只挂在覆盖度上就是死锁——实测一场 29 轮、
     * 173 条发言的讨论无法自行结束。轮数是最后一道闸。
     */
    private static final int MAX_ROUNDS_HARD_LIMIT = 32;

    /**
     * 连续这么多条嘉宾发言没有主持人控场 → 强制 HOST 出场。
     * 实测主持人在 173 条发言里只出现 2 次，全靠 LLM 自觉，几乎必然失灵。
     */
    private static final int HOST_FORCE_AFTER_GUEST_MESSAGES = 10;

    /**
     * 跑题时提前拉主持人回来的门槛（配合 isOffBook）。
     * 必须小于 STALL_END_MIN_GUESTS_SINCE_HOST，否则主持人还没被叫出来，讨论就结束了。
     */
    private static final int HOST_FORCE_AFTER_OFFBOOK_MESSAGES = 3;

    /**
     * 停滞收场的门槛：主持人上一次控场之后，至少又说了这么多条嘉宾发言，才允许因"原地踏步"收场。
     * 这样升级路径是：跑题 → 主持人介入 → 仍然踏步 → 才收场，而不是一发现重复就掐断。
     */
    private static final int STALL_END_MIN_GUESTS_SINCE_HOST = 4;

    /**
     * 判定"原地踏步"的相似度阈值（字符二元组 Jaccard）。
     */
    private static final double STALL_SIMILARITY_THRESHOLD = 0.42;

    /**
     * 首轮不判停滞（预热阶段本来就容易重复开场话术）。
     */
    private static final int STALL_END_MIN_ROUNDS = 6;

    /**
     * 发言垄断硬上限：以"均分份额 × 此系数"为上限。
     * 原实现用平均值 × 1.5 当阈值，头部角色说话越多平均值越高，上限跟着涨，等于没有上限。
     */
    private static final double SPEAKER_OVERWEIGHT_RATIO = 1.25;

    /**
     * 纯 LLM 判断下一轮发言人（同时判断是否应该结束讨论）
     * <p>
     * 核心设计：
     * 1. 给 LLM 完整的角色信息（含性格参数、专业领域、说话风格）
     * 2. 给加权对话历史——越靠后的发言权重越高，让 LLM 能感知对话流向
     * 3. 给公平性约束——禁止连续发言、鼓励沉默者、主持人控场
     * 4. LLM 返回 nextSpeaker + shouldEnd + closingSummary，不再经过算法二次调整
     * <p>
     * 结束判断频控：
     * - 轮次 < 角色数 × 3：不判断（讨论预热）
     * - 轮次 % 3 == 0：判断窗口打开
     * - 覆盖度 >= 85：强制 shouldEnd=true（基于讨论质量，替代旧的轮次硬上限）
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return 下一发言人结果（含 shouldEnd / closingSummary）
     */
    @LogAction("纯LLM判断下一发言人")
    @RedisLock(key = "'rt:speaker:' + #sessionId", leaseTime = 30)
    public NextSpeakerResult getNextSpeakerOnlyLLM(Long userId, String sessionId) {
        // 1. 加载会话 + 书籍信息
        RoundTableSession session = sessionRepository.query()
                .where(RoundTableSession::getSessionId, eq(sessionId))
                .list(1)
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException("会话不存在"));

        Book book = null;
        if (session.getBookId() != null) {
            try {
                book = bookService.getBookById(session.getBookId());
            } catch (Exception e) {
                log.warn("加载书籍信息失败, bookId={}: {}", session.getBookId(), e.getMessage());
            }
        }

        // 2. 构建消息列表（顺序即 KV-cache 友好顺序：不变消息在前，可变消息在后）
        List<ChatMessage> messages = new ArrayList<>();

        // [消息1 - SystemMessage] 角色定义 + 选择规则（全局不变）
        messages.add(SystemMessage.from(RoundTablePrompts.ROUND_TABLE_NEXT_SPEAKER_SYSTEM));

        // [消息2 - UserMessage] 书籍背景 + 角色人设（会话内不变，缓存命中）
        String bookAndRoles = buildBookAndRolesMessage(session, book);
        messages.add(UserMessage.from(bookAndRoles));

        // 3. 加载历史消息并同步压缩
        int currentOverhead = RoundTablePrompts.ROUND_TABLE_NEXT_SPEAKER_SYSTEM.length()
                + bookAndRoles.length()
                + 2000; // 约束 + 历史 + 输出格式 + LLM 回复预留
        List<RoundTableMessage> allMessages;
        try {
            allMessages = speechService.loadAndCompressHistory(userId, sessionId, currentOverhead);
        } catch (Exception e) {
            log.warn("加载/压缩圆桌派历史失败，回退到直接加载: {}", e.getMessage());
            allMessages = messageRepository.query()
                    .where(RoundTableMessage::getSessionId, eq(sessionId))
                    .orderBy(RoundTableMessage::getId)
                    .list();
        }
        if (CollectionUtils.isEmpty(allMessages)) {
            return NextSpeakerResult.builder().nextSpeaker("HOST").shouldEnd(false).build();
        }

        // 4. 统计发言数据
        String[] roleKeys = session.getRoleKeys().split(",");
        // 轮次口径（统一）：嘉宾发言数 / 嘉宾数，向上取整；HOST 不计入——它只控场，不参与轮次与公平性
        long guestCount = Arrays.stream(roleKeys).map(String::trim)
                .filter(k -> !k.isEmpty() && !"HOST".equals(k)).count();
        Map<String, Long> speakCounts = allMessages.stream()
                .collect(Collectors.groupingBy(RoundTableMessage::getRoleKey, Collectors.counting()));
        long guestMsgCount = allMessages.stream()
                .filter(m -> !"HOST".equals(m.getRoleKey())).count();
        int currentRound = guestCount > 0
                ? (int) Math.ceil(guestMsgCount / (double) guestCount) : 0;
        String lastSpeaker = allMessages.get(allMessages.size() - 1).getRoleKey();

        // ==================== 机制兜底信号（不依赖 LLM 判断） ====================
        // 这些信号直接由数据算出，LLM 的判断只作为"谁最想接话"的参考，
        // 该不该控场、该不该结束由这里说了算。原因：选人用的是 TOOL 场景的 flash 级模型，
        // 指望它自己发现"跑了 27 轮没主持人了"是不现实的。

        // 距上次主持人发言过了多少条嘉宾发言
        int guestMessagesSinceHost = 0;
        for (int i = allMessages.size() - 1; i >= 0; i--) {
            if ("HOST".equals(allMessages.get(i).getRoleKey())) break;
            guestMessagesSinceHost++;
        }
        // 最近 3 条嘉宾发言（用于跑题/复读判定）
        List<RoundTableMessage> recentGuestMessages = allMessages.stream()
                .filter(m -> !"HOST".equals(m.getRoleKey()))
                .skip(Math.max(0, guestMsgCount - 3))
                .collect(Collectors.toList());
        boolean offBook = isOffBook(recentGuestMessages, book);
        boolean stalled = isStalled(allMessages, recentGuestMessages);

        // 强制结束：轮数硬上限 / 原地踏步
        String ruleClosingSummary = null;
        if (currentRound >= MAX_ROUNDS_HARD_LIMIT) {
            ruleClosingSummary = "讨论已经进行了" + currentRound + "轮，各方立场都已充分展开。"
                    + "请在谢幕中收束核心分歧，点出最有价值的一两个碰撞，然后结束本期讨论。";
        } else if (currentRound >= STALL_END_MIN_ROUNDS && stalled
                && guestMessagesSinceHost >= STALL_END_MIN_GUESTS_SINCE_HOST) {
            ruleClosingSummary = "讨论已连续多轮在原地打转，嘉宾反复用相似的论证回应彼此，没有产生新的信息量。"
                    + "请在谢幕中点出这几个来回的核心分歧、评价各方立场，然后收场。";
        }
        boolean forceEndByRule = ruleClosingSummary != null;

        // 强制控场：太久没主持人 / 明显跑题
        boolean forceHostByRule = !forceEndByRule
                && (guestMessagesSinceHost >= HOST_FORCE_AFTER_GUEST_MESSAGES
                || (offBook && guestMessagesSinceHost >= HOST_FORCE_AFTER_OFFBOOK_MESSAGES));
        String hostHint = null;
        if (forceHostByRule) {
            hostHint = buildHostHint(sessionId, guestMessagesSinceHost, offBook, stalled);
            log.info("机制强制控场: sessionId={}, round={}, sinceHost={}, offBook={}, stalled={}",
                    sessionId, currentRound, guestMessagesSinceHost, offBook, stalled);
        }

        // 5. 判断是否打开"结束判断窗口"（预热 = 每位嘉宾至少发言 3 轮）
        int warmupRounds = Math.max((int) guestCount * END_CHECK_WARMUP_ROLE_MULTIPLIER, (int) guestCount + 3);
        boolean endCheckWindow = currentRound >= warmupRounds
                && currentRound % END_CHECK_INTERVAL_ROUNDS == 0;

        // 读取覆盖度——基于讨论质量判断结束（替代旧的轮次硬上限）
        RoundTableCoverage coverage = coverageService.getCoverage(sessionId);
        double coverageScore = coverage != null && coverage.getOverallScore() != null
                ? coverage.getOverallScore() : 0.0;
        boolean coverageHighEnough = coverage != null && coverageScore >= COVERAGE_FORCE_END_THRESHOLD;
        if (coverageHighEnough) {
            log.info("覆盖度 {} 达到阈值 {}，触发谢幕: sessionId={}",
                    coverageScore, COVERAGE_FORCE_END_THRESHOLD, sessionId);
        }

        // [消息3 - UserMessage] 讨论历史（每次只追加一条新发言，前缀可大量复用 KV-cache）
        String weightedHistory = buildWeightedHistoryForLLM(allMessages);
        messages.add(UserMessage.from(weightedHistory));

        // [消息3.5 - UserMessage] 话题覆盖度（给 LLM 结束判断提供量化依据）
        if (coverage != null) {
            String coverageBrief = buildCoverageBriefForLLM(coverage);
            if (!coverageBrief.isBlank()) {
                messages.add(UserMessage.from(coverageBrief));
            }
        }

        // [消息4 - UserMessage] 公平性约束 + 输出格式（每轮完全变化，放最后不破坏前缀缓存）
        String fairnessConstraints = buildFairnessConstraints(roleKeys, speakCounts, allMessages);
        if (forceEndByRule) {
            fairnessConstraints += "\n- 【强制结束】讨论已到该收尾的时候（" + ruleClosingSummary + "）\n";
        } else if (forceHostByRule) {
            fairnessConstraints += "\n- 【强制控场】已经连续 " + guestMessagesSinceHost + " 条嘉宾发言没有主持人控场"
                    + (offBook ? "，且最近的发言已经偏离书本内容" : "") + "。NEXT 第一位必须是 HOST，END 必须 false。\n";
        }
        // 构建 角色中文名 → key 反向映射，用于解析 LLM 返回中文名时的容错
        Map<String, String> nameToKey = buildNameToKeyMap(session, roleKeys);
        // 明确列出所有合法 KEY，避免 LLM 把中文名当 key 返回（历史/约束里用的是中文名）
        String validKeysList = Arrays.stream(roleKeys).map(String::trim)
                .collect(Collectors.joining(", "));

        // 输出格式：行式 KV（比 JSON 更不容易出格式错误，LLM 训练数据中大量存在）
        String outputFormat;
        if (forceEndByRule) {
            outputFormat = "【强制结束】" + ruleClosingSummary + "\n\n"
                    + "必须 END=true，NEXT=HOST，并给出谢幕提纲。\n\n"
                    + "请按以下格式返回（每行一个字段，不要输出 JSON、不要输出 markdown 代码块）：\n\n"
                    + "NEXT: HOST\n"
                    + "END: true\n"
                    + "SUMMARY: 不超过200字的谢幕提纲\n\n"
                    + "规则：\n"
                    + "- KEY 必须是以下英文标识符之一（区分大小写，原样返回）：" + validKeysList + "\n";
        } else if (coverageHighEnough) {
            outputFormat = "【强制结束】话题覆盖度已达 " + String.format("%.0f", coverageScore)
                    + "（阈值 " + COVERAGE_FORCE_END_THRESHOLD + "），讨论已充分覆盖书籍核心内容，"
                    + "必须 END=true，NEXT=HOST，并给出谢幕提纲。\n\n"
                    + "请按以下格式返回（每行一个字段，不要输出 JSON、不要输出 markdown 代码块）：\n\n"
                    + "NEXT: HOST\n"
                    + "END: true\n"
                    + "SUMMARY: 不超过200字的谢幕提纲\n\n"
                    + "规则：\n"
                    + "- KEY 必须是以下英文标识符之一（区分大小写，原样返回）：" + validKeysList + "\n";
        } else if (endCheckWindow) {
            outputFormat = "请同时判断讨论是否应该结束（见系统提示词的【结束判断】规则）。\n\n"
                    + "请按以下格式返回（每行一个字段，不要输出 JSON、不要输出 markdown 代码块）：\n\n"
                    + "NEXT: 候选1, 候选2, 候选3\n"
                    + "END: true|false\n"
                    + "SUMMARY: 不超过200字的谢幕提纲（END=false 时留空）\n\n"
                    + "规则：\n"
                    + "- NEXT 行：列出 top-3 候选角色 key，用英文逗号分隔，按接话意愿从高到低排序\n"
                    + "- END 行：true 或 false\n"
                    + "- SUMMARY 行：END=true 时填写谢幕提纲，END=false 时留空\n"
                    + "- END=true 时 NEXT 必须只包含 HOST\n"
                    + "- KEY 必须是以下英文标识符之一（区分大小写，原样返回）：" + validKeysList + "\n";
        } else {
            outputFormat = "请按以下格式返回（每行一个字段，不要输出 JSON、不要输出 markdown 代码块）：\n\n"
                    + "NEXT: 候选1, 候选2, 候选3\n"
                    + "END: false\n"
                    + "SUMMARY: \n\n"
                    + "规则：\n"
                    + "- NEXT 行：列出 top-3 候选角色 key，用英文逗号分隔，按接话意愿从高到低排序\n"
                    + "- END 行：固定 false（本轮不判断是否结束）\n"
                    + "- SUMMARY 行：留空\n"
                    + "- KEY 必须是以下英文标识符之一（区分大小写，原样返回）：" + validKeysList + "\n";
        }
        messages.add(UserMessage.from(fairnessConstraints + "\n\n" + outputFormat));

        // 6. 调用 LLM
        try {
            String response = chatModelManager.callAiForScene(AiScene.ROUND_TABLE_SPEAKER_SELECT,
                    "圆桌派纯LLM发言人选择",
                    String.format("sessionId=%s", sessionId),
                    messages);
            response = CommonUtils.stripCodeFence(response).trim();

            // 解析候选集（top-3，容错：无 NEXT 行时回退到 parseLlmSpeakerResponse）
            List<String> candidates = parseCandidateKeysFromResponse(response, roleKeys, nameToKey);
            boolean shouldEnd = parseShouldEndFromResponse(response);
            String closingSummary = parseClosingSummaryFromResponse(response);

            // 覆盖度兜底：LLM 没判断 shouldEnd=true 时强制改
            if (coverageHighEnough && !shouldEnd) {
                shouldEnd = true;
                if (closingSummary == null || closingSummary.isBlank()) {
                    closingSummary = "话题覆盖度已达阈值，讨论已充分展开，请做总结谢幕。";
                }
                log.warn("覆盖度触发强制谢幕: sessionId={}, llmShouldEnd=false→true", sessionId);
            }

            // 机制兜底优先于 LLM：轮数上限 / 原地踏步
            if (forceEndByRule) {
                if (!shouldEnd) {
                    log.info("机制触发强制谢幕: sessionId={}, round={}, stalled={}, llmShouldEnd=false→true",
                            sessionId, currentRound, stalled);
                }
                shouldEnd = true;
                if (closingSummary == null || closingSummary.isBlank()) {
                    closingSummary = ruleClosingSummary;
                }
            }

            // shouldEnd=true 时强制 nextSpeaker=HOST，不走候选过滤
            String selectedKey;
            if (shouldEnd) {
                selectedKey = "HOST";
            } else if (forceHostByRule) {
                // 机制强制控场：绕过候选过滤，直接给主持人
                selectedKey = "HOST";
            } else {
                // 从候选中按公平性过滤选择
                selectedKey = selectSpeakerFromCandidates(candidates, lastSpeaker, speakCounts, roleKeys);
                if (selectedKey == null) {
                    throw new BusinessException("候选集为空，无法选择发言人");
                }
            }

            log.info("纯LLM选择发言人: sessionId={}, selected={}, candidates={}, shouldEnd={}, round={}, speakCounts={}",
                    sessionId, selectedKey, candidates, shouldEnd, currentRound, speakCounts);

            return NextSpeakerResult.builder()
                    .nextSpeaker(selectedKey)
                    .shouldEnd(shouldEnd)
                    .closingSummary(closingSummary != null ? closingSummary : "")
                    .hostHint(hostHint)
                    .build();
        } catch (Exception e) {
            log.warn("纯LLM发言人选择失败，回退到简单模式: {}", e.getMessage());
            // 机制兜底优先：即使 LLM 挂了，轮数上限/原地踏步也要能收尾
            if (forceEndByRule) {
                log.info("机制强制谢幕（LLM失败路径）: sessionId={}, round={}", sessionId, currentRound);
                return NextSpeakerResult.builder()
                        .nextSpeaker("HOST")
                        .shouldEnd(true)
                        .closingSummary(ruleClosingSummary)
                        .build();
            }
            if (forceHostByRule) {
                return NextSpeakerResult.builder()
                        .nextSpeaker("HOST")
                        .shouldEnd(false)
                        .closingSummary("")
                        .hostHint(hostHint)
                        .build();
            }
            // 覆盖度达标时即使 LLM 失败也强制结束，避免"覆盖度达标→LLM失败→回退→继续→覆盖度仍达标→..."死循环
            if (coverageHighEnough) {
                log.warn("覆盖度达标+LLM失败，强制谢幕: sessionId={}", sessionId);
                return NextSpeakerResult.builder()
                        .nextSpeaker("HOST")
                        .shouldEnd(true)
                        .closingSummary("话题覆盖度已达阈值，讨论已充分展开，请做总结谢幕。")
                        .build();
            }
            // 兜底：优先从嘉宾里选发言最少者（HOST 不参与公平性兜底）
            String fallbackSpeaker = Arrays.stream(roleKeys)
                    .map(String::trim)
                    .filter(k -> !k.equals(lastSpeaker) && !"HOST".equals(k))
                    .min(Comparator.comparingLong(k -> speakCounts.getOrDefault(k, 0L)))
                    .orElse(roleKeys[0].trim());
            return NextSpeakerResult.builder()
                    .nextSpeaker(fallbackSpeaker)
                    .shouldEnd(false)
                    .closingSummary("")
                    .build();
        }
    }

    /**
     * 从 LLM 返回中解析候选 key 列表（行式 KV 格式：`NEXT: key1, key2, key3`）
     * <p>
     * 容错策略：
     * 1. 有 NEXT 行 → split by [,，]（兼容中英文逗号），逐个 key 容错匹配
     * 2. 无 NEXT 行 → 回退到 parseLlmSpeakerResponse（子串匹配），返回单元素列表
     * 3. 解析出的 key 去重，保持顺序
     */
    private List<String> parseCandidateKeysFromResponse(String response, String[] validRoleKeys,
                                                         Map<String, String> nameToKey) {
        Set<String> validKeys = Arrays.stream(validRoleKeys).map(String::trim).collect(Collectors.toSet());
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 提取 NEXT: 行（大小写不敏感）
        Matcher m = Pattern.compile("(?im)^\\s*NEXT\\s*:\\s*(.+)$").matcher(response);
        if (m.find()) {
            String rest = m.group(1).trim();
            // split by 中英文逗号
            String[] parts = rest.split("[,，]");
            for (String part : parts) {
                String key = part.trim();
                if (key.isEmpty()) continue;
                // 去掉可能的列表前缀（如 "1." "- " "• "）
                key = key.replaceAll("^[\\d]+[.)\\-]\\s*", "").replaceAll("^[-•*]\\s*", "").trim();
                // 1. 直接匹配英文 key
                if (validKeys.contains(key)) {
                    if (seen.add(key)) result.add(key);
                    continue;
                }
                // 2. 反查中文名 → key
                String mapped = nameToKey.get(key);
                if (mapped != null) {
                    if (seen.add(mapped)) result.add(mapped);
                    continue;
                }
                // 3. 子串匹配英文 key（大小写不敏感）
                String upperKey = key.toUpperCase();
                for (String vk : validKeys) {
                    if (upperKey.contains(vk.toUpperCase()) && seen.add(vk)) {
                        result.add(vk);
                        break;
                    }
                }
            }
        }

        // NEXT 行没解析出任何候选 → 回退到旧解析（子串匹配整段文本）
        if (result.isEmpty()) {
            try {
                String single = parseLlmSpeakerResponse(response, validRoleKeys, nameToKey);
                if (single != null) result.add(single);
            } catch (Exception ignored) {
                // 旧解析也失败，返回空列表让上层兜底
            }
        }

        return result;
    }

    /**
     * 从候选列表中按公平性过滤选择发言人
     * <p>
     * HOST 不参与公平性体系：它只在 LLM 明确点名（排在候选第 1 位）时允许控场/谢幕，
     * 否则一律丢弃——避免主持人被当成"沉默救济对象"或平均发言者。
     * <p>
     * 过滤逻辑：
     * 1. 跳过 lastSpeaker（禁止连续发言）
     * 2. 丢弃非首位候选的 HOST（除非 LLM 首位点名为控场/谢幕）
     * 3. 跳过 speakCount > avg × 1.5 的角色（avg 只统计嘉宾，HOST 不计入）
     * 4. 从剩余候选中选 top-1（LLM 最想让他说的）
     * 5. 候选全部超标 → 选 candidates 中 speakCount 最少的
     * 6. 候选为空或全是 lastSpeaker → 回退到嘉宾中 speakCount 最少的非 lastSpeaker
     *
     * @param candidates  LLM 返回的 top-3 候选（按意愿排序）
     * @param lastSpeaker 上一位发言人
     * @param speakCounts 各角色发言次数统计
     * @param roleKeys    所有在场角色 key
     * @return 选中的角色 key，null 表示无可用候选（上层兜底）
     */
    private String selectSpeakerFromCandidates(List<String> candidates, String lastSpeaker,
                                                Map<String, Long> speakCounts, String[] roleKeys) {
        // HOST 仅当位候选首位时有效（LLM 明确要主持人控场/谢幕）
        boolean hostRequestedFirst = candidates != null && !candidates.isEmpty()
                && "HOST".equals(candidates.get(0));

        // 1. 过滤掉 lastSpeaker + 无效的 HOST
        List<String> filtered = candidates == null ? List.of() : candidates.stream()
                .filter(k -> !k.equals(lastSpeaker))
                .filter(k -> !"HOST".equals(k) || hostRequestedFirst)
                .collect(Collectors.toList());

        // 候选为空 → 回退到嘉宾中 speakCount 最少的非 lastSpeaker
        if (filtered.isEmpty()) {
            String fallback = Arrays.stream(roleKeys)
                    .map(String::trim)
                    .filter(k -> !k.equals(lastSpeaker) && !"HOST".equals(k))
                    .min(Comparator.comparingLong(k -> speakCounts.getOrDefault(k, 0L)))
                    .orElse(null);
            if (fallback != null) return fallback;
            // 场上无嘉宾可用时，才允许 HOST 兜底
            return Arrays.stream(roleKeys)
                    .map(String::trim)
                    .filter(k -> !k.equals(lastSpeaker))
                    .min(Comparator.comparingLong(k -> speakCounts.getOrDefault(k, 0L)))
                    .orElse(null);
        }

        // 2. 计算"均分份额"作为硬上限基准
        //    旧实现用平均值 × 1.5：说得越多平均值越高，上限跟着一起涨，等于没有上限。
        //    （实测 6 位嘉宾 173 条发言时 avg≈28.8、阈值 43.2，说满 42 条的刘强东始终"不超标"。）
        //    改成以均分份额为基准：每人应得 = 嘉宾总发言 / 嘉宾数，超过 1.25 倍视为垄断。
        long guestSpeakTotal = speakCounts.entrySet().stream()
                .filter(e -> !"HOST".equals(e.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
        long guestSeats = Arrays.stream(roleKeys)
                .map(String::trim)
                .filter(k -> !k.isEmpty() && !"HOST".equals(k))
                .count();
        double equalShare = guestSeats > 0 ? guestSpeakTotal / (double) guestSeats : 0;
        double threshold = Math.max(2.0, Math.ceil(equalShare * SPEAKER_OVERWEIGHT_RATIO));

        // 3. 过滤掉超标的（speakCount > 均分份额 × 1.25）
        List<String> notOverweight = filtered.stream()
                .filter(k -> speakCounts.getOrDefault(k, 0L) <= threshold)
                .collect(Collectors.toList());

        if (!notOverweight.isEmpty()) {
            // 从未超标候选中选 top-1
            return notOverweight.get(0);
        }

        // 4. 全部超标 → 选 candidates 中 speakCount 最少的
        return filtered.stream()
                .min(Comparator.comparingLong(k -> speakCounts.getOrDefault(k, 0L)))
                .orElse(filtered.get(0));
    }

    /**
     * 从 LLM 返回中解析 END 字段（行式 KV：`END: true|false`）
     * <p>
     * 容错：缺失或非 true 视为 false（继续讨论）
     */
    private boolean parseShouldEndFromResponse(String response) {
        Matcher m = Pattern.compile("(?im)^\\s*END\\s*:\\s*(\\w+)").matcher(response);
        if (m.find()) {
            String val = m.group(1).trim().toLowerCase();
            return "true".equals(val) || "yes".equals(val) || "1".equals(val) || "是".equals(val);
        }
        return false;
    }

    /**
     * 从 LLM 返回中解析 SUMMARY 字段（行式 KV：`SUMMARY: ...`）
     * <p>
     * 容错：缺失返回空串；支持多行内容（取到下一个 `KEY:` 行或文件末尾）
     */
    private String parseClosingSummaryFromResponse(String response) {
        // 匹配 SUMMARY: 后到下一个 "KEY:" 行开头或字符串末尾
        Matcher m = Pattern.compile("(?im)^\\s*SUMMARY\\s*:\\s*(.*(?:\\r?\\n(?!\\s*[A-Z_]+\\s*:).*)*)",
                Pattern.MULTILINE).matcher(response);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }

    /**
     * 构建「书籍背景 + 角色人设」消息块（会话内不变，放在 SystemMessage 之后作为第二条缓存消息）
     */
    private String buildBookAndRolesMessage(RoundTableSession session, Book book) {
        StringBuilder sb = new StringBuilder();

        // 讨论背景
        sb.append("【讨论背景】\n");
        if (book != null) {
            sb.append("书籍：《").append(book.getTitle()).append("》\n");
        }
        sb.append("主题：").append(session.getTitle()).append("\n\n");

        // 角色人设
        sb.append("【当前在场角色及其人设】\n");
        sb.append("重点关注「社交直觉与张力」中的天然警惕/天然共鸣/接话直觉，");
        sb.append("这些决定了谁在面对某类发言时会本能地想接话或反驳。\n\n");
        sb.append(buildRolesInfoForLLMSpeakerSelection(session));

        return sb.toString();
    }

    /**
     * 构建角色信息文本（用于纯 LLM 发言人选择）
     * <p>
     * 以 session.roleKeys（后端已自动补全 HOST + 默认角色）为准遍历，
     * 从 roleConfigs JSON 中匹配各角色的 session 专属参数（domainRelevance 等），
     * 未在 roleConfigs 中找到的角色使用 AiConfig.RoundTableRole 默认值。
     * <p>
     * 包含角色完整人设描述（从 AiConfig.RoundTableRole 读取），
     * 让 LLM 理解每个角色的身份、社交倾向、与谁冲突/共鸣，
     * 从而能根据【社交直觉与张力】中的「天然警惕」「天然共鸣」「接话直觉」
     * 来判断谁最适合接当前发言。
     */
    private String buildRolesInfoForLLMSpeakerSelection(RoundTableSession session) {
        StringBuilder sb = new StringBuilder();

        // 以 roleKeys 为准遍历（roleKeys 已含后端自动补全的 HOST + 默认角色）
        String[] roleKeys = session.getRoleKeys() != null
                ? session.getRoleKeys().split(",")
                : new String[0];
        if (roleKeys.length == 0) return "";

        // 解析 roleConfigs JSON，建立 key → JsonNode 映射
        Map<String, JsonNode> configMap = new java.util.LinkedHashMap<>();
        try {
            if (session.getRoleConfigs() != null && !session.getRoleConfigs().isBlank()) {
                var configs = objectMapper.readTree(session.getRoleConfigs());
                if (configs.isArray()) {
                    for (var config : configs) {
                        String key = config.has("key") ? config.get("key").asText() : "";
                        if (!key.isBlank()) {
                            configMap.put(key.trim(), config);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 roleConfigs 失败: {}", e.getMessage());
        }

        for (String key : roleKeys) {
            key = key.trim();
            if (key.isBlank()) continue;

            AiConfig.RoundTableRole role = aiConfigProvider.getRoundTableRole(key);
            if (role == null) {
                log.warn("未知角色 key: {}, 跳过", key);
                continue;
            }

            JsonNode config = configMap.get(key);
            String name = (config != null && config.has("name")) ? config.get("name").asText() : role.getName();
            String title = (config != null && config.has("title")) ? config.get("title").asText() : role.getTitle();
            int domainRelevance = (config != null && config.has("domainRelevance")) ? config.get("domainRelevance").asInt() : 0;
            String languageStyle = (config != null && config.has("languageStyle")) ? config.get("languageStyle").asText() : "";

            sb.append("### ").append(key).append("（").append(name).append("，").append(title).append("）\n");
            // 角色人设截断到 1500 字，保留核心身份+社交倾向+说话风格，去掉决策启发式/时间线等冗余内容
            String prompt = role.getPrompt();
            if (prompt != null && prompt.length() > 1500) {
                prompt = prompt.substring(0, 1500) + "……（已截断）";
            }
            sb.append(prompt).append("\n");
            // 本次讨论专属参数
            sb.append("【本次讨论专属】专业相关度=").append(domainRelevance);
            if (!languageStyle.isBlank()) {
                sb.append(" 语言风格=").append(languageStyle);
            }
            sb.append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 构建「发言人选择」专用的对话历史
     * <p>
     * 只保留最近 {@value #SPEAKER_SELECTION_RECENT_COUNT} 条消息的完整内容：
     * - 判断下一发言人主要靠"最近对话流"——谁刚发言、谁被质疑、话题朝哪个方向走
     * - 更早的消息对"谁该接话"判断价值极低（前 80 字符看不出立场，完整内容又占太多 token）
     * - 想知道"之前聊过什么"已有发言次数统计和覆盖度信息兜底
     * <p>
     * 权重标签仍保留：【当前焦点】最后一条 / 【近期讨论】倒数 2-5 条
     */
    private static final int SPEAKER_SELECTION_RECENT_COUNT = 5;

    private String buildWeightedHistoryForLLM(List<RoundTableMessage> allMessages) {
        if (allMessages.isEmpty()) {
            return "（暂无发言记录，这是讨论的开始）";
        }

        int total = allMessages.size();
        int start = Math.max(0, total - SPEAKER_SELECTION_RECENT_COUNT);

        StringBuilder sb = new StringBuilder();
        // 不足 5 条时（讨论初期）省略提示，直接列出即可
        if (start > 0) {
            sb.append("（为节省篇幅，仅展示最近 ").append(SPEAKER_SELECTION_RECENT_COUNT)
                    .append(" 条发言；更早的发言已省略）\n\n");
        }

        for (int i = start; i < total; i++) {
            RoundTableMessage msg = allMessages.get(i);
            String content = msg.getCompressedContent();
            if (content == null || content.isBlank()) {
                content = msg.getContent();
            }

            // 最近一条标"当前焦点"，其余标"近期讨论"
            String weightLabel = (i == total - 1) ? "【当前焦点】" : "【近期讨论】";
            sb.append(weightLabel).append(msg.getRoleName()).append("：").append(content).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 构建给 LLM 的覆盖度概览（用于纯 LLM 发言人选择的结束判断）
     * <p>
     * 提供量化覆盖度数据，让 LLM 判断 shouldEnd 时有量化依据，而非纯靠感觉。
     * 比 buildHostCoverageGuidance 精简——只列总分、概念覆盖、内容块覆盖、未覆盖概念，
     * 不含未覆盖块的摘要/关键词（避免消息过长、KV-cache 失效）。
     */
    private String buildCoverageBriefForLLM(RoundTableCoverage coverage) {
        StringBuilder sb = new StringBuilder();
        sb.append("【话题覆盖度】\n");

        double score = coverage.getOverallScore() != null ? coverage.getOverallScore() : 0.0;
        sb.append(String.format("总分：%.0f/100（等级 %s）\n", score,
                coverage.getGrade() != null ? coverage.getGrade() : "-"));

        // 概念覆盖
        int totalConcepts = coverage.getTotalConcepts() != null ? coverage.getTotalConcepts() : 0;
        int coveredConcepts = coverage.getCoveredConceptsCount() != null ? coverage.getCoveredConceptsCount() : 0;
        if (totalConcepts > 0) {
            sb.append(String.format("概念覆盖：%d/%d\n", coveredConcepts, totalConcepts));
            List<String> missed = roleService.parseTags(coverage.getMissedConceptsJson());
            if (!missed.isEmpty()) {
                sb.append("未覆盖概念：").append(String.join("、", missed)).append("\n");
            }
        }

        // 内容块覆盖
        int totalBlocks = coverage.getTotalBlocks() != null ? coverage.getTotalBlocks() : 0;
        int coveredBlocks = coverage.getCoveredBlocks() != null ? coverage.getCoveredBlocks() : 0;
        if (totalBlocks > 0) {
            sb.append(String.format("内容块覆盖：%d/%d\n", coveredBlocks, totalBlocks));
        }

        return sb.toString();
    }

    /**
     * 构建公平性约束说明（用于纯 LLM 发言人选择）
     * <p>
     * 公平性约束仅作为兜底信息提供，不强制 LLM 必须执行。
     * 对话流向是首要选择依据，公平性只在 LLM 无法判断时起参考作用。
     */
    private String buildFairnessConstraints(String[] roleKeys, Map<String, Long> speakCounts,
                                            List<RoundTableMessage> allMessages) {
        StringBuilder sb = new StringBuilder();

        // 1. 禁止连续发言（硬约束）
        if (!allMessages.isEmpty()) {
            String lastSpeaker = allMessages.get(allMessages.size() - 1).getRoleKey();
            AiConfig.RoundTableRole lastRole = aiConfigProvider.getRoundTableRole(lastSpeaker);
            sb.append("- 【硬约束】").append(lastRole != null ? lastRole.getName() : lastSpeaker)
                    .append("刚发过言，不能连续发言\n");
        }

        // 1.5 复读告警：刚发言者如果只是在重复前面的观点，明确告诉选人模型，别顺着复读往下接
        if (allMessages.size() >= 3) {
            RoundTableMessage last = allMessages.get(allMessages.size() - 1);
            int start = Math.max(0, allMessages.size() - 9);
            for (int i = start; i < allMessages.size() - 1; i++) {
                if (SpeechSanitizer.similarity(last.getContent(), allMessages.get(i).getContent()) >= 0.6) {
                    sb.append("- 【警告】").append(last.getRoleName())
                            .append("刚才那段和前面某条高度重复，只是在复读。不要再接这个点，")
                            .append("选一个能提出全新角度的人，或让 HOST 点出重复。\n");
                    break;
                }
            }
        }

        // 2. 发言次数统计（只统计嘉宾，从少到多；HOST 不参与公平性体系）
        sb.append("- 嘉宾发言次数（供参考）：");
        Arrays.stream(roleKeys)
                .map(String::trim)
                .filter(k -> !"HOST".equals(k))
                .sorted(Comparator.comparingLong(k -> speakCounts.getOrDefault(k, 0L)))
                .forEach(key -> {
                    AiConfig.RoundTableRole role = aiConfigProvider.getRoundTableRole(key);
                    long count = speakCounts.getOrDefault(key, 0L);
                    sb.append(role != null ? role.getName() : key).append("=").append(count).append(" ");
                });
        sb.append("\n");

        // 3. 仅当有角色从未发言且讨论已过半程时，才标记为强制优先
        int totalRounds = allMessages.size();
        int nonHostCount = (int) Arrays.stream(roleKeys).filter(k -> !"HOST".equals(k.trim())).count();
        if (totalRounds >= nonHostCount) {
            Set<String> neverSpoken = Arrays.stream(roleKeys)
                    .map(String::trim)
                    .filter(key -> !"HOST".equals(key) && !speakCounts.containsKey(key))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!neverSpoken.isEmpty()) {
                sb.append("- 【强制优先】以下角色至今未发言：");
                for (String key : neverSpoken) {
                    AiConfig.RoundTableRole role = aiConfigProvider.getRoundTableRole(key);
                    if (role != null) sb.append(role.getName()).append("(").append(key).append(") ");
                }
                sb.append("（从其中选最能接话的）\n");
            }
        }

        return sb.toString();
    }

    /**
     * 解析 LLM 返回的发言人选择结果。
     * <p>
     * 支持三种返回形式（容错优先级）：
     * 1. JSON {"nextSpeaker": "KEY"} 中 KEY 为英文标识符 → 直接匹配
     * 2. JSON {"nextSpeaker": "中文名"} → 通过 nameToKey 反查
     * 3. 非 JSON 文本 → 子串匹配英文 key 或中文名
     * <p>
     * 无法解析出有效角色时抛异常，让外层回退到简单模式。
     *
     * @param response     LLM 原始返回（已去代码块围栏）
     * @param validRoleKeys 合法角色 key 数组（来自 session.roleKeys）
     * @param nameToKey    角色中文名 → key 反向映射（预置+自定义角色）
     */
    private String parseLlmSpeakerResponse(String response, String[] validRoleKeys,
                                           Map<String, String> nameToKey) {
        Set<String> validKeys = Arrays.stream(validRoleKeys).map(String::trim).collect(Collectors.toSet());

        // 尝试解析 JSON {"nextSpeaker": "..."}
        try {
            var jsonNode = objectMapper.readTree(response);
            if (jsonNode.has("nextSpeaker")) {
                String key = jsonNode.get("nextSpeaker").asText().trim();
                // 1. 直接匹配英文 key
                if (validKeys.contains(key)) return key;
                // 2. 反查中文名 → key（LLM 可能把历史/约束里的中文名当 key 返回）
                String mapped = nameToKey.get(key);
                if (mapped != null) return mapped;
                // JSON 解析成功但既不是合法 key 也不是已知中文名 → 抛，不进入子串匹配
                throw new BusinessException("LLM返回了不在场角色: " + key);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception ignored) {
            // JSON 解析失败，进入子串匹配兜底
        }

        // 3a. 子串匹配英文 key（大小写不敏感）
        String upperResponse = response.toUpperCase();
        for (String key : validKeys) {
            if (upperResponse.contains(key.toUpperCase())) {
                return key;
            }
        }

        // 3b. 子串匹配中文名
        for (Map.Entry<String, String> entry : nameToKey.entrySet()) {
            if (response.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        // LLM 返回了数据但不在在场角色中 → 抛异常，让外层回退简单模式
        throw new BusinessException("LLM返回的角色不在在场角色列表中: " + response);
    }

    /**
     * 构建 角色中文名 → key 的反向映射（用于 LLM 返回中文名时的容错解析）。
     * <p>
     * 同时覆盖两类角色：
     * - 预置角色：name 来自 AiConfig.RoundTableRole
     * - 自定义角色：name 来自 session.roleConfigs JSON（自定义角色不在 ai-config.json 中）
     * <p>
     * roleConfigs 中的 name 优先于 AiConfig 默认 name（允许会话级覆盖）。
     */
    private Map<String, String> buildNameToKeyMap(RoundTableSession session, String[] roleKeys) {
        Map<String, String> nameToKey = new HashMap<>();

        // 解析 roleConfigs JSON，建立 key → JsonNode 映射（与 buildRolesInfoForLLMSpeakerSelection 同源）
        Map<String, JsonNode> configMap = new LinkedHashMap<>();
        try {
            if (session.getRoleConfigs() != null && !session.getRoleConfigs().isBlank()) {
                var configs = objectMapper.readTree(session.getRoleConfigs());
                if (configs.isArray()) {
                    for (var config : configs) {
                        String key = config.has("key") ? config.get("key").asText() : "";
                        if (!key.isBlank()) {
                            configMap.put(key.trim(), config);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 roleConfigs 失败: {}", e.getMessage());
        }

        for (String key : roleKeys) {
            String k = key.trim();
            if (k.isBlank()) continue;

            AiConfig.RoundTableRole role = aiConfigProvider.getRoundTableRole(k);
            JsonNode config = configMap.get(k);
            // roleConfigs 中的 name 优先，其次 AiConfig 默认 name
            String name = (config != null && config.has("name")) ? config.get("name").asText()
                    : (role != null ? role.getName() : null);
            if (name != null && !name.isBlank()) {
                nameToKey.put(name.trim(), k);
            }
        }
        return nameToKey;
    }

    /**
     * 解析书籍核心概念标签为可匹配关键词（含复合标签的分词）。
     */
    private List<String> parseConceptKeywords(String conceptTags) {
        List<String> out = new ArrayList<>();
        if (conceptTags == null || conceptTags.isBlank()) return out;
        try {
            JsonNode arr = objectMapper.readTree(conceptTags);
            if (arr.isArray()) {
                for (JsonNode n : arr) addConceptKeyword(out, n.asText());
                return out;
            }
        } catch (Exception ignored) {
            // 不是 JSON，走下面的分隔符拆分
        }
        for (String part : conceptTags.replaceAll("[\\[\\]\"]", "").split("[,，、]")) {
            addConceptKeyword(out, part);
        }
        return out;
    }

    private void addConceptKeyword(List<String> out, String tag) {
        if (tag == null) return;
        String t = tag.trim();
        if (t.length() < 2) return;
        if (!out.contains(t)) out.add(t);
        // "管制与干预成本" 这类复合标签拆开，才能命中发言里实际写的"管制成本"
        for (String part : t.split("[与和、/]")) {
            String p = part.trim();
            if (p.length() >= 2 && !out.contains(p)) out.add(p);
        }
    }

    /**
     * 书外话题判定：最近几条嘉宾发言没有任何一条提到书里的核心概念。
     */
    private boolean isOffBook(List<RoundTableMessage> recent, Book book) {
        if (book == null || recent == null || recent.isEmpty()) return false;
        List<String> keywords = parseConceptKeywords(book.getConceptTags());
        if (keywords.isEmpty()) return false;
        for (RoundTableMessage m : recent) {
            String c = m.getContent() == null ? "" : m.getContent();
            for (String k : keywords) {
                if (c.contains(k)) return false;
            }
        }
        return true;
    }

    /**
     * 原地踏步判定：最近两条高度相似，或最新一条与此前 8 条中任何一条高度重复。
     */
    private boolean isStalled(List<RoundTableMessage> all, List<RoundTableMessage> recent) {
        if (recent == null || recent.size() < 2 || all == null || all.isEmpty()) return false;
        String latest = recent.get(recent.size() - 1).getContent();
        String previous = recent.get(recent.size() - 2).getContent();
        if (SpeechSanitizer.similarity(latest, previous) >= STALL_SIMILARITY_THRESHOLD) return true;

        int start = Math.max(0, all.size() - 9);
        for (int i = start; i < all.size() - 1; i++) {
            RoundTableMessage m = all.get(i);
            if ("HOST".equals(m.getRoleKey())) continue;
            if (SpeechSanitizer.similarity(latest, m.getContent()) >= 0.6) return true;
        }
        return false;
    }

    /**
     * 给被强制叫出来的主持人一句具体指令——只说"你该控场了"没有用，得告诉他为什么、拉回哪一节。
     */
    private String buildHostHint(String sessionId, int guestMessagesSinceHost, boolean offBook, boolean stalled) {
        StringBuilder sb = new StringBuilder();
        sb.append("已经连续 ").append(guestMessagesSinceHost).append(" 条嘉宾发言没有主持人控场");
        if (offBook) sb.append("，而且大家在聊书外的事，已经偏离了这本书");
        if (stalled) sb.append("，观点开始原地打转，在重复同一套说法");

        List<String> titles = speechService.loadUncoveredTitles(sessionId);
        if (!titles.isEmpty()) {
            sb.append("。请点名一位嘉宾，把话题拉回书中《").append(titles.get(0)).append("》这一节讲的现象");
        } else {
            sb.append("。请点名一位嘉宾，让他就刚才的争议给出一个具体的、书里的例子");
        }
        sb.append("。一两句话即可，不要总结全场。");
        return sb.toString();
    }
}
