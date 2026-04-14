package com.kbook.config;

import com.kbook.service.AiChatMemory;
import com.kbook.service.AiToolService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import com.kbook.service.AiAssistant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * LangChain4j 配置 — 构建默认 AiAssistant 实例
 * <p>
 * 此 Bean 仅作为兜底使用，实际对话通过 AiProviderConfigService
 * 为每个用户动态构建 Assistant（支持自定义提供商）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class LangChain4jConfig {

    private final AiChatMemory chatMemoryStore;
    private final AiToolService toolService;

    /**
     * 默认 AI Assistant（兜底 Bean）
     * <p>
     * 使用 application.yml 中的 Ollama 配置构建。
     * AiChatService 已改用 AiProviderConfigService.getAssistant() 获取用户专属实例。
     */
    @Bean
    @Primary
    public AiAssistant aiAssistant(OllamaChatModel ollamaChatModel) {
        log.info("初始化默认 AI Assistant (Ollama)...");
        return AiServices.builder(AiAssistant.class)
                .chatModel(ollamaChatModel)
                .chatMemoryProvider(sessionId -> MessageWindowChatMemory.builder()
                        .id(sessionId)
                        .maxMessages(20)
                        .chatMemoryStore(chatMemoryStore)
                        .build())
                .tools(toolService)
                .build();
    }
}
