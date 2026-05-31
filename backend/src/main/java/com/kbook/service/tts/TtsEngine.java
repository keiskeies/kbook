package com.kbook.service.tts;

import com.kbook.entity.TtsConfig;

import java.util.function.Consumer;

public interface TtsEngine {
    byte[] synthesize(String text, TtsConfig config);

    boolean supports(TtsConfig config);

    default void synthesizeStream(String text, TtsConfig config, Consumer<byte[]> onChunk, Runnable onDone) {
        byte[] result = synthesize(text, config);
        onChunk.accept(result);
        onDone.run();
    }

    default boolean supportsStreaming(TtsConfig config) {
        return false;
    }
}
