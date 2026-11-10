package com.kbook.service.ai.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 统一上下文预算：验证「上下文 − 输出 − 历史 − 开销 − 安全余量」这条减法。 */
class ContextBudgetTest {

    @Test
    void subtractsOutputHistoryAndOverhead() {
        ContextBudget.Budget b = ContextBudget.compute(100_000, 10_000, 1.5, 0.15, 5_000, 20_000);
        assertEquals(100_000, b.contextTokens());
        assertEquals(10_000, b.outputTokens());
        assertEquals(135_000, b.grossChars());      // (100000 - 10000) × 1.5
        assertEquals(25_000, b.reservedChars());    // 5000 开销 + 20000 历史
        assertEquals(89_750, b.availableChars());   // 135000 − 20250 安全余量 − 25000
    }

    @Test
    void longerHistoryShrinksAvailableSpace() {
        ContextBudget.Budget shortHistory = ContextBudget.compute(100_000, 10_000, 1.5, 0.15, 5_000, 0);
        ContextBudget.Budget longHistory = ContextBudget.compute(100_000, 10_000, 1.5, 0.15, 5_000, 50_000);
        assertEquals(50_000, shortHistory.availableChars() - longHistory.availableChars());
    }

    @Test
    void outputNeverExceedsHalfOfContext() {
        ContextBudget.Budget b = ContextBudget.compute(8_000, 7_000, 1.5, 0.0, 0, 0);
        assertEquals(4_000, b.outputTokens());
    }

    @Test
    void neverReturnsNegativeAvailable() {
        ContextBudget.Budget b = ContextBudget.compute(2_048, 1_024, 1.5, 0.15, 100_000, 100_000);
        assertEquals(0, b.availableChars());
        assertTrue(b.reservedChars() >= 0);
    }

    @Test
    void fallsBackToDefaultCharRatio() {
        ContextBudget.Budget b = ContextBudget.compute(10_000, 1_000, 0, 0.0, 0, 0);
        assertEquals(13_500, b.grossChars()); // 默认 1.5
    }
}
