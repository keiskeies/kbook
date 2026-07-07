package com.kbook.config.ai;

import lombok.Data;
import java.util.List;

/**
 * AI 系统统一配置根对象 — 对应 ai-config.json 的顶级结构
 */
@Data
public class AiConfig {
    private BookChatConfig bookChat;
    private RoundTableConfig roundTable;
    private DebateConfig debate;

    // ==================== 图书问答风格 ====================

    @Data
    public static class BookChatConfig {
        private String defaultStyle;
        private List<ChatStyle> styles;
    }

    @Data
    public static class ChatStyle {
        private String key;
        private String name;
        private String title;
        private String prompt;
    }

    // ==================== 圆桌派角色 ====================

    @Data
    public static class RoundTableConfig {
        private RoundTableHost host;
        private RoundTableSettings settings;
        private List<RoundTableRole> roles;
    }

    @Data
    public static class RoundTableSettings {
        private int maxRolesPerSession;
        private List<String> defaultSelectedKeys;
    }

    @Data
    public static class RoundTableHost {
        private String key;
        private String name;
        private String title;
        private String group;
        private String color;
        private String icon;
        private TtsConfig tts;
        private String prompt;
        private String catchphrase;
        private RoleParams params;
    }

    @Data
    public static class RoundTableRole {
        private String key;
        private String name;
        private String title;
        private String group;
        private String color;
        private String icon;
        private TtsConfig tts;
        private String prompt;
        private String catchphrase;
        private RoleParams params;
        private List<String> searchKeywords;
        private List<String> tags;
        /** 角色 RAG 检索策略（可选，未配置走默认策略） */
        private RagStrategy ragStrategy;
    }

    @Data
    public static class TtsConfig {
        private double pitch;
        private double rate;
    }

    @Data
    public static class RoleParams {
        private int grabWeight;
        private int verbosity;
        private int opinionated;
        private int challenge;
        private int empathy;
        private int humor;
    }

    /**
     * 角色 RAG 检索策略配置
     * <p>
     * 不同角色对图书内容的关注点不同，通过该策略驱动检索管线：
     * - topN：每个子查询的最大返回数
     * - neighborPrev/Next：邻域扩展范围（前 N 后 M 个 chunk）
     * - subQueryCount：LLM 生成的子查询数量（≥1）
     * - focusKeywords：角色视角偏好词，命中这些词的 chunk 在排序时加权
     * - perspectiveHint：角色视角提示，注入 LLM 查询生成
     */
    @Data
    public static class RagStrategy {
        /** 每个子查询的最大返回数（默认 10） */
        private int topN = 10;
        /** 邻域向前扩展数（默认 0，不扩展） */
        private int neighborPrev = 0;
        /** 邻域向后扩展数（默认 0，不扩展） */
        private int neighborNext = 0;
        /** LLM 生成的子查询数量（默认 1，单查询） */
        private int subQueryCount = 1;
        /** 角色 RAG 最终上下文最大字符数（默认 8000） */
        private int maxChars = 8000;
        /** 视角偏好词：命中这些词的 chunk 在排序时加权 */
        private List<String> focusKeywords;
        /** 视角提示：注入 LLM 查询生成阶段 */
        private String perspectiveHint;
    }

    // ==================== 奇葩说性格 ====================

    @Data
    public static class DebateConfig {
        private DebateHost host;
        private List<DebatePersonality> personalities;
    }

    @Data
    public static class DebateHost {
        private String key;
        private String name;
        private String title;
        private String color;
        private String icon;
        private String prompt;
        private String catchphrase;
        private RoleParams params;
    }

    @Data
    public static class DebatePersonality {
        private String key;
        private String name;
        private String title;
        private String color;
        private String icon;
        private String prompt;
        private String catchphrase;
        private RoleParams params;
    }
}
