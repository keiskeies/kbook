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

@Slf4j
@Service
public class DimensionStatsService {

    private final BookRepository bookRepository;
    private final ObjectMapper objectMapper;

    private volatile Map<String, Double> dimensionMeans = new ConcurrentHashMap<>();
    private volatile Map<String, Double> dimensionStddevs = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    public DimensionStatsService(BookRepository bookRepository, ObjectMapper objectMapper) {
        this.bookRepository = bookRepository;
        this.objectMapper = objectMapper;
    }

    public void ensureLoaded() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    refresh();
                }
            }
        }
    }

    @Scheduled(fixedDelay = 3600_000)
    public void refresh() {
        try {
            long start = System.currentTimeMillis();
            List<Book> allBooks = bookRepository.findAll();

            Map<String, Double> sums = new HashMap<>();
            Map<String, Double> sumSquares = new HashMap<>();
            Map<String, Integer> counts = new HashMap<>();

            for (Book book : allBooks) {
                if (book.getRelevanceScores() == null || book.getRelevanceScores().isBlank()) continue;
                try {
                    JsonNode scores = objectMapper.readTree(book.getRelevanceScores());
                    var iter = scores.fields();
                    while (iter.hasNext()) {
                        var entry = iter.next();
                        String key = entry.getKey();
                        double val = entry.getValue().asDouble();
                        sums.merge(key, val, Double::sum);
                        sumSquares.merge(key, val * val, Double::sum);
                        counts.merge(key, 1, Integer::sum);
                    }
                } catch (Exception ignored) {}
            }

            Map<String, Double> means = new ConcurrentHashMap<>();
            Map<String, Double> stddevs = new ConcurrentHashMap<>();

            for (String key : sums.keySet()) {
                int n = counts.getOrDefault(key, 1);
                double mean = sums.get(key) / n;
                double variance = sumSquares.get(key) / n - mean * mean;
                double stddev = Math.sqrt(Math.max(0, variance));
                means.put(key, mean);
                stddevs.put(key, stddev > 0.001 ? stddev : 0.001);
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

    public double getMean(String dimensionKey) {
        ensureLoaded();
        return dimensionMeans.getOrDefault(dimensionKey, 0.5);
    }

    public double getStddev(String dimensionKey) {
        ensureLoaded();
        return dimensionStddevs.getOrDefault(dimensionKey, 0.15);
    }

    public double getZScore(String dimensionKey, double rawScore) {
        double mean = getMean(dimensionKey);
        double stddev = getStddev(dimensionKey);
        return (rawScore - mean) / stddev;
    }
}
