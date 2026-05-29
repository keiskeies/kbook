package com.kbook.config.properties;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 供应商预设配置属性 — 从 classpath:ai-providers.yml 加载供应商和模型预设列表
 * <p>
 * 不使用 @ConfigurationProperties，而是通过 @PostConstruct 手动解析 YAML，
 * 因为预设配置是只读的静态数据，不需要绑定到 application.yml 前缀。
 */
@Slf4j
@Data
public class AiProviderProperties {

    /** AI 供应商预设列表 */
    private List<ProviderPreset> providers = new ArrayList<>();

    /**
     * 初始化方法 — 从 ai-providers.yml 加载供应商预设配置
     */
    @PostConstruct
    public void init() {
        try {
            ClassPathResource resource = new ClassPathResource("ai-providers.yml");
            try (InputStream is = resource.getInputStream()) {
                Yaml yaml = new Yaml();
                Map<String, Object> root = yaml.load(is);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> providerList = (List<Map<String, Object>>) root.get("providers");
                if (providerList != null) {
                    for (Map<String, Object> item : providerList) {
                        ProviderPreset preset = new ProviderPreset();
                        preset.setId((String) item.get("id"));
                        preset.setName((String) item.get("name"));
                        preset.setProvider((String) item.get("provider"));
                        preset.setBaseUrl((String) item.get("baseUrl"));
                        preset.setRegion((String) item.get("region"));
                        preset.setDescription((String) item.get("description"));
                        preset.setApiKeyUrl((String) item.get("apiKeyUrl"));
                        preset.setTokenPlanUrl((String) item.get("tokenPlanUrl"));
                        preset.setCodingPlanUrl((String) item.get("codingPlanUrl"));
                        preset.setWebsiteUrl((String) item.get("websiteUrl"));

                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> modelList = (List<Map<String, Object>>) item.get("models");
                        if (modelList != null) {
                            for (Map<String, Object> m : modelList) {
                                ModelPreset model = new ModelPreset();
                                model.setName((String) m.get("name"));
                                model.setLabel((String) m.get("label"));
                                model.setFree((Boolean) m.get("free"));
                                if (m.get("maxTokens") instanceof Number) {
                                    model.setMaxTokens(((Number) m.get("maxTokens")).longValue());
                                }
                                preset.getModels().add(model);
                            }
                        }
                        providers.add(preset);
                    }
                }
                log.info("已加载 {} 个 AI 供应商预设配置", providers.size());
            }
        } catch (Exception e) {
            log.error("加载 ai-providers.yml 失败", e);
        }
    }

    /** 供应商预设信息 */
    @Data
    public static class ProviderPreset {
        /** 供应商唯一标识 */
        private String id;
        /** 供应商显示名称 */
        private String name;
        /** 供应商类型（如 ollama、openai） */
        private String provider;
        /** API 基础地址 */
        private String baseUrl;
        /** 服务区域 */
        private String region;
        /** 供应商描述 */
        private String description;
        /** API Key 申请地址 */
        private String apiKeyUrl;
        /** Token 计费方案地址 */
        private String tokenPlanUrl;
        /** 编码计费方案地址 */
        private String codingPlanUrl;
        /** 官网地址 */
        private String websiteUrl;
        /** 该供应商下可用的模型列表 */
        private List<ModelPreset> models = new ArrayList<>();
    }

    /** 模型预设信息 */
    @Data
    public static class ModelPreset {
        /** 模型名称（对应 API 调用时的 modelName） */
        private String name;
        /** 模型显示标签 */
        private String label;
        /** 是否免费 */
        private Boolean free;
        /** 上下文长度（token 数），如 131072=128K, 1048576=1M */
        private Long maxTokens;
    }
}
