package com.kbook.test;

import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.recommend.BookDimensionScoreService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回填测试：将图书的 relevanceScores JSON 解析到 book_dimension_scores 表
 * <p>
 * book_dimension_scores 表在 {@link BookDimensionScoreService#initTable()} 启动时自动创建，
 * 但已有数据的旧表不会自动回填。运行此测试可将全部图书的 AI 维度得分展开到单独的维度列中，
 * 供 SQL 批量评分使用（替代 Java parallelStream 的慢速路径）。
 * <p>
 * 运行方式：在 IDE 或 Maven 中直接运行此测试方法即可。
 * 注意：测试会修改数据库（写入 book_dimension_scores 表），不会回滚。
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("dev")
public class BookDimensionScoreBackfillTest {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private BookDimensionScoreService dimensionScoreService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 全量回填所有图书的 relevanceScores 到 book_dimension_scores
     * <p>
     * 流程：
     * 1. 清空 book_dimension_scores 表（防止重复数据）
     * 2. 加载所有 relevanceScores 非空的图书
     * 3. 逐本解析 JSON 并 REPLACE INTO 维度列
     * 4. 验证行数匹配
     */
    @Test
    public void backfillAllDimensionScores() {
        // 1. 清空表，确保从干净状态开始
        jdbcTemplate.execute("TRUNCATE TABLE book_dimension_scores");
        log.info("已清空 book_dimension_scores 表");

        // 2. 统计有 relevanceScores 的图书数
        List<Object[]> scoreEntries = bookRepository.findAllRelevanceScores();
        int expectedCount = scoreEntries.size();
        log.info("有 relevanceScores 的图书共 {} 本", expectedCount);

        if (expectedCount == 0) {
            log.warn("没有图书包含 relevanceScores，跳过回填");
            return;
        }

        // 3. 加载全部图书并回填
        List<Book> allBooks = bookRepository.findAll();
        long start = System.currentTimeMillis();

        dimensionScoreService.syncAll(allBooks);

        long elapsed = System.currentTimeMillis() - start;

        // 4. 验证行数
        Integer actualCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM book_dimension_scores", Integer.class);

        log.info("回填完成: book_dimension_scores 表共 {} 行, 预期 {} 行, 耗时 {}ms",
                actualCount, expectedCount, elapsed);

        assertNotNull(actualCount, "book_dimension_scores 表查询结果不应为 null");
        // 允许少量解析失败（个别 book 的 JSON 可能格式异常）
        assertTrue(actualCount >= expectedCount * 0.95,
                "回填行数 (" + actualCount + ") 应达到预期的 95% (" + expectedCount + ")");
    }
}
