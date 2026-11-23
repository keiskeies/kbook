package com.kbook.service.debate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.util.CommonUtils;
import com.kbook.config.properties.DebateProperties;
import com.kbook.constants.ai.DebatePrompts;
import com.kbook.dto.debate.DebateDossierVO;
import com.kbook.dto.debate.DebateDossierVO.BattleStateVO;
import com.kbook.dto.debate.DebateDossierVO.ChallengeVO;
import com.kbook.dto.debate.DebateDossierVO.FocalPointVO;
import com.kbook.entity.AiScene;
import com.kbook.entity.Book;
import com.kbook.entity.debate.DebateDossier;
import com.kbook.repository.debate.DebateDossierRepository;
import com.kbook.service.ai.core.ChatModelManager;
import com.kbook.service.book.BookService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 辩论论点弹药库服务 — 深度模式的核心基础设施。
 * <p>
 * 职责：
 * <ol>
 *   <li>会话创建后异步生成 3-5 个分歧点（基于书的结构化摘要，不含原文）</li>
 *   <li>为发言构建注入文本（分歧点 + 双方论证方向 + 章节定位 + 战场状态）</li>
 *   <li>每轮发言后异步更新战场状态</li>
 *   <li>报告生成时提供逐点裁决文本</li>
 * </ol>
 * <p>
 * 所有 LLM 调用、JSON 解析、DB 读写均 try-catch 降级，绝不影响辩论主流程。
 */
@Slf4j
@Service
public class DebateDossierService {

    private static final String STATUS_GENERATING = "GENERATING";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_FAILED = "FAILED";

    private final BookService bookService;
    private final ChatModelManager chatModelManager;
    private final ObjectMapper objectMapper;
    private final DebateDossierRepository dossierRepository;
    private final DebateProperties debateProperties;

    /** 每个会话一条串行更新链，防止并发 LLM 更新互相覆盖（key=sessionId） */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> pendingUpdates = new ConcurrentHashMap<>();

    public DebateDossierService(BookService bookService,
                                ChatModelManager chatModelManager,
                                ObjectMapper objectMapper,
                                DebateDossierRepository dossierRepository,
                                DebateProperties debateProperties) {
        this.bookService = bookService;
        this.chatModelManager = chatModelManager;
        this.objectMapper = objectMapper;
        this.dossierRepository = dossierRepository;
        this.debateProperties = debateProperties;
    }

    // ================================================================
    // 公开方法（契约 §1.4）
    // ================================================================

    /**
     * 会话创建成功后调用，内部 @Async 生成，不阻塞；任何异常内部吞掉。
     */
    @Async
    public void generateAsync(Long userId, Long bookId, String sessionId,
                              String topic, String proArgument, String conArgument) {
        if (!debateProperties.isDeepEnabled()) {
            return;
        }
        try {
            DebateDossier dossier = dossierRepository.findBySessionId(sessionId)
                    .orElseGet(() -> DebateDossier.builder().sessionId(sessionId).build());
            dossier.setStatus(STATUS_GENERATING);
            dossierRepository.save(dossier);
        } catch (Exception e) {
            log.warn("弹药库初始化落库失败: sessionId={} - {}", sessionId, e.getMessage());
            return;
        }

        try {
            String bookInfo = buildBookInfo(bookId);
            String userPrompt = String.format("""
                    辩题：%s
                    正方初始观点：%s
                    反方初始观点：%s

                    书籍结构化信息（仅摘要/目录/简介，不含原文）：
                    %s""",
                    nvl(topic), nvl(proArgument), nvl(conArgument), bookInfo);

            String result = chatModelManager.callAiForScene(AiScene.DEBATE_DOSSIER, "辩论论点弹药库生成",
                    String.format("sessionId=%s, userId=%s, bookId=%s", sessionId, userId, bookId),
                    List.of(SystemMessage.from(DebatePrompts.DEBATE_DOSSIER_SYSTEM_PROMPT),
                            UserMessage.from(userPrompt)));

            List<FocalPointVO> focalPoints = parseFocalPoints(result);
            if (focalPoints.isEmpty()) {
                throw new IllegalStateException("LLM 未返回有效分歧点");
            }

            BattleStateVO battleState = BattleStateVO.builder()
                    .currentFocalPointId(focalPoints.get(0).getId())
                    .unansweredChallenges(new ArrayList<>())
                    .brokenPoints(new ArrayList<>())
                    .establishedPoints(new ArrayList<>())
                    .build();

            DebateDossier dossier = dossierRepository.findBySessionId(sessionId).orElse(null);
            if (dossier == null) {
                dossier = DebateDossier.builder().sessionId(sessionId).build();
            }
            dossier.setFocalPoints(objectMapper.writeValueAsString(focalPoints));
            dossier.setBattleState(objectMapper.writeValueAsString(battleState));
            dossier.setStatus(STATUS_READY);
            dossierRepository.save(dossier);
            log.info("辩论弹药库生成完成: sessionId={}, focalPoints={}", sessionId, focalPoints.size());
        } catch (Exception e) {
            log.warn("辩论弹药库生成失败，标记 FAILED: sessionId={} - {}", sessionId, e.getMessage());
            markFailed(sessionId);
        }
    }

    /**
     * 查询；不存在返回 null，不抛异常。
     */
    public DebateDossierVO getBySessionId(String sessionId) {
        try {
            DebateDossier dossier = dossierRepository.findBySessionId(sessionId).orElse(null);
            if (dossier == null) {
                return null;
            }
            return DebateDossierVO.builder()
                    .sessionId(dossier.getSessionId())
                    .status(dossier.getStatus())
                    .focalPoints(parseFocalPoints(dossier.getFocalPoints()))
                    .battleState(parseBattleState(dossier.getBattleState()))
                    .build();
        } catch (Exception e) {
            log.warn("弹药库查询失败: sessionId={} - {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 构建发言注入文本。无 dossier / status != READY / 任何异常 → 返回 null（B 回退旧路径）。
     *
     * @param side PRO/CON
     */
    public String buildInjectionText(String sessionId, String side) {
        try {
            // 发言构建前等待上一轮战场状态写回，否则本轮会读到滞后状态，
            // 「必须先回应未决质疑」的约束在正常语速下永远不生效。
            awaitPendingUpdates(sessionId, 15);
            DebateDossierVO dossier = getBySessionId(sessionId);
            if (dossier == null || !STATUS_READY.equals(dossier.getStatus())
                    || dossier.getFocalPoints() == null || dossier.getFocalPoints().isEmpty()) {
                return null;
            }
            BattleStateVO battleState = dossier.getBattleState() != null
                    ? dossier.getBattleState() : BattleStateVO.builder().build();
            FocalPointVO focal = resolveCurrentFocalPoint(dossier.getFocalPoints(), battleState);
            if (focal == null) {
                return null;
            }

            boolean isPro = "PRO".equalsIgnoreCase(side);
            StringBuilder sb = new StringBuilder();
            sb.append("【当前分歧点】").append(nvl(focal.getTitle())).append('\n');
            sb.append("【双方论证方向】\n");
            sb.append("- 正方：").append(nvl(focal.getProThesis()))
                    .append(isPro ? "（己方论证方向，重点展开）" : "").append('\n');
            sb.append("- 反方：").append(nvl(focal.getConThesis()))
                    .append(!isPro ? "（己方论证方向，重点展开）" : "").append('\n');
            sb.append("【书中章节】").append(nvl(focal.getBookAnchor())).append('\n');
            sb.append("【关键概念】").append(join(focal.getConcepts())).append('\n');
            sb.append("【战场状态】\n");
            sb.append("尚未回应的质疑：").append(describeChallenges(battleState.getUnansweredChallenges())).append('\n');
            sb.append("已被击穿的论点：").append(join(battleState.getBrokenPoints())).append('\n');
            sb.append("已确立的论点：").append(join(battleState.getEstablishedPoints())).append('\n');
            sb.append("【发言硬约束】必须先回应尚未回应的质疑，再围绕当前分歧点展开己方论证；")
                    .append("禁止输出书中连续 20 字以上的原文，只做概括、评论、转述；")
                    .append("书中定位只写章节名或序号。");
            return sb.toString();
        } catch (Exception e) {
            log.warn("发言注入文本构建失败: sessionId={}, side={} - {}", sessionId, side, e.getMessage());
            return null;
        }
    }

    /**
     * 发言落库后 @Async 更新战场状态；任何异常内部吞掉。
     */
    @Async
    public void updateBattleStateAsync(String sessionId, String positionKey,
                                       String side, String content) {
        // 同一会话的多次更新串行化：每轮都基于上一轮写回后的状态做读-改-写，
        // 避免 500ms 间隔内多个 LLM 调用并发读同一快照造成后写覆盖先写。
        CompletableFuture<Void> chain;
        synchronized (pendingUpdates) {
            CompletableFuture<Void> prev = pendingUpdates.get(sessionId);
            CompletableFuture<Void> base = prev == null
                    ? CompletableFuture.completedFuture(null)
                    : prev.handle((r, e) -> null);
            chain = base.thenRun(() -> doUpdateBattleState(sessionId, positionKey, side, content));
            // LLM 调用挂起时不能让后续所有发言都卡在等待上：超时后异常完成并清理，
            // doUpdateBattleState 内部若迟到写回不再阻塞流程。
            chain.orTimeout(120, TimeUnit.SECONDS);
            pendingUpdates.put(sessionId, chain);
        }
        chain.whenComplete((r, e) -> {
            synchronized (pendingUpdates) {
                pendingUpdates.computeIfPresent(sessionId, (k, cur) -> cur == chain ? null : cur);
            }
        });
    }

    /**
     * 等待该会话所有 pending 状态更新完成；超时只 WARN，不抛异常。
     * 供发言注入与报告生成前对齐状态。
     */
    public void awaitPendingUpdates(String sessionId, int timeoutSeconds) {
        CompletableFuture<Void> pending;
        synchronized (pendingUpdates) {
            pending = pendingUpdates.get(sessionId);
        }
        if (pending == null) {
            return;
        }
        try {
            pending.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待战场状态更新被中断: sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("等待战场状态更新超时: sessionId={} - {}", sessionId, e.getMessage());
        }
    }

    /**
     * 把当前分歧点切到发言序列指定的 id（焦点唯一真相源）。
     * 同步执行；仅当 dossier READY 且 id 合法时生效，异常吞掉。
     */
    public void focusFocalPoint(String sessionId, Integer focalId) {
        if (focalId == null) {
            return;
        }
        try {
            awaitPendingUpdates(sessionId, 15);
            DebateDossier dossier = dossierRepository.findBySessionId(sessionId).orElse(null);
            if (dossier == null || !STATUS_READY.equals(dossier.getStatus())) {
                return;
            }
            List<FocalPointVO> points = parseFocalPoints(dossier.getFocalPoints());
            if (points.stream().noneMatch(p -> focalId.equals(p.getId()))) {
                return;
            }
            BattleStateVO state = parseBattleState(dossier.getBattleState());
            if (state == null) {
                state = BattleStateVO.builder().build();
            }
            if (focalId.equals(state.getCurrentFocalPointId())) {
                return;
            }
            state.setCurrentFocalPointId(focalId);
            dossier.setBattleState(objectMapper.writeValueAsString(state));
            dossierRepository.save(dossier);
        } catch (Exception e) {
            log.warn("切换当前分歧点失败: sessionId={}, focalId={} - {}", sessionId, focalId, e.getMessage());
        }
    }

    private void doUpdateBattleState(String sessionId, String positionKey,
                                     String side, String content) {
        try {
            DebateDossierVO dossier = getBySessionId(sessionId);
            if (dossier == null || !STATUS_READY.equals(dossier.getStatus())
                    || dossier.getFocalPoints() == null || dossier.getFocalPoints().isEmpty()) {
                return;
            }
            BattleStateVO current = dossier.getBattleState() != null
                    ? dossier.getBattleState() : BattleStateVO.builder().build();
            FocalPointVO focal = resolveCurrentFocalPoint(dossier.getFocalPoints(), current);

            String userPrompt = String.format("""
                    当前分歧点：%s
                    当前战场状态：%s
                    本轮发言：%s（%s方）
                    发言内容：%s

                    请基于本轮发言更新战场状态，只输出 JSON。""",
                    focal != null ? nvl(focal.getTitle()) : "无",
                    toJsonQuietly(current), nvl(positionKey), nvl(side), nvl(content));

            String result = chatModelManager.callAiForScene(AiScene.DEBATE_BATTLEFIELD, "辩论战场状态更新",
                    String.format("sessionId=%s, positionKey=%s", sessionId, positionKey),
                    List.of(SystemMessage.from(DebatePrompts.DEBATE_BATTLEFIELD_SYSTEM_PROMPT),
                            UserMessage.from(userPrompt)));

            BattleStateVO next = parseBattleState(result);
            if (next == null || !hasSignal(next)) {
                log.warn("战场状态解析为空，跳过更新: sessionId={}", sessionId);
                return;
            }
            sanitize(next, dossier.getFocalPoints());
            // 焦点由发言序列通过 focusFocalPoint 显式驱动，LLM 只负责质疑/击穿/确立列表。
            // 不允许 LLM 自行切换，否则会和前端追问链、自由辩分块的焦点打架。
            if (current.getCurrentFocalPointId() != null) {
                next.setCurrentFocalPointId(current.getCurrentFocalPointId());
            }

            DebateDossier entity = dossierRepository.findBySessionId(sessionId).orElse(null);
            if (entity == null) {
                return;
            }
            entity.setBattleState(objectMapper.writeValueAsString(next));
            dossierRepository.save(entity);
        } catch (Exception e) {
            log.warn("战场状态更新失败（跳过）: sessionId={}, positionKey={} - {}",
                    sessionId, positionKey, e.getMessage());
        }
    }

    /**
     * 报告生成时的「分歧点裁决」文本；无 dossier 返回空串。
     */
    public String buildVerdictText(String sessionId) {
        try {
            DebateDossierVO dossier = getBySessionId(sessionId);
            if (dossier == null || dossier.getFocalPoints() == null || dossier.getFocalPoints().isEmpty()) {
                return "";
            }
            String userPrompt = String.format("""
                    分歧点列表：%s
                    最终战场状态：%s""",
                    toJsonQuietly(dossier.getFocalPoints()), toJsonQuietly(dossier.getBattleState()));

            String result = chatModelManager.callAiForScene(AiScene.DEBATE_REPORT, "辩论分歧点裁决",
                    "sessionId=" + sessionId,
                    List.of(SystemMessage.from(DebatePrompts.DEBATE_VERDICT_SYSTEM_PROMPT),
                            UserMessage.from(userPrompt)));
            if (result == null || result.isBlank()) {
                return "";
            }
            return CommonUtils.stripCodeFence(result).trim();
        } catch (Exception e) {
            log.warn("分歧点裁决生成失败: sessionId={} - {}", sessionId, e.getMessage());
            return "";
        }
    }

    // ================================================================
    // 私有辅助
    // ================================================================

    /** 构建书籍结构化信息（摘要优先 compressedSummary，兜底 chapterSummary） */
    private String buildBookInfo(Long bookId) {
        try {
            Book book = bookService.getBookById(bookId);
            if (book == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("书名：《").append(nvl(book.getTitle())).append("》\n");
            if (book.getDescription() != null && !book.getDescription().isBlank()) {
                sb.append("简介：").append(book.getDescription()).append("\n");
            }
            if (book.getToc() != null && !book.getToc().isBlank()) {
                sb.append("目录：\n").append(book.getToc()).append("\n");
            }
            if (book.getCompressedSummary() != null && !book.getCompressedSummary().isBlank()) {
                sb.append("【图书精炼摘要】\n").append(book.getCompressedSummary()).append("\n");
            } else if (book.getChapterSummary() != null && !book.getChapterSummary().isBlank()) {
                sb.append("【章节摘要】\n").append(book.getChapterSummary()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("书籍结构化信息读取失败: bookId={} - {}", bookId, e.getMessage());
            return "";
        }
    }

    /** 标记失败，独立 try-catch，绝不让降级路径再抛异常 */
    private void markFailed(String sessionId) {
        try {
            DebateDossier dossier = dossierRepository.findBySessionId(sessionId).orElse(null);
            if (dossier != null) {
                dossier.setStatus(STATUS_FAILED);
                dossierRepository.save(dossier);
            }
        } catch (Exception e) {
            log.warn("弹药库失败状态落库失败: sessionId={} - {}", sessionId, e.getMessage());
        }
    }

    /** 解析分歧点 JSON（数组）；容错：去代码围栏 + 截取首个 [...] */
    private List<FocalPointVO> parseFocalPoints(String text) {
        List<FocalPointVO> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        try {
            String json = extractJson(text, '[', ']');
            if (json == null) {
                return result;
            }
            List<FocalPointVO> parsed = objectMapper.readValue(json,
                    new TypeReference<List<FocalPointVO>>() {});
            if (parsed == null) {
                return result;
            }
            for (int i = 0; i < parsed.size(); i++) {
                FocalPointVO point = parsed.get(i);
                if (point == null) {
                    continue;
                }
                // 顺序化 id，保证 battlefield.currentFocalPointId 始终可定位
                point.setId(i + 1);
                if (point.getConcepts() == null) {
                    point.setConcepts(new ArrayList<>());
                }
                result.add(point);
            }
        } catch (Exception e) {
            log.warn("分歧点 JSON 解析失败: {}", e.getMessage());
        }
        return result;
    }

    /** 解析战场状态 JSON（对象）；容错：去代码围栏 + 截取首个 {...} */
    private BattleStateVO parseBattleState(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            String json = extractJson(text, '{', '}');
            if (json == null) {
                return null;
            }
            BattleStateVO state = objectMapper.readValue(json, BattleStateVO.class);
            if (state == null) {
                return null;
            }
            List<ChallengeVO> challenges = state.getUnansweredChallenges();
            if (challenges != null) {
                challenges.removeIf(c -> c == null);
                for (ChallengeVO c : challenges) {
                    if (c.getFrom() == null) c.setFrom("");
                    if (c.getText() == null) c.setText("");
                    if (c.getTargetSide() == null) c.setTargetSide("");
                }
            }
            return state;
        } catch (Exception e) {
            log.warn("战场状态 JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 去掉代码围栏后截取成对括号内容（支持前后带说明文字） */
    private String extractJson(String text, char open, char close) {
        String cleaned = CommonUtils.stripCodeFence(text);
        int start = cleaned.indexOf(open);
        int end = cleaned.lastIndexOf(close);
        if (start < 0 || end <= start) {
            return null;
        }
        return cleaned.substring(start, end + 1).trim();
    }

    /**
     * 解析出的战场状态是否含有效信号（避免空 JSON 覆盖旧状态）。
     * 刻意不看 currentFocalPointId：战场 prompt 要求原样回传这个字段，
     * 若只带回它而三个列表都空，说明生成/解析不完整，必须用旧状态兜底，
     * 否则一轮偷懒就把累积的质疑、击穿、确立点全清空。
     */
    private boolean hasSignal(BattleStateVO state) {
        return (state.getUnansweredChallenges() != null && !state.getUnansweredChallenges().isEmpty())
                || (state.getBrokenPoints() != null && !state.getBrokenPoints().isEmpty())
                || (state.getEstablishedPoints() != null && !state.getEstablishedPoints().isEmpty());
    }

    /** 兜底字段 + 把焦点 id 收敛到现有分歧点范围内 */
    private void sanitize(BattleStateVO state, List<FocalPointVO> focalPoints) {
        if (state.getUnansweredChallenges() == null) {
            state.setUnansweredChallenges(new ArrayList<>());
        }
        if (state.getBrokenPoints() == null) {
            state.setBrokenPoints(new ArrayList<>());
        }
        if (state.getEstablishedPoints() == null) {
            state.setEstablishedPoints(new ArrayList<>());
        }
        if (focalPoints != null && !focalPoints.isEmpty()) {
            Integer current = state.getCurrentFocalPointId();
            boolean valid = current != null && focalPoints.stream()
                    .anyMatch(p -> current.equals(p.getId()));
            if (!valid) {
                state.setCurrentFocalPointId(focalPoints.get(0).getId());
            }
        }
    }

    /** 取当前分歧点；id 对不上时回退第一个 */
    private FocalPointVO resolveCurrentFocalPoint(List<FocalPointVO> focalPoints, BattleStateVO state) {
        if (focalPoints == null || focalPoints.isEmpty()) {
            return null;
        }
        Integer currentId = state != null ? state.getCurrentFocalPointId() : null;
        if (currentId != null) {
            for (FocalPointVO p : focalPoints) {
                if (currentId.equals(p.getId())) {
                    return p;
                }
            }
        }
        return focalPoints.get(0);
    }

    private String describeChallenges(List<ChallengeVO> challenges) {
        if (challenges == null || challenges.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < challenges.size(); i++) {
            ChallengeVO c = challenges.get(i);
            if (i > 0) sb.append("；");
            sb.append("[").append(nvl(c.getFrom())).append("→").append(nvl(c.getTargetSide())).append("] ")
                    .append(nvl(c.getText()));
        }
        return sb.toString();
    }

    private String join(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "无";
        }
        return String.join("；", items);
    }

    private String toJsonQuietly(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }
}
