package com.kbook.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Qdrant 向量数据库配置
 * Qdrant 使用 gRPC 协议通信，端口默认 6334（REST API 为 6333）
 */
@Slf4j
@Configuration
public class QdrantConfig {

    @Value("${kbook.qdrant.host:localhost}")
    private String host;

    @Value("${kbook.qdrant.port:6334}")
    private int port;

    @Value("${kbook.qdrant.api-key:}")
    private String apiKey;

    @Bean
    public QdrantClient qdrantClient() {
        log.info("初始化 Qdrant 客户端: {}:{}", host, port);

        var builder = QdrantGrpcClient.newBuilder(host, port, false)
                .withTimeout(Duration.ofSeconds(60));

        if (apiKey != null && !apiKey.isBlank()) {
            builder.withApiKey(apiKey);
            log.info("Qdrant 已配置 API Key");
        }

        return new QdrantClient(builder.build());
    }
}
