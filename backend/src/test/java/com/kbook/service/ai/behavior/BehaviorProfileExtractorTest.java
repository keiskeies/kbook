package com.kbook.service.ai.behavior;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.config.ChatModelFactory;
import com.kbook.entity.AiScene;
import com.kbook.entity.UserBehaviorProfile;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 行为画像抽取器单测（阶段 2 第 2 批 — Mockito）。
 * <p>
 * 重点：清单截断与排序、weight clamp、枚举容错、lifeContext 截断、信号数累计、
 * suppressedSignals 保留、失败时返回 current/null 的兜底语义。
 */
class BehaviorProfileExtractorTest {

    private ChatModelFactory chatModelFactory;
    private ChatModel chatModel;
    private BehaviorProfileExtractor extractor;

    @BeforeEach
    void setUp() {
        chatModelFactory = mock(ChatModelFactory.class);
        chatModel = mock(ChatModel.class);
        extractor = new BehaviorProfileExtractor(chatModelFactory, new ObjectMapper());
    }

    private void llmReturns(String text) {
        when(chatModelFactory.buildForScene(AiScene.FOLLOW_UP_QUESTION)).thenReturn(chatModel);
        when(chatModel.chat(anyString())).thenReturn(text);
    }

    private static List<BehaviorProfileExtractor.BehaviorSignal> signals(int n) {
        return java.util.stream.IntStream.rangeClosed(1, n)
                .mapToObj(i -> new BehaviorProfileExtractor.BehaviorSignal("问题" + i, 1.0))
                .toList();
    }

    @Nested
    @DisplayName("extract — 兜底语义")
    class Fallbacks {

        @Test
        void 无信号直接返回当前画像_不调LLM() {
            UserBehaviorProfile current = UserBehaviorProfile.builder().build();

            UserBehaviorProfile result = extractor.extract(1L, current, List.of(), null);

            assertThat(result).isSameAs(current);
            verify(chatModelFactory, never()).buildForScene(any(AiScene.class));
        }

        @Test
        void 响应无JSON返回当前画像() {
            llmReturns("抱歉，我无法处理这个请求。");

            UserBehaviorProfile result = extractor.extract(1L, null, signals(2), null);

            assertThat(result).isNull(); // current 为 null 时兜底返回 null
        }

        @Test
        void 响应JSON损坏返回当前画像() {
            llmReturns("{broken json");

            UserBehaviorProfile current = UserBehaviorProfile.builder().build();
            UserBehaviorProfile result = extractor.extract(1L, current, signals(2), null);

            assertThat(result).isSameAs(current);
        }

        @Test
        void LLM抛异常返回null() {
            when(chatModelFactory.buildForScene(AiScene.FOLLOW_UP_QUESTION)).thenReturn(chatModel);
            when(chatModel.chat(anyString())).thenThrow(new RuntimeException("timeout"));

            assertThat(extractor.extract(1L, null, signals(2), null)).isNull();
        }
    }

    @Nested
    @DisplayName("extract — 解析与规范化")
    class Parsing {

        @Test
        void 完整响应解析_清单截断_weight截断排序() throws Exception {
            // interestTags 给 7 条（上限 5），weight 有越界值 1.5
            llmReturns("""
                    {
                      "interestTags": [
                        {"tag":"职场女性困境","weight":0.6},
                        {"tag":"亲子沟通的技巧与方法论","weight":0.9},
                        {"tag":"断舍离","weight":1.5},
                        {"tag":"极简主义生活","weight":0.2},
                        {"tag":"历史","weight":0.1},
                        {"tag":"多余项一","weight":0.05},
                        {"tag":"多余项二","weight":0.0}
                      ],
                      "readingMotivations": [{"motivation":"解惑","weight":0.8}],
                      "knowledgeGaps": ["认知失调","认知失调","依恋理论","第四项应被截掉"],
                      "valueOrientation": ["独立"],
                      "personalityTraits": [{"tag":"内省","weight":0.7}],
                      "cognitiveDepth": "ANALYTICAL",
                      "emotionalTone": "QUESTIONING",
                      "thinkingStyle": "SYSTEMATIC",
                      "readerArchetype": "DEEP_DIVER",
                      "confusions": ["该不该转行"],
                      "lifeContext": "职场转型期，对未来方向感到迷茫，正在犹豫要不要离开现在的团队去尝试新的领域并且思考要不要读一些书来帮助决策"
                    }""");

            UserBehaviorProfile result = extractor.extract(9L, null, signals(4), null);

            assertThat(result).isNotNull();
            assertThat(result.getUserId()).isEqualTo(9L);

            // interestTags 截断到 5，按 weight 降序，weight clamp 到 [0,1]，tag 截到 10 字
            List<java.util.Map<String, Object>> tags = new ObjectMapper().readValue(
                    result.getInterestTags(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<java.util.Map<String, Object>>>() {});
            assertThat(tags).extracting(t -> t.get("tag").toString())
                    // 断舍离(1.5→1.0) > 亲子(0.9, 11字截10字) > 职场(0.6) > 极简(0.2) > 历史(0.1)；多余项被挤出
                    .containsExactly("断舍离", "亲子沟通的技巧与方法", "职场女性困境", "极简主义生活", "历史");
            assertThat(((Number) tags.get(0).get("weight")).doubleValue()).isEqualTo(1.0);

            // knowledgeGaps 去重 + 截断到 3
            List<String> gaps = new ObjectMapper().readValue(
                    result.getKnowledgeGaps(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            assertThat(gaps).hasSize(3);
            assertThat(gaps.stream().distinct().count()).isEqualTo(gaps.size());

            // 枚举正确解析
            assertThat(result.getCognitiveDepth()).isEqualTo(UserBehaviorProfile.CognitiveDepth.ANALYTICAL);
            assertThat(result.getReaderArchetype()).isEqualTo(UserBehaviorProfile.ReaderArchetype.DEEP_DIVER);

            // lifeContext 截断到 50 字
            assertThat(result.getLifeContext()).hasSize(50);

            // 首次抽取 suppressedSignals 兜底 "[]"
            assertThat(result.getSuppressedSignals()).isEqualTo("[]");
            // totalSignals 累计
            assertThat(result.getTotalSignals()).isEqualTo(4);
            assertThat(result.getLastInferredAt()).isNotNull();
        }

        @Test
        void 枚举非法值容错为null() {
            llmReturns("""
                    {
                      "interestTags": [],
                      "cognitiveDepth": "SUPER_DEEP",
                      "emotionalTone": "UNKNOWN_TONE",
                      "thinkingStyle": null,
                      "readerArchetype": 123
                    }""");

            UserBehaviorProfile result = extractor.extract(1L, null, signals(1), null);

            assertThat(result).isNotNull();
            assertThat(result.getCognitiveDepth()).isNull();
            assertThat(result.getEmotionalTone()).isNull();
            assertThat(result.getThinkingStyle()).isNull();
            assertThat(result.getReaderArchetype()).isNull();
        }

        @Test
        void 更新已有画像_保留suppressed_累计信号数() {
            UserBehaviorProfile current = UserBehaviorProfile.builder()
                    .userId(7L)
                    .suppressedSignals("[\"旧信号\"]")
                    .totalSignals(10)
                    .build();
            llmReturns("{\"interestTags\":[],\"lifeContext\":null}");

            UserBehaviorProfile result = extractor.extract(7L, current, signals(3), null);

            assertThat(result).isSameAs(current);
            assertThat(result.getSuppressedSignals()).isEqualTo("[\"旧信号\"]");
            assertThat(result.getTotalSignals()).isEqualTo(13);
            assertThat(result.getUserId()).isEqualTo(7L); // current 非空时不覆盖 userId
        }

        @Test
        void 空主题与空白条目被过滤() throws Exception {
            llmReturns("""
                    {
                      "interestTags": [{"tag":"  ","weight":0.9},{"tag":"有效","weight":0.5}],
                      "knowledgeGaps": ["", "  ", "有效gap"]
                    }""");

            UserBehaviorProfile result = extractor.extract(1L, null, signals(1), null);

            List<java.util.Map<String, Object>> tags = new ObjectMapper().readValue(
                    result.getInterestTags(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<java.util.Map<String, Object>>>() {});
            assertThat(tags).hasSize(1);
            assertThat(tags.get(0).get("tag")).isEqualTo("有效");
        }
    }
}
