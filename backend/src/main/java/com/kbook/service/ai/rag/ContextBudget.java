package com.kbook.service.ai.rag;

import com.kbook.config.properties.BookQaProperties;
import com.kbook.entity.AiProviderConfig;
import com.kbook.entity.AiScene;
import com.kbook.service.ai.core.AiSceneConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 统一的上下文预算。
 * <p>
 * 改造前有三套互不相干的系数：{@code getRagMaxChars = maxTokens×1.5×0.6}、
 * {@code effectiveMaxOutputTokens = min(desired, context/2)}、历史压缩 {@code 0.8}。
 * 用户提出的判据「可用空间 = 模型上下文 − 历史问答 − 输出预留」并没有真正实现。
 * 本类把它收口成唯一公式，分档判定与后续截断共用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextBudget {

    /** 场景配置缺失时的兜底（与 DataInitializer 种子、Ollama 预设 128K 对齐） */
    public static final int DEFAULT_CONTEXT_TOKENS = 131072;
    /** ChatModelFactory 对 BOOK_QA 的期望输出上限 */
    private static final int BOOK_QA_DESIRED_OUTPUT_TOKENS = 16384;

    private final AiSceneConfigService sceneConfigService;
    private final BookQaProperties properties;

    public record Budget(int contextTokens, int outputTokens, int grossChars,
                         int reservedChars, int availableChars) {
    }

    /**
     * 纯函数，便于单测。
     *
     * @param overheadChars 系统提示 + 静态书籍语料 + 动态元信息 + 画像 + 问题 + 回复预留
     * @param historyChars  当前会话历史（压缩后）的字符数
     */
    public static Budget compute(int contextTokens, int outputTokens, double charPerToken,
                                 double safetyMargin, int overheadChars, int historyChars) {
        int ctx = Math.max(1024, contextTokens);
        int out = Math.min(Math.max(512, outputTokens), ctx / 2);
        double ratio = charPerToken > 0 ? charPerToken : 1.5;
        int gross = (int) Math.max(0L, Math.round((long) (ctx - out) * ratio));
        double margin = Math.max(0.0, Math.min(0.9, safetyMargin));
        int safety = (int) (gross * margin);
        int overhead = Math.max(0, overheadChars);
        int history = Math.max(0, historyChars);
        int available = Math.max(0, gross - safety - overhead - history);
        return new Budget(ctx, out, gross, overhead + history, available);
    }

    /** 从 BOOK_QA 场景绑定的模型配置解析真实上下文长度与输出上限。 */
    public Budget resolve(int overheadChars, int historyChars) {
        Integer contextTokens = null;
        Integer outputTokens = null;
        try {
            AiProviderConfig cfg = sceneConfigService.resolveConfig(AiScene.BOOK_QA);
            if (cfg != null) {
                contextTokens = cfg.getMaxTokens();
                outputTokens = cfg.getMaxOutputTokens();
            }
        } catch (Exception e) {
            log.warn("[ContextBudget] 解析 BOOK_QA 场景配置失败，回退默认: {}", e.getMessage());
        }
        int ctx = contextTokens != null && contextTokens > 0
                ? contextTokens : DEFAULT_CONTEXT_TOKENS;
        int out = outputTokens != null && outputTokens > 0
                ? outputTokens : Math.min(BOOK_QA_DESIRED_OUTPUT_TOKENS, ctx / 2);
        return compute(ctx, out, properties.getCharPerToken(),
                properties.getSafetyMarginRatio(), overheadChars, historyChars);
    }
}
