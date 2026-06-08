package com.kbook.service.tts;

import com.kbook.entity.TtsConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * TTS 引擎工厂
 * <p>
 * 根据配置的 provider 和 ttsType 自动匹配对应的 TTS 引擎实现。
 * Spring 自动注入所有 {@link TtsEngine} 实现，通过 {@code supports()} 方法匹配。
 */
@Component
@RequiredArgsConstructor
public class TtsEngineFactory {

    /** 所有 TtsEngine 实现类的列表，由 Spring 自动注入 */
    private final List<TtsEngine> engines;

    /**
     * 根据配置获取对应的 TTS 引擎
     *
     * @param config TTS 配置（包含 provider 和 ttsType）
     * @return 匹配的引擎实例
     * @throws RuntimeException 无匹配引擎时抛出
     */
    public TtsEngine getEngine(TtsConfig config) {
        return engines.stream()
                .filter(e -> e.supports(config))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("不支持的 TTS 配置: type=" + config.getTtsType() + ", provider=" + config.getProvider()));
    }
}
