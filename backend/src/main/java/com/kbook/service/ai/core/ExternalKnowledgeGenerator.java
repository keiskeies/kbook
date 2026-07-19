package com.kbook.service.ai.core;

import com.kbook.common.util.CommonUtils;
import com.kbook.config.ChatModelFactory;
import com.kbook.entity.AiScene;
import com.kbook.constants.AiPromptConstants;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部知识生成器 — 为圆桌派角色和辩论辩手生成领域外部知识，丰富讨论内容。
 *
 * <p>从 ChatModelManager 中抽取，直接依赖 ChatModelFactory 避免循环依赖。</p>
 *
 * @author kbook
 * @since 1.1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalKnowledgeGenerator {

    private final ChatModelFactory chatModelFactory;

    /** 为圆桌派角色生成外部知识 */
    public String generateForRoundTable(String roleDomain, String topic) {
        long startTime = System.currentTimeMillis();
        try {
            var model = chatModelFactory.buildForScene(AiScene.ROUND_TABLE_KNOWLEDGE);
            if (model == null) {
                log.warn("AI 模型未配置，跳过外部知识生成");
                return null;
            }
            List<ChatMessage> messages = appendNoThink(List.of(
                    SystemMessage.from(AiPromptConstants.EXTERNAL_KNOWLEDGE_SYSTEM_PROMPT),
                    UserMessage.from("角色专业领域：" + roleDomain + "\n讨论话题：" + topic)));
            ChatResponse response = model.chat(messages);
            long elapsed = System.currentTimeMillis() - startTime;
            int inputTokens = response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null
                    ? response.tokenUsage().inputTokenCount() : 0;
            int outputTokens = response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null
                    ? response.tokenUsage().outputTokenCount() : 0;
            String text = response.aiMessage().text();
            if (text != null) text = text.trim();
            var ctx = chatModelFactory.buildLogContext(AiScene.ROUND_TABLE_KNOWLEDGE);
            CommonUtils.logAiSummary("外部知识生成", ctx.scene(), ctx.modelName(), ctx.configName(),
                    ctx.thinkingMode(), ctx.thinkingEnabled(), ctx.reasoningEffort(),
                    messages, text, null, elapsed, inputTokens, outputTokens);
            return text;
        } catch (Exception e) {
            log.warn("外部知识生成失败: {}", e.getMessage());
            return null;
        }
    }

    /** 为辩论辩手生成外部知识 */
    public String generateForDebate(String topic, String side, String stance) {
        long startTime = System.currentTimeMillis();
        try {
            var model = chatModelFactory.buildForScene(AiScene.DEBATE_KNOWLEDGE);
            if (model == null) {
                log.warn("AI 模型未配置，跳过辩论外部知识生成");
                return null;
            }
            List<ChatMessage> messages = appendNoThink(List.of(
                    SystemMessage.from(AiPromptConstants.DEBATE_EXTERNAL_KNOWLEDGE_SYSTEM_PROMPT),
                    UserMessage.from("辩题：" + topic + "\n立场：" + side + "\n辩手视角：" + stance)));
            ChatResponse response = model.chat(messages);
            long elapsed = System.currentTimeMillis() - startTime;
            int inputTokens = response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null
                    ? response.tokenUsage().inputTokenCount() : 0;
            int outputTokens = response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null
                    ? response.tokenUsage().outputTokenCount() : 0;
            String text = response.aiMessage().text();
            if (text != null) text = text.trim();
            var ctx = chatModelFactory.buildLogContext(AiScene.DEBATE_KNOWLEDGE);
            CommonUtils.logAiSummary("辩论外部知识生成", ctx.scene(), ctx.modelName(), ctx.configName(),
                    ctx.thinkingMode(), ctx.thinkingEnabled(), ctx.reasoningEffort(),
                    messages, text, null, elapsed, inputTokens, outputTokens);
            return text;
        } catch (Exception e) {
            log.warn("辩论外部知识生成失败: {}", e.getMessage());
            return null;
        }
    }

    /** 在消息列表中追加 /no_think 到 SystemMessage（与 ChatModelManager.appendNoThink 同逻辑） */
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
}
