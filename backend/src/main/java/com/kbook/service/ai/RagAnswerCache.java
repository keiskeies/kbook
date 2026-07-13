package com.kbook.service.ai;

import com.kbook.common.util.SseHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * RAG 答案缓存 — Redis 存储，按 bookId+question+model 维度缓存
 * <p>
 * 设计：
 * <ul>
 *   <li>只缓存首问（无对话上下文依赖的 RAG 答案），追问不缓存</li>
 *   <li>TTL 24 小时（书籍内容不变，答案可复用）</li>
 *   <li>缓存命中时流式回放（按段落分割 + 模拟打字延迟），避免一次性吐完整答案</li>
 *   <li>击穿保护：Redis SETNX 分布式锁，防并发击穿</li>
 *   <li>主动失效：书籍内容更新时按 bookId 批量清除</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagAnswerCache {

    private static final String KEY_PREFIX = "rag:ans:";
    private static final String LOCK_PREFIX = "rag:lock:";
    private static final Duration TTL = Duration.ofHours(24);
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;

    /**
     * 读取缓存的 RAG 答案
     */
    public String get(Long bookId, String question, String modelName) {
        try {
            return redis.opsForValue().get(buildKey(bookId, question, modelName));
        } catch (Exception e) {
            log.warn("RAG 缓存读取失败，降级为未命中: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 写入 RAG 答案缓存
     */
    public void put(Long bookId, String question, String modelName, String answer) {
        if (answer == null || answer.isBlank()) return;
        try {
            redis.opsForValue().set(buildKey(bookId, question, modelName), answer, TTL);
            log.info("[RAG-CACHE] 写入 bookId={} question={} ({}字符)", bookId,
                    truncate(question), answer.length());
        } catch (Exception e) {
            log.warn("RAG 缓存写入失败: {}", e.getMessage());
        }
    }

    /**
     * 尝试获取生成锁（击穿保护）
     *
     * @return true 表示获取成功（使用后必须调用 unlock）
     */
    public boolean tryLock(Long bookId, String question) {
        try {
            Boolean locked = redis.opsForValue()
                    .setIfAbsent(buildLockKey(bookId, question), "1", LOCK_TTL);
            return Boolean.TRUE.equals(locked);
        } catch (Exception e) {
            log.warn("RAG 缓存锁获取失败，降级为无锁: {}", e.getMessage());
            return true; // 降级为无锁，允许并发生成
        }
    }

    /**
     * 释放生成锁
     */
    public void unlock(Long bookId, String question) {
        try {
            redis.delete(buildLockKey(bookId, question));
        } catch (Exception ignored) {
        }
    }

    /**
     * 批量失效指定书籍的所有 RAG 答案缓存
     * <p>
     * 书籍内容更新（重新导入、章节修订、向量重建）时调用。
     */
    public void invalidateBook(Long bookId) {
        try {
            Set<String> keys = redis.keys(KEY_PREFIX + bookId + ":*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
                log.info("[RAG-CACHE] 失效 bookId={} 的 {} 条缓存", bookId, keys.size());
            }
        } catch (Exception e) {
            log.warn("RAG 缓存批量失效失败: {}", e.getMessage());
        }
    }

    /**
     * 失效所有 RAG 答案缓存
     * <p>
     * 清空整个内容向量库时调用（全量重建场景）。
     */
    public void invalidateAll() {
        try {
            Set<String> keys = redis.keys(KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
                log.info("[RAG-CACHE] 全量失效 {} 条缓存", keys.size());
            }
        } catch (Exception e) {
            log.warn("RAG 缓存全量失效失败: {}", e.getMessage());
        }
    }

    /**
     * 流式回放缓存答案 — 按段落分割 + 模拟打字延迟
     * <p>
     * 延迟设计：每段 50-120ms，总体 3-8 秒（与真实 LLM 流式输出节奏接近）。
     * 如果连接断开，立即停止回放。
     *
     * @param emitter      SSE 发射器
     * @param cachedAnswer 缓存的完整答案
     * @param onComplete   回放完成后的回调（保存对话记录等）
     * @return true 表示回放成功完成，false 表示连接断开
     */
    public boolean replay(SseEmitter emitter, String cachedAnswer, Runnable onComplete) {
        log.info("[RAG-CACHE] 命中，流式回放 ({}字符)", cachedAnswer.length());

        // 按段落分割：优先双换行，超长段落按 150 字符再切
        String[] paragraphs = cachedAnswer.split("\n\n");
        for (String para : paragraphs) {
            if (para.isEmpty()) continue;

            // 超长段落按 150 字符切分，避免单次发送过大
            if (para.length() > 150) {
                for (int i = 0; i < para.length(); i += 150) {
                    int end = Math.min(i + 150, para.length());
                    String chunk = para.substring(i, end);
                    if (!sendChunk(emitter, chunk)) return false;
                }
            } else {
                if (!sendChunk(emitter, para)) return false;
            }
        }

        // 回放完成回调
        try {
            onComplete.run();
        } catch (Exception e) {
            log.warn("[RAG-CACHE] 回放完成回调异常: {}", e.getMessage());
        }

        // 发送 done 事件
        try {
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
        } catch (Exception e) {
            log.warn("[RAG-CACHE] 发送 done 事件失败: {}", e.getMessage());
        }

        log.info("[RAG-CACHE] 回放完成");
        return true;
    }

    /**
     * 发送单个 chunk + 模拟延迟
     */
    private boolean sendChunk(SseEmitter emitter, String chunk) {
        if (!SseHelper.safeSendEvent(emitter, "message", chunk)) {
            log.warn("[RAG-CACHE] 连接已关闭，停止回放");
            return false;
        }
        try {
            // 模拟打字延迟：50-120ms
            Thread.sleep(50 + ThreadLocalRandom.current().nextInt(70));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    private String buildKey(Long bookId, String question, String modelName) {
        return KEY_PREFIX + bookId + ":" + md5(question + "|" + modelName);
    }

    private String buildLockKey(Long bookId, String question) {
        return LOCK_PREFIX + bookId + ":" + md5(question);
    }

    private static String md5(String input) {
        return DigestUtils.md5DigestAsHex(input.getBytes(StandardCharsets.UTF_8));
    }

    private static String truncate(String s) {
        return s.length() > 30 ? s.substring(0, 30) + "..." : s;
    }
}
