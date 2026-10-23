package com.kbook.service.tts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渐进式语音播放的临时凭据存储
 * <p>
 * {@code <audio src>} 只能发起 GET 且无法携带自定义请求头，而长文本（上千字）
 * 直接放进 query string 会超出长度限制。因此采用「一次性凭据」模式：
 * 前端先用 POST 换取一个短 ID，再把 {@code /api/tts/stream/{id}?token=...}
 * 作为音源地址。
 * <p>
 * 凭据带 TTL 且容量有上限，过期后前端重新 POST 即可（缓存命中时几乎无耗时）。
 * 注意：此实现将凭据保存在单实例内存中，多实例部署需改为 Redis 等共享存储。
 */
@Slf4j
@Component
public class TtsStreamTicketStore {

    /** 凭据有效期：10 分钟，足够覆盖一次长文本朗读的重连与续播 */
    private static final long TTL_MILLIS = 10 * 60 * 1000L;

    /** 凭据总量上限，防止无界增长 */
    private static final int MAX_ENTRIES = 500;

    /** 定期清理阈值：超过该数量时触发一次过期清理 */
    private static final int SWEEP_THRESHOLD = 200;

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    private final SecureRandom random = new SecureRandom();

    /**
     * 播放凭据
     *
     * @param text     待合成文本
     * @param configId TTS 配置ID，可为 null 表示使用当前默认配置
     * @param userId   发起者用户ID，用于校验凭据归属
     * @param expiresAt 过期时间戳
     */
    public record Ticket(String text, Long configId, Long userId, long expiresAt) {
        boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    /**
     * 创建一个播放凭据
     *
     * @param text     待合成文本
     * @param configId TTS 配置ID
     * @param userId   发起者用户ID
     * @return 凭据 ID
     */
    public String create(String text, Long configId, Long userId) {
        sweepIfNeeded();
        String id = randomId();
        tickets.put(id, new Ticket(text, configId, userId, System.currentTimeMillis() + TTL_MILLIS));
        return id;
    }

    /**
     * 取出凭据对应的文本；不存在、已过期或不属于该用户时返回 null
     *
     * @param id     凭据 ID
     * @param userId 当前用户ID
     * @return 凭据，或 null
     */
    public Ticket resolve(String id, Long userId) {
        if (id == null) return null;
        Ticket ticket = tickets.get(id);
        if (ticket == null) return null;
        if (ticket.expired()) {
            tickets.remove(id);
            return null;
        }
        if (userId == null || !userId.equals(ticket.userId())) {
            log.warn("TTS stream ticket owner mismatch: ticketUserId={}, requestUserId={}",
                    ticket.userId(), userId);
            return null;
        }
        return ticket;
    }

    /**
     * 主动释放凭据
     *
     * @param id 凭据 ID
     */
    public void remove(String id) {
        if (id != null) tickets.remove(id);
    }

    /**
     * 当前存活凭据数量（用于可观测性）
     *
     * @return 凭据数量
     */
    public int size() {
        return tickets.size();
    }

    private void sweepIfNeeded() {
        if (tickets.size() < SWEEP_THRESHOLD) return;
        Iterator<Map.Entry<String, Ticket>> it = tickets.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expired()) it.remove();
        }
        // 清理后仍超上限，按插入顺序丢弃最旧的，保证容量硬约束
        if (tickets.size() >= MAX_ENTRIES) {
            int toDrop = tickets.size() - MAX_ENTRIES + 1;
            Iterator<String> keys = tickets.keySet().iterator();
            while (keys.hasNext() && toDrop-- > 0) {
                keys.next();
                keys.remove();
            }
        }
    }

    private String randomId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}