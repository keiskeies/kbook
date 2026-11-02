package com.kbook.service.ai.core.model;

import java.util.List;

/**
 * 「获取模型」接口的响应体。
 * <p>
 * 三种结局都用 {@code code=0} 返回，靠 {@link #source()} 区分：
 * 失败时管理员仍然需要知道<b>实际尝试了哪个地址</b>，
 * 所以不把错误塞进 {@code Result.fail}（那会丢掉 endpoint 排查信息，
 * 也让前端多出一条 toast 打断编辑）。
 *
 * @param models   模型条目，失败或空列表时为空
 * @param source   {@code PROVIDER}=拿到列表 / {@code EMPTY}=连通但列表为空 / {@code UPSTREAM_ERROR}=没拿到
 * @param endpoint 最后尝试的地址（不含密钥）
 * @param total    条目数
 * @param hint     给管理员看的一句话说明
 */
public record ModelListResult(List<ModelSummary> models, String source, String endpoint, int total, String hint) {

    /** 成功从服务商取到列表 */
    public static final String PROVIDER = "PROVIDER";
    /** 地址连通、返回 2xx，但列表为空 */
    public static final String EMPTY = "EMPTY";
    /** 拉取失败，需要降级为手动填写 */
    public static final String UPSTREAM_ERROR = "UPSTREAM_ERROR";

    public static ModelListResult of(List<ModelSummary> models, String endpoint) {
        return new ModelListResult(models, PROVIDER, endpoint, models.size(), null);
    }

    public static ModelListResult empty(String endpoint) {
        return new ModelListResult(List.of(), EMPTY, endpoint, 0, "该服务商返回了空列表");
    }

    public static ModelListResult error(String endpoint, String hint) {
        return new ModelListResult(List.of(), UPSTREAM_ERROR, endpoint, 0, hint);
    }

    /** 是否值得缓存（失败不缓存，否则一个偶发超时会把错误钉住 10 分钟） */
    public boolean cacheable() {
        return !UPSTREAM_ERROR.equals(source);
    }
}
