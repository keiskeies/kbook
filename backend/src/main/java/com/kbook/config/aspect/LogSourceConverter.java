package com.kbook.config.aspect;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.slf4j.MDC;

/**
 * Logback 自定义转换词 — 从 MDC 读取源码位置，使 IDEA 控制台可点击跳转。
 * <p>
 * 用法：
 * <ol>
 *   <li>在 AOP 切面中设置 {@code MDC.put("sourceLocation", "BookController.java:42")}</li>
 *   <li>logback pattern 中引用 {@code %sourceLoc}</li>
 * </ol>
 * 注册方式：在 {@code logback-spring.xml} 中 {@code <conversionRule conversionWord="sourceLoc"
 * converterClass="com.kbook.config.aspect.LogSourceConverter"/>}
 */
public class LogSourceConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        // 如果 MDC 中有 sourceLocation，优先使用（由 AOP 设置，可包含精确行号）
        String location = event.getMDCPropertyMap().get("sourceLocation");
        if (location != null) {
            return location;
        }
        // 兜底：从 caller data 提取（仍然指向 Aspect 自身，仅作为 fallback）
        StackTraceElement[] cda = event.getCallerData();
        if (cda != null && cda.length > 0) {
            StackTraceElement ste = cda[0];
            return ste.getFileName() + ":" + ste.getLineNumber();
        }
        return "?:?";
    }
}
