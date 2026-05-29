package com.kbook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 维度统计服务
 * <p>
 * 计算并缓存书籍相关度得分（relevanceScores）各维度的均值和标准差，
 * 用于推荐匹配度计算中的 Z-Score 标准化。每小时自动刷新一次。
 */
@Slf4j
@Service
public class DimensionStatsService {

    private final BookRepository bookRepository;
    private final ObjectMapper objectMapper;

    /** 各维度的均值 */
    private volatile Map<String, Double> dimensionMeans = new ConcurrentHashMap<>();
    /** 各维度的标准差 */
    private volatile Map<String, Double> dimensionStddevs = new ConcurrentHashMap<>();
    /** 数据是否已加载 */
    private volatile boolean loaded = false;

    public DimensionStatsService(BookRepository bookRepository, ObjectMapper objectMapper) {
        this.bookRepository = bookRepository;
        this.objectMapper = objectMapper;
    }

    /** 确保统计数据已加载（双重检查锁的懒加载模式） */
    public void ensureLoaded() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    refresh();
                }
            }
        }
    }

    /**
     * 刷新维度统计：遍历所有书籍的 relevanceScores，计算各维度均值和标准差
     * 使用 findAllRelevanceScores() 只查询需要的字段，减少内存占用
     */
    @Scheduled(fixedDelay = 3600_000)
    public void refresh() {
        try {
            long start = System.currentTimeMillis();
            List<Object[]> scoreRows = bookRepository.findAllRelevanceScores();

            Map<String, Double> sums = new HashMap<>();
            Map<String, Double> sumSquares = new HashMap<>();
            Map<String, Integer> counts = new HashMap<>();

            for (Object[] row : scoreRows) {
                String relevanceScores = (String) row[1];
                if (relevanceScores == null || relevanceScores.isBlank()) continue;
                try {
                    JsonNode scores = objectMapper.readTree(relevanceScores);
                    var iter = scores.fields();
                    while (iter.hasNext()) {
                        var entry = iter.next();
                        String key = entry.getKey();
                        double val = entry.getValue().asDouble();
                        sums.merge(key, val, Double::sum);
                        sumSquares.merge(key, val * val, Double::sum);
                        counts.merge(key, 1, Integer::sum);
                    }
                } catch (Exception e) {
                    log.debug("解析 relevanceScores 失败: {}", e.getMessage());
                }
            }

            Map<String, Double> means = new ConcurrentHashMap<>();
            Map<String, Double> stddevs = new ConcurrentHashMap<>();

            for (String key : sums.keySet()) {
                int n = counts.getOrDefault(key, 1);
                double mean = sums.get(key) / n;
                double variance = sumSquares.get(key) / n - mean * mean;
                double stddev = Math.sqrt(Math.max(0, variance));
                means.put(key, mean);
                stddevs.put(key, Math.max(stddev, 0.15));
            }

            this.dimensionMeans = means;
            this.dimensionStddevs = stddevs;
            this.loaded = true;

            long elapsed = System.currentTimeMillis() - start;
            log.info("维度统计刷新完成: {} 个维度, elapsed={}ms", means.size(), elapsed);
        } catch (Exception e) {
            log.error("维度统计刷新失败", e);
        }
    }

    /**
     * 获取指定维度的均值
     * @param dimensionKey 维度键名
     * @return 均值，未加载时默认0.5
     */
    public double getMean(String dimensionKey) {
        ensureLoaded();
        return dimensionMeans.getOrDefault(dimensionKey, 0.5);
    }

    /**
     * 获取指定维度的标准差
     * @param dimensionKey 维度键名
     * @return 标准差，未加载时默认0.15
     */
    public double getStddev(String dimensionKey) {
        ensureLoaded();
        return dimensionStddevs.getOrDefault(dimensionKey, 0.15);
    }

    /**
     * 计算指定维度的 Z-Score 标准分
     * @param dimensionKey 维度键名
     * @param rawScore 原始得分
     * @return Z-Score 值
     */
    public double getZScore(String dimensionKey, double rawScore) {
        double mean = getMean(dimensionKey);
        double stddev = getStddev(dimensionKey);
        return (rawScore - mean) / stddev;
    }
}
