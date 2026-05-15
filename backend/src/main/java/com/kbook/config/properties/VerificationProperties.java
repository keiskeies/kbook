package com.kbook.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 验证码配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "verification")
public class VerificationProperties {

    /** 验证码长度 */
    private int codeLength = 6;

    /** 验证码有效期（分钟） */
    private int expireMinutes = 5;

    /** 发送限频间隔（秒） */
    private int rateLimitSeconds = 60;

    /** 每日发送上限 */
    private int dailyLimit = 10;
}
