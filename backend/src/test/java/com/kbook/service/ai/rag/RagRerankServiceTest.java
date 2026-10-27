package com.kbook.service.ai.rag;

import com.kbook.config.ChatModelFactory;
import com.kbook.config.properties.QdrantProperties;
import com.kbook.entity.AiScene;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LLM rerank 单测：开关、失败兜底、按分数重排、保留向量分。
 */
class RagRerankServiceTest {

    private ChatModelFactory chatModelFactory;
    private ChatModel chatModel;
    private QdrantProperties properties;
    private RagRerankService service;

    @BeforeEach
    void setUp() {
        chatModelFactory = mock(ChatModelFactory.class);
        chatModel = mock(ChatModel.class);
        properties = new QdrantProperties();
        properties.setRerankEnabled(true);
        properties.setRerankTopN(30);
        service = new RagRerankService(chatModelFactory, properties);
    }

    private void llmReturns(String text) {
        when(chatModelFactory.buildForScene(AiScene.RAG_RERANK)).thenReturn(chatModel);
        when(chatModel.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from(text)).build());
    }

    private static EmbeddingMatch<TextSegment> match(int chunkIndex, double score, String text) {
        Metadata metadata = new Metadata().put("chunkIndex", chunkIndex);
        return new EmbeddingMatch<>(score, String.valueOf(chunkIndex), null,
                TextSegment.from(text, metadata));
    }

    @Test
    @DisplayName("按 LLM 分数重排，向量分保留，重排分写入 metadata")
    void reranksByLlmScore() {
        llmReturns("1:0\n2:3");

        List<EmbeddingMatch<TextSegment>> result = service.rerank("问题", List.of(
                match(1, 0.9, "低相关"),
                match(2, 0.8, "直接回答")));

        assertThat(result)
                .extracting(RagPipelineComponents::getChunkIndex)
                .containsExactly(2, 1);
        assertThat(result.get(0).score()).isEqualTo(0.8);
        assertThat(result.get(0).embedded().metadata().getDouble("rerankScore")).isEqualTo(3.0);
    }

    @Test
    @DisplayName("开关关闭时不调用模型")
    void disabledSkipsModel() {
        properties.setRerankEnabled(false);
        List<EmbeddingMatch<TextSegment>> input = List.of(
                match(1, 0.9, "A"), match(2, 0.8, "B"));

        assertThat(service.rerank("问题", input)).isSameAs(input);
        verifyNoInteractions(chatModelFactory);
    }

    @Test
    @DisplayName("模型不可用/输出不可解析时保持向量顺序")
    void failuresKeepVectorOrder() {
        List<EmbeddingMatch<TextSegment>> input = List.of(
                match(1, 0.9, "A"), match(2, 0.8, "B"));

        when(chatModelFactory.buildForScene(AiScene.RAG_RERANK)).thenReturn(null);
        assertThat(service.rerank("问题", input)).isSameAs(input);

        llmReturns("抱歉，我无法重排");
        assertThat(service.rerank("问题", input))
                .extracting(RagPipelineComponents::getChunkIndex)
                .containsExactly(1, 2);
    }

    @Test
    @DisplayName("场景模型解析抛异常时退回向量顺序，不把异常抛给检索链")
    void sceneResolutionFailureFallsBackToVectorOrder() {
        List<EmbeddingMatch<TextSegment>> input = List.of(
                match(1, 0.9, "A"), match(2, 0.8, "B"));

        // 实测 RAG_RERANK 未绑定时 buildForScene 直接抛；异常冒泡会让整条检索变空
        when(chatModelFactory.buildForScene(AiScene.RAG_RERANK))
                .thenThrow(new IllegalStateException("场景 [RAG_RERANK] 回退到 TOOL 默认配置失败"));

        assertThat(service.rerank("问题", input)).isSameAs(input);
    }
}
