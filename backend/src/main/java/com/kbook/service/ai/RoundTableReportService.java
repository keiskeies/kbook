package com.kbook.service.ai;

import com.kbook.common.exception.BusinessException;
import com.kbook.config.annotation.RedisLock;
import com.kbook.constants.AiPromptConstants;
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

    private final RoundTableReportRepository reportRepository;
    private final RoundTableSessionRepository sessionRepository;
    private final RoundTableMessageRepository messageRepository;
    private final BookRepository bookRepository;
    private final ChatModelManager chatModelManager;
    private final NotificationService notificationService;
    private final ExecutorService sseExecutor;

    public RoundTableReportService(
            RoundTableReportRepository reportRepository,
            RoundTableSessionRepository sessionRepository,
            RoundTableMessageRepository messageRepository,
            BookRepository bookRepository,
            ChatModelManager chatModelManager,
            NotificationService notificationService,
            @Qualifier("sseExecutor") ExecutorService sseExecutor) {
        this.reportRepository = reportRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.bookRepository = bookRepository;
        this.chatModelManager = chatModelManager;
        this.notificationService = notificationService;
        this.sseExecutor = sseExecutor;
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
                case "COMPLETED" -> {
                    // 已完成，直接返回
                    return existing;
                }
                case "FAILED" -> {
                    // 失败则重置状态，允许重新生成
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

            // 5. 调用 LLM 生成解读（带重试）
            String reportContent = interpretWithRetry(fullDiscussion, session, book, messages);

            if (reportContent == null || reportContent.isBlank()) {
                report.setStatus("FAILED");
                report.setErrorMessage("LLM 解读生成失败（已重试 " + LLM_MAX_RETRIES + " 次）");
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

        return chatModelManager.callAi("圆桌派解读报告",
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
