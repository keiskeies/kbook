package com.kbook.service.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.util.CommonUtils;
import com.kbook.constants.AiPromptConstants;
import com.kbook.dto.debate.DebateScoreVO;
import com.kbook.entity.debate.DebateScore;
import com.kbook.repository.debate.DebateMessageRepository;
import com.kbook.repository.debate.DebateScoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 奇葩说辩论评分服务 — 对单次发言进行7维度评分
 * <p>
 * 评分维度：逻辑性、论据丰富度、反驳力、感染力、幽默感、表达清晰度、观点新颖度
 * 评分范围：1-10分，0.5分精度
 * 执行方式：异步执行，不阻塞发言 SSE 流程
 */
@Slf4j
@Service
public class DebateScoringService {

    private final ChatModelManager chatModelManager;
    private final DebateScoreRepository scoreRepository;
    private final DebateMessageRepository messageRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DebateScoringService(
            ChatModelManager chatModelManager,
            DebateScoreRepository scoreRepository,
            DebateMessageRepository messageRepository) {
        this.chatModelManager = chatModelManager;
        this.scoreRepository = scoreRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 异步评分 — 不阻塞调用方
     */
    @Async
    public void scoreSpeechAsync(Long userId, String sessionId, String roleKey, String positionKey,
                                  String side, String content, int roundNumber, String roundType) {
        try {
            DebateScore score = doScore(userId, sessionId, roleKey, positionKey, side, content, roundNumber, roundType);
            if (score != null) {
                log.info("辩论评分完成: sessionId={}, roleKey={}, round={}, avg={}",
                        sessionId, roleKey, roundNumber, score.getAverageScore());
            }
        } catch (Exception e) {
            log.warn("辩论评分失败（跳过）: sessionId={}, roleKey={} - {}",
                    sessionId, roleKey, e.getMessage());
        }
    }

    /**
     * 执行评分
     */
    private DebateScore doScore(Long userId, String sessionId, String roleKey, String positionKey,
                                  String side, String content, int roundNumber, String roundType) {
        // 获取最后一条消息的 ID
        var messages = messageRepository.findBySessionIdAndRoundNumberOrderByPhaseOrder(sessionId, roundNumber);
        if (messages.isEmpty()) return null;
        Long messageId = messages.get(messages.size() - 1).getId();

        try {
            String prompt = String.format(
                    AiPromptConstants.DEBATE_SCORING_PROMPT,
                    "", // 辩题会在创建时动态填充
                    roleKey, side,
                    getRoundTypeLabel(roundType),
                    content);

            // 获取会话辩题（从 message 中获取）
            String topic = messages.get(0).getContent();
            prompt = String.format(
                    AiPromptConstants.DEBATE_SCORING_PROMPT,
                    topic, roleKey, side,
                    getRoundTypeLabel(roundType),
                    content);

            String result = chatModelManager.callAi(
                    "辩论评分",
                    String.format("sessionId=%s, roleKey=%s, round=%d", sessionId, roleKey, roundNumber),
                    prompt);

            if (result == null || result.isBlank()) {
                return null;
            }

            result = CommonUtils.stripCodeFence(result);
            Map<String, Double> scores = objectMapper.readValue(result,
                    new TypeReference<Map<String, Double>>() {});

            // 计算平均分
            double avg = (scores.getOrDefault("logicScore", 0.0)
                    + scores.getOrDefault("evidenceScore", 0.0)
                    + scores.getOrDefault("rebuttalScore", 0.0)
                    + scores.getOrDefault("impactScore", 0.0)
                    + scores.getOrDefault("humorScore", 0.0)
                    + scores.getOrDefault("clarityScore", 0.0)
                    + scores.getOrDefault("noveltyScore", 0.0)) / 7.0;

            DebateScore entity = DebateScore.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .messageId(messageId)
                    .roleKey(roleKey)
                    .positionKey(positionKey)
                    .side(side)
                    .roundNumber(roundNumber)
                    .roundType(roundType)
                    .logicScore(scores.get("logicScore"))
                    .evidenceScore(scores.get("evidenceScore"))
                    .rebuttalScore(scores.get("rebuttalScore"))
                    .impactScore(scores.get("impactScore"))
                    .humorScore(scores.get("humorScore"))
                    .clarityScore(scores.get("clarityScore"))
                    .noveltyScore(scores.get("noveltyScore"))
                    .averageScore(Math.round(avg * 10.0) / 10.0)
                    .build();

            scoreRepository.save(entity);
            return entity;

        } catch (Exception e) {
            log.warn("辩论评分 LLM 调用失败: sessionId={}, roleKey={} - {}",
                    sessionId, roleKey, e.getMessage());
            return null;
        }
    }

    // ==================== 查询方法 ====================

    /**
     * 获取会话所有评分
     */
    public List<DebateScoreVO> getScoresBySession(String sessionId) {
        return scoreRepository.findBySessionId(sessionId).stream()
                .map(DebateScoreVO::from)
                .collect(Collectors.toList());
    }

    /**
     * 获取某轮评分
     */
    public List<DebateScoreVO> getScoresByRound(String sessionId, int roundNumber) {
        return scoreRepository.findBySessionIdAndRoundNumber(sessionId, roundNumber).stream()
                .map(DebateScoreVO::from)
                .collect(Collectors.toList());
    }

    /**
     * 获取某角色全部分数
     */
    public List<DebateScoreVO> getScoresByRole(String roleKey) {
        return scoreRepository.findByRoleKey(roleKey).stream()
                .map(DebateScoreVO::from)
                .collect(Collectors.toList());
    }

    /**
     * 获取轮次类型中文标签
     */
    private String getRoundTypeLabel(String roundType) {
        return switch (roundType) {
            case "OPENING" -> "开篇立论";
            case "ATTACK" -> "奇袭攻辩";
            case "FREE" -> "自由辩论";
            case "CLOSING" -> "总结陈词";
            default -> roundType;
        };
    }
}
