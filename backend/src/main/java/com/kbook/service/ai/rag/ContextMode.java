package com.kbook.service.ai.rag;

/**
 * 图书问答的上下文供给模式（按书规模分档）。
 * <p>
 * 判据：{@code 可用空间 = 模型上下文 − 输出预留 − 系统/画像 − 历史问答 − 安全余量}。
 * 书远小于可用空间时整本直塞，装不下但问题指向局部时整章直塞，其余才走向量检索。
 */
public enum ContextMode {

    /** 整本书原文作为静态前缀直塞，跳过检索 */
    SMALL("full_text"),
    /** 按目录/章节定位后整章原文直塞，跳过检索 */
    MEDIUM("chapter"),
    /** 现有 RAG 检索链路（topK + 上下文预算） */
    LARGE("retrieval");

    /** 写入 ai_retrieval_snapshots.source 的模式标记 */
    private final String snapshotSource;

    ContextMode(String snapshotSource) {
        this.snapshotSource = snapshotSource;
    }

    public String snapshotSource() {
        return snapshotSource;
    }

    /** 是否需要执行向量检索。 */
    public boolean usesRetrieval() {
        return this == LARGE;
    }

    /** 是否把原文直接放进了提示词（决定引用契约与评测口径）。 */
    public boolean fullContext() {
        return this != LARGE;
    }
}
