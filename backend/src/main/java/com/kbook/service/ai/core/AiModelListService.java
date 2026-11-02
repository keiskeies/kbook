package com.kbook.service.ai.core;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kbook.config.AiConfigCacheInvalidator;
import com.kbook.entity.AiProviderConfig;
import com.kbook.repository.AiProviderConfigRepository;
import com.kbook.service.ai.core.model.ModelEndpointResolver;
import com.kbook.service.ai.core.model.ModelEndpointResolver.Auth;
import com.kbook.service.ai.core.model.ModelEndpointResolver.Candidate;
import com.kbook.service.ai.core.model.ModelListParser;
import com.kbook.service.ai.core.model.ModelListResult;
import com.kbook.service.ai.core.model.ModelSummary;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 向服务商查询「这个地址有哪些模型」，供管理端 AI 配置选择模型名。
 * <p>
 * 与对话调用完全隔离：不走 {@code ChatModelFactory.wrap()} 的重试/熔断，
 * 否则管理员点一次「获取模型」失败就会污染 provider 的熔断计数。
 * <p>
 * 超时是这里唯一需要硬编码的东西 — 配置里的 {@code timeout} 默认 600 秒是给长对话用的，
 * 拿它当探测超时会把管理页变成转圈到天荒地老。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelListService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(8);
    /** 多候选地址的累计上限 */
    private static final Duration TOTAL_DEADLINE = Duration.ofSeconds(20);
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final AiProviderConfigRepository configRepository;
    private final AiConfigCacheInvalidator cacheInvalidator;

    /** 不跟随重定向：这个端点由管理员传任意地址、服务端发请求，跟随跳转等于把探测面扩大 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final Cache<String, ModelListResult> cache = Caffeine.newBuilder()
            .maximumSize(64)
            .expireAfterWrite(CACHE_TTL)
            .build();

    @PostConstruct
    void registerCacheInvalidation() {
        cacheInvalidator.register(this::invalidateCaches);
    }

    /** 配置变更后清空本地列表缓存 */
    public void invalidateCaches() {
        cache.invalidateAll();
        log.debug("模型列表缓存已清空");
    }

    /**
     * @param provider 供应商类型；与 {@code baseUrl} 一起留空时按 {@code configId} 用库存值
     * @param baseUrl  表单当前填写的 API 地址
     * @param apiKey   表单当前填写的密钥；为空且给了 {@code configId} 时由服务端从库里取
     * @param configId 已保存配置的 id，可为空
     * @return 拉取结果；任何失败都表达为 {@link ModelListResult#UPSTREAM_ERROR} 而不是异常
     */
    public ModelListResult list(AiProviderConfig.Provider provider, String baseUrl, String apiKey, Long configId) {
        List<Candidate> candidates;
        String key;
        try {
            // 只给 configId 的调用法（配置行上的「刷新模型」）：地址与类型都取库存值，
            // 不让客户端回传，行操作才等于「刷新这一行」。没给 configId 时留给解析器报「地址不能为空」
            AiProviderConfig stored = baseUrl == null || baseUrl.isBlank()
                    ? (configId == null ? null : requireConfig(configId)) : null;
            if (stored != null) {
                provider = stored.getProvider();
                baseUrl = stored.getBaseUrl();
            }
            candidates = ModelEndpointResolver.resolve(provider, baseUrl);
            key = resolveApiKey(apiKey, configId);
        } catch (IllegalArgumentException e) {
            return ModelListResult.error(null, e.getMessage());
        }
        // 归一化后的首个候选地址参与缓存键，尾斜杠之类的写法差异不会各占一份缓存
        String cacheKey = candidates.get(0).url() + '|' + apiKeyFingerprint(key);
        ModelListResult cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        ModelListResult result = fetch(candidates, key);
        if (result.cacheable()) {
            cache.put(cacheKey, result);
        }
        return result;
    }

    // ==================== 内部实现 ====================

    private AiProviderConfig requireConfig(Long configId) {
        AiProviderConfig config = configId == null ? null : configRepository.findById(configId).orElse(null);
        if (config == null) {
            throw new IllegalArgumentException("配置不存在");
        }
        return config;
    }

    /**
     * 表单里刚输入的密钥优先于库里的旧值 —— 「换了 key 想验证一下能不能列出模型」
     * 正是这个功能的主要用途之一。
     */
    private String resolveApiKey(String formKey, Long configId) {
        if (formKey != null && !formKey.isBlank()) {
            return formKey.trim();
        }
        if (configId == null) {
            return null;
        }
        String stored = requireConfig(configId).getApiKey();
        return stored == null || stored.isBlank() ? null : stored.trim();
    }

    private ModelListResult fetch(List<Candidate> candidates, String key) {
        long deadlineNanos = System.nanoTime() + TOTAL_DEADLINE.toNanos();
        String lastEndpoint = null;
        String emptyEndpoint = null;
        Attempt worst = null;
        boolean connectedButEmpty = false;

        for (Candidate candidate : candidates) {
            lastEndpoint = candidate.url();
            if (System.nanoTime() > deadlineNanos) {
                worst = keep(worst, new Attempt(RANK_TIMEOUT, candidate.url(), "候选地址累计尝试超时"));
                break;
            }
            try {
                HttpResponse<InputStream> response = send(candidate, key);
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    List<ModelSummary> models = ModelListParser.parse(readCapped(response));
                    if (!models.isEmpty()) {
                        log.info("[模型列表] 拉取成功: endpoint={}, count={}", candidate.url(), models.size());
                        return ModelListResult.of(models, candidate.url());
                    }
                    connectedButEmpty = true;
                    emptyEndpoint = candidate.url();
                    continue;
                }
                worst = keep(worst, describeStatus(status, candidate.url()));
                log.warn("[模型列表] 未命中: endpoint={}, status={}", candidate.url(), status);
            } catch (IllegalArgumentException e) {
                // 地址解析后被安全策略拒绝、或响应超过上限 —— 再试别的候选也没意义
                return ModelListResult.error(candidate.url(), e.getMessage());
            } catch (HttpTimeoutException e) {
                worst = keep(worst, new Attempt(RANK_TIMEOUT, candidate.url(),
                        "连接超时（" + READ_TIMEOUT.toSeconds() + " 秒）"));
            } catch (ConnectException | UnknownHostException e) {
                worst = keep(worst, new Attempt(RANK_IO, candidate.url(), "无法连接该地址，请检查 API 地址与端口"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ModelListResult.error(candidate.url(), "拉取已中断，请重试");
            } catch (IOException e) {
                worst = keep(worst, new Attempt(RANK_IO, candidate.url(), "读取服务商响应失败"));
            }
        }
        if (connectedButEmpty) {
            return ModelListResult.empty(emptyEndpoint);
        }
        if (worst == null) {
            return ModelListResult.error(lastEndpoint, "未取到模型列表，可手动填写模型名");
        }
        return ModelListResult.error(worst.endpoint(), worst.message() + "，可手动填写模型名");
    }

    /**
     * 多个候选地址各失败一次时，保留<b>最有 actionable 的那条</b>。
     * <p>
     * 按发生顺序覆盖会出误导：{@code /models} 返回 401（密钥不对）之后
     * {@code /v1/models} 返回 404，最终文案变成「该地址没有模型列表接口」，
     * 管理员会去改地址，而真正该改的是密钥。
     */
    private static Attempt keep(Attempt current, Attempt candidate) {
        return current == null || candidate.rank() > current.rank() ? candidate : current;
    }

    private record Attempt(int rank, String endpoint, String message) {
    }

    private static final int RANK_IO = 0;
    private static final int RANK_NOT_FOUND = 1;
    private static final int RANK_TIMEOUT = 2;
    private static final int RANK_SERVER_ERROR = 3;
    private static final int RANK_REDIRECT = 4;
    private static final int RANK_AUTH = 5;

    private static Attempt describeStatus(int status, String endpoint) {
        if (status == 401 || status == 403) {
            return new Attempt(RANK_AUTH, endpoint, "API Key 无效或没有权限（HTTP " + status + "）");
        }
        if (status >= 300 && status < 400) {
            return new Attempt(RANK_REDIRECT, endpoint, "地址发生跳转，已按安全策略拒绝跟随（HTTP " + status + "）");
        }
        if (status == 404 || status == 405) {
            return new Attempt(RANK_NOT_FOUND, endpoint, "该地址没有模型列表接口（HTTP " + status + "）");
        }
        if (status >= 500) {
            return new Attempt(RANK_SERVER_ERROR, endpoint, "服务商返回 HTTP " + status);
        }
        return new Attempt(RANK_NOT_FOUND, endpoint, "服务商返回 HTTP " + status);
    }

    private HttpResponse<InputStream> send(Candidate candidate, String key)
            throws IOException, InterruptedException {
        String url = candidate.url();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(READ_TIMEOUT)
                .GET();
        switch (candidate.auth()) {
            case NONE -> {
            }
            case BEARER -> {
                if (key != null) {
                    builder.header("Authorization", "Bearer " + key);
                }
            }
            case X_API_KEY -> {
                if (key != null) {
                    builder.header("x-api-key", key);
                    builder.header("anthropic-version", ANTHROPIC_VERSION);
                }
            }
            case QUERY_KEY -> {
                if (key != null) {
                    url = url + "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
                    builder = HttpRequest.newBuilder(URI.create(url)).timeout(READ_TIMEOUT).GET();
                }
            }
        }
        // 字面量层面的元数据地址在解析阶段已拦；这里补一次 DNS 解析后的判断，
        // 覆盖十进制/混淆写法（http://2852039166 → 169.254.169.254）
        assertNotLinkLocal(URI.create(url).getHost());
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private static void assertNotLinkLocal(String host) {
        if (host == null || host.isBlank()) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // 交给调用方按「无法连接」处理，这里不改变异常类型
            return;
        }
        for (InetAddress address : addresses) {
            if (address.isLinkLocalAddress()) {
                throw new IllegalArgumentException("该地址解析到链路本地网络（云元数据地址），已拒绝访问");
            }
        }
    }

    /** 响应体硬上限：超过就断流，不能让它撑爆内存 */
    private static String readCapped(HttpResponse<InputStream> response) throws IOException {
        byte[] raw;
        try (InputStream in = response.body()) {
            raw = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (raw.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("服务商响应超过 1MB，已中止读取");
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static String describeStatus(int status) {
        if (status == 401 || status == 403) {
            return "API Key 无效或没有权限（HTTP " + status + "）";
        }
        if (status == 404 || status == 405) {
            return "该地址没有模型列表接口（HTTP " + status + "）";
        }
        if (status >= 300 && status < 400) {
            return "地址发生跳转，已按安全策略拒绝跟随（HTTP " + status + "）";
        }
        return "服务商返回 HTTP " + status;
    }

    /** 沿用 ChatModelFactory 的做法：明文密钥不进缓存键 */
    private static String apiKeyFingerprint(String apiKey) {
        return apiKey == null ? "-" : Integer.toHexString(apiKey.hashCode());
    }
}
