package com.kbook.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 通知与邮件配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {

    /** 网站域名，用于生成邀请链接等 */
    private String baseUrl = "http://localhost:5173";

    /** 邀请配置 */
    private InvitationConfig invitation = new InvitationConfig();

    /** 书评回复通知阈值（逗号分隔） */
    private String commentReplyThresholds = "10,50,100";

    /** 书评点赞通知阈值（逗号分隔） */
    private String commentLikeThresholds = "10,50,100,500";

    /** 邮件发送是否启用 */
    private boolean sendEnabled = false;

    @Data
    public static class InvitationConfig {
        /** 邀请链接有效期（小时） */
        private int expireHours = 72;
    }
}
