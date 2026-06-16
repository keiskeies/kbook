package com.kbook.service.speech;

import com.kbook.entity.TtsConfig;
import com.kbook.repository.TtsConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 语音认证服务 — 从 TtsConfig 读取凭证，为前端生成临时鉴权凭证（Token / 签名 URL）
 * <p>
 * Azure: 查 provider=AZURE 的启用配置，用 apiKey+baseUrl 换 Token
 * 讯飞:  查 provider=IFLYTEK 的启用配置，用 apiKey+apiSecret 生成签名 URL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpeechAuthService {

    private final TtsConfigRepository ttsConfigRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    // ─── Azure ────────────────────────────────────────────────────

    public Map<String, String> getAzureToken() {
        TtsConfig config = ttsConfigRepository.findAll().stream()
                .filter(c -> c.getProvider() == TtsConfig.Provider.AZURE && Boolean.TRUE.equals(c.getEnabled()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Azure Speech 未配置"));

        String region = config.getBaseUrl();
        if (region == null || region.isBlank()) {
            throw new RuntimeException("Azure region(baseUrl) 不能为空");
        }

        String url = "https://" + region + ".api.cognitive.microsoft.com/sts/v1.0/issueToken";
        HttpHeaders headers = new HttpHeaders();
        headers.set("Ocp-Apim-Subscription-Key", config.getApiKey());

        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(headers), String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Azure token 获取失败: " + response.getStatusCode());
        }

        Map<String, String> result = new HashMap<>();
        result.put("token", response.getBody());
        result.put("region", region);
        return result;
    }

    // ─── 讯飞 ──────────────────────────────────────────────────────

    public Map<String, String> getXfyunAuthUrl() {
        TtsConfig config = ttsConfigRepository.findAll().stream()
                .filter(c -> c.getProvider() == TtsConfig.Provider.IFLYTEK && Boolean.TRUE.equals(c.getEnabled()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("讯飞语音未配置"));

        String host = "tts-api.xfyun.cn";
        String path = "/v2/tts";
        String date = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                .format(new Date());

        String signatureOrigin = "host: " + host + "\ndate: " + date + "\nGET " + path + " HTTP/1.1";
        String signatureSha = hmacSha256(signatureOrigin, config.getApiSecret());
        String authorizationOrigin = String.format(
                "api_key=\"%s\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"%s\"",
                config.getApiKey(), signatureSha);
        String authorization = Base64.getEncoder().encodeToString(
                authorizationOrigin.getBytes(StandardCharsets.UTF_8));

        String wsUrl = String.format("wss://%s%s?host=%s&date=%s&authorization=%s",
                host, path, urlEncode(host), urlEncode(date), urlEncode(authorization));

        Map<String, String> result = new HashMap<>();
        result.put("wsUrl", wsUrl);
        result.put("appId", config.getAppId());
        return result;
    }

    // ─── 工具 ─────────────────────────────────────────────────────

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec spec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(spec);
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 签名失败", e);
        }
    }
}
