package com.kbook.service.ai;

import com.kbook.common.exception.BusinessException;
import com.kbook.constants.AiPromptConstants;
import com.kbook.dto.debate.DebateReportVO;
import com.kbook.entity.Book;
import com.kbook.entity.debate.DebateMessage;
import com.kbook.entity.debate.DebateReport;
import com.kbook.entity.debate.DebateSession;
import com.kbook.repository.BookRepository;
import com.kbook.repository.debate.DebateMessageRepository;
import com.kbook.repository.debate.DebateReportRepository;
import com.kbook.repository.debate.DebateScoreRepository;
import com.kbook.repository.debate.DebateSessionRepository;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static com.kbook.common.util.QueryBuilder.eq;

/**
 * 奇葩说辩论报告服务 — 异步生成完整辩论报告
 * <p>
 * 报告内容包含：
 * - 辩论概要（辩题、正反方、参与角色）
 * - 各轮次精彩回顾
 * - 评分汇总（7维度雷达图数据/表格）
 * - 正方/反方整体表现分析
 * - 观点亮点/逻辑漏洞总结
 * - 评审结语
 */
@Slf4j
@Service
public class DebateReportService {

    private final DebateReportRepository reportRepository;
    private final DebateSessionRepository sessionRepository;
    private final DebateMessageRepository messageRepository;
    private final DebateScoreRepository scoreRepository;
    private final BookRepository bookRepository;
    private final ChatModelManager chatModelManager;
    private final ExecutorService sseExecutor;

    public DebateReportService(
            DebateReportRepository reportRepository,
            DebateSessionRepository sessionRepository,
            DebateMessageRepository messageRepository,
            DebateScoreRepository scoreRepository,
            BookRepository bookRepository,
            ChatModelManager chatModelManager,
            @Qualifier("sseExecutor") ExecutorService sseExecutor) {
        this.reportRepository = reportRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.scoreRepository = scoreRepository;
        this.bookRepository = bookRepository;
        this.chatModelManager = chatModelManager;
        this.sseExecutor = sseExecutor;
    }

    /**
     * 触发报告生成
     */
    public DebateReportVO triggerReport(String sessionId, Long userId) {
        DebateSession session = sessionRepository.query()
                .where(DebateSession::getSessionId, eq(sessionId))
                .list(1).stream().findFirst().orElse(null);
        if (session == null) {
            throw new BusinessException("辩论会话不存在");
        }
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("无权操作此会话");
        }

        // 检查是否已有报告
        DebateReport existing = reportRepository.findOneBySessionId(sessionId);
        if (existing != null && "COMPLETED".equals(existing.getStatus())) {
            return DebateReportVO.from(existing);
        }
        if (existing != null && "GENERATING".equals(existing.getStatus())) {
            return DebateReportVO.from(existing);
        }

        // 创建或重置报告
        Book book = bookRepository.findById(session.getBookId()).orElse(null);
        DebateReport report;
        if (existing != null) {
            existing.setStatus("GENERATING");
            existing.setErrorMessage(null);
            report = reportRepository.save(existing);
        } else {
            report = DebateReport.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .bookId(session.getBookId())
                    .topic(session.getTopic())
                    .status("GENERATING")
                    .build();
            report = reportRepository.save(report);
        }

        // 异步生成
        DebateReport finalReport = report;
        sseExecutor.submit(() -> {
            try {
                generateReport(finalReport, session, book);
            } catch (Exception e) {
                log.error("辩论报告生成失败: sessionId={} - {}", sessionId, e.getMessage());
                finalReport.setStatus("FAILED");
                finalReport.setErrorMessage(e.getMessage());
                reportRepository.save(finalReport);
            }
        });

        return DebateReportVO.from(report);
    }

    /**
     * 异步生成报告内容
     */
    private void generateReport(DebateReport report, DebateSession session, Book book) {
        try {
            // 获取全部消息
            List<DebateMessage> allMessages = messageRepository.findBySessionIdOrderById(session.getSessionId());

            // 构建报告用户消息（动态内容）
            String reportUserMessage = buildReportUserMessage(session, book, allMessages);

            List<ChatMessage> chatMessages = List.of(
                    SystemMessage.from(AiPromptConstants.DEBATE_REPORT_SYSTEM_PROMPT),
                    UserMessage.from(reportUserMessage));

            String result = chatModelManager.callAi(
                    "辩论报告生成",
                    String.format("sessionId=%s", session.getSessionId()),
                    chatMessages);

            if (result == null || result.isBlank()) {
                throw new BusinessException("报告生成失败");
            }

            report.setContent(result);
            report.setStatus("COMPLETED");

            // 计算最佳辩手：7维度平均分最高的辩手
            var bestDebater = computeBestDebater(session.getSessionId());
            report.setBestDebater(bestDebater.roleKey());
            report.setBestDebaterPosition(bestDebater.positionKey());

            reportRepository.save(report);

            log.info("辩论报告生成完成: sessionId={}, length={}, bestDebater={}, position={}",
                    session.getSessionId(), result.length(), bestDebater.roleKey(), bestDebater.positionKey());

        } catch (Exception e) {
            log.error("辩论报告生成异常: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * 构建报告用户消息（动态内容：辩论全过程）
     */
    private String buildReportUserMessage(DebateSession session, Book book, List<DebateMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 辩论基本信息\n");
        sb.append("辩题：").append(session.getTopic()).append("\n");
        if (book != null) {
            sb.append("关联书籍：").append(book.getTitle()).append("\n");
        }
        sb.append("\n");

        // 按轮次整理发言
        sb.append("## 辩论全过程\n\n");
        for (DebateMessage msg : messages) {
            String sideLabel = switch (msg.getSide()) {
                case "PRO" -> "[正方]";
                case "CON" -> "[反方]";
                default -> "[主持]";
            };
            String roundLabel = switch (msg.getRoundType()) {
                case "OPENING" -> "【开篇立论】";
                case "CROSS_EXAM" -> "【交叉质询】";
                case "REBUTTAL" -> "【驳论】";
                case "ATTACK" -> "【奇袭攻辩】";
                case "FREE" -> "【自由辩论】";
                case "CLOSING" -> "【总结陈词】";
                default -> "";
            };
            sb.append(roundLabel).append(sideLabel).append(msg.getRoleName())
                    .append("（第").append(msg.getRoundNumber()).append("轮）：\n")
                    .append(msg.getContent()).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 获取报告
     */
    public DebateReportVO getReport(String sessionId) {
        DebateReport report = reportRepository.findOneBySessionId(sessionId);
        if (report == null) return null;
        return DebateReportVO.from(report);
    }

    /**
     * 计算最佳辩手：所有非主持人辩手中7维度平均分最高者
     */
    private BestDebaterResult computeBestDebater(String sessionId) {
        try {
            var scores = scoreRepository.findBySessionId(sessionId);
            if (scores == null || scores.isEmpty()) return new BestDebaterResult(null, null);

            // 按 roleKey 分组，计算每人平均分
            var best = scores.stream()
                    .filter(s -> s.getAverageScore() != null)
                    .collect(Collectors.groupingBy(
                            s -> s.getRoleKey() != null ? s.getRoleKey() : "unknown",
                            Collectors.averagingDouble(s -> s.getAverageScore() != null ? s.getAverageScore() : 0)
                    ))
                    .entrySet().stream()
                    .filter(e -> !"HOST".equals(e.getKey()))
                    .max(Map.Entry.comparingByValue())
                    .orElse(null);

            if (best == null) return new BestDebaterResult(null, null);

            String roleKey = best.getKey();
            String positionKey = scores.stream()
                    .filter(s -> roleKey.equals(s.getRoleKey()))
                    .map(s -> s.getPositionKey())
                    .filter(p -> p != null)
                    .findFirst()
                    .orElse(null);

            return new BestDebaterResult(roleKey, positionKey);
        } catch (Exception e) {
            log.warn("计算最佳辩手失败: {}", e.getMessage());
            return new BestDebaterResult(null, null);
        }
    }

    private record BestDebaterResult(String roleKey, String positionKey) {}
}
