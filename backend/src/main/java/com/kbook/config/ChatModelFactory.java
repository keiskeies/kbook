package com.kbook.config;

import com.kbook.config.properties.AiModelProperties;
import com.kbook.entity.AiProviderConfig;
import com.kbook.repository.AiProviderConfigRepository;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 聊天模型工厂类
 * <p>
 * 全部从数据库 {@link AiProviderConfig} 中读取配置构建 AI 聊天模型。
 * 通过 {@link com.kbook.entity.AiScene} 场景路由构建模型，思考参数联动
 * {@link AiProviderConfig.ThinkingMode} 和 {@link com.kbook.entity.AiSceneConfig}。
 * <p>
 * 特例方法：
 * <ul>
 *   <li>{@link #buildVisionChatModel()} — OCR 视觉模型（从 YML 配置读取）</li>
 *   <li>{@link #buildChatModelForTest(Long)} — 测试连接（按指定 configId 构建）</li>
 *   <li>{@link #buildDefaultEmbeddingModel()} — 嵌入模型</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelFactory {

    /** AI 提供商配置仓库 */
    private final AiProviderConfigRepository configRepository;

    /** AI 熔断器注册表 — 按 provider+model 维度管理熔断器 */
    private final AiCircuitBreakerRegistry cbRegistry;

    /** AI 模型配置属性（视觉 OCR 模型等仅 YML 配置的部分） */
    private final AiModelProperties aiModelProperties;

    /**
     * AI 场景配置服务 — {@code @Lazy} 打破循环依赖：
     * ChatModelFactory ← AiSceneConfigService ← AiProviderConfigService ← ChatModelFactory
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private com.kbook.service.ai.AiSceneConfigService sceneConfigService;

    // ======================== 其他公开方法 ========================

    /**
     * 构建视觉模型（用于 OCR/PDF 处理）。
     * <p>
     * 优先从 YML 的 {@code kbook.ai.vision} 配置读取（专用的本地 OCR 模型，
     * 如 Ollama 的 Unlimited-OCR:Q4_K_M），避免云端模型（如 Gemini）对
     * data URI 格式图片的兼容性问题。
     * <p>
     * 如果 vision 配置缺失（base-url 或 model-name 留空），则回退到 CHAT-QA 配置。
     * 关闭思考过程、使用低温度以提高 OCR 准确率。
     *
     * @return 聊天模型实例，已包装重试机制
     */
    public ChatModel buildVisionChatModel() {
        AiModelProperties.VisionConfig vision = aiModelProperties.getVision();
        ThinkingConfig tc = ThinkingConfig.off(); // OCR 关闭思考，提高准确率

        // 回退逻辑：vision 配置缺失时使用 QA 配置
        if (vision == null
                || vision.getBaseUrl() == null || vision.getBaseUrl().isBlank()
                || vision.getModelName() == null || vision.getModelName().isBlank()) {
            log.warn("Vision 配置缺失，回退到 CHAT-QA 配置构建 OCR 模型");
            AiProviderConfig qaConfig = resolveQaConfig();
            Duration timeout = Duration.ofSeconds(600);
            double temperature = 0.3;
            return qaConfig.getProvider() == AiProviderConfig.Provider.OPENAI
                    ? wrap(buildOpenAiChat(qaConfig.getBaseUrl(), qaConfig.getModelName(),
                            temperature, timeout, qaConfig.getApiKey(), tc), qaConfig)
                    : wrap(buildOllamaChat(qaConfig.getBaseUrl(), qaConfig.getModelName(),
                            temperature, timeout, tc), qaConfig);
        }

        AiProviderConfig.Provider provider = AiProviderConfig.Provider.from(vision.getProvider());
        if (provider == null) {
            provider = AiProviderConfig.Provider.OLLAMA;
        }
        Duration timeout = vision.getTimeout() != null ? vision.getTimeout() : Duration.ofSeconds(600);
        double temperature = 0.3;

        log.info("构建 OCR 视觉 ChatModel (from vision config): provider={}, model={}, baseUrl={}, thinking=false",
                provider, vision.getModelName(), vision.getBaseUrl());

        String providerKey = provider + ":" + vision.getModelName();
        ChatModel model = provider == AiProviderConfig.Provider.OPENAI
                ? buildOpenAiChat(vision.getBaseUrl(), vision.getModelName(),
                        temperature, timeout, null, tc)
                : buildOllamaChat(vision.getBaseUrl(), vision.getModelName(),
                        temperature, timeout, tc);
        return wrap(model, providerKey);
    }

    /**
     * 根据指定的配置 ID 构建聊天模型（用于测试）。
     *
     * @param configId AI 提供商配置的 ID
     * @return 聊天模型实例，已包装重试机制
     * @throws IllegalArgumentException 如果指定的配置不存在
     */
    public ChatModel buildChatModelForTest(Long configId) {
        AiProviderConfig config = configRepository.findById(configId)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + configId));
        return wrap(buildChat(config, ThinkingConfig.from(config, true)), config);
    }

    /**
     * 按场景构建聊天模型 — 路由层入口。
     * <p>
     * 由 {@link com.kbook.service.ai.AiSceneConfigService#resolveSceneConfig(AiScene)} 解析：
     * <ul>
     *   <li>AI 配置（providerConfig）— 优先场景绑定，回退到默认分类</li>
     *   <li>思考参数（thinkingEnabled/reasoningEffort/thinkingBudget）— 联动 providerConfig.thinkingMode</li>
     * </ul>
     * 联动逻辑：
     * <ul>
     *   <li>NONE 模式：不发送任何思考参数（如 Gemini）</li>
     *   <li>SWITCH 模式：think=thinkingEnabled, returnThinking=thinkingEnabled</li>
     *   <li>REASONING_EFFORT 模式：SWITCH 行为 + reasoningEffort（非空时发送）</li>
     *   <li>THINKING_BUDGET 模式：SWITCH 行为 + thinking_budget（非空时通过 customParameters 透传）</li>
     * </ul>
     */
    public ChatModel buildForScene(com.kbook.entity.AiScene scene) {
        com.kbook.service.ai.AiSceneConfigService.ResolvedSceneConfig resolved =
                sceneConfigService.resolveSceneConfig(scene);
        com.kbook.entity.AiProviderConfig config = resolved.providerConfig();
        ThinkingConfig tc = ThinkingConfig.from(resolved);
        // 场景构建日志降级为 DEBUG，避免与统一摘要日志（logAiSummary）重复打印模型信息
        log.debug("构建场景 ChatModel: scene={}, config={}, model={}, thinkingMode={}, thinkingEnabled={}, reasoningEffort={}, thinkingBudget={}",
                scene, config.getName(), config.getModelName(),
                config.getThinkingMode(), tc.thinkingEnabled(), tc.reasoningEffort(), tc.thinkingBudget());
        return wrap(buildChat(config, tc), config);
    }

    /**
     * 按场景构建流式聊天模型 — 路由层入口。
     */
    public StreamingChatModel buildStreamingForScene(com.kbook.entity.AiScene scene) {
        com.kbook.service.ai.AiSceneConfigService.ResolvedSceneConfig resolved =
                sceneConfigService.resolveSceneConfig(scene);
        com.kbook.entity.AiProviderConfig config = resolved.providerConfig();
        ThinkingConfig tc = ThinkingConfig.from(resolved);
        log.debug("构建场景 StreamingChatModel: scene={}, config={}, model={}, thinkingMode={}, thinkingEnabled={}, reasoningEffort={}, thinkingBudget={}",
                scene, config.getName(), config.getModelName(),
                config.getThinkingMode(), tc.thinkingEnabled(), tc.reasoningEffort(), tc.thinkingBudget());
        return wrapStreaming(buildStreaming(config, tc), config);
    }

    /**
     * 按场景构建压缩专用模型（温度 0.1、关闭思考）。
     */
    public ChatModel buildCompressionForScene(com.kbook.entity.AiScene scene) {
        com.kbook.service.ai.AiSceneConfigService.ResolvedSceneConfig resolved =
                sceneConfigService.resolveSceneConfig(scene);
        com.kbook.entity.AiProviderConfig config = resolved.providerConfig();
        Duration t = timeout(config.getTimeout());
        ThinkingConfig tc = ThinkingConfig.off(); // 压缩强制关闭思考
        log.debug("构建场景压缩 ChatModel: scene={}, config={}, model={}",
                scene, config.getName(), config.getModelName());
        return config.getProvider() == AiProviderConfig.Provider.OPENAI
                ? wrap(buildOpenAiChat(config.getBaseUrl(), config.getModelName(),
                0.1, t, config.getApiKey(), tc), config)
                : wrap(buildOllamaChat(config.getBaseUrl(), config.getModelName(),
                0.1, t, tc), config);
    }

    /**
     * 构建场景的日志上下文 — 供 callAiForScene / StreamingSseHandler 传递给 logAiSummary。
     * <p>
     * 注意：此方法会调用 {@code sceneConfigService.resolveSceneConfig(scene)}，
     * 与 {@link #buildForScene} 中的调用是独立的两次 DB 查询。
     * 可接受的代价：摘要日志需要场景/模型/思考配置信息。
     *
     * @param scene AI 场景
     * @return 日志上下文（scene/modelName/configName/thinkingMode/thinkingEnabled/reasoningEffort）
     */
    public com.kbook.service.ai.ChatModelManager.AiCallLogContext buildLogContext(com.kbook.entity.AiScene scene) {
        com.kbook.service.ai.AiSceneConfigService.ResolvedSceneConfig resolved =
                sceneConfigService.resolveSceneConfig(scene);
        com.kbook.entity.AiProviderConfig config = resolved.providerConfig();
        String thinkingMode = config.getThinkingMode() != null
                ? config.getThinkingMode().name() : "SWITCH";
        return new com.kbook.service.ai.ChatModelManager.AiCallLogContext(
                scene.name(),
                config.getModelName(),
                config.getName(),
                thinkingMode,
                resolved.thinkingEnabled(),
                resolved.reasoningEffort()
        );
    }

    /**
     * 构建 Ollama 嵌入模型。
     */
    private EmbeddingModel buildOllamaEmbeddingModel(String baseUrl, String modelName, Duration timeout) {
        return OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl).modelName(modelName)
                .timeout(timeout != null ? timeout : Duration.ofSeconds(300))
                .customHeaders(AiModelProperties.UTF8_HEADERS)
                .build();
    }

    /**
     * 构建 OpenAI 兼容的嵌入模型。
     */
    private EmbeddingModel buildOpenAiEmbeddingModel(String baseUrl, String modelName, String apiKey, Duration timeout) {
        var builder = OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl).modelName(modelName)
                .timeout(timeout != null ? timeout : Duration.ofSeconds(300));
        builder.apiKey(apiKey != null && !apiKey.isBlank() ? apiKey : "sk-placeholder");
        return builder.build();
    }

    /**
     * 构建默认的嵌入模型 — 从数据库读取 EMBEDDING 用途的启用配置。
     *
     * @return 嵌入模型实例
     * @throws IllegalStateException 无可用 EMBEDDING 配置时抛出
     */
    public EmbeddingModel buildDefaultEmbeddingModel() {
        AiProviderConfig config = configRepository.findFirstByPurposeAndEnabledTrueOrderByUpdatedAtDesc(
                        AiProviderConfig.Purpose.EMBEDDING.name())
                .orElseThrow(() -> new IllegalStateException(
                        "未找到可用的嵌入模型配置，请在管理后台添加 EMBEDDING 用途的配置"));

        log.info("构建 EmbeddingModel (from DB): provider={}, baseUrl={}, model={}",
                config.getProvider(), config.getBaseUrl(), config.getModelName());
        return config.getProvider() == AiProviderConfig.Provider.OPENAI
                ? buildOpenAiEmbeddingModel(config.getBaseUrl(), config.getModelName(), config.getApiKey(), timeout(config.getTimeout()))
                : buildOllamaEmbeddingModel(config.getBaseUrl(), config.getModelName(), timeout(config.getTimeout()));
    }

    /**
     * 判断指定的 AI 提供商配置是否支持 Tool Calling 功能。
     */
    public boolean isToolsSupported(AiProviderConfig config) {
        if (config == null) return false;
        if (config.getToolsEnabled() != null) return config.getToolsEnabled();
        return !config.getModelName().toLowerCase().startsWith("gemma3n");
    }

    /**
     * 获取嵌入模型的服务基础地址 — 从 EMBEDDING 配置中读取。
     */
    public String getEmbeddingBaseUrl() {
        AiProviderConfig config = configRepository
                .findFirstByPurposeAndEnabledTrueOrderByUpdatedAtDesc(AiProviderConfig.Purpose.EMBEDDING.name())
                .orElse(null);
        return config != null ? config.getBaseUrl() : "http://localhost:11434";
    }

    /**
     * 获取当前 QA 配置的聊天模型名称。
     */
    public String getModelName() {
        return resolveQaConfig().getModelName();
    }

    /**
     * 获取当前嵌入模型名称。
     */
    public String getEmbeddingModelName() {
        AiProviderConfig config = configRepository
                .findFirstByPurposeAndEnabledTrueOrderByUpdatedAtDesc(AiProviderConfig.Purpose.EMBEDDING.name())
                .orElse(null);
        return config != null ? config.getModelName() : "bge-m3:latest";
    }

    // ======================== 内部方法 ========================

    /**
     * 解析 CHAT-QA 配置。
     */
    private AiProviderConfig resolveQaConfig() {
        return configRepository.findByPurposeAndEnabledAndRolesContaining(
                        AiProviderConfig.Purpose.CHAT.name(), "%QA%")
                .orElseThrow(() -> new IllegalStateException(
                        "未找到可用的对话模型(roles=QA)配置，请在管理后台添加并启用"));
    }

    private Duration timeout(Integer seconds) {
        return Duration.ofSeconds(seconds != null ? seconds : 600);
    }

    /**
     * 包装 ChatModel：熔断器 → 重试 → 实际模型
     * <p>
     * 熔断器在外层：provider 故障时直接拒绝请求，不进入重试排队。
     * providerKey 按 provider+model 维度隔离，一个 provider 挂了不影响另一个。
     */
    private ChatModel wrap(ChatModel model, AiProviderConfig config) {
        return wrap(model, config.getProvider() + ":" + config.getModelName());
    }

    /**
     * 包装 ChatModel（按 providerKey 字符串）— 用于非 DB 配置来源（如 YML vision 配置）。
     */
    private ChatModel wrap(ChatModel model, String providerKey) {
        CircuitBreaker cb = cbRegistry.getOrCreate(providerKey);
        return new CircuitBreakerChatModel(new RetryableChatModel(model), cb);
    }

    /**
     * 包装 StreamingChatModel：熔断器 → 实际流式模型
     * <p>
     * 流式模型不套重试（StreamingSseHandler 已有重试逻辑），只套熔断器。
     */
    private StreamingChatModel wrapStreaming(StreamingChatModel model, AiProviderConfig config) {
        String providerKey = config.getProvider() + ":" + config.getModelName();
        CircuitBreaker cb = cbRegistry.getOrCreate(providerKey);
        return new CircuitBreakerStreamingChatModel(model, cb);
    }

    // ---- DB config 组合器 ----

    private ChatModel buildChat(AiProviderConfig config, ThinkingConfig tc) {
        Duration t = timeout(config.getTimeout());
        log.debug("构建 ChatModel (from DB): provider={}, model={}, baseUrl={}, thinkingMode={}, thinkingEnabled={}",
                config.getProvider(), config.getModelName(), config.getBaseUrl(),
                config.getThinkingMode(), tc.thinkingEnabled());
        return config.getProvider() == AiProviderConfig.Provider.OPENAI
                ? buildOpenAiChat(config.getBaseUrl(), config.getModelName(),
                config.getTemperature(), t, config.getApiKey(), tc)
                : buildOllamaChat(config.getBaseUrl(), config.getModelName(),
                config.getTemperature(), t, tc);
    }

    private StreamingChatModel buildStreaming(AiProviderConfig config, ThinkingConfig tc) {
        Duration t = timeout(config.getTimeout());
        log.debug("构建 StreamingChatModel (from DB): provider={}, model={}, baseUrl={}, thinkingMode={}, thinkingEnabled={}",
                config.getProvider(), config.getModelName(), config.getBaseUrl(),
                config.getThinkingMode(), tc.thinkingEnabled());
        return config.getProvider() == AiProviderConfig.Provider.OPENAI
                ? buildOpenAiStreaming(config.getBaseUrl(), config.getModelName(),
                config.getTemperature(), t, config.getApiKey(), tc)
                : buildOllamaStreaming(config.getBaseUrl(), config.getModelName(),
                config.getTemperature(), t, tc);
    }

    // ======================== 底层构建器 ========================

    private OllamaChatModel buildOllamaChat(String baseUrl, String modelName,
                                            Double temperature, Duration timeout, ThinkingConfig tc) {
        // Ollama 仅支持 think + returnThinking；reasoningEffort/thinkingBudget 由 OpenAI 系模型支持
        boolean think = tc.shouldThink();
        return OllamaChatModel.builder()
                .baseUrl(baseUrl).modelName(modelName)
                .temperature(temperature != null ? temperature : 0.7)
                .timeout(timeout != null ? timeout : Duration.ofSeconds(600))
                .customHeaders(AiModelProperties.UTF8_HEADERS)
                .listeners(List.of(ollamaCounterListener()))
                .think(think)
                .returnThinking(think)
//                .logRequests(true)
                .build();
    }

    private OllamaStreamingChatModel buildOllamaStreaming(String baseUrl, String modelName,
                                                          Double temperature, Duration timeout, ThinkingConfig tc) {
        boolean think = tc.shouldThink();
        return OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl).modelName(modelName)
                .temperature(temperature != null ? temperature : 0.7)
                .timeout(timeout != null ? timeout : Duration.ofSeconds(600))
                .customHeaders(AiModelProperties.UTF8_HEADERS)
                .listeners(List.of(ollamaCounterListener()))
                .think(think)
                .returnThinking(think)
//                .logRequests(true)
                .build();
    }

    private OpenAiChatModel buildOpenAiChat(String baseUrl, String modelName,
                                            Double temperature, Duration timeout, String apiKey,
                                            ThinkingConfig tc) {
        boolean gemini = isGeminiModel(baseUrl, modelName);
        var builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl).modelName(modelName)
                .temperature(temperature != null ? temperature : 0.7)
                .timeout(timeout != null ? timeout : Duration.ofSeconds(600))
                .listeners(List.of(new DiagnosticChatListener()));
        // 联动 thinkingMode 决定发送哪些思考参数
        if (!gemini && tc.thinkingMode() != AiProviderConfig.ThinkingMode.NONE) {
            boolean think = tc.shouldThink();
            // SWITCH 行为：所有非 NONE 模式都有的基础行为
            builder.returnThinking(think).sendThinking(false);
            // 关闭思考时，仅对 Qwen3 系列透传 enable_thinking=false
            //（Qwen3 默认会思考，returnThinking(false) 只是隐藏思考内容，关不掉思考过程）
            // 注意：enable_thinking 是 Qwen3 专属参数，其他 API（如 Google）会返回 400
            if (!think && isQwen3Model(modelName)) {
                builder.customParameters(Map.of("enable_thinking", false));
            }
            // REASONING_EFFORT 模式：额外发送 reasoning_effort（非空时）
            if (tc.thinkingMode() == AiProviderConfig.ThinkingMode.REASONING_EFFORT
                    && tc.reasoningEffort() != null && !tc.reasoningEffort().isBlank()
                    && think) {
                builder.reasoningEffort(tc.reasoningEffort());
            }
            // THINKING_BUDGET 模式：通过 customParameters 透传 thinking_budget
            if (tc.thinkingMode() == AiProviderConfig.ThinkingMode.THINKING_BUDGET
                    && tc.thinkingBudget() != null && think) {
                builder.customParameters(Map.of("thinking_budget", tc.thinkingBudget()));
            }
        }
        builder.apiKey(apiKey != null && !apiKey.isBlank() ? apiKey : "sk-placeholder");
        return builder.build();
    }

    private OpenAiStreamingChatModel buildOpenAiStreaming(String baseUrl, String modelName,
                                                          Double temperature, Duration timeout, String apiKey,
                                                          ThinkingConfig tc) {
        boolean gemini = isGeminiModel(baseUrl, modelName);
        var builder = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl).modelName(modelName)
                .temperature(temperature != null ? temperature : 0.7)
                .timeout(timeout != null ? timeout : Duration.ofSeconds(600))
                .listeners(List.of(new DiagnosticChatListener()));
        if (!gemini && tc.thinkingMode() != AiProviderConfig.ThinkingMode.NONE) {
            boolean think = tc.shouldThink();
            builder.returnThinking(think).sendThinking(false);
            // 关闭思考时，仅对 Qwen3 系列透传 enable_thinking=false
            if (!think && isQwen3Model(modelName)) {
                builder.customParameters(Map.of("enable_thinking", false));
            }
            if (tc.thinkingMode() == AiProviderConfig.ThinkingMode.REASONING_EFFORT
                    && tc.reasoningEffort() != null && !tc.reasoningEffort().isBlank()
                    && think) {
                builder.reasoningEffort(tc.reasoningEffort());
            }
            if (tc.thinkingMode() == AiProviderConfig.ThinkingMode.THINKING_BUDGET
                    && tc.thinkingBudget() != null && think) {
                builder.customParameters(Map.of("thinking_budget", tc.thinkingBudget()));
            }
        }
        builder.apiKey(apiKey != null && !apiKey.isBlank() ? apiKey : "sk-placeholder");
        return builder.build();
    }

    /**
     * 判断是否为 Google Gemini 模型（通过 baseUrl 或模型名识别）。
     * <p>
     * 仅用于防御性兜底：即使数据库 thinking_mode 误配为非 NONE，
     * Gemini 模型也会跳过所有思考参数（发送会触发 400）。
     * <p>
     * 其他不支持思考参数的模型（如 gemma4-31b）应通过数据库设置
     * {@link AiProviderConfig.ThinkingMode#NONE} 来跳过思考参数，
     * 不再在此处硬编码模型名。
     */
    private static boolean isGeminiModel(String baseUrl, String modelName) {
        if (baseUrl != null && baseUrl.contains("generativelanguage.googleapis.com")) return true;
        if (modelName != null) {
            String lower = modelName.toLowerCase(Locale.ROOT);
            return lower.contains("gemini");
        }
        return false;
    }

    /**
     * 判断是否为 Qwen3 系列模型（通过模型名识别）。
     * <p>
     * Qwen3 默认会思考，关闭思考需要发送 enable_thinking=false 参数。
     * enable_thinking 是 Qwen3 专属参数，其他 API（如 Google）会返回 400。
     */
    private static boolean isQwen3Model(String modelName) {
        if (modelName == null) return false;
        return modelName.toLowerCase(Locale.ROOT).contains("qwen3");
    }

    /**
     * 思考参数容器 — 联动 AiProviderConfig.thinkingMode 和 AiSceneConfig 的思考字段。
     * <p>
     * 联动规则（{@link AiProviderConfig.ThinkingMode}）：
     * <ul>
     *   <li>{@link AiProviderConfig.ThinkingMode#NONE} 不发送任何思考参数（如 Gemini）</li>
     *   <li>{@link AiProviderConfig.ThinkingMode#SWITCH} think=thinkingEnabled, returnThinking=thinkingEnabled</li>
     *   <li>{@link AiProviderConfig.ThinkingMode#REASONING_EFFORT} SWITCH + reasoningEffort（非空时发送）</li>
     *   <li>{@link AiProviderConfig.ThinkingMode#THINKING_BUDGET} SWITCH + thinking_budget（非空时透传）</li>
     * </ul>
     */
    public record ThinkingConfig(
            AiProviderConfig.ThinkingMode thinkingMode,
            boolean thinkingEnabled,
            String reasoningEffort,
            Integer thinkingBudget
    ) {
        /** 从 ResolvedSceneConfig 构建思考参数（联动） */
        public static ThinkingConfig from(com.kbook.service.ai.AiSceneConfigService.ResolvedSceneConfig resolved) {
            AiProviderConfig.ThinkingMode mode = resolved.providerConfig().getThinkingMode();
            if (mode == null) {
                mode = AiProviderConfig.ThinkingMode.SWITCH; // 老配置兼容
            }
            return new ThinkingConfig(
                    mode,
                    resolved.thinkingEnabled(),
                    resolved.reasoningEffort(),
                    resolved.thinkingBudget()
            );
        }

        /** 从 AiProviderConfig 直接构建（用于非场景路径：测试、压缩、OCR 等） */
        public static ThinkingConfig from(AiProviderConfig config, boolean thinking) {
            AiProviderConfig.ThinkingMode mode = config.getThinkingMode();
            if (mode == null) mode = AiProviderConfig.ThinkingMode.SWITCH;
            return new ThinkingConfig(mode, thinking, null, null);
        }

        /** 完全关闭思考（压缩、OCR 等场景） */
        public static ThinkingConfig off() {
            return new ThinkingConfig(AiProviderConfig.ThinkingMode.SWITCH, false, null, null);
        }

        /** 是否发送 think=true（Ollama）/ returnThinking=true（OpenAI） */
        public boolean shouldThink() {
            return thinkingMode != AiProviderConfig.ThinkingMode.NONE && thinkingEnabled;
        }
    }

    // ======================== Ollama KV 缓存管理 ========================

    private ChatModelListener ollamaCounterListener() {
        return new ChatModelListener() {
            @Override
            public void onRequest(ChatModelRequestContext ctx) {}

            @Override
            public void onResponse(ChatModelResponseContext ctx) {}

            @Override
            public void onError(ChatModelErrorContext ctx) {}
        };
    }
}
