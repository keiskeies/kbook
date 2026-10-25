package com.kbook.service.ai.outline;

import com.kbook.config.ChatModelFactory;
import com.kbook.entity.AiScene;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回答条目化服务单测（阶段 2 第 2 批 — Mockito）。
 * <p>
 * 重点：围栏/杂文本容忍解析、id 补全与锚点置空的规范化、条目数上下限守卫、DB 读取路径。
 */
class AnswerOutlineServiceTest {

    private ChatModelFactory chatModelFactory;
    private ChatModel chatModel;
    private AnswerOutlineService service;

    @BeforeEach
    void setUp() {
        chatModelFactory = mock(ChatModelFactory.class);
        chatModel = mock(ChatModel.class);
        service = new AnswerOutlineService(chatModelFactory);
    }

    private void llmReturns(String text) {
        when(chatModelFactory.buildForScene(AiScene.ANSWER_OUTLINE)).thenReturn(chatModel);
        when(chatModel.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from(text)).build());
    }

    private static String outlineJson(int itemCount) {
        StringBuilder sb = new StringBuilder("{\"topic\":\"列表主题\",\"items\":[");
        for (int i = 1; i <= itemCount; i++) {
            if (i > 1) sb.append(',');
            sb.append(String.format(
                    "{\"id\":null,\"title\":\"条目%d\",\"gist\":\"摘要%d\",\"keywords\":[\"k%d\"],\"chunkAnchors\":[%d]}",
                    i, i, i, i));
        }
        sb.append("]}");
        return sb.toString();
    }

    @Nested
    @DisplayName("build — 现场条目化")
    class Build {

        @Test
        void 空回答直接返回null不调模型() {
            assertThat(service.build(null)).isNull();
            assertThat(service.build("  ")).isNull();
        }

        @Test
        void 模型未配置返回null() {
            when(chatModelFactory.buildForScene(AiScene.ANSWER_OUTLINE)).thenReturn(null);

            assertThat(service.build("上一轮回答")).isNull();
        }

        @Test
        void 正常输出_围栏与杂文本容忍_id补全_锚点置空() {
            llmReturns("好的，以下是结构化结果：\n```json\n" + outlineJson(3) + "\n```\n以上。");

            AnswerOutline outline = service.build("上一轮回答");

            assertThat(outline).isNotNull();
            assertThat(outline.topic()).isEqualTo("列表主题");
            assertThat(outline.items()).hasSize(3);
            // id 缺失时按序号补全；chunkAnchors 统一置空（防跨轮漂移）
            assertThat(outline.items().get(0).id()).isEqualTo(1);
            assertThat(outline.items().get(2).id()).isEqualTo(3);
            assertThat(outline.items().get(0).chunkAnchors()).isNull();
            assertThat(outline.items().get(0).keywords()).containsExactly("k1");
        }

        @Test
        void 条目不足2个返回null() {
            llmReturns(outlineJson(1));

            assertThat(service.build("上一轮回答")).isNull();
        }

        @Test
        void 条目数超过合理性上限返回null() {
            llmReturns(outlineJson(31));

            assertThat(service.build("上一轮回答")).isNull();
        }

        @Test
        void 非列表结构空items返回null() {
            llmReturns("{\"topic\":\"x\",\"items\":[]}");

            assertThat(service.build("上一轮回答")).isNull();
        }

        @Test
        void 无JSON输出返回null() {
            llmReturns("这段回答是散文，没有条目。");

            assertThat(service.build("上一轮回答")).isNull();
        }

        @Test
        void 模型抛异常返回null不外抛() {
            when(chatModelFactory.buildForScene(AiScene.ANSWER_OUTLINE)).thenReturn(chatModel);
            when(chatModel.chat(anyList())).thenThrow(new RuntimeException("timeout"));

            assertThat(service.build("上一轮回答")).isNull();
        }
    }

    @Nested
    @DisplayName("parseFromDb / toJson — DB 读写路径")
    class DbRoundTrip {

        @Test
        void 有效的DB轮廓解析并清洗() {
            String json = "{\"topic\":\"t\",\"items\":["
                    + "{\"id\":5,\"title\":\"条目A\",\"gist\":\"g\",\"keywords\":[\"a\"],\"chunkAnchors\":[1,2]},"
                    + "{\"id\":null,\"title\":\"条目B\",\"gist\":null,\"keywords\":null,\"chunkAnchors\":null}]}";

            AnswerOutline outline = service.parseFromDb(json);

            assertThat(outline).isNotNull();
            assertThat(outline.items()).hasSize(2);
            assertThat(outline.items().get(0).id()).isEqualTo(5);
            assertThat(outline.items().get(0).chunkAnchors()).isNull();
            assertThat(outline.items().get(1).id()).isEqualTo(2); // 缺失补全
        }

        @Test
        void 有效条目不足2条返回null() {
            String json = "{\"topic\":\"t\",\"items\":[{\"id\":1,\"title\":\"A\"}]}";

            assertThat(service.parseFromDb(json)).isNull();
        }

        @Test
        void 坏JSON返回null() {
            assertThat(service.parseFromDb("{broken")).isNull();
        }

        @Test
        void 空串null返回null() {
            assertThat(service.parseFromDb(null)).isNull();
            assertThat(service.parseFromDb(" ")).isNull();
        }

        @Test
        void toJson与parseFromDb往返一致() {
            AnswerOutline outline = new AnswerOutline("主题", java.util.List.of(
                    new AnswerOutline.Item(1, "条目一", "g", java.util.List.of("k"), null),
                    new AnswerOutline.Item(2, "条目二", null, java.util.List.of(), null)));

            String json = service.toJson(outline);
            assertThat(json).contains("主题");

            AnswerOutline parsed = service.parseFromDb(json);
            assertThat(parsed).isNotNull();
            assertThat(parsed.items()).hasSize(2);
            assertThat(parsed.items().get(0).title()).isEqualTo("条目一");
        }
    }
}
