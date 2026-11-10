package com.kbook.config;

import com.kbook.config.properties.AiProviderProperties;
import com.kbook.entity.AiProviderConfig;
import com.kbook.repository.AiProviderConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型上下文长度校准。
 * <p>
 * 背景：{@code DataInitializer} 的历史种子把 {@code max_tokens} 固定写为 32768，
 * 而 {@code ai-providers.yml} 里的模型实际是 128K / 1M。{@code BookChatRagService.getRagMaxChars()}
 * 与 {@code BookChatSessionService.contextCharLimit()} 都直接拿这个值做预算，
 * 导致「可用空间 = 上下文 − 历史 − 输出」的分档判定分子本身就是错的。
 * <p>
 * 本组件在 {@link ApplicationReadyEvent}（所有 CommandLineRunner 之后）执行一次：
 * 只同步「未同步过 且 maxTokens 仍是旧默认/为空」的行；同步过的行以及管理员手工设置过
 * 非默认值的行一律不动。后台新建/改模型时由 {@link #fillMissingContextWindow} 补全。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiModelContextLengthResolver {

    /** 历史种子默认值，作为「尚未校准」的哨兵值 */
    public static final int LEGACY_DEFAULT_MAX_TOKENS = 32768;

    private final AiProviderConfigRepository configRepository;
    private final AiProviderProperties providerProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void syncLegacyContextWindows() {
        try {
            List<AiProviderConfig> configs = configRepository.findAll();
            int fixed = 0;
            for (AiProviderConfig config : configs) {
                if (Boolean.TRUE.equals(config.getContextWindowSynced())) {
                    continue;
                }
                Integer current = config.getMaxTokens();
                boolean legacy = current == null || current <= 0
                        || current == LEGACY_DEFAULT_MAX_TOKENS;
                if (legacy) {
                    Long preset = providerProperties.resolveMaxTokens(config.getModelName());
                    if (preset != null && preset <= Integer.MAX_VALUE) {
                        config.setMaxTokens(preset.intValue());
                        fixed++;
                        log.info("[ContextWindow] 已按模型预设校准: id={}, model={}, {} → {}",
                                config.getId(), config.getModelName(), current, preset.intValue());
                    }
                }
                config.setContextWindowSynced(true);
            }
            configRepository.saveAll(configs);
            if (fixed > 0) {
                log.info("[ContextWindow] 启动校准完成: 共 {} 行, 修正 {} 行", configs.size(), fixed);
            }
            warnOnRemainingMismatch(configs);
        } catch (Exception e) {
            // 校准是启动期的辅助逻辑，失败不能阻断服务
            log.warn("[ContextWindow] 启动校准失败（不影响服务）: {}", e.getMessage());
        }
    }

    /**
     * 新建/编辑配置时补全缺失的上下文长度。已有显式值一律不覆盖，避免顶掉管理员的手工设置。
     */
    public void fillMissingContextWindow(AiProviderConfig config) {
        if (config == null) return;
        Integer current = config.getMaxTokens();
        if (current == null || current <= 0) {
            Long preset = providerProperties.resolveMaxTokens(config.getModelName());
            if (preset != null && preset <= Integer.MAX_VALUE) {
                config.setMaxTokens(preset.intValue());
            }
        }
        config.setContextWindowSynced(true);
    }

    /** 预设与显式值差异过大时给出告警，交由管理员判断（不自动改）。 */
    private void warnOnRemainingMismatch(List<AiProviderConfig> configs) {
        for (AiProviderConfig config : configs) {
            Long preset = providerProperties.resolveMaxTokens(config.getModelName());
            Integer current = config.getMaxTokens();
            if (preset == null || current == null || current <= 0) continue;
            if (preset > current * 4L || current > preset * 4L) {
                log.warn("[ContextWindow] 上下文长度与模型预设差异较大，请确认是否手工配置: id={}, model={}, 当前={}, 预设={}",
                        config.getId(), config.getModelName(), current, preset);
            }
        }
    }
}
