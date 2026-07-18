package com.kbook.entity;

/**
 * AI 使用场景枚举 — 每个业务场景可独立绑定一个 AiProviderConfig。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>每个场景对应一个唯一的调用上下文（图书问答、圆桌派发言、压缩等）</li>
 *   <li>{@link #defaultCategory} 是回退分类：场景未配置专属 AI 配置时，
 *       按 QA/TOOL/COMPRESSION/VISION/EMBEDDING 查询默认配置</li>
 *   <li>{@link #streaming} 标记场景是否为流式调用，影响路由方法选择</li>
 *   <li>{@link #thinking} 标记场景是否启用思考过程（QA 类默认 true，
 *       TOOL/COMPRESSION/VISION/EMBEDDING 默认 false）</li>
 * </ul>
 * <p>
 * 新增场景：在 {@link Category} 已有的分类下添加新枚举值即可，
 * 管理后台 UI 会自动渲染新场景。
 */
public enum AiScene {

    // ==================== 图书问答域 ====================
    /** 图书问答主入口（SSE 流式 + RAG） */
    BOOK_QA("图书问答", Category.QA, true, true),
    /** AI 助理（通用对话 + 工具调用） */
    AI_ASSISTANT("AI 助理", Category.QA, true, true),
    /** 管理员助理（后台管理对话） */
    ADMIN_ASSISTANT("管理员助理", Category.QA, true, true),
    /** 预设问题生成（图书页底部推荐问题） */
    PRESET_QUESTION("预设问题生成", Category.TOOL, false, false),
    /** 图书摘要精炼（从原始内容提炼结构化摘要） */
    BOOK_SUMMARY_REFINE("图书摘要精炼", Category.TOOL, false, false),
    /** 3 分钟速读摘要（SSE 流式） */
    SPEED_READ("3分钟速读", Category.TOOL, true, false),

    // ==================== 圆桌派域 ====================
    /** 圆桌派流式发言（核心输出） */
    ROUND_TABLE_SPEECH("圆桌派发言", Category.QA_WITHOUT_THINKING, true, false),
    /** 圆桌派外部知识生成（为角色准备领域知识） */
    ROUND_TABLE_KNOWLEDGE("圆桌派外部知识", Category.QA_WITHOUT_THINKING, false, false),
    /** 圆桌派角色推荐（LLM 选 3 个角色） */
    ROUND_TABLE_ROLE_RECOMMEND("圆桌派角色推荐", Category.TOOL, false, false),
    /** 圆桌派角色检索查询生成 */
    ROUND_TABLE_ROLE_SEARCH("圆桌派角色检索", Category.TOOL, false, false),
    /** 圆桌派纯 LLM 判断下一个发言人（输出 NEXT/END/SUMMARY 决策） */
    ROUND_TABLE_SPEAKER_SELECT("圆桌派发言人选择", Category.TOOL, false, false),
    /** 圆桌派覆盖度评估 */
    ROUND_TABLE_COVERAGE("圆桌派覆盖度", Category.TOOL, false, false),
    /** 圆桌派报告生成 */
    ROUND_TABLE_REPORT("圆桌派报告", Category.TOOL, false, false),
    /** 圆桌派导出钩子（结束语生成） */
    ROUND_TABLE_EXPORT_HOOK("圆桌派导出钩子", Category.TOOL, false, false),

    // ==================== 辩论域（奇葩说）====================
    /** 辩论流式发言（开篇/质询/自由辩论/结辩共 7 环节） */
    DEBATE_SPEECH("辩论发言", Category.QA_WITHOUT_THINKING, true, false),
    /** 辩论外部知识生成（为辩手准备论据） */
    DEBATE_KNOWLEDGE("辩论外部知识", Category.QA_WITHOUT_THINKING, false, false),
    /** 辩题生成 */
    DEBATE_TOPIC_GENERATE("辩题生成", Category.TOOL, false, false),
    /** 辩题优化 */
    DEBATE_TOPIC_OPTIMIZE("辩题优化", Category.TOOL, false, false),
    /** 自由辩论发言人选择（KV 格式 LLM 输出） */
    DEBATE_SPEAKER_SELECT("辩论发言人选择", Category.TOOL, false, false),
    /** 辩论评分 */
    DEBATE_SCORING("辩论评分", Category.TOOL, false, false),
    /** 辩论报告生成 */
    DEBATE_REPORT("辩论报告", Category.TOOL, false, false),

    // ==================== RAG/检索域 ====================
    /** RAG 查询扩展（多关键词扩展） */
    QUERY_EXPAND("RAG查询扩展", Category.TOOL, false, false),
    /** 向量搜索查询扩展（口语化转正式） */
    VECTOR_QUERY_EXPAND("向量查询扩展", Category.TOOL, false, false),
    /** 追问问题生成 */
    FOLLOW_UP_QUESTION("追问问题生成", Category.TOOL, false, false),
    /** 列表型问题检测 */
    LIST_QUERY_DETECT("列表问题检测", Category.TOOL, false, false),
    /** 列表查询 chunks LLM 精筛 */
    LIST_QUERY_REFINE("列表查询精筛", Category.TOOL, false, false),
    /** 列表查询章节范围推断（从目录推断） */
    LIST_QUERY_CHAPTER_RANGE("列表章节范围推断", Category.TOOL, false, false),

    // ==================== 元数据/OCR/嵌入域 ====================
    /** 书籍元数据推断（作者/简介/标签） */
    BOOK_METADATA_INFER("元数据推断", Category.TOOL, false, false),
    /** 图书解析组合 AI 操作（标签/分类等，无思考） */
    BOOK_PARSE_COMBINED("图书解析组合", Category.QA_WITHOUT_THINKING, false, false),
    /** PDF OCR（元数据识别 + 全文识别） */
    PDF_OCR("PDF OCR", Category.VISION, false, false),
    /** 向量嵌入 */
    EMBEDDING("向量嵌入", Category.EMBEDDING, false, false),

    // ==================== 压缩域 ====================
    /** 通用对话压缩 */
    CHAT_COMPRESSION("通用对话压缩", Category.COMPRESSION, false, false),
    /** 圆桌派历史压缩（保留论点/态度/情绪） */
    ROUND_TABLE_COMPRESSION("圆桌派压缩", Category.COMPRESSION, false, false);

    /** 场景默认分类 — 决定未配置时的回退查询逻辑 */
    public enum Category {
        /** 大型问答（带思考） — 回退到 roles=QA */
        QA,
        /** 大型问答（无思考） — 回退到 roles=QA 但 thinking=false */
        QA_WITHOUT_THINKING,
        /** 小型工具 — 回退到 roles=TOOL */
        TOOL,
        /** 压缩 — 回退到 TOOL 配置 + 低温度 0.1 */
        COMPRESSION,
        /** OCR 视觉 — 回退到 YML vision 配置或 QA */
        VISION,
        /** 嵌入 — 回退到 purpose=EMBEDDING 配置 */
        EMBEDDING
    }

    private final String displayName;
    private final Category defaultCategory;
    private final boolean streaming;
    private final boolean thinking;

    AiScene(String displayName, Category defaultCategory, boolean streaming, boolean thinking) {
        this.displayName = displayName;
        this.defaultCategory = defaultCategory;
        this.streaming = streaming;
        this.thinking = thinking;
    }

    public String getDisplayName() { return displayName; }
    public Category getDefaultCategory() { return defaultCategory; }
    public boolean isStreaming() { return streaming; }
    public boolean isThinking() { return thinking; }
}
