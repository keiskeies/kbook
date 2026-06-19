package com.kbook.dto.roundtable;

import com.kbook.config.ai.AiConfig;
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

    /** 角色 Emoji 图标（前端展示用） */
    private String icon;

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

    /** TTS 音调 0.5~2.0 */
    private double pitch;

    /** TTS 语速 0.5~2.0 */
    private double rate;

    /**
     * 从外部配置构建视图对象（颜色/参数优先使用配置值）
     *
     * @param configRole 外部配置中的角色数据
     * @return 角色视图对象
     */
    public static RoleVO fromConfig(AiConfig.RoundTableRole configRole) {
        return RoleVO.builder()
                .key(configRole.getKey())
                .name(configRole.getName())
                .title(configRole.getTitle())
                .color(configRole.getColor())
                .icon(configRole.getIcon())
                .roleGroup(configRole.getGroup())
                .grabWeight(configRole.getParams().getGrabWeight())
                .verbosity(configRole.getParams().getVerbosity())
                .opinionated(configRole.getParams().getOpinionated())
                .challenge(configRole.getParams().getChallenge())
                .empathy(configRole.getParams().getEmpathy())
                .humor(configRole.getParams().getHumor())
                .domainRelevance(0)
                .languageStyle("")
                .pitch(configRole.getTts() != null ? configRole.getTts().getPitch() : 1.0)
                .rate(configRole.getTts() != null ? configRole.getTts().getRate() : 1.0)
                .build();
    }
}
