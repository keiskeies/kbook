package com.kbook.service.tts;

import com.kbook.entity.TtsConfig;

public interface TtsEngine {
    byte[] synthesize(String text, TtsConfig config);
    boolean supports(TtsConfig config);
}
