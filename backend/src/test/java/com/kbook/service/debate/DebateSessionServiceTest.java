package com.kbook.service.debate;

import com.kbook.common.exception.BusinessException;
import com.kbook.entity.debate.DebateSession;
import com.kbook.repository.debate.DebateMessageRepository;
import com.kbook.repository.debate.DebateSessionRepository;
import com.kbook.repository.BookRepository;
import com.kbook.service.progress.ReadingProgressService;
import com.kbook.repository.debate.DebateScoreRepository;
import com.kbook.common.util.QueryBuilder;
import com.kbook.common.util.SFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 辩论会话服务单测（A5 硬约束的立场/位置键矩阵 + 会话生命周期）。
 */
class DebateSessionServiceTest {

    private DebateSessionRepository sessionRepository;
    private DebateMessageRepository messageRepository;
    private StringRedisTemplate stringRedisTemplate;
    private ReadingProgressService readingProgressService;
    private DebateSessionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sessionRepository = mock(DebateSessionRepository.class);
        messageRepository = mock(DebateMessageRepository.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        readingProgressService = mock(ReadingProgressService.class);
        service = new DebateSessionService(sessionRepository, messageRepository, stringRedisTemplate,
                readingProgressService, mock(BookRepository.class), mock(DebateScoreRepository.class));
    }

    // ==================== 立场与位置键工具（A5 前后端契约） ====================

    @Nested
    @DisplayName("位置键工具矩阵")
    class PositionKeyTools {

        @Test
        void 立场解析_PRO与CON与HOST() {
            assertThat(service.getSideFromPositionKey("PRO_1")).isEqualTo("PRO");
            assertThat(service.getSideFromPositionKey("PRO_4")).isEqualTo("PRO");
            assertThat(service.getSideFromPositionKey("CON_1")).isEqualTo("CON");
            assertThat(service.getSideFromPositionKey("HOST")).isEqualTo("NEUTRAL");
            assertThat(service.getSideFromPositionKey(null)).isEqualTo("NEUTRAL");
        }

        @Test
        void 立场中文名() {
            assertThat(service.getSideName("PRO")).isEqualTo("正方");
            assertThat(service.getSideName("CON")).isEqualTo("反方");
            assertThat(service.getSideName("NEUTRAL")).isEmpty();
        }

        @Test
        void 位置全称_HOST中立() {
            assertThat(service.getSideFull("PRO_2")).isEqualTo("正方");
            assertThat(service.getSideFull("CON_3")).isEqualTo("反方");
            assertThat(service.getSideFull("HOST")).isEqualTo("主持人（中立）");
        }

        @Test
        void 位置标签一到四辩() {
            assertThat(service.getPositionLabel("PRO_1")).isEqualTo("正方一辩");
            assertThat(service.getPositionLabel("PRO_4")).isEqualTo("正方四辩");
            assertThat(service.getPositionLabel("CON_2")).isEqualTo("反方二辩");
            assertThat(service.getPositionLabel("HOST")).isEqualTo("主持人");
        }

        @Test
        void 性格键按辩位序号取_越界兜底LOGICAL() {
            DebateSession s = DebateSession.builder()
                    .proRoleKeys("LOGICAL,SHARP,HUMOROUS,EMPATHETIC")
                    .conRoleKeys("SENSITIVE,DOMINEERING")
                    .build();
            assertThat(service.getPersonalityKey(s, "PRO_1")).isEqualTo("LOGICAL");
            assertThat(service.getPersonalityKey(s, "PRO_3")).isEqualTo("HUMOROUS");
            assertThat(service.getPersonalityKey(s, "CON_2")).isEqualTo("DOMINEERING");
            // CON 只有 2 个角色，三辩越界 → 兜底
            assertThat(service.getPersonalityKey(s, "CON_3")).isEqualTo("LOGICAL");
            assertThat(service.getPersonalityKey(s, "HOST")).isEqualTo("HOST");
        }
    }

    // ==================== 会话生命周期 ====================

    @Nested
    @DisplayName("会话生命周期")
    class Lifecycle {

        @Test
        void 创建会话_空角色键用默认且写Redis与阅读历史() {
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> ops = mock(ValueOperations.class);
            when(stringRedisTemplate.opsForValue()).thenReturn(ops);
            when(sessionRepository.save(any(DebateSession.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            var vo = service.createSession(7L, 1L, "躺平是不是毒药", null, null, " ", null);

            assertThat(vo.getTopic()).isEqualTo("躺平是不是毒药");
            assertThat(vo.getProRoleKeys()).isEqualTo("LOGICAL,SHARP,HUMOROUS,EMPATHETIC");
            assertThat(vo.getConRoleKeys()).isEqualTo("SENSITIVE,DOMINEERING,ERUDITE,PRACTICAL");
            assertThat(vo.getCurrentPhase()).isEqualTo("OPENING");
            verify(ops).set(anyString(), anyString());
            verify(readingProgressService).reportProgress(7L, 1L, 0.0, "chat");
        }

        @Test
        @SuppressWarnings("unchecked")
        void 获取不存在的会话抛异常() {
            var qb = mock(QueryBuilder.class);
            when(sessionRepository.query()).thenReturn(qb);
            when(qb.where(any(SFunction.class), any())).thenReturn(qb);
            when(qb.list(1)).thenReturn(List.of());

            assertThatThrownBy(() -> service.getSession("nope"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("会话不存在");
        }

        @Test
        @SuppressWarnings("unchecked")
        void 删除不存在的会话抛异常() {
            var qb = mock(QueryBuilder.class);
            when(sessionRepository.query()).thenReturn(qb);
            when(qb.where(any(SFunction.class), any())).thenReturn(qb);
            when(qb.and(any(SFunction.class), any())).thenReturn(qb);
            when(qb.list(1)).thenReturn(List.of());

            assertThatThrownBy(() -> service.deleteSession(7L, "nope"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不存在");
        }

        @Test
        @SuppressWarnings("unchecked")
        void 删除会话置ABANDONED并清Redis() {
            DebateSession s = DebateSession.builder().userId(7L).sessionId("s1").status("ACTIVE").build();
            var qb = mock(QueryBuilder.class);
            when(sessionRepository.query()).thenReturn(qb);
            when(qb.where(any(SFunction.class), any())).thenReturn(qb);
            when(qb.and(any(SFunction.class), any())).thenReturn(qb);
            when(qb.list(1)).thenReturn(List.of(s));

            service.deleteSession(7L, "s1");

            assertThat(s.getStatus()).isEqualTo("ABANDONED");
            verify(sessionRepository).save(s);
            verify(stringRedisTemplate).delete(anyString());
        }
    }
}
