package com.kbook.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 匹配度得分缓存服务
 * <p>
 * 缓存结构：
 * 1. 主缓存 (Hash): match:scores:{userId} -> {bookId: score}
 * 2. 反向索引 (Set): match:ref:book:{bookId} -> {userId1, userId2...}
 * <p>
 * 优势：支持按用户维度批量读取/清除，同时支持按图书维度精确反向清除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchScoreCacheService {

    private final StringRedisTemplate redisTemplate;
    private static final long CACHE_TTL_MINUTES = 120;

    /**
     * 批量获取缓存的匹配分
     * @return Map<bookId, score>，仅包含已缓存的数据
     */
    public Map<String, Double> getScores(Long userId, List<String> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) return Collections.emptyMap();
        
        String key = "match:scores:" + userId;
        try {
            List<Object> values = redisTemplate.opsForHash().multiGet(key, new java.util.ArrayList<>(bookIds));
            
            Map<String, Double> cached = new HashMap<>();
            for (int i = 0; i < bookIds.size(); i++) {
                Object val = values.get(i);
                if (val != null) {
                    try {
                        cached.put(bookIds.get(i), Double.parseDouble(val.toString()));
                    } catch (NumberFormatException ignored) {}
                }
            }
            return cached;
        } catch (Exception e) {
            log.warn("获取匹配分缓存失败: userId={}", userId, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 将新计算的匹配分写入缓存，并维护反向索引
     */
    public void putScores(Long userId, Map<String, Double> scores) {
        if (scores == null || scores.isEmpty()) return;
        
        String userKey = "match:scores:" + userId;
        Map<String, String> strScores = scores.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue())));
        
        try {
            redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                // 写入主缓存
                connection.hMSet(userKey.getBytes(), strScores.entrySet().stream()
                        .collect(Collectors.toMap(e -> e.getKey().getBytes(), e -> e.getValue().getBytes())));
                connection.expire(userKey.getBytes(), CACHE_TTL_MINUTES * 60);
                
                // 维护反向索引
                for (String bookId : scores.keySet()) {
                    String bookKey = "match:ref:book:" + bookId;
                    connection.sAdd(bookKey.getBytes(), String.valueOf(userId).getBytes());
                    connection.expire(bookKey.getBytes(), CACHE_TTL_MINUTES * 60);
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("写入匹配分缓存失败: userId={}", userId, e);
        }
    }

    /**
     * 清除指定用户的所有匹配分缓存（用户画像更新时调用）
     */
    public void evictUser(Long userId) {
        String userKey = "match:scores:" + userId;
        try {
            // 获取该用户缓存的所有 bookId 用于清理反向索引
            Set<Object> bookIds = redisTemplate.opsForHash().keys(userKey);
            
            if (bookIds != null && !bookIds.isEmpty()) {
                redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    // 清理反向索引
                    for (Object bookIdObj : bookIds) {
                        String bookKey = "match:ref:book:" + bookIdObj;
                        connection.sRem(bookKey.getBytes(), String.valueOf(userId).getBytes());
                    }
                    // 删除主缓存
                    connection.del(userKey.getBytes());
                    return null;
                });
            }
        } catch (Exception e) {
            log.warn("清除用户匹配分缓存失败: userId={}", userId, e);
        }
    }

    /**
     * 清除指定图书的所有用户匹配分缓存（图书更新时调用）
     */
    public void evictBook(Long bookId) {
        String bookKey = "match:ref:book:" + bookId;
        try {
            Set<String> userIds = redisTemplate.opsForSet().members(bookKey);
            
            if (userIds != null && !userIds.isEmpty()) {
                redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    for (Object uidObj : userIds) {
                        String userKey = "match:scores:" + uidObj;
                        connection.hDel(userKey.getBytes(), String.valueOf(bookId).getBytes());
                    }
                    connection.del(bookKey.getBytes());
                    return null;
                });
            }
        } catch (Exception e) {
            log.warn("清除图书匹配分缓存失败: bookId={}", bookId, e);
        }
    }
}
