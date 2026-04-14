package com.kbook.repository;

import com.kbook.entity.Book;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 图书数据访问层
 */
public interface BookRepository extends JpaRepository<Book, Long> {

    List<Book> findByFormat(String format);

    List<Book> findAllByOrderByReadCountDesc();

    List<Book> findAllByOrderByRatingDesc();

    /**
     * 搜索图书（标题/作者模糊匹配）
     */
    @Query("SELECT b FROM Book b WHERE " +
           "(:keyword IS NULL OR LOWER(b.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(b.author) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
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
}
