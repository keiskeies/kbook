package com.kbook.service.ai.core.model;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 缓存破坏（nonce 注入）的公共动作。
 * <p>
 * 目的：让 provider 侧的提示词缓存 / KV cache / 网关响应缓存必然 miss。
 * nonce 必须<b>在调用期</b>生成 — 模型实例被 {@code ChatModelFactory} 缓存，
 * 构建期生成会让同一实例的所有请求复用同一个随机数，等于没破坏。
 */
public final class CacheBustingSupport {

    /** 标记前缀，排查时可在 provider 侧日志里一眼认出 */
    static final String MARKER_PREFIX = "[kbook-req-";

    private CacheBustingSupport() {
    }

    /** 形如 {@code [kbook-req-3f9a1c02]\n} */
    static String newNonce() {
        return MARKER_PREFIX + String.format("%08x", ThreadLocalRandom.current().nextInt()) + "]\n";
    }

    /**
     * 把 nonce 注入到消息序列最前面。
     * <p>
     * 首条已是 {@link SystemMessage} 时拼到它文本最前（不新增一条 system，
     * 免得 provider 侧 system 段数变化影响指令解析）；否则在 0 位补一条只含 nonce 的 system。
     *
     * @return 新列表，<b>不修改调用方入参</b>
     */
    public static List<ChatMessage> inject(List<ChatMessage> messages) {
        String nonce = newNonce();
        int size = messages == null ? 0 : messages.size();
        List<ChatMessage> out = new ArrayList<>(size + 1);
        if (size > 0 && messages.get(0) instanceof SystemMessage first) {
            out.add(SystemMessage.from(nonce + first.text()));
            out.addAll(messages.subList(1, size));
            return out;
        }
        out.add(SystemMessage.from(nonce.trim()));
        if (messages != null) {
            out.addAll(messages);
        }
        return out;
    }

    /**
     * 对 {@link ChatRequest} 注入 nonce。
     * <p>
     * 用 {@code toBuilder()} 重建，temperature/topP/maxOutputTokens/tools/responseFormat
     * 等参数原样保留。
     */
    public static ChatRequest inject(ChatRequest request) {
        return request.toBuilder().messages(inject(request.messages())).build();
    }
}
