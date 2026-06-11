package com.kbook.config.aspect;

import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 方法行号缓存 — 通过 ASM 读取 .class 文件的 LineNumberTable，
 * 为 AOP 日志提供精确行号，使 IDEA 控制台可点击跳转到具体方法。
 * <p>
 * <b>性能特征：</b>
 * <ul>
 *   <li><b>按类解析，全量缓存</b> — 首次访问某类的任一方法时，一次 ASM 解析该类所有方法行号</li>
 *   <li><b>零运行时开销</b> — 缓存命中后仅为 {@code ConcurrentHashMap.get()}，~0.001ms</li>
 *   <li><b>类文件读取</b> — 由 OS Page Cache 缓存，首次读取 ~0.1-1ms</li>
 *   <li><b>典型项目</b> — 假设 50 个注解方法，均摊到首个请求 ~2-6ms，之后零影响</li>
 * </ul>
 * <p>
 * 依赖 Spring 内置的 repackaged ASM（spring-core），无需额外引入。
 */
public final class MethodLineNumberCache {

    private static final ConcurrentMap<String, Integer> cache = new ConcurrentHashMap<>();

    /** 防止对同一个类的并发重复解析 */
    private static final ConcurrentMap<String, Object> classLocks = new ConcurrentHashMap<>();

    /**
     * 获取方法声明的起始行号。
     *
     * @param clazz      方法所属类（非代理类，已通过 {@code ClassUtils.getUserClass} 解代理）
     * @param methodName 方法名
     * @param method     反射 Method 对象，用于计算 JVM 描述符
     * @return 起始行号，解析失败返回 1
     */
    public static int getLineNumber(Class<?> clazz, String methodName, Method method) {
        String descriptor = org.springframework.asm.Type.getMethodDescriptor(method);
        String key = clazz.getName() + "#" + methodName + descriptor;

        Integer line = cache.get(key);
        if (line != null) return line;

        // 类级别的锁，防止并发解析同一个类
        Object lock = classLocks.computeIfAbsent(clazz.getName(), k -> new Object());
        synchronized (lock) {
            // double-check
            line = cache.get(key);
            if (line != null) return line;
            resolveClass(clazz);
            classLocks.remove(clazz.getName());
        }

        return cache.getOrDefault(key, 1);
    }

    /**
     * 按类全量解析所有方法的起始行号，批量写入缓存。
     */
    private static void resolveClass(Class<?> clazz) {
        try {
            ClassReader reader = new ClassReader(clazz.getName());
            // 暂存行号：key = "方法名+描述符" → 行号
            Map<String, Integer> lines = new HashMap<>();
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                private String currentName;
                private String currentDesc;

                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                  String signature, String[] exceptions) {
                    currentName = name;
                    currentDesc = desc;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitLineNumber(int line, org.springframework.asm.Label start) {
                            // 每个方法只采第一个 visitLineNumber 即为声明行
                            String mKey = currentName + currentDesc;
                            if (!lines.containsKey(mKey)) {
                                lines.put(mKey, line);
                            }
                        }
                    };
                }
            }, 0);

            // 批量写入全局缓存
            String classPrefix = clazz.getName() + "#";
            for (Map.Entry<String, Integer> entry : lines.entrySet()) {
                cache.put(classPrefix + entry.getKey(), entry.getValue());
            }
        } catch (Exception ignored) {
            // 解析失败时兜底行号 1，由 caller 的 getOrDefault 处理
        }
    }
}
