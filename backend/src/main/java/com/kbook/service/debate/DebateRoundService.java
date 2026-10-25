package com.kbook.service.debate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.exception.BusinessException;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.config.ai.AiConfig;
import com.kbook.config.ai.AiConfigProvider;
import com.kbook.dto.debate.*;
import com.kbook.entity.AiScene;
import com.kbook.entity.Book;
import com.kbook.entity.debate.DebateMessage;
import com.kbook.entity.debate.DebateScore;
import com.kbook.entity.debate.DebateSession;
import com.kbook.common.enums.DebateRole;
import com.kbook.repository.BookRepository;
import com.kbook.repository.debate.DebateMessageRepository;
import com.kbook.repository.debate.DebateScoreRepository;
import com.kbook.repository.debate.DebateSessionRepository;
import com.kbook.service.ai.core.ExternalKnowledgeGenerator;
import com.kbook.service.ai.streaming.StreamingSseHandler;
import com.kbook.service.book.BookService;
import com.kbook.service.progress.ReadingProgressService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.*;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.time.LocalDateTime;
import static com.kbook.common.util.QueryBuilder.eq;
import static com.kbook.common.util.QueryBuilder.ne;
import com.kbook.service.ai.core.ChatModelManager;
import com.kbook.service.ai.core.AiUsageContext;
import com.kbook.constants.ai.DebatePrompts;

/**
 * 辩论轮次服务 — 阶段推进（A5 硬约束）/自由辩论下一发言人选择（交替规则）。
 */
@Slf4j
@Service
public class DebateRoundService {

    private final ChatModelManager chatModelManager;
    private final DebateSessionRepository sessionRepository;
    private final DebateMessageRepository messageRepository;
    private final DebateSessionService sessionService;

    public DebateRoundService(
            ChatModelManager chatModelManager,
            DebateSessionRepository sessionRepository,
            DebateMessageRepository messageRepository,
            DebateSessionService sessionService) {
        this.chatModelManager = chatModelManager;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.sessionService = sessionService;
    }

    /**
     * 自由辩论环节选择下一个发言人。
     */
    public String callAiForFreeDebaterSelection(String sessionId, String topic, String rolesInfo,
                                                String lastSpeaker, String lastSide, String countInfo) {
        String userPrompt = String.format("""
                当前辩题：%s

                参与辩手：
                %s

                上一位发言者：%s（%s方）

                发言次数统计：
                %s""", topic, rolesInfo, lastSpeaker, lastSide, countInfo);

        return chatModelManager.callAiForScene(AiScene.DEBATE_SPEAKER_SELECT, "辩论自由辩论发言人选择",
                "sessionId=" + sessionId, List.of(
                        SystemMessage.from(DebatePrompts.DEBATE_NEXT_SPEAKER_FREE_SYSTEM_PROMPT),
                        UserMessage.from(userPrompt)));
    }

    /**
     * LLM 决定自由辩论下一发言人
     */
    public String getNextSpeakerFree(Long userId, String sessionId) {
        DebateSession session = sessionService.getSessionBySessionId(sessionId);
        if (session == null) return null;
        // 验证会话所有权
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("无权操作该会话");
        }

        List<DebateMessage> allMessages = messageRepository.findBySessionIdOrderById(sessionId);
        List<DebateMessage> roundMessages = messageRepository.findBySessionIdAndRoundNumberOrderByPhaseOrder(
                sessionId, session.getCurrentRound());

        // 统计发言次数（按位置键统计）
        Map<String, Long> speakCounts = allMessages.stream()
                .collect(Collectors.groupingBy(
                        m -> m.getPositionKey() != null ? m.getPositionKey() : m.getRoleKey(),
                        Collectors.counting()));

        // 角色信息
        StringBuilder rolesInfo = new StringBuilder();
        for (String positionKey : getAllPositionKeys()) {
            if (!"HOST".equals(positionKey)) {
                String side = sessionService.getSideFromPositionKey(positionKey);
                rolesInfo.append(positionKey).append("(")
                        .append(sessionService.getSideName(side)).append(") - ")
                        .append("发言").append(
                                speakCounts.getOrDefault(positionKey, 0L)).append("次\n");
            }
        }

        // 上一位发言者
        DebateMessage lastMsg = roundMessages.isEmpty() ? null : roundMessages.get(roundMessages.size() - 1);
        String lastSpeaker = lastMsg != null ? (lastMsg.getPositionKey() != null ? lastMsg.getPositionKey() : lastMsg.getRoleKey()) : "无";
        String lastSide = lastMsg != null ? lastMsg.getSide() : "";

        // 发言次数统计
        StringBuilder countInfo = new StringBuilder();
        for (String positionKey : getAllPositionKeys()) {
            if (!"HOST".equals(positionKey)) {
                countInfo.append(positionKey).append(": ").append(
                        speakCounts.getOrDefault(positionKey, 0L)).append("次\n");
            }
        }

        try {
            String result = callAiForFreeDebaterSelection(
                    sessionId, session.getTopic(),
                    rolesInfo.toString(), lastSpeaker, lastSide,
                    countInfo.toString());

            if (result != null && !result.isBlank()) {
                result = result.trim();
                // 验证位置键有效性
                if (isValidPositionKey(result)) {
                    // 验证交替：不能与上一位同方
                    if (lastSide.isEmpty() || !lastSide.equals(sessionService.getSideFromPositionKey(result))) {
                        return result;
                    }
                    // 同方 → 回退到对方发言最少的辩手
                    log.warn("LLM 选择了同方辩手 {}，强制交替到对方", result);
                }
            }
        } catch (Exception e) {
            log.warn("LLM 发言人选择失败: {}", e.getMessage());
        }

        // 回退：选取对方发言次数最少的非 HOST 辩手（保证交替）
        String oppositeSide = "PRO".equals(lastSide) ? "CON" : "CON".equals(lastSide) ? "PRO" : null;
        return getLeastSpokenSpeakerBySide(speakCounts, Objects.requireNonNullElse(oppositeSide, "PRO"));
    }

    /**
     * 按方筛选：获取指定立场发言次数最少的辩手
     */
    private String getLeastSpokenSpeakerBySide(Map<String, Long> speakCounts, String side) {
        String leastSpeaker = null;
        long minCount = Long.MAX_VALUE;

        for (String positionKey : getAllPositionKeys()) {
            if ("HOST".equals(positionKey)) continue;
            if (!side.equals(sessionService.getSideFromPositionKey(positionKey))) continue;
            long count = speakCounts.getOrDefault(positionKey, 0L);
            if (count < minCount) {
                minCount = count;
                leastSpeaker = positionKey;
            }
        }
        return leastSpeaker;
    }

    /** 获取所有位置键 */
    private String[] getAllPositionKeys() {
        return new String[]{"HOST", "PRO_1", "PRO_2", "PRO_3", "PRO_4", "CON_1", "CON_2", "CON_3", "CON_4"};
    }

    /** 校验位置键是否有效 */
    private boolean isValidPositionKey(String key) {
        if (key == null) return false;
        for (String pk : getAllPositionKeys()) {
            if (pk.equals(key)) return true;
        }
        return false;
    }

    public DebateSessionVO advanceRound(Long userId, String sessionId) {
        DebateSession session = sessionService.getSessionBySessionId(sessionId);
        if (session == null) {
            throw new BusinessException("辩论会话不存在");
        }
        // 验证会话所有权
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("无权操作该会话");
        }

        String currentPhase = session.getCurrentPhase();
        int currentRound = session.getCurrentRound();

        String nextPhase;
        int nextRound;
        boolean completed = false;

        switch (currentPhase) {
            case "OPENING" -> {
                nextPhase = "CROSS_EXAM";
                nextRound = 2;
            }
            case "CROSS_EXAM" -> {
                nextPhase = "REBUTTAL";
                nextRound = 3;
            }
            case "REBUTTAL" -> {
                nextPhase = "FREE";
                nextRound = 4;
            }
            case "FREE" -> {
                nextPhase = "CLOSING";
                nextRound = 5;
            }
            case "CLOSING" -> {
                nextPhase = "CLOSING";
                nextRound = 5;
                completed = true;
            }
            default -> {
                nextPhase = "OPENING";
                nextRound = 1;
            }
        }

        if (completed) {
            session.setStatus("COMPLETED");
        } else {
            session.setCurrentRound(nextRound);
            session.setCurrentPhase(nextPhase);
        }
        sessionRepository.save(session);

        log.info("辩论轮次推进: sessionId={}, round={}→{}, phase={}→{}, status={}",
                sessionId, currentRound, session.getCurrentRound(),
                currentPhase, session.getCurrentPhase(), session.getStatus());
        return DebateSessionVO.from(session);
    }

    /** @deprecated 使用 advanceRound 中的显式 switch */
    @Deprecated
    private String getPhaseForRound(int round) {
        return switch (round) {
            case 1 -> "OPENING";
            case 2 -> "CROSS_EXAM";
            case 3 -> "REBUTTAL";
            case 4 -> "FREE";
            case 5 -> "CLOSING";
            default -> "OPENING";
        };
    }
}
