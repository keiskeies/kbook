package com.kbook.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 辩论深度增强配置属性。
 */
@Data
@Component
@ConfigurationProperties(prefix = "kbook.debate")
public class DebateProperties {

    /** 深度模式总开关；false 时所有发言/报告链路与改造前逐字节等价 */
    private boolean deepEnabled = false;
}
