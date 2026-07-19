package com.kbook.service.ai;

import com.kbook.common.exception.BusinessException;
import com.kbook.config.annotation.RedisLock;
import com.kbook.constants.AiPromptConstants;
import com.kbook.entity.AiScene;
import com.kbook.entity.AiProviderConfig;
import com.kbook.entity.Book;
import com.kbook.entity.RoundTableMessage;
import com.kbook.entity.RoundTableReport;
import com.kbook.entity.RoundTableSession;
import com.kbook.repository.BookRepository;
import com.kbook.repository.RoundTableMessageRepository;
import com.kbook.repository.RoundTableReportRepository;
import com.kbook.repository.RoundTableSessionRepository;
import com.kbook.service.notification.NotificationService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.kbook.common.util.QueryBuilder.eq;

/**
 * 圆桌派解读报告服务
 * <p>
 * 从测试工具 RoundTableInterpretationTool 提取核心逻辑，
 * 支持异步生成解读报告并通过站内信通知用户。
 * <p>
 * 幂等保证：
 * - @RedisLock 防止同一会话并发触发
 * - GENERATING 状态直接返回，不重复生成
 * - FAILED 状态才允许重新触发
 * - LLM 调用内置 3 次重试
 */
@Slf4j
@Service
public class RoundTableReportService {

    private static final int LLM_MAX_RETRIES = 3;
    /** GENERATING 状态超过此时间（分钟）视为宕机残留，自动标记 FAILED */
    private static final int STALE_THRESHOLD_MINUTES = 10;
    /** token 到字符的转换比（中文约 1.5 字符/token） */
    private static final double TOKEN_TO_CHAR_RATIO = 1.5;
    /** 系统默认 maxTokens（配置为空时的兜底值） */
    private static final int DEFAULT_MAX_TOKENS = 32768;

    private final RoundTableReportRepository reportRepository;
    private final RoundTableSessionRepository sessionRepository;
    private final RoundTableMessageRepository messageRepository;
    private final BookRepository bookRepository;
    private final ChatModelManager chatModelManager;
    private final NotificationService notificationService;
    private final ExecutorService sseExecutor;
    private final AiProviderConfigService aiProviderConfigService;
    private final AiSceneConfigService aiSceneConfigService;

    public RoundTableReportService(
            RoundTableReportRepository reportRepository,
            RoundTableSessionRepository sessionRepository,
            RoundTableMessageRepository messageRepository,
            BookRepository bookRepository,
            ChatModelManager chatModelManager,
            NotificationService notificationService,
            @Qualifier("sseExecutor") ExecutorService sseExecutor,
            AiProviderConfigService aiProviderConfigService,
            AiSceneConfigService aiSceneConfigService) {
        this.reportRepository = reportRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.bookRepository = bookRepository;
        this.chatModelManager = chatModelManager;
        this.notificationService = notificationService;
        this.sseExecutor = sseExecutor;
        this.aiProviderConfigService = aiProviderConfigService;
        this.aiSceneConfigService = aiSceneConfigService;
    }

    /**
     * 触发报告生成（异步 + 幂等）
     * <p>
     * 使用 @RedisLock 防止同一会话并发触发。
     * - 无报告 → 创建并异步生成
     * - GENERATING → 直接返回（正在生成中，不重复触发）
     * - COMPLETED → 直接返回（已有报告）
     * - FAILED → 重置状态并重新生成
     *
     * @param sessionId 圆桌派会话ID
     * @param userId    用户ID
     * @return 报告实体
     */
    @RedisLock(key = "'rt:report:' + #sessionId", leaseTime = 10, timeUnit = TimeUnit.MINUTES)
    public RoundTableReport triggerReport(String sessionId, Long userId) {
        // 验证会话存在且属于该用户
        RoundTableSession session = loadSession(sessionId);
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("无权操作此会话");
        }

        // 检查是否已有报告
        RoundTableReport existing = reportRepository.findOneBySessionId(sessionId);
        if (existing != null) {
            switch (existing.getStatus()) {
                case "GENERATING", "PENDING" -> {
                    // 正在生成中，直接返回，不重复触发
                    log.info("报告正在生成中，跳过: sessionId={}, status={}", sessionId, existing.getStatus());
                    return existing;
                }
                case "FAILED", "COMPLETED" -> {
                    // 失败或已完成，重置状态，允许重新生成
                    existing.setStatus("GENERATING");
                    existing.setErrorMessage(null);
                    existing.setContent(null);
                    reportRepository.save(existing);
                    reportRepository.flush();
                    sseExecutor.execute(() -> doGenerateReport(sessionId, userId, session.getBookId()));
                    return existing;
                }
                default -> {
                    return existing;
                }
            }
        }

        // 创建新报告记录
        RoundTableReport report = RoundTableReport.builder()
                .sessionId(sessionId)
                .userId(userId)
                .bookId(session.getBookId())
                .status("GENERATING")
                .build();
        reportRepository.save(report);
        reportRepository.flush();
        sseExecutor.execute(() -> doGenerateReport(sessionId, userId, session.getBookId()));

        return report;
    }

    /**
     * 获取报告（如果存在）
     * <p>
     * 自动检测宕机残留：GENERATING 超过 10 分钟的报告标记为 FAILED，
     * 防止系统重启后报告永远卡在"生成中"状态。
     *
     * @param sessionId 圆桌派会话ID
     * @return 报告实体，不存在返回 null
     */
    public RoundTableReport getReport(String sessionId) {
        RoundTableReport report = reportRepository.findOneBySessionId(sessionId);
        if (report != null && isStale(report)) {
            report.setStatus("FAILED");
            report.setErrorMessage("生成超时（系统可能已重启），请重新生成");
            reportRepository.save(report);
            log.warn("检测到过期报告，已标记为 FAILED: sessionId={}", sessionId);
        }
        return report;
    }

    /**
     * 应用启动时恢复卡住的报告
     * <p>
     * 扫描所有 GENERATING/PENDING 状态的报告，超过阈值时间的标记为 FAILED。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleReportsOnStartup() {
        try {
            List<RoundTableReport> allReports = reportRepository.findAll();
            int recovered = 0;
            for (RoundTableReport report : allReports) {
                if (("GENERATING".equals(report.getStatus()) || "PENDING".equals(report.getStatus()))
                        && isStale(report)) {
                    report.setStatus("FAILED");
                    report.setErrorMessage("生成超时（系统重启），请重新生成");
                    reportRepository.save(report);
                    recovered++;
                }
            }
            if (recovered > 0) {
                log.info("启动恢复：已将 {} 个过期报告标记为 FAILED", recovered);
            }
        } catch (Exception e) {
            log.warn("启动恢复过期报告失败: {}", e.getMessage());
        }
    }

    /**
     * 判断报告是否过期（GENERATING/PENDING 状态超过阈值时间）
     */
    private boolean isStale(RoundTableReport report) {
        if (!"GENERATING".equals(report.getStatus()) && !"PENDING".equals(report.getStatus())) {
            return false;
        }
        LocalDateTime updatedAt = report.getUpdatedAt();
        if (updatedAt == null) return true; // 无更新时间，视为过期
        return ChronoUnit.MINUTES.between(updatedAt, LocalDateTime.now()) > STALE_THRESHOLD_MINUTES;
    }

    /**
     * 异步生成报告的核心逻辑
     * <p>
     * 策略：讨论超过 8000 字时自动分段评估（按主持人发言切割），
     * 每段独立评估后合并为最终报告，避免单次 LLM 调用丢失细节。
     * LLM 调用内置重试机制（最多 3 次），全部失败才标记为 FAILED。
     */
    private void doGenerateReport(String sessionId, Long userId, Long bookId) {
        try {
            log.info("开始生成圆桌派解读报告: sessionId={}", sessionId);

            // 1. 加载报告记录
            RoundTableReport report = reportRepository.findOneBySessionId(sessionId);
            if (report == null) {
                log.error("报告记录不存在: sessionId={}", sessionId);
                return;
            }

            // 2. 加载讨论消息
            List<RoundTableMessage> messages = messageRepository.query()
                    .where("sessionId", eq(sessionId)).orderBy("id").list();
            if (messages.isEmpty()) {
                report.setStatus("FAILED");
                report.setErrorMessage("该会话没有发言记录");
                reportRepository.save(report);
                return;
            }

            // 3. 加载会话和书籍信息
            RoundTableSession session = loadSession(sessionId);
            Book book = bookRepository.findById(bookId).orElse(null);

            // 4. 构建讨论文本
            String fullDiscussion = buildDiscussionText(messages);
            log.info("讨论文本构建完成: {} 字符, {} 条发言, {}", fullDiscussion.length(), messages.size(), getRoleNames(messages));

            // 5. 决定策略：短讨论直接评估，长讨论分段评估
            String reportContent;
            if (fullDiscussion.length() <= 8000) {
                reportContent = interpretWithRetry(fullDiscussion, session, book, messages);
            } else {
                reportContent = interpretSegmented(fullDiscussion, session, book, messages);
            }

            if (reportContent == null || reportContent.isBlank()) {
                report.setStatus("FAILED");
                report.setErrorMessage("LLM 解读生成失败（已重试）");
                reportRepository.save(report);
                return;
            }

            // 6. 保存报告
            report.setContent(reportContent);
            report.setStatus("COMPLETED");
            reportRepository.save(report);

            log.info("圆桌派解读报告生成完成: sessionId={}", sessionId);

            // 7. 发送站内信通知
            notificationService.notifyRoundTableReport(userId, sessionId, bookId);

        } catch (Exception e) {
            log.error("圆桌派解读报告生成异常: sessionId={} - {}", sessionId, e.getMessage(), e);
            try {
                RoundTableReport report = reportRepository.findOneBySessionId(sessionId);
                if (report != null) {
                    report.setStatus("FAILED");
                    report.setErrorMessage(e.getMessage() != null ? e.getMessage().substring(0, Math.min(500, e.getMessage().length())) : "未知错误");
                    reportRepository.save(report);
                }
            } catch (Exception ex) {
                log.error("更新报告失败状态异常: {}", ex.getMessage());
            }
        }
    }

    // ==================== 分段评估 ====================

    /**
     * 分段评估：按主持人发言切割讨论，逐段评估后合并
     */
    private String interpretSegmented(String fullDiscussion, RoundTableSession session,
                                       Book book, List<RoundTableMessage> messages) {
        // 1. 按 HOST 发言切割为多个 segment
        List<String> segments = splitByHost(messages);
        log.info("讨论分为 {} 段进行评估", segments.size());

        // 只切成 1 段：不需要分段评估，改走整体解读逻辑，避免输出"本段概要/本段讨论"格式的残缺报告
        if (segments.size() <= 1) {
            log.info("实际只切成 1 段，改用整体解读逻辑生成完整报告");
            return interpretWithRetry(fullDiscussion, session, book, messages);
        }

        // 2. 逐段评估，传递前文概要
        List<String> segmentReports = new ArrayList<>();
        String previousSummary = "";

        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            log.info("评估第 {}/{} 段 ({} 字符)", i + 1, segments.size(), segment.length());

            StringBuilder userMsg = new StringBuilder();
            if (!previousSummary.isEmpty()) {
                userMsg.append("【前文概要】\n").append(previousSummary).append("\n\n");
            }
            userMsg.append("【本段讨论】\n").append(segment);

            List<ChatMessage> chatMessages = List.of(
                    SystemMessage.from(AiPromptConstants.ROUND_TABLE_REPORT_SEGMENT_PROMPT),
                    UserMessage.from(buildBookAndRoleContext(session, book) + "\n\n" + userMsg));

            String segmentReport = callAiWithRetry(chatMessages, "分段评估第" + (i + 1) + "段");
            if (segmentReport == null || segmentReport.isBlank()) {
                log.warn("第 {} 段评估失败，跳过", i + 1);
                continue;
            }
            segmentReports.add(segmentReport);

            // 完整传递前文评估，不截断
            previousSummary = segmentReport;
        }

        if (segmentReports.isEmpty()) {
            return null;
        }

        // 只有 1 段，直接返回，不走合并
        if (segmentReports.size() == 1) {
            log.info("只有 1 段评估，直接返回");
            return segmentReports.get(0);
        }

        // 多段：合并所有分段评估为最终报告
        log.info("合并 {} 段评估为最终报告", segmentReports.size());
        StringBuilder mergedSegments = new StringBuilder();
        for (int i = 0; i < segmentReports.size(); i++) {
            mergedSegments.append("=== 第").append(i + 1).append("段评估 ===\n");
            mergedSegments.append(segmentReports.get(i)).append("\n\n");
        }

        List<ChatMessage> mergeMessages = List.of(
                SystemMessage.from(AiPromptConstants.ROUND_TABLE_REPORT_MERGE_PROMPT),
                UserMessage.from(buildBookAndRoleContext(session, book)
                        + "\n\n【各段评估结果】\n" + mergedSegments));

        return callAiWithRetry(mergeMessages, "报告合并");
    }

    /**
     * 按主持人（HOST）发言切割讨论为多个段落，按动态计算的目标字符数分段
     * <p>
     * 目标字符数 = maxTokens × 1.5 × 0.6
     * - maxTokens：从 AiProviderConfig 读取（如 8K/32K/128K）
     * - 1.5：token 到字符的转换比（中文约 1.5 字符/token）
     * - 0.6：留 40% 给系统提示词 + 书籍信息 + 前文概要 + 输出
     * <p>
     * 切割优先在 HOST 发言处，其次在目标字符数附近的发言边界切割。
     * 相邻段保留最后 2 条发言重叠，保证上下文连贯。
     */
    private List<String> splitByHost(List<RoundTableMessage> messages) {
        // 动态计算目标字符数：maxTokens × 1.5 × 0.35
        // - 1.5：token 到字符转换比（中文）
        // - 0.35：只用 35% 的上下文放讨论内容，留 65% 给系统提示词 + 书籍信息 + 前文概要 + 输出
        // 从 ROUND_TABLE_REPORT 场景绑定配置读 maxTokens（跟随场景路由，替代旧的 getActiveMaxTokens）
        Integer maxTokens = null;
        try {
            AiProviderConfig sceneConfig = aiSceneConfigService.resolveConfig(AiScene.ROUND_TABLE_REPORT);
            if (sceneConfig != null) {
                maxTokens = sceneConfig.getMaxTokens();
            }
        } catch (Exception e) {
            log.warn("解析 ROUND_TABLE_REPORT 场景配置失败，回退到默认 maxTokens: {}", e.getMessage());
        }
        int tokens = maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS;
        int targetChars = (int) (tokens * TOKEN_TO_CHAR_RATIO * 0.35);
        log.info("分段目标字符数: {} (maxTokens={}, ratio=0.35)", targetChars, tokens);

        // 如果总字符数在目标范围内，不分段
        int totalChars = 0;
        for (RoundTableMessage msg : messages) {
            String c = msg.getCompressedContent() != null && !msg.getCompressedContent().isBlank()
                    ? msg.getCompressedContent() : msg.getContent();
            if (c != null) totalChars += c.length();
        }
        if (totalChars <= (int) (targetChars * 1.5)) {
            return List.of(buildDiscussionText(messages));
        }

        // 第一遍：标记每个位置的累计字符数和是否为 HOST
        int[] cumLen = new int[messages.size()];
        boolean[] isHost = new boolean[messages.size()];
        int running = 0;
        for (int i = 0; i < messages.size(); i++) {
            String c = messages.get(i).getCompressedContent() != null && !messages.get(i).getCompressedContent().isBlank()
                    ? messages.get(i).getCompressedContent() : messages.get(i).getContent();
            running += (c != null ? c.length() : 0);
            cumLen[i] = running;
            isHost[i] = "HOST".equals(messages.get(i).getRoleKey());
        }

        // 第二遍：找切割点（尽量在 HOST 处切割，否则在目标字符数附近的发言边界切割）
        // 如果切割后剩余字数 < targetChars × 0.3，不切割，直接合并到当前段
        int minSegmentChars = (int) (targetChars * 0.3);
        List<Integer> cutPoints = new ArrayList<>();
        int segStart = 0;
        while (segStart < messages.size()) {
            int nextCut = -1;

            for (int i = segStart; i < messages.size(); i++) {
                int segLen = cumLen[i] - (segStart > 0 ? cumLen[segStart - 1] : 0);
                int remainingLen = cumLen[messages.size() - 1] - cumLen[i];
                if (segLen >= targetChars) {
                    // 剩余字数太少，不切割，合并到当前段
                    if (remainingLen < minSegmentChars) {
                        break;
                    }
                    // 超过目标字符数，从这个点往回找最近的 HOST
                    for (int j = i; j >= segStart; j--) {
                        if (isHost[j]) {
                            nextCut = j;
                            break;
                        }
                    }
                    if (nextCut < 0) {
                        nextCut = i;
                    }
                    break;
                }
            }

            if (nextCut < 0) {
                break;
            }

            cutPoints.add(nextCut);
            segStart = nextCut + 1;
        }

        if (cutPoints.isEmpty()) {
            return List.of(buildDiscussionText(messages));
        }

        // 第三遍：按切割点分段
        List<String> segments = new ArrayList<>();
        int prevStart = 0;
        for (int cutIdx = 0; cutIdx < cutPoints.size(); cutIdx++) {
            int cut = cutPoints.get(cutIdx);

            StringBuilder seg = new StringBuilder();
            for (int i = prevStart; i <= cut && i < messages.size(); i++) {
                appendMessage(seg, messages.get(i));
            }
            segments.add(seg.toString());

            prevStart = cut + 1;
        }

        // 剩余内容
        if (prevStart < messages.size()) {
            StringBuilder seg = new StringBuilder();
            int overlapStart = Math.max(0, prevStart - 3);
            for (int i = overlapStart; i < prevStart && i < messages.size(); i++) {
                appendMessage(seg, messages.get(i));
            }
            if (overlapStart < prevStart) {
                seg.append("——以下为新段落——\n\n");
            }
            for (int i = prevStart; i < messages.size(); i++) {
                appendMessage(seg, messages.get(i));
            }
            segments.add(seg.toString());
        }

        return segments;
    }

    private void appendMessage(StringBuilder sb, RoundTableMessage msg) {
        String content = msg.getCompressedContent() != null && !msg.getCompressedContent().isBlank()
                ? msg.getCompressedContent() : msg.getContent();
        if (content == null || content.isBlank()) return;
        sb.append("【").append(msg.getRoleName()).append("】(第").append(msg.getRound()).append("轮)\n")
                .append(content).append("\n\n");
    }

    /**
     * 构建书籍+角色上下文（供分段评估使用）
     */
    private String buildBookAndRoleContext(RoundTableSession session, Book book) {
        StringBuilder sb = new StringBuilder();
        sb.append("【图书信息】\n").append(buildBookInfo(book)).append("\n\n");
        // 从 session 获取角色信息
        if (session.getRoleKeys() != null) {
            sb.append("【角色说明】\n");
            for (String key : session.getRoleKeys().split(",")) {
                key = key.trim();
                if (!key.isBlank()) sb.append("  ").append(key).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 带重试的 LLM 调用
     */
    private String callAiWithRetry(List<ChatMessage> messages, String taskName) {
        String lastError = null;
        for (int i = 1; i <= LLM_MAX_RETRIES; i++) {
            try {
                String result = chatModelManager.callAiForScene(AiScene.ROUND_TABLE_REPORT, "圆桌派" + taskName, "", messages);
                if (result != null && !result.isBlank()) {
                    return result;
                }
                lastError = "LLM 返回空内容";
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("{}第 {}/{} 次失败: {}", taskName, i, LLM_MAX_RETRIES, e.getMessage());
            }
            if (i < LLM_MAX_RETRIES) {
                try { Thread.sleep(2000L * i); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return null; }
            }
        }
        log.error("{}全部 {} 次尝试失败: {}", taskName, LLM_MAX_RETRIES, lastError);
        return null;
    }

    // ==================== LLM 解读（带重试） ====================

    private String interpretWithRetry(String fullDiscussion, RoundTableSession session,
                                      Book book, List<RoundTableMessage> messages) {
        String lastError = null;
        for (int i = 1; i <= LLM_MAX_RETRIES; i++) {
            try {
                log.info("LLM 解读第 {}/{} 次尝试: sessionId={}", i, LLM_MAX_RETRIES, session.getSessionId());
                String result = interpret(fullDiscussion, session, book, messages);
                if (result != null && !result.isBlank()) {
                    return result;
                }
                lastError = "LLM 返回空内容";
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("LLM 解读第 {}/{} 次失败: {}", i, LLM_MAX_RETRIES, e.getMessage());
            }
            if (i < LLM_MAX_RETRIES) {
                try { Thread.sleep(2000L * i); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return null; }
            }
        }
        log.error("LLM 解读全部 {} 次尝试失败: sessionId={}, lastError={}", LLM_MAX_RETRIES, session.getSessionId(), lastError);
        return null;
    }

    private String interpret(String fullDiscussion, RoundTableSession session,
                             Book book, List<RoundTableMessage> messages) {
        String bookInfo = buildBookInfo(book);
        String roleInfo = buildRoleInfo(messages);

        String userMessage = String.format("""
                以下是本次讨论的数据：

                【图书信息】
                %s

                【角色说明】
                %s

                【讨论全文】
                %s""", bookInfo, roleInfo, fullDiscussion);

        List<ChatMessage> chatMessages = List.of(
                SystemMessage.from(AiPromptConstants.ROUND_TABLE_REPORT_SYSTEM_PROMPT),
                UserMessage.from(userMessage));

        return chatModelManager.callAiForScene(AiScene.ROUND_TABLE_REPORT, "圆桌派解读报告",
                String.format("sessionId=%s", session.getSessionId()),
                chatMessages);
    }

    // ==================== 辅助方法 ====================

    private RoundTableSession loadSession(String sessionId) {
        List<RoundTableSession> sessions = sessionRepository.query()
                .where("sessionId", eq(sessionId)).list(1);
        if (sessions.isEmpty()) throw new BusinessException("会话不存在: " + sessionId);
        return sessions.get(0);
    }

    private String buildBookInfo(Book book) {
        if (book == null) return "（无图书信息）";
        StringBuilder sb = new StringBuilder();
        sb.append("书名：《").append(book.getTitle()).append("》\n");
        if (book.getAuthor() != null && !book.getAuthor().isBlank()) {
            sb.append("作者：").append(book.getAuthor()).append("\n");
        }
        if (book.getFormatTags() != null && !book.getFormatTags().isBlank()) {
            String tags = book.getFormatTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("标签：").append(tags).append("\n");
        }
        if (book.getConceptTags() != null && !book.getConceptTags().isBlank()) {
            String concepts = book.getConceptTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("核心概念：").append(concepts).append("\n");
        }
        if (book.getReaderNeedTags() != null && !book.getReaderNeedTags().isBlank()) {
            String needs = book.getReaderNeedTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("读者关注：").append(needs).append("\n");
        }
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            sb.append("简介：").append(book.getDescription()).append("\n");
        }
        return sb.toString();
    }

    private String buildRoleInfo(List<RoundTableMessage> messages) {
        Map<String, String> map = new LinkedHashMap<>();
        messages.forEach(m -> map.putIfAbsent(m.getRoleKey(), m.getRoleName()));
        return map.entrySet().stream().map(e -> "  " + e.getKey() + "(" + e.getValue() + ")")
                .collect(Collectors.joining("\n"));
    }

    private String buildDiscussionText(List<RoundTableMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (RoundTableMessage msg : messages) {
            String content = msg.getCompressedContent() != null && !msg.getCompressedContent().isBlank()
                    ? msg.getCompressedContent() : msg.getContent();
            if (content != null && !content.isBlank())
                sb.append("【").append(msg.getRoleName()).append("】(第").append(msg.getRound()).append("轮)\n")
                        .append(content).append("\n\n");
        }
        return sb.toString();
    }

    private String getRoleNames(List<RoundTableMessage> messages) {
        return messages.stream().map(RoundTableMessage::getRoleName).distinct().collect(Collectors.joining("、"));
    }
}
