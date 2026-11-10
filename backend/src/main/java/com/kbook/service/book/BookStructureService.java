package com.kbook.service.book;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 章节结构的序列化与读取。
 * <p>
 * 设计见 {@code docs/plans/rag-ingestion-and-chunking-plan.md} §4.1：
 * 保留 {@code full_text} 原文不动，结构作为独立 JSON 字段在索引阶段生成一次。
 * MEDIUM 档按章取回原文时直接读它，不必对百万字全文重跑扫描。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookStructureService {

    private final ObjectMapper objectMapper;

    public record ChapterRef(int index, String title, int startChar, int endChar) {
    }

    /** 从全书原文提取章节结构并序列化；返回 null 表示无法生成（调用方保留旧值）。 */
    public String build(String fullText) {
        if (fullText == null || fullText.isBlank()) return null;
        try {
            List<ChapterRef> refs = TextStructureExtractor.extract(fullText).stream()
                    .map(c -> new ChapterRef(c.index(), c.title(), c.startChar(), c.endChar()))
                    .toList();
            return objectMapper.writeValueAsString(refs);
        } catch (Exception e) {
            log.warn("[BookStructure] 序列化章节结构失败: {}", e.getMessage());
            return null;
        }
    }

    /** 反序列化；JSON 缺失或损坏时返回空列表，由调用方回退到运行时扫描。 */
    public List<TextStructureExtractor.Chapter> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<ChapterRef> refs = objectMapper.readValue(json, new TypeReference<>() {
            });
            return refs.stream()
                    .map(r -> new TextStructureExtractor.Chapter(
                            r.index(), r.title(), r.startChar(), r.endChar()))
                    .toList();
        } catch (Exception e) {
            log.warn("[BookStructure] 解析章节结构失败，回退运行时扫描: {}", e.getMessage());
            return List.of();
        }
    }
}
