package com.kbook.service.tts;

import com.kbook.entity.TtsConfig;

import java.util.function.Consumer;

/**
 * TTS 引擎接口
 * <p>
 * 定义文本转语音的标准契约，所有 TTS 引擎（小米 MiMo、讯飞、GPT-SoVITS 等）需实现此接口。
 * 支持普通合成和流式合成两种模式。
 */
public interface TtsEngine {

    /**
     * 执行普通文本转语音合成，返回完整音频字节数组
     *
     * @param text   待合成文本
     * @param config TTS 配置
     * @return 音频数据（PCM/WAV 格式）
     */
    byte[] synthesize(String text, TtsConfig config);

    /**
     * 判断当前引擎是否支持给定配置
     *
     * @param config TTS 配置（包含 provider 和 ttsType）
     * @return 支持返回 true
     */
    boolean supports(TtsConfig config);

    /**
     * 流式合成文本转语音，通过回调逐块返回音频数据
     * <p>
     * 默认实现：回退到普通合成，一次性返回所有数据
     *
     * @param text    待合成文本
     * @param config  TTS 配置
     * @param onChunk 每个音频数据块的回调
     * @param onDone  合成完成回调
     */
    default void synthesizeStream(String text, TtsConfig config, Consumer<byte[]> onChunk, Runnable onDone) {
        byte[] result = synthesize(text, config);
        onChunk.accept(result);
        onDone.run();
    }

    /**
     * 判断当前引擎是否支持流式合成
     *
     * @param config TTS 配置
     * @return 支持返回 true
     */
    default boolean supportsStreaming(TtsConfig config) {
        return false;
    }
}
