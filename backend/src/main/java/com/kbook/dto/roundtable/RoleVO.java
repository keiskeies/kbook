package com.kbook.dto.roundtable;

import com.kbook.enums.RoundTableRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 圆桌派角色视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleVO {

    /** 角色键名（英文标识，如 HOST、PHILOSOPHER） */
    private String key;

    /** 角色中文名（如"主持人"、"哲学家"） */
    private String name;

    /** 角色称号/头衔（如"圆桌派主持人"、"思辨者"） */
    private String title;

    /** 角色代表颜色（前端展示用） */
    private String color;

    /** 角色分组（CORE/BUSINESS/ART/LIFE/TECH/SOCIAL） */
    private String roleGroup;

    /** 抢麦权重 1-10（越高越容易抢到话筒） */
    private int grabWeight;

    /** 话量 1-5（越高说话越多） */
    private int verbosity;

    /** 主见程度 1-5（越高越坚持自己的立场） */
    private int opinionated;

    /** 挑战倾向 1-5（越高越喜欢质疑和反驳） */
    private int challenge;

    /** 共情力 1-5（越高越善于理解他人和情感共鸣） */
    private int empathy;

    /** 幽默感 1-5（越高越善于调侃和轻松化） */
    private int humor;

    /** 专业相关度 0-10（运行时由 LLM 根据书籍内容动态赋值，不存储在枚举中） */
    private int domainRelevance;

    /** 语言风格（LLM 动态生成，描述该角色在讨论中应使用的语言风格） */
    private String languageStyle;

    /** 是否默认选中（后端根据 LLM 推荐标记，前端据此初始化勾选状态） */
    private boolean selected;

    /**
     * 从角色枚举构建视图对象
     *
     * @param role 角色枚举
     * @return 角色视图对象
     */
    public static RoleVO from(RoundTableRole role) {
        return RoleVO.builder()
                .key(role.getKey())
                .name(role.getName())
                .title(role.getTitle())
                .color(getColorForKey(role.getKey()))
                .roleGroup(role.getRoleGroup())
                .grabWeight(role.getGrabWeight())
                .verbosity(role.getVerbosity())
                .opinionated(role.getOpinionated())
                .challenge(role.getChallenge())
                .empathy(role.getEmpathy())
                .humor(role.getHumor())
                .domainRelevance(0) // 运行时由 LLM 动态赋值
                .languageStyle("")
                .build();
    }

    /**
     * 为角色分配固定颜色
     */
    private static String getColorForKey(String key) {
        return switch (key) {
            // CORE
            case "HOST" -> "#8B5CF6";
            case "PHILOSOPHER" -> "#3B82F6";
            case "PSYCHOLOGIST" -> "#10B981";
            case "SOCIOLOGIST" -> "#F59E0B";
            case "SCIENTIST" -> "#6366F1";
            case "HISTORIAN" -> "#F97316";
            case "CRITIC" -> "#14B8A6";
            case "EDUCATOR" -> "#0284C7";
            case "STUDENT" -> "#A78BFA";
            case "WRITER" -> "#38BDF8";
            case "COMEDIAN" -> "#FBBF24";
            case "JOURNALIST" -> "#0EA5E9";
            // ART
            case "ACTOR" -> "#FB923C";
            case "DIRECTOR" -> "#818CF8";
            case "ARTIST" -> "#D946EF";
            case "MUSICIAN" -> "#7C3AED";
            case "POET" -> "#BE185D";
            case "TRANSLATOR" -> "#0891B2";
            // BUSINESS
            case "ENTREPRENEUR" -> "#F43F5E";
            case "INVESTOR" -> "#EAB308";
            case "ECONOMIST" -> "#16A34A";
            case "STRATEGIST" -> "#374151";
            case "LAWYER" -> "#7C3AED";
            // LIFE
            case "DOCTOR" -> "#059669";
            case "FARMER" -> "#92400E";
            case "FIREFIGHTER" -> "#DC2626";
            case "NURSE" -> "#0891B2";
            case "MEDITATION_TEACHER" -> "#6D28D9";
            case "PARENT" -> "#DB2777";
            case "TRAVELER" -> "#0369A1";
            // TECH
            case "TECH_EXPERT" -> "#475569";
            case "ENGINEER" -> "#0D9488";
            case "EDITOR" -> "#CA8A04";
            case "BOOK_REVIEWER" -> "#EA580C";
            // SOCIAL
            case "DIPLOMAT" -> "#2563EB";
            case "LIBRARIAN" -> "#4338CA";
            case "SOCIAL_WORKER" -> "#059669";
            case "SPORTS_COACH" -> "#EA580C";
            case "ANTHROPOLOGIST" -> "#1E40AF";
            case "FEMINIST" -> "#DB2777";
            case "ECOLOGIST" -> "#0F766E";
            default -> "#6B7280";
        };
    }
}
