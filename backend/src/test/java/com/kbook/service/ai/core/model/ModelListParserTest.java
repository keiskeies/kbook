package com.kbook.service.ai.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每一种输入形状对应一类真实服务商。少认一种，那家网关的管理员就还得手敲模型名。
 */
class ModelListParserTest {

    private static List<String> ids(String body) {
        return ModelListParser.parse(body).stream().map(ModelSummary::id).toList();
    }

    @Test
    @DisplayName("OpenAI 官方形状：data[].id")
    void openAiShape() {
        assertThat(ids("{\"data\":[{\"id\":\"a\",\"object\":\"model\"},{\"id\":\"b\",\"object\":\"model\"}]}"))
                .containsExactly("a", "b");
    }

    @Test
    @DisplayName("Ollama /api/tags 形状：models[].name")
    void ollamaShape() {
        assertThat(ids("{\"models\":[{\"name\":\"qwen3:8b\",\"size\":4882015657}]}")).containsExactly("qwen3:8b");
    }

    @Test
    @DisplayName("裸数组（部分 vLLM 部署）")
    void bareArray() {
        assertThat(ids("[{\"id\":\"x\"},{\"id\":\"y\"}]")).containsExactly("x", "y");
    }

    @Test
    @DisplayName("两层下钻 data.models（one-api / 各类中转）")
    void nestedDataObject() {
        assertThat(ids("{\"success\":true,\"data\":{\"models\":[{\"id\":\"z\"}]}}")).containsExactly("z");
    }

    @Test
    @DisplayName("items 容器与 model/model_id 字段名也认")
    void alternativeKeys() {
        assertThat(ids("{\"items\":[{\"model\":\"m1\"},{\"model_id\":\"m2\"}]}")).containsExactly("m1", "m2");
    }

    // ==================== 排序 ====================

    @Test
    @DisplayName("created 倒序：新模型排在列表最前（故意让字典序与时间序相反）")
    void sortsNewestFirst() {
        String body = "{\"data\":[{\"id\":\"aaa-old\",\"created\":100},{\"id\":\"zzz-new\",\"created\":200}]}";
        List<ModelSummary> parsed = ModelListParser.parse(body);
        assertThat(parsed).extracting(ModelSummary::id).containsExactly("zzz-new", "aaa-old");
        assertThat(parsed.get(0).createdAt()).isNotNull();
        assertThat(parsed.get(1).createdAt()).isNotNull();
    }

    @Test
    @DisplayName("没有 created 的条目排在有时间的之后，并按名字典序")
    void nullsSortLastAndThenByName() {
        String body = "{\"data\":[{\"id\":\"bbb\"},{\"id\":\"aaa\"},{\"id\":\"c\",\"created\":1}]}";
        assertThat(ids(body)).containsExactly("c", "aaa", "bbb");
    }

    @Test
    @DisplayName("created 给 ISO 字符串也要认")
    void isoCreatedString() {
        String body = "{\"data\":[{\"id\":\"m\",\"created_at\":\"2026-03-01T00:00:00Z\"}]}";
        assertThat(ModelListParser.parse(body).get(0).createdAt()).isEqualTo("2026-03-01T00:00:00Z");
    }

    // ==================== 补充字段 ====================

    @Test
    @DisplayName("上下文长度：Anthropic 的 max_input_tokens、Gemini 的 inputTokenLimit、vLLM 的 n_positions")
    void contextWindowKeys() {
        assertThat(ModelListParser.parse("{\"data\":[{\"id\":\"a\",\"max_input_tokens\":200000}]}")
                .get(0).contextWindow()).isEqualTo(200000);
        assertThat(ModelListParser.parse("{\"data\":[{\"id\":\"a\",\"inputTokenLimit\":1048576}]}")
                .get(0).contextWindow()).isEqualTo(1048576);
        assertThat(ModelListParser.parse("{\"data\":[{\"id\":\"a\",\"n_positions\":\"8192\"}]}")
                .get(0).contextWindow()).isEqualTo(8192);
    }

    @Test
    @DisplayName("上下文长度非法值（0 / 负数 / 文本）落成 null，不能填进 maxTokens")
    void contextWindowIgnoresGarbage() {
        assertThat(ModelListParser.parse("{\"data\":[{\"id\":\"a\",\"context_window\":0}]}")
                .get(0).contextWindow()).isNull();
        assertThat(ModelListParser.parse("{\"data\":[{\"id\":\"a\",\"context_window\":\"huge\"}]}")
                .get(0).contextWindow()).isNull();
    }

    @Test
    @DisplayName("display_name 进 label，前端下拉显示人类可读名")
    void displayName() {
        String body = "{\"data\":[{\"id\":\"claude-sonnet\",\"display_name\":\"Claude Sonnet\"}]}";
        assertThat(ModelListParser.parse(body).get(0).label()).isEqualTo("Claude Sonnet");
    }

    // ==================== 脏数据 ====================

    @Test
    @DisplayName("缺 id 的条目跳过，不产生空选项")
    void skipsBlankIds() {
        assertThat(ids("{\"data\":[{\"id\":\"\"},{\"name\":\"   \"},{\"foo\":1},{\"id\":\"ok\"}]}"))
                .containsExactly("ok");
    }

    @Test
    @DisplayName("同一模型重复出现只留一条")
    void dedupes() {
        assertThat(ids("{\"data\":[{\"id\":\"dup\",\"created\":1},{\"id\":\"dup\",\"created\":9}]}"))
                .containsExactly("dup");
    }

    @Test
    @DisplayName("HTML 错误页 / 截断 JSON / 空文本 → 空列表，不抛异常")
    void neverThrowsOnGarbage() {
        assertThat(ModelListParser.parse("<html><body>502 Bad Gateway</body></html>")).isEmpty();
        assertThat(ModelListParser.parse("{\"data\":[{\"id\":\"a\"")).isEmpty();
        assertThat(ModelListParser.parse("")).isEmpty();
        assertThat(ModelListParser.parse(null)).isEmpty();
        assertThat(ModelListParser.parse("null")).isEmpty();
        assertThat(ModelListParser.parse("{\"error\":{\"message\":\"invalid key\"}}")).isEmpty();
    }

    @Test
    @DisplayName("上限 500 条：与计划文档 §2.2 一致，改常量等于改契约")
    void capsAtLimit() {
        assertThat(ModelListParser.MAX_MODELS).isEqualTo(500);
        String body = "{\"data\":[" + IntStream.rangeClosed(1, 1200)
                .mapToObj(i -> "{\"id\":\"m" + i + "\"}")
                .collect(Collectors.joining(",")) + "]}";
        assertThat(ModelListParser.parse(body)).hasSize(500);
    }

    @Test
    @DisplayName("object=model 之外的条目也不筛除（名字判断类型不可靠）")
    void keepsNonChatEntries() {
        List<ModelSummary> parsed = ModelListParser.parse(
                "{\"data\":[{\"id\":\"text-embedding-3-small\",\"object\":\"model\"},{\"id\":\"gpt-4o\",\"object\":\"model\"}]}");
        assertThat(parsed).extracting(ModelSummary::id).contains("text-embedding-3-small", "gpt-4o");
    }

    @Test
    @DisplayName("冒烟：真实形态混合列表（含 id/created/context_window）")
    void mixedRealWorldShape() {
        String body = """
                {"object":"list","data":[
                  {"id":"deepseek-v4-flash","object":"model","created":1740787200,"max_tokens":1048576},
                  {"id":"deepseek-chat","object":"model","created":1700000000}
                ]}""";
        List<ModelSummary> parsed = ModelListParser.parse(body);
        assertThat(parsed).extracting(ModelSummary::id).containsExactly("deepseek-v4-flash", "deepseek-chat");
        assertThat(parsed.get(0).createdAt()).startsWith("2025-03");
    }
}
