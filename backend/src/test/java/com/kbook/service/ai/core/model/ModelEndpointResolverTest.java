package com.kbook.service.ai.core.model;

import com.kbook.entity.AiProviderConfig.Provider;
import com.kbook.service.ai.core.model.ModelEndpointResolver.Auth;
import com.kbook.service.ai.core.model.ModelEndpointResolver.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * URL 归一的每条规则都对应一类真实网关。这里红一条，就代表线上有一批地址拉不到列表。
 */
class ModelEndpointResolverTest {

    private static String first(Provider provider, String baseUrl) {
        return ModelEndpointResolver.resolve(provider, baseUrl).get(0).url();
    }

    // ==================== Ollama ====================

    @Test
    @DisplayName("Ollama 标准地址 → /api/tags，不需要鉴权")
    void ollamaRoot() {
        List<Candidate> candidates = ModelEndpointResolver.resolve(Provider.OLLAMA, "http://localhost:11434");
        assertThat(candidates.get(0)).isEqualTo(new Candidate("http://localhost:11434/api/tags", Auth.NONE));
    }

    @Test
    @DisplayName("Ollama 被填成 OpenAI 兼容地址（.../v1）→ 剥掉版本段回到 /api/tags")
    void ollamaStripsVersion() {
        assertThat(first(Provider.OLLAMA, "http://localhost:11434/v1"))
                .isEqualTo("http://localhost:11434/api/tags");
    }

    @Test
    @DisplayName("Ollama 反代场景留一条 OpenAI 兼容候选")
    void ollamaHasCompatFallback() {
        assertThat(ModelEndpointResolver.resolve(Provider.OLLAMA, "http://ollama.lan:11434"))
                .extracting(Candidate::url)
                .containsExactly("http://ollama.lan:11434/api/tags", "http://ollama.lan:11434/v1/models");
    }

    // ==================== OpenAI 兼容 ====================

    @Test
    @DisplayName("带 /v1 的地址 → 只补 /models，不再试 /v1/v1/models")
    void versionedBaseGetsOneCandidate() {
        assertThat(ModelEndpointResolver.resolve(Provider.OPENAI, "https://api.deepseek.com/v1"))
                .extracting(Candidate::url)
                .containsExactly("https://api.deepseek.com/v1/models");
    }

    @Test
    @DisplayName("用户把 chat 接口整段粘进来 → 剥掉动作后缀")
    void stripsChatCompletions() {
        assertThat(first(Provider.OPENAI, "https://api.openai.com/v1/chat/completions"))
                .isEqualTo("https://api.openai.com/v1/models");
    }

    @Test
    @DisplayName("末尾已经是 models → 原样使用，不重复追加")
    void alreadyModels() {
        assertThat(first(Provider.OPENAI, "https://api.moonshot.cn/v1/models"))
                .isEqualTo("https://api.moonshot.cn/v1/models");
    }

    @Test
    @DisplayName("不带版本号的自建网关 → 按序尝试裸地址与 /v1")
    void unversionedGatewayTriesTwo() {
        assertThat(ModelEndpointResolver.resolve(Provider.OPENAI, "https://one-api.internal"))
                .extracting(Candidate::url)
                .containsExactly("https://one-api.internal/models", "https://one-api.internal/v1/models");
    }

    @Test
    @DisplayName("路径前缀不是版本号的（/api/openai）同样给两条候选")
    void prefixedGateway() {
        assertThat(ModelEndpointResolver.resolve(Provider.OPENAI, "http://192.168.1.20:8000/api/openai"))
                .extracting(Candidate::url)
                .containsExactly("http://192.168.1.20:8000/api/openai/models",
                        "http://192.168.1.20:8000/api/openai/v1/models");
    }

    @Test
    @DisplayName("尾斜杠与前后空格不影响结果")
    void toleratesSlashAndSpaces() {
        assertThat(first(Provider.OPENAI, "  https://api.siliconflow.cn/v1/  "))
                .isEqualTo("https://api.siliconflow.cn/v1/models");
    }

    @Test
    @DisplayName("地址上带的 query 会被剥掉，否则拼出来的 models 地址是坏的")
    void stripsQueryString() {
        assertThat(first(Provider.OPENAI, "https://api.x.com/v1?api_version=2024-01"))
                .isEqualTo("https://api.x.com/v1/models");
    }

    // ==================== 特征域名 ====================

    @Test
    @DisplayName("Gemini 根地址 → /v1beta/models，密钥走查询串")
    void geminiRoot() {
        List<Candidate> candidates =
                ModelEndpointResolver.resolve(Provider.OPENAI, "https://generativelanguage.googleapis.com");
        assertThat(candidates.get(0).url()).isEqualTo("https://generativelanguage.googleapis.com/v1beta/models");
        assertThat(candidates.get(0).auth()).isEqualTo(Auth.QUERY_KEY);
    }

    @Test
    @DisplayName("Gemini 已带 /v1（OpenAI 兼容层）→ 保留版本段，不叠成 /v1/v1beta")
    void geminiKeepsExistingVersion() {
        assertThat(first(Provider.OPENAI, "https://generativelanguage.googleapis.com/v1beta"))
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models");
    }

    @Test
    @DisplayName("Anthropic → /v1/models 且用 x-api-key 而不是 Bearer")
    void anthropic() {
        List<Candidate> candidates = ModelEndpointResolver.resolve(Provider.OPENAI, "https://api.anthropic.com");
        assertThat(candidates.get(0).url()).isEqualTo("https://api.anthropic.com/v1/models");
        assertThat(candidates.get(0).auth()).isEqualTo(Auth.X_API_KEY);
    }

    @Test
    @DisplayName("特征域名直接粘了列表地址 → 不追加第二次，鉴权方式也别丢")
    void specialHostsAlreadyModels() {
        assertThat(first(Provider.OPENAI, "https://api.anthropic.com/v1/models"))
                .isEqualTo("https://api.anthropic.com/v1/models");
        List<Candidate> gemini = ModelEndpointResolver.resolve(
                Provider.OPENAI, "https://generativelanguage.googleapis.com/v1beta/models");
        assertThat(gemini.get(0).url()).isEqualTo("https://generativelanguage.googleapis.com/v1beta/models");
        assertThat(gemini.get(0).auth()).isEqualTo(Auth.QUERY_KEY);
    }

    // ==================== 入参校验 ====================
    @Test
    @DisplayName("空地址 / 缺协议 / 带账号密码 → 直接拒绝，消息给前端看")
    void rejectsBadInput() {
        assertThatThrownBy(() -> ModelEndpointResolver.resolve(Provider.OPENAI, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> ModelEndpointResolver.resolve(Provider.OPENAI, "api.deepseek.com/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http://");
        assertThatThrownBy(() -> ModelEndpointResolver.resolve(Provider.OPENAI, "http://root:pw@10.0.0.1/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户名密码");
        assertThatThrownBy(() -> ModelEndpointResolver.resolve(null, "https://a.com/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("供应商类型");
    }

    @Test
    @DisplayName("超过 baseUrl 列宽（500）的地址拒绝")
    void rejectsOverlongUrl() {
        String longPath = "https://a.com/" + "x".repeat(600);
        assertThatThrownBy(() -> ModelEndpointResolver.resolve(Provider.OPENAI, longPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过长");
    }

    // ==================== SSRF 拦截 ====================

    @Test
    @DisplayName("云厂商元数据地址一律拒绝（AWS/GCP 走 169.254，阿里云走 100.100.100.200）")
    void blocksMetadata() {
        assertThatThrownBy(() -> ModelEndpointResolver.resolve(Provider.OPENAI, "http://169.254.169.254/latest"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelEndpointResolver.resolve(Provider.OPENAI, "http://100.100.100.200/latest"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelEndpointResolver.resolve(Provider.OPENAI, "http://metadata.google.internal/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ModelEndpointResolver.isBlockedHostname("metadata.tencentyun.com")).isTrue();
    }

    @Test
    @DisplayName("内嵌 IPv6 与链路本地写法也拦得住")
    void blocksObfuscatedIpv6() {
        assertThat(ModelEndpointResolver.isBlockedHostname("[::ffff:169.254.169.254]")).isTrue();
        assertThat(ModelEndpointResolver.isBlockedHostname("[fe80::1]")).isTrue();
        assertThat(ModelEndpointResolver.isBlockedHostname("[::1]")).isFalse();
    }

    @Test
    @DisplayName("本机与内网网关是主要使用场景，不能拦")
    void allowsLocalAndPrivate() {
        assertThat(first(Provider.OPENAI, "http://127.0.0.1:8000/v1")).isEqualTo("http://127.0.0.1:8000/v1/models");
        assertThat(first(Provider.OPENAI, "http://192.168.1.20:8000")).isEqualTo("http://192.168.1.20:8000/models");
        assertThat(first(Provider.OPENAI, "http://10.0.0.5:3000/v1")).isEqualTo("http://10.0.0.5:3000/v1/models");
        assertThat(ModelEndpointResolver.isBlockedHostname("localhost")).isFalse();
    }
}
