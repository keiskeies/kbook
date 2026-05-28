package com.kbook.service.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.entity.TtsConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class XiaomiTtsEngine implements TtsEngine {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean supports(TtsConfig config) {
        return config.getProvider() == TtsConfig.Provider.XIAOMI
                && config.getTtsType() == TtsConfig.TtsType.LLM;
    }

    @Override
    public byte[] synthesize(String text, TtsConfig config) {
        try {
            String baseUrl = config.getBaseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://api.xiaomimimo.com/v1";
            }
            baseUrl = baseUrl.replaceAll("/+$", "");

            String voice = config.getVoice();
            if (voice == null || voice.isBlank()) {
                voice = "冰糖";
            }

            Map<String, Object> requestBody = Map.of(
                    "model", config.getModelName() != null ? config.getModelName() : "mimo-v2.5-tts",
                    "messages", List.of(
                            Map.of("role", "user", "content", "请用自然流畅的语气朗读文本"),
                            Map.of("role", "assistant", "content", text)
                    ),
                    "audio", Map.of(
                            "format", "wav",
                            "voice", voice
                    )
            );

            String json = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
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
}
