package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.service.ClickCaptchaService;
import com.kbook.dto.ClickVerifyRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 点击验证码控制器
 */
@RestController
@RequestMapping("/api/captcha")
@RequiredArgsConstructor
public class ClickCaptchaController {

    private final ClickCaptchaService clickCaptchaService;

    /**
     * 生成点击验证码
     */
    @GetMapping("/click/generate")
    public Result<ClickCaptchaService.CaptchaData> generate() {
        return Result.ok(clickCaptchaService.generateCaptcha());
    }

    /**
     * 验证点击结果
     */
    @PostMapping("/click/verify")
    public Result<Void> verify(@RequestBody ClickVerifyRequest req) {
        clickCaptchaService.verifyClick(req.getCaptchaId(), req.getPositions());
        return Result.ok();
    }

}
