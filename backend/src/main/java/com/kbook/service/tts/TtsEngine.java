package com.kbook.service.tts;

import com.kbook.entity.TtsConfig;

import java.util.function.Consumer;

/**
 * TTS 引擎接口
 * <p>
 * 定义文本转语音的标准契约，所有 TTS 引擎（小米 MiMo、讯飞、GPT-SoVITS 等）需实现此接口。
 * 支持三种输出形态：
 * <ul>
 *   <li>{@link #synthesize} — 一次性返回完整音频（历史接口，短文本用）</li>
 *   <li>{@link #synthesizeStream} — SSE + PCM16 增量帧（历史接口，Web Audio 直通用）</li>
 *   <li>{@link #synthesizeProgressiveAudio} — 单一容器格式的渐进式字节流，
 *       可直接作为 audio 元素的音源，天然支持锁屏与后台播放</li>
 * </ul>
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

    /**
     * 判断当前引擎能否输出「渐进式可播放音频」
     * <p>
     * 返回 true 时，引擎保证 {@link #synthesizeProgressiveAudio} 产出的是
     * 单一容器格式（如 MP3）的连续字节流：任意时刻已产出的全部字节拼起来
     * 都是一个可正常解码的音频文件，因此浏览器可以边下边播，
     * 中途连接断开后也能按字节偏移续传。
     *
     * @param config TTS 配置
     * @return 支持返回 true
     */
    default boolean supportsProgressiveAudio(TtsConfig config) {
        return false;
    }

    /**
     * 渐进式音频容器的扩展名，同时作为缓存文件的区分维度
     *
     * @param config TTS 配置
     * @return 扩展名（mp3 / wav）
     */
    default String audioFormat(TtsConfig config) {
        return TtsCache.EXT_WAV;
    }

    /**
     * 渐进式合成文本语音：按顺序回调音频字节块，调用方通常直接写入 HTTP 响应体
     * <p>
     * 默认实现回退为一次性合成后单块返回，保证未实现该能力的引擎依然可用。
     *
     * @param text    待合成文本
     * @param config  TTS 配置
     * @param onChunk 按序到达的音频字节块
     * @param onDone  全部音频已回调完毕
     */
    default void synthesizeProgressiveAudio(String text, TtsConfig config,
                                           Consumer<byte[]> onChunk, Runnable onDone) {
        onChunk.accept(synthesize(text, config));
        onDone.run();
    }
}
