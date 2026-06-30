package com.kbook.service.ai.core;

import com.kbook.constants.AiPromptConstants;
import com.kbook.service.ai.ChatModelManager;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 外部知识生成器 — 为圆桌派角色和辩论辩手生成领域外部知识，丰富讨论内容。
 *
 * <p>从 ChatModelManager 中抽取，职责单一：为角色生成与讨论主题相关的外部知识。</p>
 *
 * @author kbook
 * @since 1.1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalKnowledgeGenerator {

    private final ChatModelManager chatModelManager;

    /** 为圆桌派角色生成外部知识 */
    public String generateForRoundTable(String roleDomain, String topic) {
        return chatModelManager.callAiWithoutThinking("外部知识生成",
                "领域=" + roleDomain + ", 话题=" + topic,
                List.of(
                        SystemMessage.from(AiPromptConstants.EXTERNAL_KNOWLEDGE_SYSTEM_PROMPT),
                        UserMessage.from("角色专业领域：" + roleDomain + "\n讨论话题：" + topic)));
    }

    /** 为辩论辩手生成外部知识 */
    public String generateForDebate(String topic, String side, String stance) {
        return chatModelManager.callAiWithoutThinking("辩论外部知识生成",
                "辩题=" + topic + ", 立场=" + side,
                List.of(
                        SystemMessage.from(AiPromptConstants.DEBATE_EXTERNAL_KNOWLEDGE_SYSTEM_PROMPT),
                        UserMessage.from("辩题：" + topic + "\n立场：" + side + "\n辩手视角：" + stance)));
    }
}
