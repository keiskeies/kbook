package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.auth.ClickVerifyRequest;
import com.kbook.service.auth.ClickCaptchaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 点击验证码控制器（AES-GCM 加密版）
 */
@RestController
@RequestMapping("/api/captcha")
@RequiredArgsConstructor
@Tag(name = "验证码")
public class ClickCaptchaController {

    private final ClickCaptchaService clickCaptchaService;

    /**
     * 生成点击验证码（返回加密数据）
     */
    @GetMapping("/click/generate")
    @Operation(summary = "生成点击验证码")
    public Result<Map<String, String>> generate(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        return Result.ok(clickCaptchaService.generateCaptcha(ua));
    }

    /**
     * 验证点击结果
     */
    @PostMapping("/click/verify")
    @Operation(summary = "验证点击结果")
    public Result<Void> verify(@RequestBody ClickVerifyRequest req) {
        clickCaptchaService.verifyClick(req.getCaptchaId(), req.getPositions());
        return Result.ok();
    }
}
