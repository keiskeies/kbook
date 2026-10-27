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
    /** 用户私人笔记向量集合名 */
    private String userNoteCollection = "kbook_user_notes";

    /** 向量维度（需与 embedding 模型输出维度一致） */
    private int vectorDimension = 1024;

    /** RAG 内容分块大小（字符数） */
    private int chunkSize = 800;
    /** RAG 内容分块重叠大小（字符数） */
    private int chunkOverlap = 200;

    /** RAG 检索返回的最大片段数 */
    private int ragTopK = 10;

    /**
     * RAG 上下文注入的字符上限（0 = 不额外限制）。
     * <p>
     * 与「按模型上下文窗口推导的预算」是两件事：模型能吃下 512K tokens 不等于
     * 该把整本书塞进提示词。实测（book 24689，273 chunks）不设上限时全书 12.6 万字
     * 进入上下文，约 84K 输入 tokens/轮、检索片段排在第 10 名之后的 gold 反而拿不到。
     * 该值必须用黄金集 A/B 验证后再定默认值，不要直接拍。
     */
    private int ragMaxChars = 0;

    /**
     * 绝对相似度阈值：常规检索的 topScore 低于此值且问题为书内事实时，按"未找到"拒答。
     * 0 = 关闭。必须用黄金集标定后再开启，不能拍脑袋写 0.35。
     */
    private double absoluteMinScore = 0.0;

    /** 是否启用 LLM rerank（默认关闭；开启后每轮增加多次 TOOL 调用，需先压测延迟与成本）。 */
    private boolean rerankEnabled = false;
    /** rerank 候选数：按向量分取前 N 个送入重排。 */
    private int rerankTopN = 30;

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
