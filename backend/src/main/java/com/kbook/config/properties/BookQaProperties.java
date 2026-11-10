package com.kbook.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 图书问答按书规模分档路由配置。
 * <p>
 * 设计见 {@code docs/plans/book-qa-scale-aware-routing-plan.md} §2、P1-1。
 * 所有阈值都是「起步值」，必须用 {@code docs/eval/golden-set.real.jsonl} A/B 标定后再改默认。
 */
@Data
@Component
@ConfigurationProperties(prefix = "kbook.book-qa")
public class BookQaProperties {

    /** 灰度总开关：关闭时全量走现有 RAG 链路，与改造前逐字节等价 */
    private boolean scaleRoutingEnabled = false;

    /** 全书字符数 ≤ 可用空间 × 该比例 → SMALL 档 */
    private double smallBookRatio = 0.6;

    /** 预算安全余量（占毛预算比例），抵御长上下文 lost-in-the-middle 与 token 估算误差 */
    private double safetyMarginRatio = 0.15;

    /** 全文硬上限（字符）：超过一律走检索，防止 MEDIUMTEXT 极端值把请求打爆 */
    private int fullTextMaxChars = 400000;

    /** token → 中文字符换算；与现有 TOKEN_TO_CHAR_RATIO 口径一致，换 tokenizer 需重新标定 */
    private double charPerToken = 1.5;

    /** 会话内锁定档位：历史变长会让可用空间缩小，中途掉档会破坏缓存前缀 */
    private boolean sessionLockEnabled = true;

    /** MEDIUM 档整章原文的字符上限；超长章节降级为 LARGE */
    private int mediumChapterMaxChars = 60000;

    /** SMALL/MEDIUM 档是否仍允许进入列表条目流水线（默认关闭，全文已在上下文里） */
    private boolean listPipelineEnabledForFullText = false;

    /** 会话档位锁的最大条目数，超过时清空一半，避免长期运行内存无界增长 */
    private int sessionLockMaxSize = 10000;

    // ===== P2-3：Qdrant payload 脱敏 =====

    /**
     * 是否把内容向量的 {@code text_segment} 从「原文片段」换成「章节 + 位置 + 主题定位」。
     * <p>
     * 关闭时与现状逐字节等价；开启后必须重建内容向量才生效。
     * 向量本身仍由原文算出（检索语义不变），原文改由本地 {@code books.full_text}
     * 按 startChar/endChar 偏移在查询时还原，因此向量库里不再有第二份全书正文。
     */
    private boolean payloadDesensitizationEnabled = false;

    /** 还原原文时本地全文缓存的书目数量；避免每条命中都读一次 MEDIUMTEXT */
    private int fullTextCacheMaxSize = 8;

    // ===== P2-6：混合召回（候选池内 BM25 词法重排）=====

    /** 混合召回灰度开关：关闭时只走 dense + RRF，与 P1 完全等价 */
    private boolean hybridRetrievalEnabled = false;

    /** 词法名次分在融合里的权重（0 = 纯 dense，1 = 纯词法） */
    private double hybridLexicalWeight = 0.35;

    /** 开启混合召回时每路候选池的膨胀倍数，给词法信号更多可重排的候选 */
    private int hybridCandidateMultiplier = 2;

    // ===== 合规三道防线第 3 道：答案侧原文重合检测 =====

    /** 是否在答案落库前检测并替换连续原文重合片段；默认关闭，灰度验证后再开 */
    private boolean answerOverlapGuardEnabled = false;

    /** 触发替换的最小连续重合字符数 */
    private int answerOverlapMinChars = 30;

    /** 参与比对的源文本上限，防止 MEDIUMTEXT 极端值把单次请求打爆 */
    private int answerOverlapSourceMaxChars = 400000;

    // ===== 表达层：线性口语（独立于检索与分档，只影响 SystemMessage 文案）=====

    /**
     * 是否在 SystemMessage 末尾追加说话方式层：分几块由内容决定（不预设数量），
     * 块内只要求后一句能从前一句推出来，不给连接词清单、不给过渡句式模板；
     * 覆盖风格锚点里「结论 → 证据 → 意义」三段式的论据平铺腔调。
     * <p>
     * 治的是「结论先行 + 论据排队」，不是「有层次」——人讲课也分几点。
     * 默认开启：纯文案改动，不碰检索分数、阈值与上下文预算。
     * 设为 false 可逐字节回到旧提示词，用于 A/B 对照与回滚。
     */
    private boolean linearSpeechEnabled = true;
}
