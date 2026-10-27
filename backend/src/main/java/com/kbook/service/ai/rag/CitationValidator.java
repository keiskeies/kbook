package com.kbook.service.ai.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 引用校验：解析模型输出里的 [[N]] / [[1,2]]，只保留真实存在的片段编号。
 * <p>
 * 合法编号会转成可读的 [N]；非法编号直接剔除并计数。校验结果写入
 * {@code ai_conversations.citation_json}，供评测与前端展示。
 */
public final class CitationValidator {

    public record Result(String answer, String citationJson, int validCount, int invalidCount) {
    }

    /** 支持 [[1]]、[[1,2]]、[[1, 2]] */
    private static final Pattern CITATION =
            Pattern.compile("\\[\\[\\s*([0-9][0-9,\\s]*)\\s*\\]\\]");

    private CitationValidator() {
    }

    public static Result validate(String answer, int includedCount) {
        if (answer == null || answer.isEmpty()) {
            return new Result(answer, null, 0, 0);
        }

        Matcher matcher = CITATION.matcher(answer);
        StringBuilder out = new StringBuilder();
        Set<Integer> valid = new LinkedHashSet<>();
        int invalid = 0;

        while (matcher.find()) {
            List<Integer> groupValid = new ArrayList<>();
            for (String token : matcher.group(1).split("[,\\s]+")) {
                if (token.isBlank()) continue;
                int n;
                try {
                    n = Integer.parseInt(token.trim());
                } catch (NumberFormatException e) {
                    invalid++;
                    continue;
                }
                if (n >= 1 && n <= includedCount) {
                    valid.add(n);
                    if (!groupValid.contains(n)) groupValid.add(n);
                } else {
                    invalid++;
                }
            }
            if (groupValid.isEmpty()) {
                matcher.appendReplacement(out, "");
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement("[" + join(groupValid) + "]"));
            }
        }
        matcher.appendTail(out);

        String cleaned = out.toString()
                .replaceAll("[ \\t]+(?=\\n)", "")
                .trim();

        String json = null;
        if (!valid.isEmpty() || invalid > 0) {
            String validJson = valid.stream().map(String::valueOf).collect(Collectors.joining(","));
            json = "{\"valid\":[" + validJson + "],\"invalid\":" + invalid
                    + ",\"included\":" + includedCount + "}";
        }
        return new Result(cleaned, json, valid.size(), invalid);
    }

    private static String join(List<Integer> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(values.get(i));
        }
        return sb.toString();
    }
}
