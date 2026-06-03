package com.kbook.config.aspect;

import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Service 日志切面 — 记录标注了 @LogAction 的方法
 * <p>
 * 使用 @LogModule(value="模块名") + @LogAction(value="操作名") 组合为日志标识，
 * 格式：模块名-操作名（如：图书-保存）。
 * <p>
 * 日志格式：
 * - 入口: 模块名-操作名 start-params: 参数摘要
 * - 出口: 模块名-操作名 end-timer: 耗时ms
 * - 异常: 模块名-操作名 error-timer: 耗时ms 异常信息
 */
@Aspect
@Component
public class ServiceLogAspect {

    @Around("@annotation(com.kbook.config.annotation.LogAction)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Logger log = LoggerFactory.getLogger(pjp.getTarget().getClass());

        String action = resolveAction(pjp);
        String params = extractParams(pjp);

        log.debug("{} start-params: {}", action, params);

        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            long elapsed = System.currentTimeMillis() - start;
            log.debug("{} end-timer: {}ms", action, elapsed);
            return result;
        } catch (Throwable ex) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("{} error-timer: {}ms {}", action, elapsed, ex.getMessage());
            throw ex;
        }
    }

    /**
     * 解析操作名称：@LogModule(value) + @LogAction(value) → "模块名-操作名"
     * 缺少任一注解时回退到方法签名
     */
    private String resolveAction(ProceedingJoinPoint pjp) {
        MethodSignature ms = (MethodSignature) pjp.getSignature();
        Method method = ms.getMethod();

        LogModule lm = pjp.getTarget().getClass().getAnnotation(LogModule.class);
        LogAction la = method.getAnnotation(LogAction.class);

        String moduleName = (lm != null && !lm.value().isEmpty()) ? lm.value() : null;
        String opName = (la != null && !la.value().isEmpty()) ? la.value() : null;

        if (moduleName != null && opName != null) {
            return moduleName + "-" + opName;
        }
        if (opName != null) {
            return opName;
        }
        if (moduleName != null) {
            return moduleName + "-" + ms.getMethod().getName();
        }
        return ms.toShortString();
    }

    private String extractParams(ProceedingJoinPoint pjp) {
        Object[] args = pjp.getArgs();
        if (args == null || args.length == 0) return "";

        StringBuilder sb = new StringBuilder();
        for (Object arg : args) {
            if (arg == null) continue;
            if (sb.length() > 0) sb.append(", ");
            String value = arg.toString();
            if (value.length() > 80) value = value.substring(0, 80) + "...";
            sb.append(value);
        }

        if (sb.length() == 0) return "";
        String s = sb.toString();
        if (s.length() > 300) s = s.substring(0, 300) + "...";
        return s;
    }
}
