package com.kbook.service.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.entity.TtsConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Component
public class XiaomiTtsEngine implements TtsEngine {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1";
    private static final String DEFAULT_VOICE = "冰糖";
    private static final String DEFAULT_MODEL = "mimo-v2.5-tts";

    @Override
    public boolean supports(TtsConfig config) {
        return config.getProvider() == TtsConfig.Provider.XIAOMI
                && config.getTtsType() == TtsConfig.TtsType.LLM;
    }

    @Override
    public boolean supportsStreaming(TtsConfig config) {
        return supports(config);
    }

    @Override
    public byte[] synthesize(String text, TtsConfig config) {
        try {
            Map<String, Object> requestBody = buildRequestBody(text, config, "wav", false);

            String json = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resolveBaseUrl(config) + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("api-key", config.getApiKey())
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Xiaomi TTS API error: status={}, body={}", response.statusCode(), response.body());
                throw new RuntimeException("TTS 请求失败: HTTP " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode audioNode = root.path("choices").get(0).path("message").path("audio").path("data");
            if (audioNode.isMissingNode()) {
                log.warn("Xiaomi TTS response missing audio data: {}", response.body());
                throw new RuntimeException("TTS 响应中无音频数据");
            }

            return Base64.getDecoder().decode(audioNode.asText());
        } catch (Exception e) {
            log.error("Xiaomi TTS synthesis failed", e);
            throw new RuntimeException("TTS 合成失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void synthesizeStream(String text, TtsConfig config, Consumer<byte[]> onChunk, Runnable onDone) {
        try {
            Map<String, Object> requestBody = buildRequestBody(text, config, "pcm16", true);

            String json = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resolveBaseUrl(config) + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("api-key", config.getApiKey())
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes());
                log.warn("Xiaomi TTS stream API error: status={}, body={}", response.statusCode(), errorBody);
                throw new RuntimeException("TTS 流式请求失败: HTTP " + response.statusCode());
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) break;

                try {
                    JsonNode chunk = objectMapper.readTree(data);
                    JsonNode choices = chunk.path("choices");
                    if (!choices.isArray() || choices.isEmpty()) continue;
                    JsonNode audioNode = choices.get(0).path("delta").path("audio").path("data");
                    if (audioNode.isMissingNode() || audioNode.asText().isEmpty()) continue;
                    byte[] pcmBytes = Base64.getDecoder().decode(audioNode.asText());
                    onChunk.accept(pcmBytes);
                } catch (Exception e) {
                    log.debug("Failed to parse TTS stream chunk: {}", e.getMessage());
                }
            }

            onDone.run();
        } catch (Exception e) {
            log.error("Xiaomi TTS stream synthesis failed", e);
            throw new RuntimeException("TTS 流式合成失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildRequestBody(String text, TtsConfig config, String format, boolean stream) {
        Map<String, Object> audio = new java.util.LinkedHashMap<>();
        audio.put("format", format);
        audio.put("voice", resolveVoice(config));
        if (stream) {
            audio.put("stream", true);
        }

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", resolveModel(config));
        body.put("messages", List.of(
                Map.of("role", "user", "content", "请用自然流畅的语气朗读文本"),
                Map.of("role", "assistant", "content", text)
        ));
        body.put("audio", audio);
        if (stream) {
            body.put("stream", true);
        }
        return body;
    }

    private String resolveBaseUrl(TtsConfig config) {
        String baseUrl = config.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return DEFAULT_BASE_URL;
        return baseUrl.replaceAll("/+$", "");
    }

    private String resolveVoice(TtsConfig config) {
        String voice = config.getVoice();
        return (voice == null || voice.isBlank()) ? DEFAULT_VOICE : voice;
    }

    private String resolveModel(TtsConfig config) {
        String model = config.getModelName();
        return (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
    }
}
