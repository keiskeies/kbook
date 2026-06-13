package com.kbook.service.ai;

import com.kbook.common.exception.BusinessException;
import com.kbook.config.ChatModelFactory;
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
import dev.langchain4j.model.chat.ChatModel;
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
    private final ChatModelFactory chatModelFactory;
    private final ChatModelManager chatModelManager;
    private final ExecutorService sseExecutor;

    public DebateReportService(
            DebateReportRepository reportRepository,
            DebateSessionRepository sessionRepository,
            DebateMessageRepository messageRepository,
            DebateScoreRepository scoreRepository,
            BookRepository bookRepository,
            ChatModelFactory chatModelFactory,
            ChatModelManager chatModelManager,
            @Qualifier("sseExecutor") ExecutorService sseExecutor) {
        this.reportRepository = reportRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.scoreRepository = scoreRepository;
        this.bookRepository = bookRepository;
        this.chatModelFactory = chatModelFactory;
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

            // 构建报告 prompt
            String reportPrompt = buildReportPrompt(session, book, allMessages);

            ChatModel chatModel = chatModelFactory.buildChatModelWithoutThinking();
            if (chatModel == null) {
                throw new BusinessException("AI 模型未配置");
            }

            String result = chatModelManager.callAi(
                    "辩论报告生成",
                    String.format("sessionId=%s", session.getSessionId()),
                    reportPrompt);

            if (result == null || result.isBlank()) {
                throw new BusinessException("报告生成失败");
            }

            report.setContent(result);
            report.setStatus("COMPLETED");

            // 计算最佳辩手：7维度平均分最高的辩手
            String bestDebater = computeBestDebater(session.getSessionId());
            report.setBestDebater(bestDebater);

            reportRepository.save(report);

            log.info("辩论报告生成完成: sessionId={}, length={}, bestDebater={}",
                    session.getSessionId(), result.length(), bestDebater);

        } catch (Exception e) {
            log.error("辩论报告生成异常: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * 构建报告生成提示词
     */
    private String buildReportPrompt(DebateSession session, Book book, List<DebateMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位专业的辩论评审。请为以下辩论撰写一份完整的评审报告。\n\n");
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
                case "ATTACK" -> "【奇袭攻辩】";
                case "FREE" -> "【自由辩论】";
                case "CLOSING" -> "【总结陈词】";
                default -> "";
            };
            sb.append(roundLabel).append(sideLabel).append(msg.getRoleName())
                    .append("（第").append(msg.getRoundNumber()).append("轮）：\n")
                    .append(msg.getContent()).append("\n\n");
        }

        sb.append("请撰写一份包含以下章节的报告（使用Markdown格式）：\n");
        sb.append("1. 辩论概要 — 辩题、正反方阵容、参与角色\n");
        sb.append("2. 各轮次精彩回顾 — 每轮提取1-2个关键发言\n");
        sb.append("3. 评分汇总 — 各角色7维度平均分，用表格展示\n");
        sb.append("4. 正方表现分析 — 整体表现、亮点、不足\n");
        sb.append("5. 反方表现分析 — 整体表现、亮点、不足\n");
        sb.append("6. 观点亮点与逻辑漏洞总结\n");
        sb.append("7. 评审结语 — 对整体辩论质量的评价\n\n");
        sb.append("请用中文撰写报告，语言专业但富有洞察力。");

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
    private String computeBestDebater(String sessionId) {
        try {
            var scores = scoreRepository.findBySessionId(sessionId);
            if (scores == null || scores.isEmpty()) return null;

            // 按 positionKey（通过 messageId 关联）分组，计算每人平均分
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

            return best != null ? best.getKey() : null;
        } catch (Exception e) {
            log.warn("计算最佳辩手失败: {}", e.getMessage());
            return null;
        }
    }
}
