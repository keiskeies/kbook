package com.kbook.service.tts;

import com.kbook.entity.TtsConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class TtsEngineFactory {

    private final List<TtsEngine> engines;

    public TtsEngine getEngine(TtsConfig config) {
        return engines.stream()
                .filter(e -> e.supports(config))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("不支持的 TTS 配置: type=" + config.getTtsType() + ", provider=" + config.getProvider()));
    }
}
