package com.kbook.config;

import com.kbook.config.properties.AiProviderProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 供应商配置类 — 注册 AiProviderProperties 为 Spring Bean
 * <p>
 * AiProviderProperties 不使用 @Component 注解，而是通过此配置类显式注册，
 * 因为它需要通过 @PostConstruct 手动解析 YAML 文件。
 */
@Configuration
public class AiProviderConfig {

    /**
     * 注册 AI 供应商预设属性 Bean
     *
     * @return AiProviderProperties 实例
     */
    @Bean
    public AiProviderProperties aiProviderProperties() {
        return new AiProviderProperties();
    }
}
