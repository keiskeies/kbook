package com.kbook.test;

import com.kbook.config.properties.BookStorageProperties;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.BookSearchService;
import com.kbook.service.EmbeddingService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@SpringBootTest
@ActiveProfiles("test")
public class BookDeleteTool {

    private static final Logger log = LoggerFactory.getLogger(BookDeleteTool.class);
    private static final String EPUB_TEMP_DIR = "G:\\图书\\epubTemp";

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private BookSearchService bookSearchService;

    @Autowired
    private BookStorageProperties storageProps;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 使用前请先修改下方 ids 字符串为要删除的图书ID（英文逗号隔开）
     */
    @Test
    public void deleteBooks() {
        String ids = "1,2,3";
        deleteByIds(ids);
    }

    private void deleteByIds(String idsStr) {
        List<Long> ids = Arrays.stream(idsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .toList();

        System.out.println("========================================");
        System.out.println("批量删除图书工具");
        System.out.println("========================================");
        System.out.println("待删除ID: " + ids);
        System.out.println();

        int success = 0, failed = 0;
        for (Long id : ids) {
            try {
                deleteSingleBook(id);
                System.out.printf("  ✓ id=%d 删除成功%n", id);
                success++;
            } catch (Exception e) {
                System.err.printf("  ✗ id=%d 删除失败: %s%n", id, e.getMessage());
                log.error("删除图书失败 id={}", id, e);
                failed++;
            }
        }

        clearRedisCache();

        System.out.println();
        System.out.println("========================================");
        System.out.printf("删除完成: 成功=%d, 失败=%d%n", success, failed);
        System.out.println("========================================");
    }

    private void deleteSingleBook(Long id) {
        Book book = bookRepository.findById(id).orElse(null);
        if (book == null) {
            System.out.printf("  图书 id=%d 在数据库中不存在，仍将清理 ES/Qdrant/Redis%n", id);
        }

        Long bookId = id;

        // 1. 删除封面文件
        if (book != null && book.getCoverUrl() != null && !book.getCoverUrl().isBlank()) {
            deleteCoverFile(book.getCoverUrl());
        }

        // 2. 删除 Qdrant 向量
        embeddingService.removeBookEmbedding(bookId);
        embeddingService.removeContentEmbedding(bookId);

        // 3. 删除数据库关联记录 + 主记录（事务内）
        transactionTemplate.executeWithoutResult(status -> {
            // 评论的点赞和收藏
            entityManager.createNativeQuery(
                    "DELETE FROM comment_likes WHERE comment_id IN (SELECT id FROM comments WHERE book_id = :bookId)")
                    .setParameter("bookId", bookId)
                    .executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM comment_favorites WHERE comment_id IN (SELECT id FROM comments WHERE book_id = :bookId)")
                    .setParameter("bookId", bookId)
                    .executeUpdate();

            // 评论
            entityManager.createNativeQuery("DELETE FROM comments WHERE book_id = :bookId")
                    .setParameter("bookId", bookId)
                    .executeUpdate();

            // 阅读相关
            entityManager.createNativeQuery("DELETE FROM reading_progress WHERE book_id = :bookId")
                    .setParameter("bookId", bookId)
                    .executeUpdate();
            entityManager.createNativeQuery("DELETE FROM user_read_history WHERE book_id = :bookId")
                    .setParameter("bookId", bookId)
                    .executeUpdate();

            // 书架和回收站
            entityManager.createNativeQuery("DELETE FROM bookshelf WHERE book_id = :bookId")
                    .setParameter("bookId", bookId)
                    .executeUpdate();
            entityManager.createNativeQuery("DELETE FROM book_trash WHERE book_id = :bookId")
                    .setParameter("bookId", bookId)
                    .executeUpdate();

            // AI 对话
            entityManager.createNativeQuery("DELETE FROM ai_conversations WHERE book_id = :bookId")
                    .setParameter("bookId", bookId)
                    .executeUpdate();
            entityManager.createNativeQuery("DELETE FROM ai_sessions WHERE book_id = :bookId")
                    .setParameter("bookId", bookId)
                    .executeUpdate();

            // 推荐和通知
            entityManager.createNativeQuery("DELETE FROM recommend_feedback_event WHERE book_id = :bookId")
                    .setParameter("bookId", bookId)
                    .executeUpdate();
            entityManager.createNativeQuery("DELETE FROM notifications WHERE book_id = :bookId")
                    .setParameter("bookId", bookId)
                    .executeUpdate();

            // 建议问题
            entityManager.createNativeQuery("DELETE FROM book_suggested_questions WHERE book_id = :bookId")
                    .setParameter("bookId", bookId)
                    .executeUpdate();

            // 图书主记录
            entityManager.createNativeQuery("DELETE FROM books WHERE id = :bookId")
                    .setParameter("bookId", bookId)
                    .executeUpdate();
        });

        // 4. 删除 ES 索引
        bookSearchService.deleteIndex(bookId);

        // 5. 移动图书文件到 epubTemp
        if (book != null && book.getFileUrl() != null && !book.getFileUrl().isBlank()) {
            moveBookFile(book.getFileUrl());
        }
    }

    private void deleteCoverFile(String coverUrl) {
        try {
            String filename = coverUrl.substring(coverUrl.lastIndexOf('/') + 1);
            Path imagePath = Paths.get(storageProps.getCoverPath()).resolve(filename);
            if (Files.exists(imagePath)) {
                Files.delete(imagePath);
                System.out.printf("  删除封面: %s%n", imagePath);
            }
        } catch (Exception e) {
            System.err.printf("  删除封面失败 %s: %s%n", coverUrl, e.getMessage());
        }
    }

    private void moveBookFile(String fileUrl) {
        try {
            Path source = Paths.get(fileUrl);
            if (!Files.exists(source)) {
                System.out.printf("  图书文件不存在: %s%n", source);
                return;
            }
            Path tempDir = Paths.get(EPUB_TEMP_DIR);
            Files.createDirectories(tempDir);
            Path target = tempDir.resolve(source.getFileName());
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            System.out.printf("  移动文件: %s → %s%n", source.getFileName(), target);
        } catch (Exception e) {
            System.err.printf("  移动文件失败 %s: %s%n", fileUrl, e.getMessage());
        }
    }

    private void clearRedisCache() {
        try {
            Set<String> keys = redisTemplate.keys("kbook:*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                System.out.printf("  清除 kbook:* 缓存: %d 个 key%n", keys.size());
            }
            Set<String> bookKeys = redisTemplate.keys("book:*");
            if (bookKeys != null && !bookKeys.isEmpty()) {
                redisTemplate.delete(bookKeys);
                System.out.printf("  清除 book:* 缓存: %d 个 key%n", bookKeys.size());
            }
        } catch (Exception e) {
            System.err.println("  清除Redis缓存失败: " + e.getMessage());
        }
    }
}
