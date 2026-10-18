package com.kbook.service.ai.outline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 回答的结构化条目清单（流水线任务骨架）。
 * <p>
 * 持久化在 {@code AiConversation.answer_outline}（JSON），服务端内部状态：
 * 前端不读取、不参与历史压缩。由 {@link AnswerOutlineService} 生成
 * （第 1 轮流式结束后异步条目化；第 2 轮取不到时现场条目化）。
 * <p>
 * schema 见 docs/plans/book-qa-iterative-generation-plan.md §4.5：
 * <pre>{@code
 * { "topic": "本书主要内容",
 *   "items": [ { "id": 1, "title": "应变能力培养", "gist": "一句话摘要",
 *                "keywords": ["应变能力", "突发状况"], "chunkAnchors": [12, 13] } ] }
 * }</pre>
 *
 * @param topic 列表主题（读者的关注点）
 * @param items 条目列表（顺序即回答中的出现顺序）
 * @author kbook
 * @since 1.1.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnswerOutline(String topic, List<Item> items) {

    /**
     * 单个条目。
     *
     * @param id           1 起始的顺序号
     * @param title        条目名（展开追问时该节的小标题）
     * @param gist         一句话摘要（来自上一轮回答）
     * @param keywords     定向检索关键词（1~3 个短语）
     * @param chunkAnchors 上一轮 RAG 材料中对齐的 chunkIndex 锚点；无法对齐时为空
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(Integer id, String title, String gist,
                       List<String> keywords, List<Integer> chunkAnchors) {
    }

    /** 条目数（outline 或 items 为 null 时返回 0） */
    public int size() {
        return items == null ? 0 : items.size();
    }
}
