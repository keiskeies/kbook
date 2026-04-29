package com.kbook.common.util;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扫描步骤计时器 — 统计图书录入各步骤的累计耗时和调用次数，计算平均值
 * <p>
 * 用法：
 * <pre>
 *   ScanStepTimer timer = new ScanStepTimer();
 *   timer.start("parse");
 *   // ... do work ...
 *   timer.end("parse");
 *   timer.logAverages(); // 打印各步骤平均耗时
 * </pre>
 */
@Slf4j
public class ScanStepTimer {

    /** 步骤名称 → 累计耗时(ms) */
    private final Map<String, Long> totalMillis = new ConcurrentHashMap<>();

    /** 步骤名称 → 调用次数 */
    private final Map<String, Long> counts = new ConcurrentHashMap<>();

    /** 步骤名称 → 当前计时起点 */
    private final ThreadLocal<Map<String, Long>> starts = ThreadLocal.withInitial(LinkedHashMap::new);

    /** 步骤的固定显示顺序 */
    private final String[] stepOrder;

    public ScanStepTimer(String... stepOrder) {
        this.stepOrder = stepOrder;
        for (String step : stepOrder) {
            totalMillis.put(step, 0L);
            counts.put(step, 0L);
        }
    }

    /** 记录步骤开始 */
    public void start(String step) {
        starts.get().put(step, System.currentTimeMillis());
    }

    /** 记录步骤结束，累加耗时 */
    public void end(String step) {
        Long startTime = starts.get().remove(step);
        if (startTime != null) {
            long elapsed = System.currentTimeMillis() - startTime;
            totalMillis.merge(step, elapsed, Long::sum);
            counts.merge(step, 1L, Long::sum);
        }
    }

    /** 打印各步骤平均耗时到日志 */
    public void logAverages() {
        StringBuilder sb = new StringBuilder("图书录入步骤平均耗时: ");
        for (String step : stepOrder) {
            long total = totalMillis.getOrDefault(step, 0L);
            long count = counts.getOrDefault(step, 0L);
            long avg = count > 0 ? total / count : 0;
            sb.append(step).append("=").append(avg).append("ms")
              .append("(").append(count).append("次) ");
        }
        log.info(sb.toString().trim());
    }

    /** 重置所有计时数据 */
    public void reset() {
        for (String step : stepOrder) {
            totalMillis.put(step, 0L);
            counts.put(step, 0L);
        }
        starts.remove();
    }
}
