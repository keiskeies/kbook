package com.kbook.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.entity.AiProviderConfig.Provider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 否定式契约：明文密钥不得出现在任何出参里。
 * <p>
 * 这类要求不能靠「看一眼代码觉得没问题」，必须把序列化结果读出来断言字段<b>不存在</b>；
 * 否则哪天有人加一个 {@code @JsonIgnore} 之外的新出口，测试不会响。
 */
class AiProviderConfigJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET = "sk-secret-123456789";

    private static AiProviderConfig withKey(String key) {
        return AiProviderConfig.builder()
                .name("probe").purpose("CHAT").provider(Provider.OPENAI)
                .baseUrl("http://localhost:11434").modelName("m").apiKey(key)
                .build();
    }

    @Test
    @DisplayName("序列化结果里没有 apiKey 字段，只有掩码")
    void neverSerializesPlainKey() throws Exception {
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(withKey(SECRET)));

        assertThat(json.has("apiKey")).as("明文密钥字段必须整个不存在").isFalse();
        assertThat(json.get("apiKeyMasked").asText()).isEqualTo("••••6789");
        assertThat(json.toString()).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("反序列化仍然收 apiKey（保存配置这条路不能被堵死）")
    void stillAcceptsKeyOnInput() throws Exception {
        AiProviderConfig parsed = MAPPER.readValue(
                "{\"name\":\"n\",\"purpose\":\"CHAT\",\"provider\":\"OPENAI\",\"baseUrl\":\"http://x/v1\",\"modelName\":\"m\",\"apiKey\":\"sk-new\"}",
                AiProviderConfig.class);

        assertThat(parsed.getApiKey()).isEqualTo("sk-new");
    }

    @Test
    @DisplayName("短密钥不露尾巴；未配置时掩码为 null")
    void masksShortAndAbsentKeys() throws Exception {
        assertThat(withKey("abc12345").getApiKeyMasked()).isEqualTo("••••••••");
        assertThat(withKey("abcdefghijklmnop9999").getApiKeyMasked()).isEqualTo("••••9999");
        assertThat(withKey(null).getApiKeyMasked()).isNull();
        assertThat(withKey("   ").getApiKeyMasked()).isNull();
    }

    @Test
    @DisplayName("toString 不含明文（日志里顺手打实体也漏不出去）")
    void toStringExcludesKey() {
        assertThat(withKey(SECRET).toString()).doesNotContain(SECRET);
    }
}
