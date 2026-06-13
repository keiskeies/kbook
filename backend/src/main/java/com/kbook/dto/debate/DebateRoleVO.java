package com.kbook.dto.debate;

import com.kbook.enums.DebateRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 辩论角色视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebateRoleVO {

    /** 角色键名（如 HOST、PRO_1、CON_1） */
    private String key;

    /** 角色中文名 */
    private String name;

    /** 角色称号 */
    private String title;

    /** 角色分组：HOST_GROUP / PRO_GROUP / CON_GROUP */
    private String roleGroup;

    /** 立场：NEUTRAL / PRO / CON */
    private String side;

    /** 角色金句 */
    private String catchphrase;

    /** 抢麦权重 1-10 */
    private int grabWeight;

    /** 话量 1-5 */
    private int verbosity;

    /** 主见程度 1-5 */
    private int opinionated;

    /** 挑战倾向 1-5 */
    private int challenge;

    /** 共情力 1-5 */
    private int empathy;

    /** 幽默感 1-5 */
    private int humor;

    /**
     * 从角色枚举构建视图对象
     */
    public static DebateRoleVO from(DebateRole role) {
        return DebateRoleVO.builder()
                .key(role.getKey())
                .name(role.getName())
                .title(role.getTitle())
                .roleGroup(role.getRoleGroup())
                .side(role.getSide())
                .catchphrase(role.getCatchphrase())
                .grabWeight(role.getGrabWeight())
                .verbosity(role.getVerbosity())
                .opinionated(role.getOpinionated())
                .challenge(role.getChallenge())
                .empathy(role.getEmpathy())
                .humor(role.getHumor())
                .build();
    }
}
