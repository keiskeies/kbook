package com.kbook.config.aspect;

import com.kbook.common.util.CommonUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;

/**
 * 请求日志切面 — 自动记录标注了 @Operation 的 Controller 方法的入口和出口
 * <p>
 * 使用 @Tag(name="模块名") + @Operation(summary="操作名") 组合为日志标识，
 * 格式：模块名-操作名（如：图书-获取详情）。
 * <p>
 * 日志格式：
 * - 入口: 模块名-操作名 start-params: 参数摘要
 * - 出口: 模块名-操作名 end-timer: 耗时ms
 * - 异常: 模块名-操作名 error-timer: 耗时ms 异常信息
 */
@Aspect
@Component
public class RequestLogAspect {

    @Around("@annotation(io.swagger.v3.oas.annotations.Operation)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Logger log = LoggerFactory.getLogger(pjp.getTarget().getClass());

        String opName = resolveOpName(pjp);
        String params = extractParams(pjp);

        log.info("{} start-params: {}", opName, params);

        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            long elapsed = System.currentTimeMillis() - start;
            log.info("{} end-timer: {}ms", opName, elapsed);
            return result;
        } catch (Throwable ex) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("{} error-timer: {}ms {}", opName, elapsed, ex.getMessage());
            throw ex;
        }
    }

    /**
     * 解析操作名称并附带源码位置，格式: "模块名-操作名 (ControllerName.java:methodName)"
     * <p>
     * IDEA 控制台能自动识别 (Xxx.java:xxx) 模式生成可点击的源码链接,
     * 解决了 AOP 日志无法定位到原始方法的问题。
     */
    private String resolveOpName(ProceedingJoinPoint pjp) {
        MethodSignature ms = (MethodSignature) pjp.getSignature();
        Method method = ms.getMethod();

        Tag tag = pjp.getTarget().getClass().getAnnotation(Tag.class);
        Operation op = method.getAnnotation(Operation.class);

        String tagName = (tag != null && !tag.name().isEmpty()) ? tag.name() : null;
        String opSummary = (op != null && !op.summary().isEmpty()) ? op.summary() : null;

        String action;
        if (tagName != null && opSummary != null) {
            action = tagName + "-" + opSummary;
        } else if (opSummary != null) {
            action = opSummary;
        } else if (tagName != null) {
            action = tagName + "-" + ms.getMethod().getName();
        } else {
            action = ms.toShortString();
        }

        // 附加源码位置 (ClassName.java:line) 格式，IDEA 控制台自动识别为可点击链接
        Class<?> actualClass = ClassUtils.getUserClass(pjp.getTarget());
        int line = MethodLineNumberCache.getLineNumber(actualClass, method.getName(), method);
        action += " (" + actualClass.getSimpleName() + ".java:" + line + ")";
        return action;
    }


    /**
     * 提取方法参数摘要，过滤掉 request/response/file 等不可序列化参数
     */
    private String extractParams(ProceedingJoinPoint pjp) {
        Object[] args = pjp.getArgs();
        if (args == null || args.length == 0) return "";

        StringBuilder sb = new StringBuilder();
        for (Object arg : args) {
            if (arg instanceof HttpServletRequest || arg instanceof HttpServletResponse
                    || arg instanceof MultipartFile) {
                continue;
            }
            if (arg == null) continue;

            if (sb.length() > 0) sb.append(", ");
            String value = arg.toString();
            value = CommonUtils.truncateText(value, 80);
            sb.append(value);
        }

        if (sb.length() == 0) return "";
        String s = sb.toString();
        s = CommonUtils.truncateText(s, 300);
        return s;
    }

}
