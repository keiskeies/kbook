package com.kbook.service.ai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.config.properties.BookQaProperties;
import com.kbook.entity.AiProviderConfig;
import com.kbook.entity.AiScene;
import com.kbook.entity.Book;
import com.kbook.service.ai.core.AiSceneConfigService;
import com.kbook.service.book.BookStructureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookScaleRouterTest {

    @Mock
    private AiSceneConfigService sceneConfigService;

    private BookQaProperties properties;
    private BookScaleRouter router;

    @BeforeEach
    void setUp() {
        lenient().when(sceneConfigService.resolveConfig(eq(AiScene.BOOK_QA))).thenReturn(
                AiProviderConfig.builder().maxTokens(131072).maxOutputTokens(16384).build());
        properties = new BookQaProperties();
        properties.setScaleRoutingEnabled(true);
        ContextBudget budget = new ContextBudget(sceneConfigService, properties);
        router = new BookScaleRouter(properties, budget,
                new FullTextContextBuilder(properties, new BookStructureService(new ObjectMapper())));
    }

    private static Book book(long id, String fullText) {
        return Book.builder().id(id).title("测试书").fullText(fullText).build();
    }

    @Test
    void disabledSwitchAlwaysUsesRetrieval() {
        properties.setScaleRoutingEnabled(false);
        BookScaleRouter.ScaleDecision d = router.decide(book(1L, "短书"), "s1", "问题", 100, 0);
        assertEquals(ContextMode.LARGE, d.mode());
        assertEquals("disabled", d.reason());
    }

    @Test
    void missingFullTextFallsBackToRetrieval() {
        BookScaleRouter.ScaleDecision d = router.decide(book(1L, null), "s2", "问题", 100, 0);
        assertEquals(ContextMode.LARGE, d.mode());
        assertEquals("no_full_text", d.reason());
    }

    @Test
    void smallBookUsesWholeText() {
        BookScaleRouter.ScaleDecision d = router.decide(book(1L, "这是一本很短的书".repeat(20)),
                "s3", "讲了什么", 100, 0);
        assertEquals(ContextMode.SMALL, d.mode());
        assertEquals("small_book", d.reason());
    }

    @Test
    void hardCapForcesRetrieval() {
        properties.setFullTextMaxChars(1000);
        BookScaleRouter.ScaleDecision d = router.decide(book(1L, "长".repeat(2000)),
                "s4", "讲了什么", 100, 0);
        assertEquals(ContextMode.LARGE, d.mode());
        assertEquals("exceeds_hard_cap", d.reason());
    }

    @Test
    void sessionLockKeepsModeStableAsHistoryGrows() {
        String text = "这是一本很短的书".repeat(20);
        BookScaleRouter.ScaleDecision first = router.decide(book(1L, text), "s5", "问题", 100, 0);
        assertEquals(ContextMode.SMALL, first.mode());
        // 即便下一轮用更大的书 + 巨量历史，仍应沿用锁定档位，避免缓存前缀中途变化
        BookScaleRouter.ScaleDecision second = router.decide(book(1L, "长".repeat(300_000)),
                "s5", "问题", 100, 500_000);
        assertEquals(ContextMode.SMALL, second.mode());
        router.release("s5");
        BookScaleRouter.ScaleDecision third = router.decide(book(1L, "长".repeat(300_000)),
                "s5", "问题", 100, 500_000);
        assertEquals(ContextMode.LARGE, third.mode());
    }

    @Test
    void explicitChapterQuestionUsesMediumMode() {
        properties.setSmallBookRatio(0.0001);
        String text = "第一章 开场\n" + "开场内容".repeat(500)
                + "\n\n第二章 正文\n" + "正文内容".repeat(500);
        BookScaleRouter.ScaleDecision d = router.decide(book(1L, text), "s6",
                "第二章讲了什么", 100, 0);
        assertEquals(ContextMode.MEDIUM, d.mode());
        assertNotNull(d.chapter());
    }
}
