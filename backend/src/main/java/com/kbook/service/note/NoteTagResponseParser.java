package com.kbook.service.note;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.util.CommonUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 解析「笔记自动标签」的模型输出。
 * <p>
 * 模型只能做选择题：任何不在候选词库里的标签一律丢弃，宁可为空也不污染用户标签体系。
 */
public final class NoteTagResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NoteTagResponseParser() {
    }

    /**
     * @param raw        LLM 原始输出，允许带代码围栏或前后解释
     * @param candidates 候选词库
     * @param max        最多保留几个
     * @return 与候选词库逐字匹配的标签，按模型输出顺序去重；解析失败返回空列表
     */
    public static List<String> parse(String raw, Collection<String> candidates, int max) {
        if (raw == null || raw.isBlank() || candidates == null || candidates.isEmpty() || max <= 0) {
            return List.of();
        }
        String text = CommonUtils.stripCodeFence(raw);
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }

        Map<String, String> canonical = new LinkedHashMap<>();
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                canonical.putIfAbsent(candidate.toLowerCase(Locale.ROOT), candidate);
            }
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            return List.of();
        }
        if (!root.isArray()) {
            return List.of();
        }

        Set<String> selected = new LinkedHashSet<>();
        for (JsonNode node : root) {
            if (selected.size() >= max) {
                break;
            }
            if (!node.isTextual()) {
                continue;
            }
            String match = canonical.get(node.asText().strip().toLowerCase(Locale.ROOT));
            if (match != null) {
                selected.add(match);
            }
        }
        return List.copyOf(selected);
    }
}
