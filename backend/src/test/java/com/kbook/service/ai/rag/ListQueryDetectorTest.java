package com.kbook.service.ai.rag;

import com.kbook.config.ChatModelFactory;
import com.kbook.entity.AiScene;
import com.kbook.service.ai.rag.ListQueryDetector.DetectionResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 列表型问题检测器单测（阶段 2 第 2 批 — Mockito）。
 * <p>
 * 重点：信号预判的快速路径（无信号零 LLM 调用）、LLM 输出的行式 KV/JSON 双解析、失败兜底 notList。
 */
class ListQueryDetectorTest {

    private ChatModelFactory chatModelFactory;
    private ChatModel chatModel;
    private ListQueryDetector detector;

    @BeforeEach
    void setUp() {
        chatModelFactory = mock(ChatModelFactory.class);
        chatModel = mock(ChatModel.class);
        detector = new ListQueryDetector(chatModelFactory);
    }

    private void llmReturns(String text) {
        when(chatModelFactory.buildForScene(AiScene.LIST_QUERY_DETECT)).thenReturn(chatModel);
        when(chatModel.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from(text)).build());
    }

    // ==================== 快速预判（零 LLM） ====================

    @Test
    @DisplayName("空问题直接 notList，不调 LLM")
    void blankQuestionSkipsLlm() {
        assertThat(detector.detect(null, null).isListQuery()).isFalse();
        assertThat(detector.detect("  ", null).isListQuery()).isFalse();
        verify(chatModelFactory, never()).buildForScene(any(AiScene.class));
    }

    @Test
    @DisplayName("无列表信号的单一概念问题跳过 LLM")
    void noSignalQuestionSkipsLlm() {
        DetectionResult result = detector.detect("什么是应变能力？", null);

        assertThat(result.isListQuery()).isFalse();
        verify(chatModelFactory, never()).buildForScene(any(AiScene.class));
    }

    @Test
    @DisplayName("章节引用（第N章）不算列表信号")
    void chapterReferenceNotSignal() {
        DetectionResult result = detector.detect("第三章的核心论点是什么？", null);

        assertThat(result.isListQuery()).isFalse();
        verify(chatModelFactory, never()).buildForScene(any(AiScene.class));
    }

    @Test
    @DisplayName("计数信号在上轮回答中时触发 LLM 精判")
    void countedSignalInLastAnswerTriggersLlm() {
        llmReturns("IS_LIST: false\nTOPIC: （留空）");

        DetectionResult result = detector.detect("这本书的核心论点是什么", "全书提出了5条建议");

        // 问题本身无信号，但上轮回答含 "5条" → 进入 LLM 精判（此处返回否）
        assertThat(result.isListQuery()).isFalse();
        verify(chatModelFactory).buildForScene(AiScene.LIST_QUERY_DETECT);
    }

    // ==================== LLM 输出解析 ====================

    @Test
    @DisplayName("行式 KV 输出 true + 主题 → 列表型")
    void kvTrueWithTopicParsed() {
        llmReturns("IS_LIST: true\nTOPIC: 11项能力培养");

        DetectionResult result = detector.detect("详细介绍这11项能力", null);

        assertThat(result.isListQuery()).isTrue();
        assertThat(result.listTopic()).isEqualTo("11项能力培养");
    }

    @Test
    @DisplayName("行式 KV 输出 false → notList")
    void kvFalseParsedAsNotList() {
        llmReturns("IS_LIST: false\nTOPIC: （留空）");

        assertThat(detector.detect("分别讲讲这些能力", null).isListQuery()).isFalse();
    }

    @Test
    @DisplayName("LLM 偶发输出 JSON 也能兼容解析")
    void jsonOutputCompat() {
        llmReturns("{\"isList\":true,\"topic\":\"14种教育艺术\"}");

        DetectionResult result = detector.detect("14种教育艺术具体是什么", null);

        assertThat(result.isListQuery()).isTrue();
        assertThat(result.listTopic()).isEqualTo("14种教育艺术");
    }

    @Test
    @DisplayName("带代码围栏的 KV 输出可解析")
    void fencedOutputParsed() {
        llmReturns("```json\nIS_LIST: true\nTOPIC: 书中的核心方法\n```");

        DetectionResult result = detector.detect("列出书中的核心方法", null);

        assertThat(result.isListQuery()).isTrue();
        assertThat(result.listTopic()).isEqualTo("书中的核心方法");
    }

    // ==================== 失败兜底 ====================

    @Test
    @DisplayName("LLM 返回乱文本 → notList 不抛异常")
    void garbageOutputFallsBack() {
        llmReturns("抱歉我不太明白你的意思。");

        assertThat(detector.detect("逐一介绍这些方法", null).isListQuery()).isFalse();
    }

    @Test
    @DisplayName("模型未配置 → notList")
    void nullModelFallsBack() {
        when(chatModelFactory.buildForScene(AiScene.LIST_QUERY_DETECT)).thenReturn(null);

        assertThat(detector.detect("逐一介绍这些方法", null).isListQuery()).isFalse();
    }

    @Test
    @DisplayName("LLM 调用抛异常 → notList 不抛异常")
    void llmExceptionFallsBack() {
        when(chatModelFactory.buildForScene(AiScene.LIST_QUERY_DETECT)).thenReturn(chatModel);
        when(chatModel.chat(anyList())).thenThrow(new RuntimeException("provider down"));

        assertThat(detector.detect("逐一介绍这些方法", null).isListQuery()).isFalse();
    }
}
