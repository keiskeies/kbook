package com.kbook.service.debate;

import com.kbook.common.exception.BusinessException;
import com.kbook.entity.AiScene;
import com.kbook.entity.debate.DebateMessage;
import com.kbook.entity.debate.DebateSession;
import com.kbook.repository.debate.DebateMessageRepository;
import com.kbook.repository.debate.DebateSessionRepository;
import com.kbook.service.ai.core.ChatModelManager;
import com.kbook.common.util.QueryBuilder;
import com.kbook.common.util.SFunction;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 辩论轮次状态机单测（A5 硬约束的后端面）。
 * <p>
 * 锁定两条流程铁律：
 * <ol>
 *   <li>阶段流转矩阵 OPENING→CROSS_EXAM→REBUTTAL→FREE→CLOSING，CLOSING 后只能 COMPLETED；</li>
 *   <li>自由辩论发言人选择的「交替规则」——下一发言人必须与上一位异方，
 *       LLM 违反（同方/非法 key/失败）时强制回退到对方发言最少者。</li>
 * </ol>
 */
class DebateRoundServiceTest {

    private ChatModelManager chatModelManager;
    private DebateSessionRepository sessionRepository;
    private DebateMessageRepository messageRepository;
    private DebateSessionService sessionService;
    private DebateRoundService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatModelManager = mock(ChatModelManager.class);
        sessionRepository = mock(DebateSessionRepository.class);
        messageRepository = mock(DebateMessageRepository.class);
        sessionService = mock(DebateSessionService.class);
        service = new DebateRoundService(chatModelManager, sessionRepository, messageRepository, sessionService);
    }

    private static DebateSession session(String phase, int round) {
        return DebateSession.builder()
                .userId(7L).sessionId("s1").bookId(1L).topic("t")
                .currentPhase(phase).currentRound(round).status("ACTIVE")
                .build();
    }

    private void stubSession(DebateSession s) {
        // 拆分后 getSessionBySessionId 走 DebateSessionService（本测试中为 mock）
        when(sessionService.getSessionBySessionId("s1")).thenReturn(s);
    }

    private static DebateMessage msg(String positionKey, String side) {
        DebateMessage m = mock(DebateMessage.class);
        when(m.getPositionKey()).thenReturn(positionKey);
        when(m.getRoleKey()).thenReturn(positionKey);
        when(m.getSide()).thenReturn(side);
        return m;
    }

    // ==================== advanceRound：阶段流转矩阵 ====================

    @Nested
    @DisplayName("advanceRound — A5 阶段流转硬约束")
    class AdvanceRound {

        @Test
        void 开篇后进入交叉质询_轮次2() {
            DebateSession s = session("OPENING", 1);
            stubSession(s);

            var vo = service.advanceRound(7L, "s1");

            assertThat(s.getCurrentPhase()).isEqualTo("CROSS_EXAM");
            assertThat(s.getCurrentRound()).isEqualTo(2);
            assertThat(vo.getCurrentPhase()).isEqualTo("CROSS_EXAM");
        }

        @Test
        void 交叉质询后进入驳论_轮次3() {
            DebateSession s = session("CROSS_EXAM", 2);
            stubSession(s);
            service.advanceRound(7L, "s1");
            assertThat(s.getCurrentPhase()).isEqualTo("REBUTTAL");
            assertThat(s.getCurrentRound()).isEqualTo(3);
        }

        @Test
        void 驳论后进入自由辩论_轮次4() {
            DebateSession s = session("REBUTTAL", 3);
            stubSession(s);
            service.advanceRound(7L, "s1");
            assertThat(s.getCurrentPhase()).isEqualTo("FREE");
            assertThat(s.getCurrentRound()).isEqualTo(4);
        }

        @Test
        void 自由辩论后进入总结陈词_轮次5() {
            DebateSession s = session("FREE", 4);
            stubSession(s);
            service.advanceRound(7L, "s1");
            assertThat(s.getCurrentPhase()).isEqualTo("CLOSING");
            assertThat(s.getCurrentRound()).isEqualTo(5);
        }

        @Test
        void 总结后再推进只置COMPLETED_阶段不再变化() {
            DebateSession s = session("CLOSING", 5);
            stubSession(s);

            service.advanceRound(7L, "s1");

            assertThat(s.getStatus()).isEqualTo("COMPLETED");
            assertThat(s.getCurrentPhase()).isEqualTo("CLOSING");
            assertThat(s.getCurrentRound()).isEqualTo(5);
        }

        @Test
        void 会话不存在抛异常() {
            stubSession(null);
            assertThatThrownBy(() -> service.advanceRound(7L, "s1"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        void 非所有者抛异常() {
            stubSession(session("OPENING", 1));
            assertThatThrownBy(() -> service.advanceRound(99L, "s1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("无权");
        }
    }

    // ==================== getNextSpeakerFree：交替规则 ====================

    @Nested
    @DisplayName("getNextSpeakerFree — A5 交替规则硬约束")
    class NextSpeakerFree {

        private void stubFlow(DebateSession s, List<DebateMessage> all, List<DebateMessage> round,
                              String llmResult) {
            stubSession(s);
            when(messageRepository.findBySessionIdOrderById("s1")).thenReturn(all);
            when(messageRepository.findBySessionIdAndRoundNumberOrderByPhaseOrder("s1", s.getCurrentRound()))
                    .thenReturn(round);
            when(chatModelManager.callAiForScene(eq(AiScene.DEBATE_SPEAKER_SELECT), anyString(),
                    anyString(), anyList()))
                    .thenReturn(llmResult);
            // side 工具按真实语义 stub
            when(sessionService.getSideFromPositionKey(anyString())).thenAnswer(inv -> {
                String k = inv.getArgument(0);
                if (k == null || "HOST".equals(k)) return "NEUTRAL";
                return k.startsWith("PRO") ? "PRO" : "CON";
            });
            when(sessionService.getSideName(anyString())).thenAnswer(inv -> {
                String k = inv.getArgument(0);
                return "PRO".equals(k) ? "正方" : "CON".equals(k) ? "反方" : "";
            });
        }

        @Test
        void LLM返回合法且异方_原样采纳() {
            // 上一位 PRO_1（正方），LLM 选 CON_2（反方）→ 交替合法
            stubFlow(session("FREE", 4),
                    List.of(msg("PRO_1", "PRO")),
                    List.of(msg("PRO_1", "PRO")),
                    "CON_2");

            assertThat(service.getNextSpeakerFree(7L, "s1")).isEqualTo("CON_2");
        }

        @Test
        void LLM返回同方_强制回退到对方发言最少者() {
            // 上一位 PRO_1，LLM 仍选 PRO_3（同方）→ 回退 CON 方发言最少者
            stubFlow(session("FREE", 4),
                    List.of(msg("PRO_1", "PRO"), msg("CON_1", "CON"), msg("CON_1", "CON")),
                    List.of(msg("PRO_1", "PRO")),
                    "PRO_3");

            // CON_1 已发言 2 次，CON_2/3/4 为 0 → 应选 CON_2（首个最少）
            assertThat(service.getNextSpeakerFree(7L, "s1")).isEqualTo("CON_2");
        }

        @Test
        void LLM返回非法key_回退对方最少() {
            stubFlow(session("FREE", 4),
                    List.of(msg("CON_2", "CON")),
                    List.of(msg("CON_2", "CON")),
                    "NOT_A_KEY");

            // 上一位 CON → 回退 PRO 方发言最少 → PRO_1
            assertThat(service.getNextSpeakerFree(7L, "s1")).isEqualTo("PRO_1");
        }

        @Test
        void LLM调用失败_回退对方最少() {
            stubFlow(session("FREE", 4), List.of(msg("PRO_1", "PRO")), List.of(msg("PRO_1", "PRO")), null);
            when(chatModelManager.callAiForScene(eq(AiScene.DEBATE_SPEAKER_SELECT), anyString(),
                    anyString(), anyList())).thenThrow(new RuntimeException("provider down"));

            assertThat(service.getNextSpeakerFree(7L, "s1")).isEqualTo("CON_1");
        }

        @Test
        void 无历史消息_回退正方最少() {
            stubFlow(session("FREE", 4), List.of(), List.of(), "PRO_1");

            assertThat(service.getNextSpeakerFree(7L, "s1")).isEqualTo("PRO_1");
        }

        @Test
        void 会话不存在返回null() {
            stubSession(null);
            assertThat(service.getNextSpeakerFree(7L, "s1")).isNull();
        }

        @Test
        void 非所有者抛异常() {
            stubSession(session("FREE", 4));
            assertThatThrownBy(() -> service.getNextSpeakerFree(99L, "s1"))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
