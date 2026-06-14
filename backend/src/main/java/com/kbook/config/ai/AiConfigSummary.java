package com.kbook.config.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * AI 配置摘要 — 返回给管理端前端用于展示，不含完整 prompt
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiConfigSummary {

    private BookChatSummary bookChat;
    private RoundTableSummary roundTable;
    private DebateSummary debate;

    // ==================== 图书问答风格摘要 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BookChatSummary {
        private String defaultStyle;
        private List<StyleItem> styles;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StyleItem {
        private String key;
        private String name;
        private String title;
    }

    // ==================== 圆桌派角色摘要 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoundTableSummary {
        private HostItem host;
        private int maxRolesPerSession;
        private List<String> defaultSelectedKeys;
        private List<RoleItem> roles;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoleItem {
        private String key;
        private String name;
        private String title;
        private String group;
        private String color;
        private String icon;
        private AiConfig.RoleParams params;
        private String catchphrase;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HostItem {
        private String key;
        private String name;
        private String title;
        private String color;
        private String icon;
        private AiConfig.RoleParams params;
        private String catchphrase;
    }

    // ==================== 奇葩说性格摘要 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DebateSummary {
        private HostItem host;
        private List<PersonalityItem> personalities;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PersonalityItem {
        private String key;
        private String name;
        private String title;
        private String color;
        private String icon;
        private AiConfig.RoleParams params;
        private String catchphrase;
    }
}
