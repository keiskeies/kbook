package com.kbook.repository;

import com.kbook.entity.Book;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 图书数据访问层
 */
public interface BookRepository extends JpaRepository<Book, Long> {

    /**
     * 按格式查询图书列表
     */
    List<Book> findByFormat(String format);

    /**
     * 按阅读量降序查询所有图书
     */
    List<Book> findAllByOrderByReadCountDesc();

    /**
     * 按评分降序查询所有图书
     */
    List<Book> findAllByOrderByRatingDesc();

    /**
     * 根据封面URL集合批量查询图书
     */
    List<Book> findAllByCoverUrlIn(Collection<String> coverUrls);

    /**
     * 搜索图书（标题/作者/简介模糊匹配，标题优先级最高）
     */
    @Query("SELECT b FROM Book b WHERE " +
           "(:keyword IS NULL OR LOWER(b.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(b.author) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(b.description) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:format IS NULL OR b.format = :format)")
    Page<Book> searchBooks(@Param("keyword") String keyword,
                           @Param("format") String format,
                           Pageable pageable);

    /**
     * 按格式筛选分页
     */
    Page<Book> findByFormat(String format, Pageable pageable);

    /**
     * 按格式标签搜索
     */
    @Query("SELECT b FROM Book b WHERE b.formatTags LIKE CONCAT('%', :tag, '%')")
    List<Book> findByFormatTag(@Param("tag") String tag);

    /**
     * 阅读排行
     */
    Page<Book> findAllByOrderByReadCountDesc(Pageable pageable);

    /**
     * 评分排行
     */
    Page<Book> findAllByOrderByRatingDesc(Pageable pageable);

    /**
     * 新书榜（按创建时间倒序）
     */
    Page<Book> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 根据文件路径查找（用于去重）
     */
    Optional<Book> findByFileUrl(String fileUrl);

    /**
     * 根据封面URL查找（用于封面回退查找）
     */
    Optional<Book> findByCoverUrl(String coverUrl);

    /**
     * 根据作者名查找所有书籍
     */
    List<Book> findByAuthor(String author);

    /**
     * 根据书名查找所有书籍（用于合并同名不同格式的书籍）
     */
    List<Book> findByTitle(String title);

    /**
     * 根据标签查找书籍（用于查找同类书籍）
     */
    @Query("SELECT b FROM Book b WHERE b.formatTags LIKE CONCAT('%', :tag, '%')")
    List<Book> findByTag(@Param("tag") String tag);

    /**
     * 按标签分页查找书籍
     */
    @Query("SELECT b FROM Book b WHERE b.formatTags LIKE CONCAT('%', :tag, '%')")
    Page<Book> findByTag(@Param("tag") String tag, Pageable pageable);

    /**
     * 查询评分大于指定值的图书
     */
    @Query("SELECT b FROM Book b WHERE b.rating > :minRating")
    List<Book> findByRatingGreaterThan(@Param("minRating") Double minRating);

    /**
     * 统计已向量化内容的图书数量
     */
    long countByContentEmbeddedTrue();

    /**
     * 随机采样书籍（MySQL）
     */
    @Query(value = "SELECT * FROM books ORDER BY RAND() LIMIT :limit", nativeQuery = true)
    List<Book> findRandomBooks(@Param("limit") int limit);
}
