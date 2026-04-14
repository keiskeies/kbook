package com.kbook.service;

import com.kbook.common.api.PageResult;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 榜单聚合服务 — Redis 缓存 + 定时刷新
 * <p>
 * 阅读榜和评分榜通过定时任务预计算，存入 Redis，接口直接读缓存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankService {

    private final BookRepository bookRepository;
    private final StringRedisTemplate redisTemplate;

    private static final String READ_RANK_KEY = "kbook:rank:read";
    private static final String RATING_RANK_KEY = "kbook:rank:rating";
    private static final String NEW_BOOKS_RANK_KEY = "kbook:rank:new";
    private static final long CACHE_TTL_HOURS = 2;

    /**
     * 获取阅读排行榜（优先读缓存）
     */
    public PageResult<Book> getReadRank(int page, int size) {
        log.debug("获取阅读排行榜: page={}, size={}", page, size);
        // 榜单始终从 DB 读取最新数据（定时任务刷新的是热门榜单缓存，此处直接查库）
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Book> pageData = bookRepository.findAllByOrderByReadCountDesc(pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 获取评分排行榜
     */
    public PageResult<Book> getRatingRank(int page, int size) {
        log.debug("获取评分排行榜: page={}, size={}", page, size);
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Book> pageData = bookRepository.findAllByOrderByRatingDesc(pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 获取新书榜（按创建时间倒序）
     */
    public PageResult<Book> getNewBooksRank(int page, int size) {
        log.debug("获取新书榜: page={}, size={}", page, size);
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Book> pageData = bookRepository.findAllByOrderByCreatedAtDesc(pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 定时刷新热门榜单缓存 — 每2小时
     */
    @Scheduled(fixedRate = 2 * 60 * 60 * 1000)
    public void refreshRankCache() {
        log.info("开始刷新榜单缓存...");
        try {
            // 阅读榜 TOP50
            Pageable top50 = PageRequest.of(0, 50);
            Page<Book> readRank = bookRepository.findAllByOrderByReadCountDesc(top50);
            String readJson = serializeBooks(readRank.getContent());
            redisTemplate.opsForValue().set(READ_RANK_KEY, readJson, CACHE_TTL_HOURS, TimeUnit.HOURS);

            // 评分榜 TOP50
            Page<Book> ratingRank = bookRepository.findAllByOrderByRatingDesc(top50);
            String ratingJson = serializeBooks(ratingRank.getContent());
            redisTemplate.opsForValue().set(RATING_RANK_KEY, ratingJson, CACHE_TTL_HOURS, TimeUnit.HOURS);

            // 新书榜 TOP50
            Page<Book> newBooks = bookRepository.findAllByOrderByCreatedAtDesc(top50);
            String newJson = serializeBooks(newBooks.getContent());
            redisTemplate.opsForValue().set(NEW_BOOKS_RANK_KEY, newJson, CACHE_TTL_HOURS, TimeUnit.HOURS);

            log.info("榜单缓存刷新完成: 阅读榜{}本, 评分榜{}本, 新书榜{}本",
                    readRank.getContent().size(), ratingRank.getContent().size(), newBooks.getContent().size());
        } catch (Exception e) {
            log.error("刷新榜单缓存失败", e);
        }
    }

    private String serializeBooks(java.util.List<Book> books) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < books.size(); i++) {
            Book b = books.get(i);
            if (i > 0) sb.append(",");
            sb.append(String.format(
                    "{\"id\":%d,\"title\":\"%s\",\"author\":\"%s\",\"format\":\"%s\",\"readCount\":%d,\"rating\":%.1f}",
                    b.getId(), escape(b.getTitle()), escape(b.getAuthor() != null ? b.getAuthor() : ""),
                    b.getFormat(), b.getReadCount(), b.getRating()));
        }
        sb.append("]");
        return sb.toString();
    }

    private String escape(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
