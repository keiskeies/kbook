package com.kbook.config;

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
 * 此 Bean 仅作为兜底使用，实际对话通过 AiProviderConfigService
 * 为每个用户动态构建 Assistant（支持自定义提供商）。
 * <p>
 * ChatModel 的构建委托给 ChatModelFactory，本类不直接依赖 Ollama/OpenAI 实现类。
 * <p>
 * 注意：AiToolService 必须使用 ObjectProvider 延迟获取，
 * 因为 LangChain4j 的 .tools() 需要扫描真实类上的 @Tool 注解，
 * 而 @Lazy 代理会导致 @Tool 注解不可见。
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

    /**
     * 默认 AI Assistant（兜底 Bean）
     * <p>
     * 使用 application.yml 中的 Ollama 配置构建。
     * AiChatService 已改用 AiProviderConfigService.getAssistant() 获取用户专属实例。
     */
    @Bean
    @Primary
    public AiAssistant aiAssistant() {
        log.info("初始化默认 AI Assistant (Ollama)...");
        AiToolService realToolService = toolServiceProvider.getObject();
        log.debug("获取 AiToolService 实例: {}", realToolService.getClass().getName());
        return AiServices.builder(AiAssistant.class)
                .chatModel(chatModelFactory.buildDefaultChatModel())
                .chatMemoryProvider(sessionId -> MessageWindowChatMemory.builder()
                        .id(sessionId)
                        .maxMessages(20)
                        .chatMemoryStore(chatMemoryStore)
                        .build())
                .tools(realToolService)
                .build();
    }
}
