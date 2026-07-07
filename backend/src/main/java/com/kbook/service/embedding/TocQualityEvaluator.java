package com.kbook.service.embedding;

import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * TOC 质量评估器 — 为列表型问题 RAG 策略选择提供决策依据。
 * <p>
 * 评分维度（0.0~1.0）：
 * <ul>
 *   <li>行数 ≥ 8 → +0.3（结构完整）</li>
 *   <li>平均行长 > 8 字符 → +0.2（有具体描述）</li>
 *   <li>存在层级编号（1.1 / 2.1）→ +0.2（有子项细分）</li>
 *   <li>具体行占比 > 50% → +0.3（多数行有具体描述，非纯编号）</li>
 * </ul>
 * <p>
 * 评分结果持久化到 Book.tocQualityScore，toc/chapterSummary 变更时由调用方置 null 触发重算。
 */
@Slf4j
@Service
public class TocQualityEvaluator {

    /** 行数阈值 */
    private static final int LINE_COUNT_THRESHOLD = 8;
    /** 平均行长阈值 */
    private static final int AVG_LINE_LENGTH_THRESHOLD = 8;
    /** 具体行占比阈值 */
    private static final double SPECIFIC_LINE_RATIO_THRESHOLD = 0.5;

    /** 层级编号正则：1.1 / 2.1 / 1.1.1 / 一、1.1 等 */
    private static final Pattern HIERARCHY_PATTERN = Pattern.compile("\\d+\\.\\d+");
    /** 纯编号行正则：纯数字+点/顿号/空格，无实际内容 */
    private static final Pattern PURE_NUMBER_LINE_PATTERN = Pattern.compile("^[\\d\\s.、]+$");

    private final BookRepository bookRepository;

    public TocQualityEvaluator(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }

    /**
     * 评估 TOC 质量并持久化（若已缓存则直接返回）。
     *
     * @param book 书籍实体（非空，toc 字段可能为空）
     * @return 质量评分 0.0~1.0；toc 为空时返回 0.0
     */
    @Transactional
    public float evaluateAndCache(Book book) {
        if (book == null || book.getId() == null) return 0.0f;

        // 命中缓存
        if (book.getTocQualityScore() != null) {
            return book.getTocQualityScore();
        }

        float score = evaluate(book.getToc());

        // 只更新单字段，避免全字段 save 重写 TEXT 大字段（toc/parsedContent/ragContent 等）
        try {
            bookRepository.updateTocQualityScore(book.getId(), score);
            // 同步内存实体：直接用 Lombok 生成的 setTocQualityScore（不会触发 setToc 清空逻辑）
            book.setTocQualityScore(score);
            log.debug("TOC 质量评分已持久化: bookId={}, score={}", book.getId(), score);
        } catch (Exception e) {
            log.warn("TOC 质量评分持久化失败: bookId={} - {}", book.getId(), e.getMessage());
        }
        return score;
    }

    /**
     * 评估 TOC 质量评分（无副作用）。
     *
     * @param toc 目录文本（每行一个章节标题），可为 null
     * @return 质量评分 0.0~1.0
     */
    public float evaluate(String toc) {
        if (toc == null || toc.isBlank()) return 0.0f;

        List<String> lines = Arrays.stream(toc.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        if (lines.isEmpty()) return 0.0f;

        int lineCount = lines.size();
        double avgLineLength = lines.stream().mapToInt(String::length).average().orElse(0);
        boolean hasHierarchy = lines.stream().anyMatch(l -> HIERARCHY_PATTERN.matcher(l).find());
        long specificLineCount = lines.stream()
                .filter(l -> !PURE_NUMBER_LINE_PATTERN.matcher(l).matches())
                .filter(l -> l.length() >= 2)
                .count();
        double specificRatio = (double) specificLineCount / lineCount;

        float score = 0.0f;
        if (lineCount >= LINE_COUNT_THRESHOLD) score += 0.3f;
        if (avgLineLength > AVG_LINE_LENGTH_THRESHOLD) score += 0.2f;
        if (hasHierarchy) score += 0.2f;
        if (specificRatio > SPECIFIC_LINE_RATIO_THRESHOLD) score += 0.3f;

        log.debug("TOC 质量评估: lines={}, avgLen={}, hierarchy={}, specificRatio={} → score={}",
                lineCount, String.format("%.1f", avgLineLength), hasHierarchy,
                String.format("%.2f", specificRatio), score);
        return Math.min(score, 1.0f);
    }

    /**
     * TOC 质量分级。
     */
    public enum TocQualityLevel {
        /** score >= 0.7 */
        COMPLETE,
        /** 0.3 <= score < 0.7 */
        MEDIUM,
        /** score < 0.3 */
        INCOMPLETE
    }

    /**
     * 根据评分获取质量分级。
     */
    public static TocQualityLevel levelOf(float score) {
        if (score >= 0.7f) return TocQualityLevel.COMPLETE;
        if (score >= 0.3f) return TocQualityLevel.MEDIUM;
        return TocQualityLevel.INCOMPLETE;
    }
}
