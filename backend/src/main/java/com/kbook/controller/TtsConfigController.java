package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.request.TtsSynthesizeRequest;
import com.kbook.entity.TtsConfig;
import com.kbook.service.TtsConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
public class TtsConfigController {

    private final TtsConfigService ttsConfigService;

    @GetMapping("/api/tts/config/active")
    public Result<TtsConfig> getActiveConfig() {
        TtsConfig config = ttsConfigService.getActiveConfig();
        if (config == null) {
            return Result.fail("未配置 TTS");
        }
        return Result.ok(config);
    }

    @PostMapping("/api/tts/synthesize")
    public ResponseEntity<byte[]> synthesize(@RequestBody TtsSynthesizeRequest request) {
        byte[] audio = ttsConfigService.synthesize(request.getText(), request.getConfigId());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                .body(audio);
    }

    @GetMapping("/api/admin/tts-config")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<TtsConfig>> listAll() {
        return Result.ok(ttsConfigService.listAll());
    }

    @GetMapping("/api/admin/tts-config/active")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<TtsConfig> getActiveConfigAdmin() {
        TtsConfig config = ttsConfigService.getActiveConfig();
        if (config == null) {
            return Result.fail("未配置 TTS");
        }
        return Result.ok(config);
    }

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

    @PutMapping("/api/admin/tts-config/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<TtsConfig> update(@PathVariable Long id, @RequestBody TtsConfig config) {
        try {
            return Result.ok(ttsConfigService.update(id, config));
        } catch (RuntimeException e) {
            return Result.fail(e.getMessage());
        }
    }

    @DeleteMapping("/api/admin/tts-config/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        ttsConfigService.delete(id);
        return Result.ok();
    }

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
