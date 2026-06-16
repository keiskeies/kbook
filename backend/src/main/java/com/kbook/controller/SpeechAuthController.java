package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.service.speech.SpeechAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 语音认证控制器 — 为前端提供临时鉴权凭证（Token / 签名 URL）
 */
@Slf4j
@RestController
@RequestMapping("/api/speech")
@RequiredArgsConstructor
@Tag(name = "语音认证")
public class SpeechAuthController {

    private final SpeechAuthService speechAuthService;

    @Operation(summary = "获取 Azure Speech Token")
    @GetMapping("/azure/token")
    public Result<Map<String, String>> getAzureToken() {
        try {
            return Result.ok(speechAuthService.getAzureToken());
        } catch (RuntimeException e) {
            log.warn("Azure token 获取失败: {}", e.getMessage());
            return Result.fail(e.getMessage());
        }
    }

    @Operation(summary = "获取讯飞 WebSocket 签名 URL")
    @PostMapping("/xfyun/auth")
    public Result<Map<String, String>> getXfyunAuth() {
        try {
            return Result.ok(speechAuthService.getXfyunAuthUrl());
        } catch (RuntimeException e) {
            log.warn("讯飞签名生成失败: {}", e.getMessage());
            return Result.fail(e.getMessage());
        }
    }
}
