package com.kbook.service.debate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.util.QueryBuilder;
import com.kbook.common.util.SFunction;
import com.kbook.entity.AiScene;
import com.kbook.entity.debate.DebateMessage;
import com.kbook.entity.debate.DebateScore;
import com.kbook.entity.debate.DebateSession;
import com.kbook.repository.debate.DebateMessageRepository;
import com.kbook.repository.debate.DebateScoreRepository;
import com.kbook.repository.debate.DebateSessionRepository;
import com.kbook.service.ai.core.ChatModelManager;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 辩论评分服务单测（阶段 2 第 2 批 — Mockito）。
 * <p>
 * 重点：KV/JSON 双解析、平均分 round1、异常兜底不阻塞、查询映射 VO、轮次中文标签拼装。
 */
class DebateScoringServiceTest {

    private ChatModelManager chatModelManager;
    private DebateScoreRepository scoreRepository;
    private DebateMessageRepository messageRepository;
    private DebateSessionRepository sessionRepository;
    private DebateScoringService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatModelManager = mock(ChatModelManager.class);
        scoreRepository = mock(DebateScoreRepository.class);
        messageRepository = mock(DebateMessageRepository.class);
        sessionRepository = mock(DebateSessionRepository.class);
        service = new DebateScoringService(chatModelManager, scoreRepository,
                messageRepository, sessionRepository, new ObjectMapper());
    }

    /** 让 doScore 走通前置：有消息 + 有会话（辩题），按指定轮次打桩 */
    @SuppressWarnings("unchecked")
    private void stubSession(String topic, int round) {
        DebateMessage message = mock(DebateMessage.class);
        when(message.getId()).thenReturn(42L);
        when(messageRepository.findBySessionIdAndRoundNumberOrderByPhaseOrder("s1", round))
                .thenReturn(List.of(message));

        DebateSession session = mock(DebateSession.class);
        when(session.getTopic()).thenReturn(topic);
        var queryBuilder = mock(QueryBuilder.class);
        when(sessionRepository.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(any(SFunction.class), any())).thenReturn(queryBuilder);
        when(queryBuilder.list(1)).thenReturn(List.of(session));
    }

    @Test
    @DisplayName("无消息时不评分不落库")
    void noMessagesSkipsScoring() {
        when(messageRepository.findBySessionIdAndRoundNumberOrderByPhaseOrder("s1", 3))
                .thenReturn(List.of());

        service.scoreSpeechAsync(1L, "s1", "pro_main", "pro", "正方", "内容", 3, "FREE");

        verify(chatModelManager, never()).callAiForScene(any(), anyString(), anyString(), anyList());
        verify(scoreRepository, never()).save(any());
    }

    @Test
    @DisplayName("行式 KV 解析 → 7 维度落库，平均分保留 1 位小数")
    void kvScoresParsedAndSaved() {
        stubSession("躺平是不是毒药", 3);
        when(chatModelManager.callAiForScene(eq(AiScene.DEBATE_SCORING), anyString(), anyString(), anyList()))
                .thenReturn("""
                        logicScore: 8.0
                        evidenceScore: 7.0
                        rebuttalScore: 6.0
                        impactScore: 7.5
                        humorScore: 5.0
                        clarityScore: 6.0
                        noveltyScore: 7.0""");
        when(scoreRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.scoreSpeechAsync(1L, "s1", "pro_main", "pro", "正方", "内容", 3, "FREE");

        ArgumentCaptor<DebateScore> captor = ArgumentCaptor.forClass(DebateScore.class);
        verify(scoreRepository).save(captor.capture());
        DebateScore saved = captor.getValue();
        assertThat(saved.getLogicScore()).isEqualTo(8.0);
        assertThat(saved.getMessageId()).isEqualTo(42L);
        assertThat(saved.getRoundType()).isEqualTo("FREE");
        // (8+7+6+7.5+5+6+7)/7 = 6.642857 → round1 = 6.6
        assertThat(saved.getAverageScore()).isEqualTo(6.6);
    }

    @Test
    @DisplayName("LLM 偶发输出 JSON 也能解析落库")
    void jsonScoresCompat() {
        stubSession("躺平是不是毒药", 3);
        when(chatModelManager.callAiForScene(eq(AiScene.DEBATE_SCORING), anyString(), anyString(), anyList()))
                .thenReturn("{\"logicScore\":9.0,\"evidenceScore\":9.0,\"rebuttalScore\":9.0,"
                        + "\"impactScore\":9.0,\"humorScore\":9.0,\"clarityScore\":9.0,\"noveltyScore\":9.0}");
        when(scoreRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.scoreSpeechAsync(1L, "s1", "pro_main", "pro", "正方", "内容", 3, "REBUTTAL");

        ArgumentCaptor<DebateScore> captor = ArgumentCaptor.forClass(DebateScore.class);
        verify(scoreRepository).save(captor.capture());
        assertThat(captor.getValue().getAverageScore()).isEqualTo(9.0);
    }

    @Test
    @DisplayName("用户提示词含辩题与轮次中文标签")
    void promptContainsTopicAndRoundLabel() {
        stubSession("躺平是不是毒药", 2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        when(chatModelManager.callAiForScene(eq(AiScene.DEBATE_SCORING), anyString(), anyString(), anyList()))
                .thenReturn(null);

        service.scoreSpeechAsync(1L, "s1", "con_main", "con", "反方", "内容", 2, "CROSS_EXAM");

        verify(chatModelManager).callAiForScene(eq(AiScene.DEBATE_SCORING), anyString(), anyString(),
                messagesCaptor.capture());
        UserMessage userMessage = (UserMessage) messagesCaptor.getValue().get(1);
        String text = userMessage.singleText();
        assertThat(text).contains("躺平是不是毒药");
        assertThat(text).contains("交叉质询");
        assertThat(text).contains("反方");
    }

    @Test
    @DisplayName("LLM 返回空白 → 不落库")
    void blankResponseSkipsSave() {
        stubSession("辩题", 3);
        when(chatModelManager.callAiForScene(eq(AiScene.DEBATE_SCORING), anyString(), anyString(), anyList()))
                .thenReturn("  ");

        service.scoreSpeechAsync(1L, "s1", "pro_main", "pro", "正方", "内容", 3, "FREE");

        verify(scoreRepository, never()).save(any());
    }

    @Test
    @DisplayName("LLM 抛异常 → 异步方法吞掉不外抛")
    void llmExceptionSwallowed() {
        stubSession("辩题", 3);
        when(chatModelManager.callAiForScene(eq(AiScene.DEBATE_SCORING), anyString(), anyString(), anyList()))
                .thenThrow(new RuntimeException("provider down"));

        service.scoreSpeechAsync(1L, "s1", "pro_main", "pro", "正方", "内容", 3, "FREE");

        verify(scoreRepository, never()).save(any());
    }

    @Test
    @DisplayName("评分解析失败（乱文本）→ 不落库")
    void unparseableResponseSkipsSave() {
        stubSession("辩题", 3);
        when(chatModelManager.callAiForScene(eq(AiScene.DEBATE_SCORING), anyString(), anyString(), anyList()))
                .thenReturn("模型今天心情不好。");

        service.scoreSpeechAsync(1L, "s1", "pro_main", "pro", "正方", "内容", 3, "FREE");

        verify(scoreRepository, never()).save(any());
    }

    @Test
    @DisplayName("按会话查询映射 VO")
    void scoresBySessionMapped() {
        DebateScore entity = DebateScore.builder()
                .sessionId("s1").roleKey("pro_main").roundNumber(1)
                .logicScore(8.0).averageScore(7.5).build();
        when(scoreRepository.findBySessionId("s1")).thenReturn(List.of(entity));

        var vos = service.getScoresBySession("s1");

        assertThat(vos).hasSize(1);
        assertThat(vos.get(0).getRoleKey()).isEqualTo("pro_main");
    }

    @Test
    @DisplayName("按轮次查询映射 VO")
    void scoresByRoundMapped() {
        when(scoreRepository.findBySessionIdAndRoundNumber("s1", 2)).thenReturn(List.of());

        assertThat(service.getScoresByRound("s1", 2)).isEmpty();
    }

    @Test
    @DisplayName("会话缺失时辩题兜底为未知")
    void missingSessionFallsBackTopic() {
        DebateMessage message = mock(DebateMessage.class);
        when(message.getId()).thenReturn(42L);
        when(messageRepository.findBySessionIdAndRoundNumberOrderByPhaseOrder("s1", 3))
                .thenReturn(List.of(message));
        @SuppressWarnings("unchecked")
        var queryBuilder = mock(QueryBuilder.class);
        when(sessionRepository.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(any(SFunction.class), any())).thenReturn(queryBuilder);
        when(queryBuilder.list(1)).thenReturn(List.of());

        when(chatModelManager.callAiForScene(eq(AiScene.DEBATE_SCORING), anyString(), anyString(), anyList()))
                .thenReturn("logicScore: 8.0");
        when(scoreRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.scoreSpeechAsync(1L, "s1", "pro_main", "pro", "正方", "内容", 3, "FREE");

        ArgumentCaptor<DebateScore> captor = ArgumentCaptor.forClass(DebateScore.class);
        verify(scoreRepository).save(captor.capture());
        assertThat(captor.getValue().getLogicScore()).isEqualTo(8.0);
    }
}
