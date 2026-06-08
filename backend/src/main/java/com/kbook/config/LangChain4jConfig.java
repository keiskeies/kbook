package com.kbook.config;
import com.kbook.service.ai.AiProviderConfigService;

import com.kbook.constants.AiPromptConstants;
import com.kbook.service.ai.AiChatMemory;
import com.kbook.service.ai.AiToolService;
import com.kbook.service.ai.AiAssistant;
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

    /** AI 对话记忆存储 */
    private final AiChatMemory chatMemoryStore;
    /** AI 工具服务（使用 ObjectProvider 延迟加载，避免循环依赖） */
    private final ObjectProvider<AiToolService> toolServiceProvider;
    /** 聊天模型工厂 */
    private final ChatModelFactory chatModelFactory;

    /**
     * 构造函数
     *
     * @param chatMemoryStore    AI 对话记忆存储
     * @param toolServiceProvider AI 工具服务提供者（延迟加载）
     * @param chatModelFactory   聊天模型工厂
     */
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
     * 创建默认 AiAssistant Bean（兜底实例）
     * <p>
     * 使用 yml 默认配置构建，带滑动窗口记忆和工具调用能力。
     * 实际对话通过 AiProviderConfigService 获取动态配置的 Assistant。
     *
     * @return AiAssistant 实例
     */
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
