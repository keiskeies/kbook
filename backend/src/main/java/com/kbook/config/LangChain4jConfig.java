package com.kbook.config;

import com.kbook.constants.AiPromptConstants;
import com.kbook.service.AiChatMemory;
import com.kbook.service.AiToolService;
import com.kbook.service.AiAssistant;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * LangChain4j 配置 — 构建默认 AiAssistant 实例
 * <p>
 * 此 Bean 作为 Spring 容器中的兜底 AiAssistant。
 * 实际对话通过 AiProviderConfigService 获取 Assistant。
 * <p>
 * ChatModel 的构建委托给 ChatModelFactory，本类不直接依赖 Ollama/OpenAI 实现类。
 */
@Slf4j
@Configuration
public class LangChain4jConfig {

    private final AiChatMemory chatMemoryStore;
    private final ObjectProvider<AiToolService> toolServiceProvider;
    private final ChatModelFactory chatModelFactory;

    public LangChain4jConfig(
            AiChatMemory chatMemoryStore,
            ObjectProvider<AiToolService> toolServiceProvider,
            ChatModelFactory chatModelFactory
    ) {
        this.chatMemoryStore = chatMemoryStore;
        this.toolServiceProvider = toolServiceProvider;
        this.chatModelFactory = chatModelFactory;
    }

    @Bean
    @Primary
    public AiAssistant aiAssistant() {
        log.info("初始化默认 AI Assistant (Ollama)...");
        AiToolService realToolService = toolServiceProvider.getObject();
        return AiServices.builder(AiAssistant.class)
                .chatModel(chatModelFactory.buildChatModel())
                .chatMemoryProvider(sessionId -> MessageWindowChatMemory.builder()
                        .id(sessionId)
                        .maxMessages(AiPromptConstants.ADMIN_MAX_MESSAGES)
                        .chatMemoryStore(chatMemoryStore)
                        .build())
                .tools(realToolService)
                .build();
    }
}
