package com.kbook.controller;

import com.kbook.common.api.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 健康检查端点
 */
@RestController
public class HealthController {

    /**
     * 健康检查接口，返回服务状态和时间戳
     * @return 服务状态信息
     */
    @GetMapping("/api/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of(
                "status", "UP",
                "timestamp", LocalDateTime.now().toString(),
                "service", "kbook-server"
        ));
    }
}
