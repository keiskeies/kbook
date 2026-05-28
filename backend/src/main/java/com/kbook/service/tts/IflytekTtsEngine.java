package com.kbook.service.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.entity.TtsConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class IflytekTtsEngine implements TtsEngine {

    private static final String DEFAULT_URL = "wss://tts-api.xfyun.cn/v2/tts";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean supports(TtsConfig config) {
        return config.getProvider() == TtsConfig.Provider.IFLYTEK
                && config.getTtsType() == TtsConfig.TtsType.TRADITIONAL;
    }

    @Override
    public byte[] synthesize(String text, TtsConfig config) {
        try {
            String appId = config.getAppId();
            String apiKey = config.getApiKey();
            String apiSecret = config.getApiSecret();

            String host = "tts-api.xfyun.cn";
            String date = getCurrentRfc1123Date();
            String signatureOrigin = "host: " + host + "\ndate: " + date + "\nGET /v2/tts HTTP/1.1";
            String signature = hmacSha256Base64(apiSecret, signatureOrigin);
            String authorization = Base64.getEncoder().encodeToString(
                    String.format("api_key=\"%s\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"%s\"",
                            apiKey, signature).getBytes(StandardCharsets.UTF_8)
            );

            String wsUrl = DEFAULT_URL + "?authorization=" + java.net.URLEncoder.encode(authorization, "UTF-8")
                    + "&date=" + java.net.URLEncoder.encode(date, "UTF-8")
                    + "&host=" + host;

            String voice = config.getVoice() != null ? config.getVoice() : "xiaoyan";
            int speed = config.getSpeed() != null ? config.getSpeed() : 50;
            int pitch = config.getPitch() != null ? config.getPitch() : 50;

            String businessJson = objectMapper.writeValueAsString(Map.of(
                    "aue", "lame",
                    "auf", "audio/L16;rate=16000",
                    "vcn", voice,
                    "speed", speed,
                    "pitch", pitch,
                    "volume", 50
            ));

            String dataJson = objectMapper.writeValueAsString(Map.of(
                    "status", 2,
                    "text", Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))
            ));

            String commonJson = objectMapper.writeValueAsString(Map.of("app_id", appId));

            Map<String, Object> framePayload = new LinkedHashMap<>();
            framePayload.put("common", objectMapper.readTree(commonJson));
            framePayload.put("business", objectMapper.readTree(businessJson));
            framePayload.put("data", objectMapper.readTree(dataJson));

            String frameJson = objectMapper.writeValueAsString(framePayload);

            CompletableFuture<byte[]> future = new CompletableFuture<>();
            List<byte[]> audioChunks = Collections.synchronizedList(new ArrayList<>());
            StringBuilder errorMsg = new StringBuilder();

            HttpClient httpClient = HttpClient.newHttpClient();
            httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.sendText(frameJson, true);
                            webSocket.request(1);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            try {
                                JsonNode json = objectMapper.readTree(data.toString());
                                int code = json.path("code").asInt();
                                if (code != 0) {
                                    String message = json.path("message").asText("未知错误");
                                    errorMsg.append("code=").append(code).append(", msg=").append(message);
                                    log.warn("iFlytek TTS error: {}", data);
                                }
                            } catch (Exception e) {
                                log.warn("Parse iFlytek response error", e);
                            }
                            webSocket.request(1);
                            return CompletableFuture.completedFuture(null);
                        }

                        @Override
                        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                            byte[] bytes = new byte[data.remaining()];
                            data.get(bytes);
                            audioChunks.add(bytes);
                            webSocket.request(1);
                            if (last) {
                                completeFuture(future, audioChunks, errorMsg);
                            }
                            return CompletableFuture.completedFuture(null);
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                            if (!future.isDone()) {
                                completeFuture(future, audioChunks, errorMsg);
                            }
                            return CompletableFuture.completedFuture(null);
                        }

                        @Override
                        public void onError(WebSocket webSocket, Throwable error) {
                            future.completeExceptionally(error);
                        }
                    })
                    .get(10, TimeUnit.SECONDS);

            return future.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("iFlytek TTS synthesis failed", e);
            throw new RuntimeException("TTS 合成失败: " + e.getMessage(), e);
        }
    }

    private void completeFuture(CompletableFuture<byte[]> future, List<byte[]> chunks, StringBuilder errorMsg) {
        if (future.isDone()) return;
        if (errorMsg.length() > 0) {
            future.completeExceptionally(new RuntimeException("TTS 错误: " + errorMsg));
        } else if (chunks.isEmpty()) {
            future.complete(new byte[0]);
        } else {
            int total = chunks.stream().mapToInt(b -> b.length).sum();
            byte[] result = new byte[total];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, result, offset, chunk.length);
                offset += chunk.length;
            }
            future.complete(result);
        }
    }

    private static String getCurrentRfc1123Date() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        return sdf.format(new Date());
    }

    private static String hmacSha256Base64(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signData);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }
}
