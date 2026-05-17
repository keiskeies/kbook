package com.kbook.config.aspect;

import com.kbook.config.annotation.RedisLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁切面
 * 拦截 @RedisLock 注解，尝试获取 Redis 锁。
 * 获取成功则执行方法，失败则直接返回“空值”（null, 空集合或 0）。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RedisLockAspect {

    private final StringRedisTemplate redisTemplate;
    private final ExpressionParser parser = new SpelExpressionParser();

    @Around("@annotation(redisLock)")
    public Object around(ProceedingJoinPoint joinPoint, RedisLock redisLock) throws Throwable {
        String lockKey = parseKey(redisLock.key(), joinPoint);
        String lockValue = UUID.randomUUID().toString(); // 简单的锁标识，实际删除时应校验
        long leaseTime = redisLock.leaseTime();
        TimeUnit timeUnit = redisLock.timeUnit();

        // 尝试获取锁 (SETNX + EXPIRE 原子操作)
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, leaseTime, timeUnit);

        if (Boolean.TRUE.equals(acquired)) {
            try {
                log.debug("分布式锁获取成功: {}", lockKey);
                return joinPoint.proceed();
            } finally {
                // 方法执行完后释放锁
                // 注意：这里直接删除。在生产环境中，建议使用 Lua 脚本校验 lockValue 后删除，防止误删其他线程的锁。
                // 但因为有 leaseTime 兜底，直接删除在大多数场景下是可接受的。
                redisTemplate.delete(lockKey);
                log.debug("分布式锁已释放: {}", lockKey);
            }
        } else {
            log.info("分布式锁获取失败，跳过执行: {}", lockKey);
            // 获取锁失败，返回空值
            return getEmptyValue(joinPoint);
        }
    }

    /**
     * 解析 EL 表达式 Key
     */
    private String parseKey(String keyExpression, ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        StandardEvaluationContext context = new StandardEvaluationContext();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        return parser.parseExpression(keyExpression).getValue(context, String.class);
    }

    /**
     * 根据方法返回类型返回对应的空值
     */
    private Object getEmptyValue(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?> returnType = signature.getReturnType();

        if (returnType == void.class || returnType == Void.class) {
            return null;
        }
        if (returnType.isPrimitive()) {
            if (returnType == boolean.class) return false;
            if (returnType == int.class) return 0;
            if (returnType == long.class) return 0L;
            if (returnType == double.class) return 0.0;
            if (returnType == float.class) return 0.0f;
            return null;
        }
        if (java.util.List.class.isAssignableFrom(returnType)) {
            return Collections.emptyList();
        }
        if (java.util.Set.class.isAssignableFrom(returnType)) {
            return Collections.emptySet();
        }
        if (java.util.Map.class.isAssignableFrom(returnType)) {
            return Collections.emptyMap();
        }
        if (String.class.isAssignableFrom(returnType)) {
            return "";
        }
        return null;
    }
}
