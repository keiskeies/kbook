package com.kbook.service.ai.outline;

import com.kbook.config.ChatModelFactory;
import com.kbook.entity.AiScene;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    @DisplayName("buildFromContext — 首轮列表题")
    class BuildFromContext {

        @Test
        void 空问题或空材料直接返回null不调模型() {
            assertThat(service.buildFromContext(null, "材料", null)).isNull();
            assertThat(service.buildFromContext("问题", "  ", null)).isNull();
        }

        @Test
        void 模型未配置返回null() {
            when(chatModelFactory.buildForScene(AiScene.ANSWER_OUTLINE)).thenReturn(null);

            assertThat(service.buildFromContext("问题", "材料", null)).isNull();
        }

        @Test
        void 正常输出_条目完整_锚点置空() {
            llmReturns(outlineJson(3));

            AnswerOutline outline = service.buildFromContext("书里有哪些能力？", "材料内容", null);

            assertThat(outline).isNotNull();
            assertThat(outline.items()).hasSize(3);
            assertThat(outline.items().get(0).id()).isEqualTo(1);
            assertThat(outline.items().get(0).chunkAnchors()).isNull();
        }

        @Test
        void 条目不足2个返回null() {
            llmReturns(outlineJson(1));

            assertThat(service.buildFromContext("问题", "材料", null)).isNull();
        }

        @Test
        @DisplayName("目录提示随问题一起进提示词（材料截断后模型才看得到条目标题）")
        void 目录提示进入提示词() {
            llmReturns(outlineJson(6));
            ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);

            service.buildFromContext("下篇“芒格私人书单”分成哪几辑？", "材料内容",
                    "下篇 芒格私人书单\n  第一辑 自然\n  第六辑 处世");

            verify(chatModel).chat(captor.capture());
            String userPrompt = ((UserMessage) captor.getValue().get(1)).singleText();
            assertThat(userPrompt).contains("【本书目录（与问题相关的部分）】");
            assertThat(userPrompt).contains("第一辑 自然");
            assertThat(userPrompt).contains("第六辑 处世");
        }

        @Test
        @DisplayName("没有目录提示时不出现目录段落")
        void 无目录提示不出现目录段落() {
            llmReturns(outlineJson(3));
            ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);

            service.buildFromContext("问题", "材料内容", "");

            verify(chatModel).chat(captor.capture());
            assertThat(((UserMessage) captor.getValue().get(1)).singleText())
                    .doesNotContain("【本书目录");
        }
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
    @DisplayName("salvage — 模型输出局部坏掉时保留可用条目")
    class Salvage {

        /** 实测四类坏输出：字段名后缺冒号 / 值里未转义引号 / 缺逗号 / 末条截断未闭合 */
        @Test
        @DisplayName("摘要里未转义的英文引号：整份 readTree 失败，仍拿到全部标题")
        void 未转义引号仍保留条目() {
            llmReturns("{\"topic\":\"被剥夺超级反应倾向\",\"items\":["
                    + "{\"id\":1,\"title\":\"失去痛大于得到爽\",\"gist\":\"大脑对损失的\"感知\"灵敏度高于收益\",\"keywords\":[\"损失厌恶\"],\"chunkAnchors\":[]},"
                    + "{\"id\":2,\"title\":\"避免沉没成本\",\"gist\":\"别为打翻的牛奶哭泣\",\"keywords\":[\"沉没成本\"],\"chunkAnchors\":[]},"
                    + "{\"id\":3,\"title\":\"别被可能性愚弄\",\"gist\":\"小概率高赔率是彩票\",\"keywords\":[\"彩票\"],\"chunkAnchors\":[]}]}");

            AnswerOutline outline = service.build("上一轮回答");

            assertThat(outline).isNotNull();
            assertThat(outline.items()).hasSize(3);
            assertThat(outline.items()).extracting("title")
                    .containsExactly("失去痛大于得到爽", "避免沉没成本", "别被可能性愚弄");
        }

        @Test
        @DisplayName("字段名后缺冒号：坏的那条退化为正则取标题，其余照常解析")
        void 缺冒号坏条目退化为标题兜底() {
            llmReturns("{\"topic\":\"清单\",\"items\":["
                    + "{\"id\":1,\"title\":\"第一辑 自然\",\"gist\":\"g1\",\"keywords\":[\"自然\"],\"chunkAnchors\":[]},"
                    + "{\"id\":2,\"title\" \"第二辑 人类\",\"gist\":\"g2\",\"keywords\":[\"人类\"],\"chunkAnchors\":[]},"
                    + "{\"id\":3,\"title\":\"第三辑 经济\",\"gist\":\"g3\",\"keywords\":[\"经济\"],\"chunkAnchors\":[]}]}");

            AnswerOutline outline = service.build("上一轮回答");

            assertThat(outline).isNotNull();
            assertThat(outline.items()).extracting("title")
                    .containsExactly("第一辑 自然", "第二辑 人类", "第三辑 经济");
        }

        @Test
        @DisplayName("末条被截断没有闭合括号：前几条仍保留，条目数与顺序不变")
        void 末条截断仍保留前面的条目() {
            llmReturns("{\"topic\":\"25条\",\"items\":[{\"id\":1,\"title\":\"激励倾向\",\"gist\":\"g\",\"keywords\":[],\"chunkAnchors\":[]},"
                    + "{\"id\":2,\"title\":\"爱倾向\",\"gist\":\"g\",\"keywords\":[],\"chunkAnchors\":[]},"
                    + "{\"id\":3,\"title\":\"怀疑倾向\",\"gist\":\"g\",\"keywords\":[],\"chunkAnchors\":[]}");

            AnswerOutline outline = service.build("上一轮回答");

            assertThat(outline).isNotNull();
            assertThat(outline.items()).hasSize(3);
            assertThat(outline.items().get(2).title()).isEqualTo("怀疑倾向");
        }

        @Test
        @DisplayName("salvage 时 topic 一并保留（丢了它，分段指令会写成「上一条回答」而首轮并没有）")
        void salvage保留topic() {
            llmReturns("{\"topic\":\"芒格私人书单分辑\",\"items\":[{\"id\":1,\"title\":\"自然\",\"gist\" \"坏\",\"keywords\":[],\"chunkAnchors\":[]},"
                    + "{\"id\":2,\"title\":\"人类\",\"gist\":\"g\",\"keywords\":[],\"chunkAnchors\":[]}]}");

            AnswerOutline outline = service.buildFromContext("分哪几辑？", "材料", null);

            assertThat(outline).isNotNull();
            assertThat(outline.topic()).isEqualTo("芒格私人书单分辑");
            assertThat(outline.items()).extracting("title").containsExactly("自然", "人类");
        }

        @Test
        @DisplayName("salvage 只剩 1 条时仍然 null（流水线要求 ≥2，宁缺勿假清单）")
        void salvage不足两条仍返回null() {
            llmReturns("{\"topic\":\"清单\",\"items\":[{\"id\":1,\"title\":\"唯一条目\",\"gist\" \"坏\",\"keywords\":[],\"chunkAnchors\":[]}]}");

            assertThat(service.build("上一轮回答")).isNull();
        }

        @Test
        @DisplayName("散文里没有条目对象时不会被 salvage 误造成清单")
        void 散文不会被误salvage() {
            llmReturns("{\"topic\":\"没有列表\",\"items\":[]}");

            assertThat(service.build("上一轮回答")).isNull();
        }

        @Test
        @DisplayName("变异验证：salvage 与正常解析在同一输出上结果一致（不引入第二套语义）")
        void 正常输出走原路径不受影响() {
            llmReturns(outlineJson(4));

            AnswerOutline outline = service.build("上一轮回答");

            assertThat(outline.topic()).isEqualTo("列表主题");
            assertThat(outline.items()).hasSize(4);
            assertThat(outline.items().get(0).gist()).isEqualTo("摘要1");
            assertThat(outline.items().get(0).keywords()).containsExactly("k1");
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
