package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.config.properties.GptSovitsProperties;
import com.kbook.dto.request.TtsSynthesizeRequest;
import com.kbook.entity.TtsConfig;
import com.kbook.service.tts.TtsConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 语音合成控制器 — 公开 TTS 接口（语音合成、流式合成、配置查询）
 * <p>
 * 管理员 TTS 配置 CRUD 请见 {@link AdminTtsConfigController}
 */
@Slf4j
@RestController
@Tag(name = "语音合成")
public class TtsConfigController extends BaseController {

    private final TtsConfigService ttsConfigService;

    /** GPT-SoVITS 音色预设配置 */
    private final GptSovitsProperties gptSovitsProperties;

    /** SSE 异步执行器 */
    private final ExecutorService sseExecutor;

    public TtsConfigController(
            TtsConfigService ttsConfigService,
            GptSovitsProperties gptSovitsProperties,
            @Qualifier("sseExecutor") ExecutorService sseExecutor) {
        this.ttsConfigService = ttsConfigService;
        this.gptSovitsProperties = gptSovitsProperties;
        this.sseExecutor = sseExecutor;
    }

    @Operation(summary = "获取当前TTS配置")
    @GetMapping("/api/tts/config/active")
    public Result<TtsConfig> getActiveConfig() {
        TtsConfig config = ttsConfigService.getActiveConfig();
        if (config == null) {
            return Result.fail("未配置 TTS");
        }
        return Result.ok(config);
    }

    @Operation(summary = "语音合成")
    @PostMapping("/api/tts/synthesize")
    public ResponseEntity<byte[]> synthesize(@Valid @RequestBody TtsSynthesizeRequest request) {
        log.info("TTS synthesize request: textLength={}, configId={}",
                request.getText() != null ? request.getText().length() : 0, request.getConfigId());
        long start = System.currentTimeMillis();
        byte[] audio = ttsConfigService.synthesize(request.getText(), request.getConfigId());
        log.info("TTS synthesize completed: audioSize={} bytes, elapsed={}ms", audio.length, System.currentTimeMillis() - start);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                .body(audio);
    }

    @Operation(summary = "流式语音合成")
    @PostMapping(value = "/api/tts/synthesize/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter synthesizeStream(@Valid @RequestBody TtsSynthesizeRequest request) {
        log.info("TTS stream request: textLength={}, configId={}",
                request.getText() != null ? request.getText().length() : 0, request.getConfigId());

        Long userId = extractUserId();

        return withSseLimit(userId, () -> {
            // 超时 5 分钟，与引擎层流式超时对齐
            SseEmitter emitter = new SseEmitter(300_000L);

            emitter.onTimeout(() -> {
                log.warn("TTS stream SSE timeout: configId={}", request.getConfigId());
                emitter.complete();
            });
            emitter.onError(e -> log.warn("TTS stream SSE error: configId={}, msg={}", request.getConfigId(), e.getMessage()));

            CompletableFuture.runAsync(() ->
                    ttsConfigService.synthesizeStream(request.getText(), request.getConfigId(), emitter), sseExecutor);

            return emitter;
        });
    }

    @Operation(summary = "是否支持流式合成")
    @GetMapping("/api/tts/streaming-supported")
    public Result<Boolean> isStreamingSupported(@RequestParam(required = false) Long configId) {
        try {
            return Result.ok(ttsConfigService.supportsStreaming(configId));
        } catch (Exception e) {
            return Result.ok(false);
        }
    }

    @Operation(summary = "获取GPT-SoVITS音色预设列表")
    @GetMapping("/api/tts/gpt-sovits/voices")
    public Result<List<GptSovitsProperties.VoicePreset>> listGptSovitsVoices() {
        return Result.ok(gptSovitsProperties.getVoices());
    }
}
