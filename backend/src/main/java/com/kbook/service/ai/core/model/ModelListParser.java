package com.kbook.service.ai.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把服务商返回的模型列表 JSON 读成 {@link ModelSummary} 列表。
 * <p>
 * 纯函数，<b>故意写得宽容</b>：官方 OpenAI 是 {@code {"data":[{"id":...}]}}，
 * 但 Ollama 用 {@code {"models":[{"name":...}]}}、vLLM/one-api/LiteLLM 各自又有
 * {@code {"models":[...]}}、裸数组、{@code {"data":{"models":[...]}}} 等形状。
 * 只认一种形状就等于让自定义网关继续手敲模型名。
 * <p>
 * 解析失败一律返回空列表而不抛异常 —— 拉不到列表是「降级为手动填写」，不是错误页。
 */
public final class ModelListParser {

    /** 返回给前端的条目上限（中转网关动辄上千条，再多就没法看了） */
    public static final int MAX_MODELS = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> CONTAINER_KEYS = List.of("data", "models", "items");
    private static final List<String> ID_KEYS = List.of("id", "name", "model", "model_id");
    private static final List<String> LABEL_KEYS =
            List.of("display_name", "displayName", "humanname", "friendly_name", "label", "title");
    private static final List<String> CONTEXT_KEYS =
            List.of("context_window", "context_length", "max_input_tokens", "input_token_limit",
                    "inputTokenLimit", "n_positions");
    private static final List<String> CREATED_KEYS = List.of("created", "created_at", "createdAt");

    private ModelListParser() {
    }

    /**
     * @param body 原始响应文本
     * @return 去重、新模型在前、截断到 {@link #MAX_MODELS} 的列表；解析不出来时为空列表
     */
    public static List<ModelSummary> parse(String body) {
        JsonNode array = findArray(read(body), 0);
        if (array == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Row> rows = new ArrayList<>();
        for (JsonNode node : array) {
            String id = firstText(node, ID_KEYS);
            if (id == null || id.isBlank() || !seen.add(id)) {
                continue;
            }
            Long epochSec = toEpoch(firstValue(node, CREATED_KEYS));
            rows.add(new Row(new ModelSummary(id, firstText(node, LABEL_KEYS),
                    firstInt(node, CONTEXT_KEYS), isoSeconds(epochSec)), epochSec));
        }
        rows.sort(Comparator.comparing((Row r) -> r.epochSec, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(r -> r.summary.id().toLowerCase(Locale.ROOT)));
        return rows.stream().limit(MAX_MODELS).map(r -> r.summary).toList();
    }

    private static JsonNode read(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            return root == null || root.isNull() || root.isMissingNode() ? null : root;
        } catch (Exception e) {
            // HTML 错误页、纯文本、截断的 JSON 都走这里
            return null;
        }
    }

    /** 逐层找数组容器，最多下钻两层（够覆盖 {@code {"data":{"models":[...]}}}） */
    private static JsonNode findArray(JsonNode node, int depth) {
        if (node == null || depth > 2) {
            return null;
        }
        if (node.isArray()) {
            return node;
        }
        if (!node.isObject()) {
            return null;
        }
        for (String key : CONTAINER_KEYS) {
            JsonNode child = node.get(key);
            if (child == null) {
                continue;
            }
            if (child.isArray()) {
                return child;
            }
            JsonNode deeper = findArray(child, depth + 1);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    private static JsonNode firstValue(JsonNode node, List<String> keys) {
        for (String key : keys) {
            JsonNode child = node.get(key);
            if (child != null && !child.isNull() && !child.isContainerNode()) {
                return child;
            }
        }
        return null;
    }

    private static String firstText(JsonNode node, List<String> keys) {
        JsonNode value = firstValue(node, keys);
        return value == null ? null : value.asText();
    }

    private static Integer firstInt(JsonNode node, List<String> keys) {
        JsonNode value = firstValue(node, keys);
        if (value == null) {
            return null;
        }
        if (value.isNumber()) {
            long v = value.asLong();
            return v > 0 && v <= Integer.MAX_VALUE ? (int) v : null;
        }
        try {
            int v = Integer.parseInt(value.asText().trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long toEpoch(JsonNode value) {
        if (value == null) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        String text = value.asText().trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.chars().allMatch(Character::isDigit)) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return Instant.parse(text).getEpochSecond();
        } catch (Exception e) {
            return null;
        }
    }

    private static String isoSeconds(Long epochSec) {
        return epochSec == null ? null : Instant.ofEpochSecond(epochSec).toString();
    }

    private record Row(ModelSummary summary, Long epochSec) {
    }
}
