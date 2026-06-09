package com.kbook.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * GPT-SoVITS 语音克隆 TTS 配置属性
 * <p>
 * 音色预设从 tts-voices.yml 加载，包含模型路径和参考音频信息。
 */
@Data
@Component
@ConfigurationProperties(prefix = "gpt-sovits")
public class GptSovitsProperties {

    /** GPT-SoVITS 服务基础地址 */
    private String baseUrl = "http://127.0.0.1:9880";

    /** GPT 模型权重目录 */
    private String gptWeightsDir = "";

    /** SoVITS 模型权重目录 */
    private String sovitsWeightsDir = "";

    /** 音色预设列表 */
    private List<VoicePreset> voices = new ArrayList<>();

    /**
     * 根据 ID 查找音色预设
     */
    public VoicePreset findVoice(String voiceId) {
        if (voiceId == null) return null;
        return voices.stream()
                .filter(v -> voiceId.equals(v.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * GPT-SoVITS 音色预设
     */
    @Data
    public static class VoicePreset {
        /** 音色唯一标识 */
        private String id;
        /** 显示名称 */
        private String name;
        /** 语言 */
        private String lang = "zh";
        /** GPT 模型文件名（位于 gptWeightsDir 下） */
        private String gptCkpt;
        /** SoVITS 模型文件名（位于 sovitsWeightsDir 下） */
        private String sovitsPth;
        /** 参考音频绝对路径 */
        private String refAudioPath;
        /** 参考音频对应文本 */
        private String promptText;
    }
}
