package com.kbook.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Qdrant 向量数据库配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "kbook.qdrant")
public class QdrantProperties {

    /** Qdrant 服务地址 */
    private String host = "localhost";
    /** Qdrant gRPC 端口 */
    private int port = 6334;
    /** Qdrant API Key（可选，用于认证） */
    private String apiKey = "";

    /** 书籍元数据向量集合名 */
    private String bookCollection = "kbook_books";
    /** RAG 内容向量集合名 */
    private String contentCollection = "kbook_content";

    /** 向量维度（需与 embedding 模型输出维度一致） */
    private int vectorDimension = 1024;

    /** RAG 内容分块大小（字符数） */
    private int chunkSize = 800;
    /** RAG 内容分块重叠大小（字符数） */
    private int chunkOverlap = 200;

    /** RAG 检索返回的最大片段数 */
    private int ragTopK = 10;

    /** 原始向量是否存磁盘 */
    private boolean vectorsOnDisk = true;

    /** 标量量化配置 */
    private QuantizationConfig quantization = new QuantizationConfig();

    /** 标量量化配置 */
    @Data
    public static class QuantizationConfig {
        /** 是否启用量化 */
        private boolean enabled = true;
        /** 量化分位数（0-1，越大量化精度越高） */
        private float quantile = 0.99f;
        /** 量化后的向量是否始终驻留内存 */
        private boolean alwaysRam = false;
    }
}
