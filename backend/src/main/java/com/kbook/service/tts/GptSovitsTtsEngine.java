package com.kbook.service.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.config.properties.GptSovitsProperties;
import com.kbook.entity.TtsConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * GPT-SoVITS 语音克隆 TTS 引擎
 * <p>
 * 基于本地部署的 GPT-SoVITS 服务，支持零样本语音克隆合成。
 * 通过 /set_gpt_weights 和 /set_sovits_weights 切换模型，
 * 通过 /tts 接口合成语音，返回完整 WAV 音频。
 * <p>
 * 关键设计：
 * <ul>
 *   <li>GPT-SoVITS 不支持并发，使用 Semaphore(1) 串行化所有请求</li>
 *   <li>模型切换有缓存机制：仅在音色变化时才调用切换接口</li>
 *   <li>不支持流式合成（/tts 返回完整 WAV），supportsStreaming 返回 false</li>
 * </ul>
 */
@Slf4j
@Component
public class GptSovitsTtsEngine implements TtsEngine {

    private final GptSovitsProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** GPT-SoVITS 不支持并发，用信号量串行化请求 */
    private final Semaphore concurrencyLock = new Semaphore(1);

    /** 当前已加载的 GPT 模型路径（用于缓存，避免重复切换） */
    private volatile String currentGptModel;
    /** 当前已加载的 SoVITS 模型路径（用于缓存，避免重复切换） */
    private volatile String currentSovitsModel;

    public GptSovitsProperties getProperties() {
        return properties;
    }

    public GptSovitsTtsEngine(GptSovitsProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public boolean supports(TtsConfig config) {
        return config.getProvider() == TtsConfig.Provider.GPT_SOVITS
                && config.getTtsType() == TtsConfig.TtsType.CLONE;
    }

    @Override
    public boolean supportsStreaming(TtsConfig config) {
        // GPT-SoVITS /tts 返回完整 WAV，不支持真正的流式合成
        return false;
    }

    @Override
    public byte[] synthesize(String text, TtsConfig config) {
        try {
            concurrencyLock.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("GPT-SoVITS 请求被中断");
        }
        try {
            switchModel(config);

            GptSovitsProperties.VoicePreset voice = resolveVoice(config);
            Map<String, Object> requestBody = buildRequestBody(text, voice);
            String json = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resolveBaseUrl(config) + "/tts"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                String body = new String(response.body());
                log.warn("GPT-SoVITS TTS API error: status={}, body={}", response.statusCode(), body);
                throw new RuntimeException("GPT-SoVITS TTS 请求失败: HTTP " + response.statusCode());
            }

            log.info("GPT-SoVITS synthesized: textLength={} bytes, audioSize={}", text.length(), response.body().length);
            return response.body();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("GPT-SoVITS TTS synthesis failed", e);
            throw new RuntimeException("GPT-SoVITS TTS 合成失败: " + e.getMessage(), e);
        } finally {
            concurrencyLock.release();
        }
    }

    /**
     * 切换 GPT-SoVITS 模型（带缓存，仅在模型变化时才切换）
     * <p>
     * 调用方已持有 concurrencyLock，此方法不需要额外加锁
     */
    private void switchModel(TtsConfig config) throws Exception {
        GptSovitsProperties.VoicePreset voice = resolveVoice(config);
        String gptPath = properties.getGptWeightsDir() + "/" + voice.getGptCkpt();
        String sovitsPath = properties.getSovitsWeightsDir() + "/" + voice.getSovitsPth();

        String baseUrl = resolveBaseUrl(config);

        if (!gptPath.equals(currentGptModel)) {
            String encodedPath = java.net.URLEncoder.encode(gptPath, "UTF-8");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/set_gpt_weights?weights_path=" + encodedPath))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("GPT-SoVITS set_gpt_weights failed: status={}, body={}", response.statusCode(), response.body());
                throw new RuntimeException("切换 GPT 模型失败: HTTP " + response.statusCode());
            }
            currentGptModel = gptPath;
            log.info("GPT-SoVITS switched GPT model: {}", voice.getGptCkpt());
        }

        if (!sovitsPath.equals(currentSovitsModel)) {
            String encodedPath = java.net.URLEncoder.encode(sovitsPath, "UTF-8");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/set_sovits_weights?weights_path=" + encodedPath))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("GPT-SoVITS set_sovits_weights failed: status={}, body={}", response.statusCode(), response.body());
                throw new RuntimeException("切换 SoVITS 模型失败: HTTP " + response.statusCode());
            }
            currentSovitsModel = sovitsPath;
            log.info("GPT-SoVITS switched SoVITS model: {}", voice.getSovitsPth());
        }
    }

    private GptSovitsProperties.VoicePreset resolveVoice(TtsConfig config) {
        String voicePresetId = config.getVoicePresetId();
        GptSovitsProperties.VoicePreset voice = properties.findVoice(voicePresetId);
        if (voice == null) {
            throw new RuntimeException("GPT-SoVITS 音色预设不存在: " + voicePresetId);
        }
        return voice;
    }

    private Map<String, Object> buildRequestBody(String text, GptSovitsProperties.VoicePreset voice) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("text_lang", voice.getLang());
        body.put("ref_audio_path", voice.getRefAudioPath());
        body.put("prompt_text", voice.getPromptText());
        body.put("prompt_lang", voice.getLang());
        body.put("split_bucket", true);
        return body;
    }

    private String resolveBaseUrl(TtsConfig config) {
        String baseUrl = config.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return properties.getBaseUrl();
        return baseUrl.replaceAll("/+$", "");
    }
}
