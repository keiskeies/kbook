package com.kbook.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 熔断器注册表 — 按 provider+model 维度管理熔断器实例
 * <p>
 * 设计原则：一个 provider 挂了不该让另一个 provider 也不可用。
 * 例如 OpenAI 故障时，Ollama 的熔断器不受影响。
 * <p>
 * 熔断参数：
 * <ul>
 *   <li>失败率阈值 50%（滑动窗口 20 次调用中超过 10 次失败 → 熔断）</li>
 *   <li>熔断开启后 30 秒进入半开状态</li>
 *   <li>半开状态允许 3 次试探调用</li>
 * </ul>
 */
@Slf4j
@Component
public class AiCircuitBreakerRegistry {

    private final CircuitBreakerRegistry registry;

    public AiCircuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        this.registry = CircuitBreakerRegistry.of(config);
    }

    /**
     * 获取或创建指定 provider+model 的熔断器
     *
     * @param providerKey 格式: provider:modelName，如 "OPENAI:gpt-4o"、"OLLAMA:qwen3:32b"
     */
    public CircuitBreaker getOrCreate(String providerKey) {
        CircuitBreaker cb = registry.circuitBreaker(providerKey);
        if (cb.getMetrics().getNumberOfBufferedCalls() == 0) {
            log.debug("创建 AI 熔断器: key={}", providerKey);
        }
        return cb;
    }
}
