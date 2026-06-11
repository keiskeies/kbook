package com.kbook.config.aspect;

import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

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
     * 解析操作名称并附带源码位置，格式: "模块名-操作名 (ClassName.java:line)"
     * <p>
     * IDEA 控制台自动识别 (Xxx.java:数字) 格式为可点击的源码链接,
     * 行号通过 ASM 解析 .class 文件的 LineNumberTable 获取。
     */
    private String resolveAction(ProceedingJoinPoint pjp) {
        MethodSignature ms = (MethodSignature) pjp.getSignature();
        Method method = ms.getMethod();

        LogModule lm = pjp.getTarget().getClass().getAnnotation(LogModule.class);
        LogAction la = method.getAnnotation(LogAction.class);

        String moduleName = (lm != null && !lm.value().isEmpty()) ? lm.value() : null;
        String opName = (la != null && !la.value().isEmpty()) ? la.value() : null;

        String action;
        if (moduleName != null && opName != null) {
            action = moduleName + "-" + opName;
        } else if (opName != null) {
            action = opName;
        } else if (moduleName != null) {
            action = moduleName + "-" + ms.getMethod().getName();
        } else {
            action = ms.toShortString();
        }

        // 附加源码位置 (ClassName.java:line) 格式，IDEA 控制台自动识别为可点击链接
        Class<?> actualClass = ClassUtils.getUserClass(pjp.getTarget());
        int line = MethodLineNumberCache.getLineNumber(actualClass, method.getName(), method);
        action += " (" + actualClass.getSimpleName() + ".java:" + line + ")";
        return action;
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
