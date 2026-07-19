package com.kbook.service.ai;

import com.kbook.common.util.CommonUtils;
import com.kbook.config.ChatModelFactory;
import com.kbook.entity.AiScene;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * AI 模型调用管理器 — 统一的 AI 调用入口。
 * <p>
 * 职责：
 * 1. 提供场景路由的 AI 调用入口（callAiForScene / getStreamingModelForScene）
 * 2. 统一的 AI 调用日志（logAiSummary 块状摘要）
 * 3. 日志上下文构建（buildLogContext）
 * </p>
 * <p>
 * 业务逻辑（追问生成、查询扩展、辩论/圆桌派调用等）已迁移到各自的 Service。
 * </p>
 *
 * @author kbook
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatModelManager {

    private final ChatModelFactory chatModelFactory;

    // ================================================================
    // AI 调用日志上下文 — 携带场景/模型/思考配置，供 logAiSummary 使用
    // ================================================================

    /**
     * AI 调用日志上下文 — 一次 LLM 调用的场景/模型/思考配置元数据。
     * <p>
     * 由 {@link ChatModelFactory#buildLogContext} 构建，传递给
     * {@link CommonUtils#logAiSummary} 打印统一摘要日志。
     *
     * @param scene           场景名（如"ROUND_TABLE_SPEECH"）
     * @param modelName       模型名（如"agnes-2.0-flash"）
     * @param configName      配置名（如"ai-gateway-agnes-2.0-flash"）
     * @param thinkingMode    思考模式（如"SWITCH"/"REASONING_EFFORT"/"NONE"）
     * @param thinkingEnabled 思考是否开启
     * @param reasoningEffort reasoning effort（可为 null）
     */
    public record AiCallLogContext(String scene, String modelName, String configName,
                                   String thinkingMode, boolean thinkingEnabled, String reasoningEffort) {
    }


    /**
     * 核心 AI 调用方法（带日志上下文）— 所有日志合并为一条 INFO 级别的统一摘要。
     *
     * @param logContext 日志上下文（场景/模型/思考配置），可为 null（摘要中显示"未知"）
     */
    private String callAi(String logName, String logDetail,
                         Supplier<ChatModel> modelSupplier, List<ChatMessage> messages,
                         AiCallLogContext logContext) {
        // 获取 ChatModel 实例，如果模型未配置则直接返回 null
        ChatModel model = modelSupplier.get();
        if (model == null) {
            log.warn("AI 模型未配置，跳过: {}", logName);
            return null;
        }
        // 记录调用开始时间，用于计算耗时
        long startTime = System.currentTimeMillis();
        // DEBUG: 打印完整对话消息
        CommonUtils.logAiMessages(logName, messages);
        // 执行 AI 对话
        ChatResponse response = model.chat(messages);
        // 计算耗时
        long elapsed = System.currentTimeMillis() - startTime;
        // 提取 token 使用量（输入和输出），避免空指针
        int inputTokens = response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null
                ? response.tokenUsage().inputTokenCount() : 0;
        int outputTokens = response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null
                ? response.tokenUsage().outputTokenCount() : 0;
        // 获取 AI 响应文本并去除首尾空白
        String text = response.aiMessage().text();
        if (text != null && !text.isBlank()) {
            text = stripThoughtTags(text).trim();
        }

        // INFO: 统一摘要日志（一次 LLM 调用只打一条）
        String scene = logContext != null ? logContext.scene() : null;
        String modelName = logContext != null ? logContext.modelName() : null;
        String configName = logContext != null ? logContext.configName() : null;
        String thinkingMode = logContext != null ? logContext.thinkingMode() : null;
        boolean thinkingEnabled = logContext != null && logContext.thinkingEnabled();
        String reasoningEffort = logContext != null ? logContext.reasoningEffort() : null;
        CommonUtils.logAiSummary(logName, scene, modelName, configName,
                thinkingMode, thinkingEnabled, reasoningEffort,
                messages,
                text, null,
                elapsed, inputTokens, outputTokens);
        return text;
    }


    // ================================================================
    // 场景路由入口（推荐使用）— 由 AiSceneConfigService 解析场景→配置
    // ================================================================

    /**
     * 按场景调用 AI（非流式）— 推荐入口。
     * <p>
     * 场景的 {@link AiScene#isThinking()} 决定是否启用思考：
     * <ul>
     *   <li>thinking=true：不追加 /no_think，模型按 reasoningEffort 配置思考</li>
     *   <li>thinking=false：追加 /no_think，并强制 returnThinking=false</li>
     * </ul>
     *
     * @param scene     AI 场景
     * @param logName   日志标识
     * @param logDetail 日志详情
     * @param messages  完整的 ChatMessage 列表
     * @return AI 响应文本
     */
    public String callAiForScene(AiScene scene, String logName, String logDetail, List<ChatMessage> messages) {
        List<ChatMessage> finalMessages = scene.isThinking() ? messages : appendNoThink(messages);
        AiCallLogContext logContext = chatModelFactory.buildLogContext(scene);
        return callAi(logName, logDetail, () -> chatModelFactory.buildForScene(scene), finalMessages, logContext);
    }

    /**
     * 按场景获取流式 ChatModel — 推荐入口。
     */
    public StreamingChatModel getStreamingModelForScene(AiScene scene) {
        return chatModelFactory.buildStreamingForScene(scene);
    }


    /**
     * 构建场景的日志上下文（供流式调用方传递给 StreamingSseHandler）。
     */
    public AiCallLogContext buildLogContext(AiScene scene) {
        return chatModelFactory.buildLogContext(scene);
    }

    /**
     * 在消息列表中追加 /no_think 到 SystemMessage，用于禁用 LLM 推理 token。
     * <p>
     * 新建一个不可变的 List（避免修改调用方传入的可变列表），找到第一条 SystemMessage
     * 并将 " /no_think" 追加到其文本末尾。如果列表中没有 SystemMessage，则原样返回。
     * </p>
     */
    private static List<ChatMessage> appendNoThink(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return messages;
        List<ChatMessage> result = new ArrayList<>(messages);
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i) instanceof SystemMessage sysMsg) {
                result.set(i, SystemMessage.from(sysMsg.text() + " \n\n /no_think"));
                break;
            }
        }
        return result;
    }

    /**
     * 剥离 Google AI 的 {@code <thought>...</thought>} 标签及其内容。
     * <p>
     * Google 模型（如 gemma4-31b）通过 ai-gateway 代理时，思考内容以
     * {@code <thought>} 标签内嵌在普通文本中返回，{@code returnThinking(false)}
     * 对 Google API 无效。非流式调用需要手动剥离这些标签。
     * <p>
     * 流式调用由 {@link com.kbook.service.ai.streaming.ThoughtTagParser} 处理。
     *
     * @param text AI 响应文本
     * @return 剥离 {@code <thought>} 标签后的文本
     */
    private static String stripThoughtTags(String text) {
        if (text == null || text.isEmpty()) return text;
        // 移除 <thought>...</thought>（含未闭合的 <thought> 到结尾）
        String result = text.replaceAll("(?s)<thought>.*?(</thought>|$)", "");
        return result.trim();
    }

}
