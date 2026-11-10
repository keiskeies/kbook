package com.kbook.service.embedding;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kbook.config.properties.BookQaProperties;
import com.kbook.repository.BookRepository;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Qdrant 内容向量的 {@code text_segment} 编解码器（合规三道防线第 2 道 / 方案 P2-3）。
 *
 * <p><b>要解决的问题</b>：原文此前存两份 —— MySQL {@code books.full_text}（用户自己的书，本地）
 * 与 Qdrant 每个 chunk 的 {@code text_segment}。后者是「按 800 字切开后重新落盘的第二份全书副本」，
 * 对开源自部署工具没有必要。
 *
 * <p><b>做法</b>：
 * <ol>
 *   <li><b>索引侧</b>：embedding 仍由原文计算（向量语义、相似度分布、所有已标定阈值都不变），
 *       但 payload 的 {@code text_segment} 换成不含连续原文的结构化定位串
 *       （章节 + 片段号 + 字符区间 + 主题词），并打上 {@code text_redacted=1} 标记；</li>
 *   <li><b>查询侧</b>：检索命中后用 payload 里的 {@code startChar/endChar} 从本地
 *       {@code books.full_text} 精确切片还原原文。下游 RAG 上下文、邻域扩展、快照、
 *       {@code /evidence}、评测看到的文本与改造前完全一致；</li>
 *   <li><b>安全兜底</b>：书缺少 {@code full_text}（例如历史上只有 transient ragContent 的旧数据）时
 *       索引侧不脱敏，避免造出无法还原的死向量；还原失败时原样返回定位串并告警。</li>
 * </ol>
 *
 * <p>灰度开关 {@code kbook.book-qa.payload-desensitization-enabled} 默认关闭，
 * 关闭时 {@link #forIndex(TextSegment)} 原样返回，全链路逐字节等价。开启后需重建内容向量。
 * 完整倒排索引（BM25）属于桌面化 Lucene 改造，见 P2-6 的候选池词法重排。
 */
@Slf4j
@Service
public class PayloadTextCodec {

    /** payload 标记：1 = 该条 text_segment 是定位串而非原文 */
    public static final String REDACTED_FLAG_KEY = "text_redacted";
    /** 定位串统一前缀，供「标记丢失」的降级路径识别（如 LangChain4j QdrantEmbeddingStore 回退搜索） */
    public static final String DIGEST_PREFIX = "【检索定位】";

    private static final Set<Character> STOP_CHARS = Set.of(
            '的', '了', '是', '在', '和', '也', '就', '都', '而', '及', '与', '或', '一', '这', '那',
            '有', '不', '中', '为', '对', '从', '被', '把', '们', '个', '上', '下', '时', '说', '要',
            '会', '能', '可', '以', '之', '其', '所', '于', '但', '并', '等', '还', '又', '更', '很',
            '我', '你', '他', '她', '它', '着', '过', '吗', '呢', '吧', '啊', '，', '。', '、', '；');

    private final BookRepository bookRepository;
    private final BookQaProperties properties;
    private final Cache<Long, String> fullTextCache;

    public PayloadTextCodec(BookRepository bookRepository, BookQaProperties properties) {
        this.bookRepository = bookRepository;
        this.properties = properties;
        this.fullTextCache = Caffeine.newBuilder()
                .maximumSize(Math.max(1, properties.getFullTextCacheMaxSize()))
                .expireAfterAccess(Duration.ofMinutes(10))
                .build();
    }

    public boolean isEnabled() {
        return properties.isPayloadDesensitizationEnabled();
    }

    /** 重建向量前调用：避免缓存里仍旧全文让新索引的偏移对不上。 */
    public void invalidate(Long bookId) {
        if (bookId != null) fullTextCache.invalidate(bookId);
    }

    /**
     * 索引侧：把原文换成定位串。脱敏不可安全还原时原样返回原文（fail-safe）。
     */
    public String forIndex(TextSegment segment) {
        if (segment == null) return "";
        String raw = segment.text() == null ? "" : segment.text();
        if (!isEnabled()) return raw;
        Long bookId = longOf(segment, "bookId");
        if (bookId == null || isBlank(loadFullText(bookId))) {
            log.warn("[PayloadRedact] 书 {} 缺少 full_text，跳过脱敏以避免产生不可还原的向量", bookId);
            return raw;
        }
        return digestOf(segment, raw);
    }

    /**
     * 查询侧：把定位串还原成本地全文切片。
     *
     * @param storedText Qdrant 里读到的 text_segment
     * @param redactedFlag payload 的 text_redacted（1 = 已脱敏），可为 null
     */
    public String restore(String storedText, Long bookId, Long startChar, Long endChar, Long redactedFlag) {
        if (isBlank(storedText)) return storedText;
        boolean redacted = (redactedFlag != null && redactedFlag == 1L)
                || storedText.startsWith(DIGEST_PREFIX);
        if (!redacted) return storedText;
        if (bookId == null || startChar == null || endChar == null) {
            log.warn("[PayloadRedact] 定位串缺少 bookId/偏移量，无法还原: {}", preview(storedText));
            return storedText;
        }
        String full = loadFullText(bookId);
        if (isBlank(full)) return storedText;
        int from = (int) Math.max(0, Math.min(startChar, full.length()));
        int to = (int) Math.max(from, Math.min(endChar, full.length()));
        if (to <= from) return storedText;
        return full.substring(from, to);
    }

    /** 从 payload Map 还原（EmbeddingStoreManager 的 gRPC 搜索路径）。 */
    public String restore(String storedText, Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload,
                          EmbeddingStoreManager store) {
        if (isBlank(storedText)) return storedText;
        return restore(storedText,
                store.extractPayloadLong(payload, "bookId"),
                store.extractPayloadLong(payload, "startChar"),
                store.extractPayloadLong(payload, "endChar"),
                store.extractPayloadLong(payload, REDACTED_FLAG_KEY));
    }

    /** 从 LangChain4j 的 TextSegment metadata 还原（EmbeddingSearchService 降级路径）。 */
    public String restore(TextSegment segment) {
        if (segment == null) return null;
        String stored = segment.text();
        if (isBlank(stored)) return stored;
        return restore(stored,
                longOf(segment, "bookId"),
                longOf(segment, "startChar"),
                longOf(segment, "endChar"),
                longOf(segment, REDACTED_FLAG_KEY));
    }

    /** 定位串是否包含连续原文：用于单测与合规自检。 */
    public static boolean looksLikeDigest(String text) {
        return text != null && text.startsWith(DIGEST_PREFIX);
    }

    // ==================== 内部 ====================

    /** 结构化定位 + 非逐字主题词。绝不拼接原文连续片段。 */
    String digestOf(TextSegment segment, String raw) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(DIGEST_PREFIX);
        String chapterTitle = stringOf(segment, "chapterTitle");
        Long chapterIndex = longOf(segment, "chapterIndex");
        if (chapterIndex != null) sb.append("第").append(chapterIndex).append("章");
        if (!isBlank(chapterTitle)) sb.append("「").append(trim(chapterTitle, 40)).append("」");
        Long chunkIndex = longOf(segment, "chunkIndex");
        Long totalChunks = longOf(segment, "totalChunks");
        if (chunkIndex != null) {
            sb.append(" · 片段 ").append(chunkIndex);
            if (totalChunks != null) sb.append('/').append(totalChunks);
        }
        Long startChar = longOf(segment, "startChar");
        Long endChar = longOf(segment, "endChar");
        if (startChar != null && endChar != null) {
            sb.append(" · 字符区间 [").append(startChar).append(',').append(endChar).append(']');
        }
        sb.append(" · 约 ").append(raw.length()).append(" 字");
        String topics = topicKeywords(raw, 6);
        if (!topics.isEmpty()) sb.append(" · 主题词：").append(topics);
        sb.append("\n（原文不入库；由本地全文按偏移还原）");
        return sb.toString();
    }

    /**
     * 主题词提取：中文二元组词频 + 英文单词，只输出离散词、不输出连续原文。
     * 遵守 {@code rag-ingestion-and-chunking-plan.md} §3.3「不要用 LLM 改写正文入库」——
     * 这里根本不调 LLM，也不生成句子，只做确定性词频统计。
     */
    static String topicKeywords(String text, int limit) {
        if (text == null || text.length() < 40 || limit <= 0) return "";
        Map<String, int[]> freq = new HashMap<>();
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        java.util.regex.Matcher latin = java.util.regex.Pattern.compile("[a-z]{3,}")
                .matcher(lower);
        while (latin.find()) {
            freq.computeIfAbsent(latin.group(), k -> new int[1])[0]++;
        }
        for (int i = 0; i + 1 < text.length(); i++) {
            char a = text.charAt(i);
            char b = text.charAt(i + 1);
            if (!isCjk(a) || !isCjk(b) || STOP_CHARS.contains(a) || STOP_CHARS.contains(b)) continue;
            freq.computeIfAbsent("" + a + b, k -> new int[1])[0]++;
        }
        List<Map.Entry<String, int[]>> sorted = new ArrayList<>(freq.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, int[]>>comparingInt(e -> e.getValue()[0]).reversed());
        Set<String> picked = new HashSet<>();
        List<String> out = new ArrayList<>(limit);
        for (Map.Entry<String, int[]> e : sorted) {
            String term = e.getKey();
            if (e.getValue()[0] < 2) break;
            // 已被更长词覆盖的短词不重复输出，避免「思维/维模/模型」连成原文
            boolean covered = false;
            for (String p : picked) {
                if (p.contains(term) || term.contains(p)) { covered = true; break; }
            }
            if (covered || !picked.add(term)) continue;
            out.add(term);
            if (out.size() >= limit) break;
        }
        return String.join("、", out);
    }

    private static boolean isCjk(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }

    private String loadFullText(Long bookId) {
        return fullTextCache.get(bookId, id -> {
            try {
                List<String> rows = bookRepository.findFullTextById(id);
                return rows == null || rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0);
            } catch (Exception e) {
                log.warn("[PayloadRedact] 读取本地全文失败: bookId={} - {}", id, e.getMessage());
                return "";
            }
        });
    }

    private static Long longOf(TextSegment segment, String key) {
        if (segment == null || segment.metadata() == null) return null;
        Object v = segment.metadata().toMap().get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static String stringOf(TextSegment segment, String key) {
        if (segment == null || segment.metadata() == null) return null;
        Object v = segment.metadata().toMap().get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String preview(String s) {
        return trim(s.replace('\n', ' '), 80);
    }
}
