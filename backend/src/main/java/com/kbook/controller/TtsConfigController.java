package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.common.util.SseHelper;
import com.kbook.config.properties.GptSovitsProperties;
import com.kbook.dto.request.TtsSynthesizeRequest;
import com.kbook.entity.TtsConfig;
import com.kbook.service.tts.TtsConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.Future;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "语音合成")
public class TtsConfigController {

    private final TtsConfigService ttsConfigService;

    /** GPT-SoVITS 音色预设配置 */
    private final GptSovitsProperties gptSovitsProperties;

    /** SSE 异步执行器（由 AsyncExecutorConfig 提供，自动 shutdown） */
    @Qualifier("sseExecutor")
    private final ThreadPoolTaskExecutor sseExecutor;

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
    @PostMapping("/api/tts/synthesize/stream")
    public SseEmitter synthesizeStream(@Valid @RequestBody TtsSynthesizeRequest request) {
        log.info("TTS stream request: textLength={}, configId={}",
                request.getText() != null ? request.getText().length() : 0, request.getConfigId());
        long start = System.currentTimeMillis();
        SseEmitter emitter = new SseEmitter(300_000L);

        Future<?> aiFuture = sseExecutor.submit(() -> {
            try {
                ttsConfigService.synthesizeStream(request.getText(), request.getConfigId(), emitter);
            } catch (Exception e) {
                log.error("TTS stream synthesis failed", e);
                SseHelper.sendErrorAndComplete(emitter, e.getMessage());
            }
        });

        emitter.onCompletion(() -> {
            aiFuture.cancel(true);
            log.info("TTS stream completed: elapsed={}ms", System.currentTimeMillis() - start);
        });
        emitter.onTimeout(() -> {
            aiFuture.cancel(true);
            log.warn("TTS stream timed out: elapsed={}ms", System.currentTimeMillis() - start);
            emitter.complete();
        });
        emitter.onError(e -> {
            aiFuture.cancel(true);
            log.warn("TTS stream error: {}, elapsed={}ms", e.getMessage(), System.currentTimeMillis() - start);
        });

        return emitter;
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

    @Operation(summary = "获取所有TTS配置")
    @GetMapping("/api/admin/tts-config")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<TtsConfig>> listAll() {
        return Result.ok(ttsConfigService.listAll());
    }

    @Operation(summary = "获取当前TTS配置(管理端)")
    @GetMapping("/api/admin/tts-config/active")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<TtsConfig> getActiveConfigAdmin() {
        TtsConfig config = ttsConfigService.getActiveConfig();
        if (config == null) {
            return Result.fail("未配置 TTS");
        }
        return Result.ok(config);
    }

    @Operation(summary = "创建TTS配置")
    @PostMapping("/api/admin/tts-config")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<TtsConfig> create(@RequestBody TtsConfig config) {
        if (config.getName() == null || config.getName().isBlank()) {
            return Result.fail("配置名称不能为空");
        }
        if (config.getTtsType() == null || config.getProvider() == null) {
            return Result.fail("缺少必要参数：ttsType, provider");
        }
        return Result.ok(ttsConfigService.create(config));
    }

    @Operation(summary = "更新TTS配置")
    @PutMapping("/api/admin/tts-config/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<TtsConfig> update(@PathVariable Long id, @RequestBody TtsConfig config) {
        try {
            return Result.ok(ttsConfigService.update(id, config));
        } catch (RuntimeException e) {
            return Result.fail(e.getMessage());
        }
    }

    @Operation(summary = "删除TTS配置")
    @DeleteMapping("/api/admin/tts-config/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        ttsConfigService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "切换默认TTS配置")
    @PostMapping("/api/admin/tts-config/{id}/switch-default")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<TtsConfig> switchDefault(@PathVariable Long id) {
        try {
            return Result.ok(ttsConfigService.switchDefault(id));
        } catch (RuntimeException e) {
            return Result.fail(e.getMessage());
        }
    }
}
