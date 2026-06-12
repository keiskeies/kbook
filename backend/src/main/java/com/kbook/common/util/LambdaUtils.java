package com.kbook.common.util;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lambda 表达式工具类，用于提取 SFunction 的字段名
 */
public class LambdaUtils {

    private static final Map<SFunction<?, ?>, String> CACHE = new ConcurrentHashMap<>();

    /**
     * 从 SFunction 中提取字段名
     * <p>
     * 原理：Lambda 表达式编译后会生成一个实现 Serializable 的内部类，
     * 通过 readResolve 方法可以获取到 SerializedLambda，
     * 其中包含了方法名（如 "getId"），去掉 "get" 前缀并转为小写即为字段名。
     *
     * @param fn Lambda 表达式，如 Book::getId
     * @return 字段名，如 "id"
     */
    @SuppressWarnings("unchecked")
    public static <T> String resolve(SFunction<T, ?> fn) {
        return CACHE.computeIfAbsent(fn, f -> {
            try {
                Method writeReplace = f.getClass().getDeclaredMethod("writeReplace");
                writeReplace.setAccessible(true);
                SerializedLambda lambda = (SerializedLambda) writeReplace.invoke(f);
                String methodName = lambda.getImplMethodName();

                // get/is 开头的方法名转为字段名
                if (methodName.startsWith("get")) {
                    methodName = methodName.substring(3);
                } else if (methodName.startsWith("is")) {
                    methodName = methodName.substring(2);
                }

                // 首字母小写
                if (!methodName.isEmpty()) {
                    methodName = Character.toLowerCase(methodName.charAt(0)) + methodName.substring(1);
                }

                return methodName;
            } catch (Exception e) {
                throw new RuntimeException("无法解析 Lambda 表达式字段名", e);
            }
        });
    }
}
