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
