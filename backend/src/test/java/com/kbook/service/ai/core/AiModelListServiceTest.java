package com.kbook.service.ai.core;

import com.kbook.config.AiConfigCacheInvalidator;
import com.kbook.entity.AiProviderConfig;
import com.kbook.entity.AiProviderConfig.Provider;
import com.kbook.repository.AiProviderConfigRepository;
import com.kbook.service.ai.core.model.ModelListResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 对着一个真实的本地 HTTP 上游跑服务层：URL 归一、候选回退顺序、鉴权优先级、
 * 不跟随重定向、响应体上限、缓存命中，这些都只有发真请求才验得出来。
 */
class AiModelListServiceTest {

    private static HttpServer server;
    private static int port;
    private static final List<String> REQUESTED = new ArrayList<>();

    private AiProviderConfigRepository configRepository;
    private AiModelListService service;

    @BeforeAll
    static void startUpstream() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        // 带版本号的标准地址
        respond("/v1/models", 200,
                "{\"data\":[{\"id\":\"m-old\",\"created\":100},{\"id\":\"m-new\",\"created\":1700000000}]}");
        // Ollama 原生列表
        respond("/api/tags", 200, "{\"models\":[{\"name\":\"qwen3:8b\",\"size\":4882015657}]}");
        // 需要正确密钥；失败响应里带一段敏感文本，用来断言原文不会外泄
        respondAuth("/strict/models", 200, "{\"data\":[{\"id\":\"ok\"}]}", "Bearer sk-good",
                "{\"error\":{\"message\":\"invalid api key: sk-BADSECRET-DONT-LEAK\"}}");
        respond("/empty/models", 200, "{\"data\":[]}");
        respond("/html/models", 200, "<html><body>502 Bad Gateway</body></html>");
        respondLarge("/huge/models", 200, 2_000_000);
        respondRedirect("/moved/models", "/v1/models");
        respondSlow("/slow/models", 200, "{\"data\":[{\"id\":\"late\"}]}", 10_000);
        // 其余路径一律 404，模拟「这个前缀没有列表接口」
        server.createContext("/", exchange -> {
            record(exchange.getRequestURI().getPath());
            byte[] body = "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
    }

    @AfterAll
    static void shutDown() {
        server.stop(0);
    }

    @BeforeEach
    void reset() {
        REQUESTED.clear();
        configRepository = mock(AiProviderConfigRepository.class);
        service = new AiModelListService(configRepository, mock(AiConfigCacheInvalidator.class));
    }

    private static void record(String path) {
        synchronized (REQUESTED) {
            REQUESTED.add(path);
        }
    }

    private String base(String prefix) {
        return "http://127.0.0.1:" + port + prefix;
    }

    // ==================== 候选回退 ====================

    @Test
    @DisplayName("不带版本号的网关：先试 /models（404），再试 /v1/models 命中，顺序与顺序都验")
    void fallsBackToVersionedCandidate() {
        ModelListResult result = service.list(Provider.OPENAI, base(""), "sk-any", null);

        assertThat(result.source()).isEqualTo(ModelListResult.PROVIDER);
        assertThat(result.endpoint()).isEqualTo(base("/v1/models"));
        assertThat(result.models()).extracting(m -> m.id()).containsExactly("m-new", "m-old");
        synchronized (REQUESTED) {
            assertThat(REQUESTED).containsExactly("/models", "/v1/models");
        }
    }

    @Test
    @DisplayName("Ollama 走 /api/tags 且不需要密钥")
    void ollamaUsesTags() {
        ModelListResult result = service.list(Provider.OLLAMA, base(""), null, null);

        assertThat(result.source()).isEqualTo(ModelListResult.PROVIDER);
        assertThat(result.endpoint()).isEqualTo(base("/api/tags"));
        assertThat(result.models()).extracting(m -> m.id()).containsExactly("qwen3:8b");
    }

    // ==================== 错误文案 ====================

    @Test
    @DisplayName("密钥不对时优先说「API Key」，不能被后一个候选的 404 文案盖掉")
    void authErrorOutranksNotFound() {
        ModelListResult result = service.list(Provider.OPENAI, base("/strict"), "sk-bad", null);

        assertThat(result.source()).isEqualTo(ModelListResult.UPSTREAM_ERROR);
        assertThat(result.hint()).contains("API Key 无效").contains("401");
    }

    @Test
    @DisplayName("失败响应原文不外泄（只回传解析后的模型 id 和一句中文提示）")
    void doesNotLeakUpstreamBody() {
        ModelListResult result = service.list(Provider.OPENAI, base("/strict"), "sk-bad", null);

        assertThat(result.hint()).doesNotContain("sk-BADSECRET-DONT-LEAK");
        assertThat(result.models()).isEmpty();
    }

    @Test
    @DisplayName("返回 2xx 但列表为空 → EMPTY，且 endpoint 报的是那个 2xx 地址而不是后面的 404")
    void emptyListReportsTheRightEndpoint() {
        ModelListResult result = service.list(Provider.OPENAI, base("/empty"), "sk-any", null);

        assertThat(result.source()).isEqualTo(ModelListResult.EMPTY);
        assertThat(result.endpoint()).isEqualTo(base("/empty/models"));
        assertThat(result.hint()).contains("空列表");
    }

    @Test
    @DisplayName("2xx 但返回 HTML（网关挂了/路径错）→ 解析不出条目，按 EMPTY 降级为手填")
    void htmlBodyDegradesGracefully() {
        ModelListResult result = service.list(Provider.OPENAI, base("/html"), "sk-any", null);

        assertThat(result.models()).isEmpty();
        assertThat(result.source()).isEqualTo(ModelListResult.EMPTY);
    }

    @Test
    @DisplayName("3xx 不跟随：重定向信息优先于后一个候选的 404")
    void doesNotFollowRedirect() {
        ModelListResult result = service.list(Provider.OPENAI, base("/moved"), "sk-any", null);

        assertThat(result.source()).isEqualTo(ModelListResult.UPSTREAM_ERROR);
        assertThat(result.hint()).contains("跳转");
        synchronized (REQUESTED) {
            assertThat(REQUESTED).doesNotContain("/v1/models");
        }
    }

    @Test
    @DisplayName("响应体超过 1MB 直接断掉，不把上游当下载器")
    void capsResponseBody() {
        ModelListResult result = service.list(Provider.OPENAI, base("/huge"), "sk-any", null);

        assertThat(result.source()).isEqualTo(ModelListResult.UPSTREAM_ERROR);
        assertThat(result.hint()).contains("1MB");
    }

    @Test
    @DisplayName("上游 10 秒不返回 → 用 8 秒硬超时，而不是配置里的 600 秒")
    void readTimeoutIsHardCoded() {
        long start = System.currentTimeMillis();
        ModelListResult result = service.list(Provider.OPENAI, base("/slow"), "sk-any", null);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.source()).isEqualTo(ModelListResult.UPSTREAM_ERROR);
        assertThat(result.hint()).contains("超时");
        assertThat(elapsed).isLessThan(19_000);
    }

    // ==================== 入参与密钥来源 ====================

    @Test
    @DisplayName("地址为空 / 非 http 前缀 → 校验消息直接给前端，不发请求")
    void validatesBeforeNetworking() {
        assertThat(service.list(Provider.OPENAI, "", "sk-any", null).hint()).contains("不能为空");
        assertThat(service.list(Provider.OPENAI, "api.deepseek.com/v1", "sk-any", null).hint()).contains("http://");
        synchronized (REQUESTED) {
            assertThat(REQUESTED).isEmpty();
        }
    }

    @Test
    @DisplayName("元数据地址被拦（即使端口是通的）")
    void blocksMetadataAddress() {
        assertThat(service.list(Provider.OPENAI, "http://169.254.169.254", "sk-any", null).hint())
                .contains("元数据");
    }

    @Test
    @DisplayName("apiKey 留空时用 configId 从库里取")
    void fallsBackToStoredKey() {
        AiProviderConfig stored = mock(AiProviderConfig.class);
        when(stored.getApiKey()).thenReturn("sk-good");
        when(configRepository.findById(anyLong())).thenReturn(Optional.of(stored));

        ModelListResult result = service.list(Provider.OPENAI, base("/strict"), null, 7L);

        assertThat(result.source()).isEqualTo(ModelListResult.PROVIDER);
    }

    @Test
    @DisplayName("表单里新输入的密钥优先于库里的旧值")
    void prefersFormKey() {
        AiProviderConfig stored = mock(AiProviderConfig.class);
        when(stored.getApiKey()).thenReturn("sk-stale");
        when(configRepository.findById(anyLong())).thenReturn(Optional.of(stored));

        ModelListResult result = service.list(Provider.OPENAI, base("/strict"), "sk-good", 7L);

        assertThat(result.source()).isEqualTo(ModelListResult.PROVIDER);
    }

    @Test
    @DisplayName("只给 configId（配置行上的「刷新模型」）→ 类型与地址都取库存值，不信客户端回传")
    void probesFromStoredConfigWhenAddressOmitted() {
        AiProviderConfig stored = mock(AiProviderConfig.class);
        when(stored.getProvider()).thenReturn(Provider.OPENAI);
        when(stored.getBaseUrl()).thenReturn(base("/strict"));
        when(stored.getApiKey()).thenReturn("sk-good");
        when(configRepository.findById(anyLong())).thenReturn(Optional.of(stored));

        ModelListResult result = service.list(null, null, null, 42L);

        assertThat(result.source()).isEqualTo(ModelListResult.PROVIDER);
        assertThat(result.endpoint()).isEqualTo(base("/strict/models"));
    }

    @Test
    @DisplayName("configId 不存在 → 明确报「配置不存在」")
    void missingConfigIsNamed() {
        when(configRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThat(service.list(Provider.OPENAI, base("/strict"), null, 999L).hint()).contains("配置不存在");
    }

    // ==================== 缓存 ====================

    @Test
    @DisplayName("同一地址+密钥第二次点「获取模型」不再打上游；换密钥要重新打")
    void cachesByAddressAndKey() {
        ModelListResult first = service.list(Provider.OPENAI, base("/strict"), "sk-good", null);
        int callsAfterFirst;
        synchronized (REQUESTED) {
            callsAfterFirst = REQUESTED.size();
        }
        service.list(Provider.OPENAI, base("/strict"), "sk-good", null);
        synchronized (REQUESTED) {
            assertThat(REQUESTED).hasSize(callsAfterFirst);
        }

        service.list(Provider.OPENAI, base("/strict"), "sk-other", null);
        synchronized (REQUESTED) {
            assertThat(REQUESTED).hasSizeGreaterThan(callsAfterFirst);
        }
        assertThat(first.source()).isEqualTo(ModelListResult.PROVIDER);
    }

    @Test
    @DisplayName("失败不写缓存，否则一次偶发超时会把错误钉住 10 分钟")
    void failuresAreNotCached() {
        ModelListResult failed = service.list(Provider.OPENAI, base("/strict"), "sk-bad", null);
        assertThat(failed.source()).isEqualTo(ModelListResult.UPSTREAM_ERROR);

        int callsAfterFailure;
        synchronized (REQUESTED) {
            callsAfterFailure = REQUESTED.size();
        }
        service.list(Provider.OPENAI, base("/strict"), "sk-bad", null);
        synchronized (REQUESTED) {
            assertThat(REQUESTED).hasSizeGreaterThan(callsAfterFailure);
        }
    }

    @Test
    @DisplayName("返回给前端的 endpoint 永不含密钥")
    void endpointNeverCarriesKey() {
        ModelListResult result = service.list(Provider.OPENAI, base("/strict"), "sk-good", null);
        assertThat(String.valueOf(result.endpoint())).doesNotContain("sk-good");
    }

    @Test
    @DisplayName("invalidateCaches 后重新拉取（配置保存会触发）")
    void invalidateForcesRefetch() {
        service.list(Provider.OPENAI, base("/v1"), "sk-any", null);
        int calls;
        synchronized (REQUESTED) {
            calls = REQUESTED.size();
        }
        service.invalidateCaches();
        service.list(Provider.OPENAI, base("/v1"), "sk-any", null);
        synchronized (REQUESTED) {
            assertThat(REQUESTED).hasSizeGreaterThan(calls);
        }
    }

    // ==================== 上游桩 ====================

    private static void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            record(exchange.getRequestURI().getPath());
            write(exchange, status, body);
        });
    }

    private static void respondAuth(String path, int status, String body, String expectedAuth, String deniedBody) {
        server.createContext(path, exchange -> {
            record(exchange.getRequestURI().getPath());
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (expectedAuth.equals(auth)) {
                write(exchange, status, body);
            } else {
                write(exchange, 401, deniedBody);
            }
        });
    }

    private static void respondLarge(String path, int status, int bytes) {
        server.createContext(path, exchange -> {
            record(exchange.getRequestURI().getPath());
            byte[] payload = new byte[bytes];
            java.util.Arrays.fill(payload, (byte) 'a');
            exchange.sendResponseHeaders(status, payload.length);
            try (var out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
    }

    private static void respondRedirect(String path, String location) {
        server.createContext(path, exchange -> {
            record(exchange.getRequestURI().getPath());
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
    }

    private static void respondSlow(String path, int status, String body, long delayMs) {
        server.createContext(path, exchange -> {
            record(exchange.getRequestURI().getPath());
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            write(exchange, status, body);
        });
    }

    private static void write(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (var out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
