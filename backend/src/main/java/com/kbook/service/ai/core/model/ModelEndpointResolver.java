package com.kbook.service.ai.core.model;

import com.kbook.entity.AiProviderConfig.Provider;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 把「API 地址」输入框里的文本换算成一组可尝试的模型列表地址。
 * <p>
 * 纯函数：不发网络、不碰 Spring，便于把每条规则单独钉死在单测里。
 * <p>
 * 之所以返回<b>候选列表</b>而不是单个地址：自定义网关的路径前缀互不相同
 * （vLLM 不带版本号、one-api 常挂 {@code /v1}、Gemini 用 {@code /v1beta}、
 * Anthropic 只有 {@code /v1/models}），只拼一种会大面积 404。
 */
public final class ModelEndpointResolver {

    /** 候选地址要求的鉴权方式 */
    public enum Auth {
        /** 无鉴权（Ollama 本地） */
        NONE,
        /** {@code Authorization: Bearer <key>} */
        BEARER,
        /** {@code x-api-key} + {@code anthropic-version} */
        X_API_KEY,
        /** key 拼在查询串上（Gemini 原生接口） */
        QUERY_KEY
    }

    /**
     * 一个候选地址。
     *
     * @param url  不含密钥的完整地址（可直接展示给管理员排查）
     * @param auth 该地址要求的鉴权方式
     */
    public record Candidate(String url, Auth auth) {
    }

    /** 与实体 baseUrl 列宽一致 */
    private static final int MAX_URL_LENGTH = 500;

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /** 尾部的「动作」路径，用户常把 chat 接口地址整段粘进来 */
    private static final List<String> ACTION_SUFFIXES =
            List.of("/chat/completions", "/completions", "/embeddings");

    /**
     * 云厂商元数据服务地址：响应不是模型列表，且是典型 SSRF 目标。
     * 阿里云是 {@code 100.100.100.200}，AWS/GCP/腾讯云走 {@code 169.254/16} 或主机名。
     */
    private static final List<String> METADATA_HOSTS = List.of(
            "metadata", "metadata.google.internal", "metadata.google.com",
            "metadata.tencentyun.com");
    private static final String ALIYUN_METADATA_IP = "100.100.100.200";

    private ModelEndpointResolver() {
    }

    /**
     * 解析候选地址。
     *
     * @param provider 实体里的供应商类型（只有 OLLAMA / OPENAI 两种）
     * @param baseUrl  表单当前值
     * @return 按尝试顺序排列的候选地址
     * @throws IllegalArgumentException 地址不合法或被安全策略拒绝（消息可直接给前端展示）
     */
    public static List<Candidate> resolve(Provider provider, String baseUrl) {
        if (provider == null) {
            throw new IllegalArgumentException("供应商类型不能为空");
        }
        String base = normalize(baseUrl);
        String host = hostOf(base);
        assertHostAllowed(host);

        if (provider == Provider.OLLAMA) {
            String root = stripTrailingVersion(stripActionSuffix(base));
            // Ollama 的原生列表接口不需要 key
            return dedupe(new Candidate(root + "/api/tags", Auth.NONE),
                    // 也提供 OpenAI 兼容层，供套了反代的场景兜底
                    new Candidate(root + "/v1/models", Auth.BEARER));
        }

        if (base.toLowerCase(Locale.ROOT).endsWith("/models")) {
            // 管理员直接粘了列表地址：用他写的，别再追加一次
            return dedupe(new Candidate(base, authForHost(host)));
        }

        if (isGeminiHost(host)) {
            return dedupe(new Candidate(geminiRoot(stripActionSuffix(base)) + "/models", Auth.QUERY_KEY));
        }

        if (isAnthropicHost(host)) {
            String root = stripActionSuffix(base);
            String v1 = endsWithVersionSegment(root) ? root : root + "/v1";
            return dedupe(new Candidate(v1 + "/models", Auth.X_API_KEY));
        }

        String root = stripActionSuffix(base);
        Candidate primary = new Candidate(root + "/models", Auth.BEARER);
        if (endsWithVersionSegment(root)) {
            return dedupe(primary);
        }
        return dedupe(primary, new Candidate(root + "/v1/models", Auth.BEARER));
    }

    /**
     * 归一化：去空白、剥 query/fragment、剥尾斜杠、校验协议与主机名。
     *
     * @return 可安全拼接的绝对地址
     */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("API 地址不能为空");
        }
        String s = raw.trim();
        if (s.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("API 地址过长（上限 " + MAX_URL_LENGTH + " 字符）");
        }
        int cut = s.indexOf('?');
        if (cut < 0) {
            cut = s.indexOf('#');
        }
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("API 地址需要以 http:// 或 https:// 开头");
        }
        while (s.length() > "http://".length() && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        URI uri = toUri(s);
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("API 地址缺少主机名");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("API 地址不能包含用户名密码");
        }
        return s;
    }

    /**
     * 纯字面量层面的元数据地址拦截。
     * <p>
     * 十进制/十六进制等混淆写法（{@code http://2852039166}）在这里放过，
     * 由服务层解析 DNS 之后再判一次（{@link #isLinkLocalIpv6} 同理只是字面判断）。
     */
    public static boolean isBlockedHostname(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (METADATA_HOSTS.contains(h)) {
            return true;
        }
        if (IPV4.matcher(h).matches()) {
            return ALIYUN_METADATA_IP.equals(h) || isLinkLocalIpv4(h);
        }
        if (h.startsWith("[") && h.endsWith("]")) {
            String v6 = h.substring(1, h.length() - 1);
            int lastColon = v6.lastIndexOf(':');
            String tail = v6.substring(lastColon + 1);
            if (IPV4.matcher(tail).matches()) {
                // ::ffff:169.254.169.254 这类内嵌 IPv4 写法
                return isLinkLocalIpv4(tail);
            }
            return isLinkLocalIpv6(v6);
        }
        return false;
    }

    private static void assertHostAllowed(String host) {
        if (isBlockedHostname(host)) {
            throw new IllegalArgumentException("该地址属于元数据服务或被安全策略禁止，无法用于获取模型列表");
        }
    }

    private static boolean isLinkLocalIpv4(String h) {
        String[] parts = h.split("\\.");
        return parts.length == 4
                && "169".equals(parts[0]) && "254".equals(parts[1]);
    }

    private static boolean isLinkLocalIpv6(String v6) {
        String first = v6.split(":")[0];
        if (first.length() == 0 || first.length() > 4) {
            return false;
        }
        try {
            int head = Integer.parseInt(first, 16);
            return (head & 0xffc0) == 0xfe80;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static URI toUri(String s) {
        try {
            return new URI(s);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("API 地址格式不合法");
        }
    }

    private static String hostOf(String base) {
        return toUri(base).getHost().toLowerCase(Locale.ROOT);
    }

    private static List<String> pathSegments(String base) {
        String path = toUri(base).getPath();
        return path == null ? List.of() : List.of(path.split("/"));
    }

    /** 剥掉用户误粘的接口动作后缀 */
    private static String stripActionSuffix(String base) {
        String s = base;
        String lower = s.toLowerCase(Locale.ROOT);
        for (String suffix : ACTION_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                s = s.substring(0, s.length() - suffix.length());
                break;
            }
        }
        return s;
    }

    /** 剥掉尾部版本段（Ollama 用户常填 {@code .../v1}） */
    private static String stripTrailingVersion(String base) {
        String s = base;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/v1beta") || lower.endsWith("/openai")) {
            return s.substring(0, s.length() - (lower.endsWith("/openai") ? "/openai".length() : "/v1beta".length()));
        }
        while (endsWithVersionSegment(s)) {
            s = s.substring(0, s.lastIndexOf('/'));
        }
        return s;
    }

    private static boolean endsWithVersionSegment(String base) {
        List<String> segments = pathSegments(base);
        if (segments.isEmpty()) {
            return false;
        }
        String last = segments.get(segments.size() - 1).toLowerCase(Locale.ROOT);
        return last.matches("v\\d+") || last.equals("v1beta");
    }

    /** Gemini 必须落在带版本的路径上，缺省补 {@code /v1beta} */
    private static String geminiRoot(String base) {
        String path = toUri(base).getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return base + "/v1beta";
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.matches(".*/v\\d+.*") || lower.contains("v1beta")) {
            return base;
        }
        return base + "/v1beta";
    }

    private static Auth authForHost(String host) {
        if (isGeminiHost(host)) {
            return Auth.QUERY_KEY;
        }
        return isAnthropicHost(host) ? Auth.X_API_KEY : Auth.BEARER;
    }

    private static boolean isGeminiHost(String host) {
        return host.contains("generativelanguage.googleapis.com");
    }

    private static boolean isAnthropicHost(String host) {
        return host.contains("anthropic.com");
    }

    private static List<Candidate> dedupe(Candidate... candidates) {
        LinkedHashSet<Candidate> set = new LinkedHashSet<>();
        for (Candidate c : candidates) {
            set.add(c);
        }
        return List.copyOf(set);
    }
}
