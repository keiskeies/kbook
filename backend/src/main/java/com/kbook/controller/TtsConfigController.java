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

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 语音合成控制器 — 公开 TTS 接口（语音合成、流式合成、配置查询）
 * <p>
 * 管理员 TTS 配置 CRUD 请见 {@link AdminTtsConfigController}
 */
@Slf4j
@RestController
@Tag(name = "语音合成")
public class TtsConfigController {

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
