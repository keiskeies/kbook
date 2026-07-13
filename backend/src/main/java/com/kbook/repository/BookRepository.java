package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.dto.book.BookProjection;
import com.kbook.entity.Book;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 图书数据访问层
 */
public interface BookRepository extends BaseRepository<Book, Long> {

    /**
     * 只更新 tocQualityScore 字段（避免全字段 save 重写 TEXT 大字段）
     * @param id 书籍 ID
     * @param score TOC 质量评分，null 表示清除
     */
    @Modifying
    @Query("UPDATE Book b SET b.tocQualityScore = :score WHERE b.id = :id")
    void updateTocQualityScore(@Param("id") Long id, @Param("score") Float score);


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


    List<Book> findAllByIdGreaterThan(Long idIsGreaterThan);

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
           "OR LOWER(b.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Book> searchBooks(@Param("keyword") String keyword,
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
     * 根据文件名+格式查找（用于去重，新格式：fileUrl 仅存文件名）
     */
    Optional<Book> findByFileUrlAndFormat(String fileUrl, String format);

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
     * 查找内容已向量化但模型标识与当前不一致的书籍（用于向量层一致性校验）
     */
    @Query("SELECT b FROM Book b WHERE b.contentEmbedded = true " +
            "AND (b.contentEmbeddingModel IS NULL OR b.contentEmbeddingModel <> :currentModel)")
    List<Book> findBooksNeedingContentRebuild(@Param("currentModel") String currentModel);

    /**
     * 统计内容已向量化的书籍（仅取 id/title/contentEmbeddingModel/contentEmbeddingDim 字段，轻量扫描）
     */
    @Query("SELECT b.id FROM Book b WHERE b.contentEmbedded = true")
    List<Long> findIdsByContentEmbeddedTrue();

    /**
     * 随机采样书籍（MySQL）
     */
    @Query(value = "SELECT * FROM books ORDER BY RAND() LIMIT :limit", nativeQuery = true)
    List<Book> findRandomBooks(@Param("limit") int limit);

    /**
     * 分页查询所有图书（按ID升序，用于批量处理）
     */
    Page<Book> findAllByOrderByIdAsc(Pageable pageable);

    /**
     * 只查询ID列表（用于需要遍历所有书籍但不需要完整实体的场景）
     */
    @Query("SELECT b.id FROM Book b ORDER BY b.id")
    List<Long> findAllIds();

    /**
     * 只查询ID和relevanceScores（用于维度统计，减少内存占用）
     */
    @Query("SELECT b.id, b.relevanceScores FROM Book b WHERE b.relevanceScores IS NOT NULL")
    List<Object[]> findAllRelevanceScores();

    /**
     * 分页查询投影（仅推荐所需字段，避免加载TEXT大字段）
     */
    @Query("SELECT new com.kbook.dto.book.BookProjection(b.id, b.title, b.author, b.coverUrl, b.format, b.fileSize, b.fileUrl, b.formatTags, b.rating, b.readCount, b.totalUnits, b.description, b.relevanceScores, b.createdAt, b.conceptTags, b.readerNeedTags, b.targetReaderTags, b.toc, b.ratingCount, b.dimensionRatingCount, b.contentEmbedded, b.updatedAt) FROM Book b ORDER BY b.id")
    Page<BookProjection> findAllProjectedByOrderByIdAsc(Pageable pageable);

    @Query("SELECT new com.kbook.dto.book.BookProjection(b.id, b.title, b.author, b.coverUrl, b.format, b.fileSize, b.fileUrl, b.formatTags, b.rating, b.readCount, b.totalUnits, b.description, b.relevanceScores, b.createdAt, b.conceptTags, b.readerNeedTags, b.targetReaderTags, b.toc, b.ratingCount, b.dimensionRatingCount, b.contentEmbedded, b.updatedAt) FROM Book b ORDER BY b.id")
    List<BookProjection> findAllProjectedByOrderByIdAsc();

    /**
     * 按ID查询投影
     */
    @Query("SELECT new com.kbook.dto.book.BookProjection(b.id, b.title, b.author, b.coverUrl, b.format, b.fileSize, b.fileUrl, b.formatTags, b.rating, b.readCount, b.totalUnits, b.description, b.relevanceScores, b.createdAt, b.conceptTags, b.readerNeedTags, b.targetReaderTags, b.toc, b.ratingCount, b.dimensionRatingCount, b.contentEmbedded, b.updatedAt) FROM Book b WHERE b.id = :id")
    Optional<BookProjection> findProjectedById(@Param("id") Long id);

    /**
     * 按ID集合批量查询投影
     */
    @Query("SELECT new com.kbook.dto.book.BookProjection(b.id, b.title, b.author, b.coverUrl, b.format, b.fileSize, b.fileUrl, b.formatTags, b.rating, b.readCount, b.totalUnits, b.description, b.relevanceScores, b.createdAt, b.conceptTags, b.readerNeedTags, b.targetReaderTags, b.toc, b.ratingCount, b.dimensionRatingCount, b.contentEmbedded, b.updatedAt) FROM Book b WHERE b.id IN :ids")
    List<BookProjection> findProjectedByIdIn(@Param("ids") Iterable<Long> ids);

    /**
     * 按阅读量降序查询投影
     */
    @Query("SELECT new com.kbook.dto.book.BookProjection(b.id, b.title, b.author, b.coverUrl, b.format, b.fileSize, b.fileUrl, b.formatTags, b.rating, b.readCount, b.totalUnits, b.description, b.relevanceScores, b.createdAt, b.conceptTags, b.readerNeedTags, b.targetReaderTags, b.toc, b.ratingCount, b.dimensionRatingCount, b.contentEmbedded, b.updatedAt) FROM Book b ORDER BY b.readCount DESC")
    Page<BookProjection> findAllProjectedByOrderByReadCountDesc(Pageable pageable);

    /**
     * 按评分降序查询投影
     */
    @Query("SELECT new com.kbook.dto.book.BookProjection(b.id, b.title, b.author, b.coverUrl, b.format, b.fileSize, b.fileUrl, b.formatTags, b.rating, b.readCount, b.totalUnits, b.description, b.relevanceScores, b.createdAt, b.conceptTags, b.readerNeedTags, b.targetReaderTags, b.toc, b.ratingCount, b.dimensionRatingCount, b.contentEmbedded, b.updatedAt) FROM Book b ORDER BY b.rating DESC")
    Page<BookProjection> findAllProjectedByOrderByRatingDesc(Pageable pageable);

    /**
     * 按创建时间降序查询投影
     */
    @Query("SELECT new com.kbook.dto.book.BookProjection(b.id, b.title, b.author, b.coverUrl, b.format, b.fileSize, b.fileUrl, b.formatTags, b.rating, b.readCount, b.totalUnits, b.description, b.relevanceScores, b.createdAt, b.conceptTags, b.readerNeedTags, b.targetReaderTags, b.toc, b.ratingCount, b.dimensionRatingCount, b.contentEmbedded, b.updatedAt) FROM Book b ORDER BY b.createdAt DESC")
    Page<BookProjection> findAllProjectedByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 按格式筛选投影
     */
    @Query("SELECT new com.kbook.dto.book.BookProjection(b.id, b.title, b.author, b.coverUrl, b.format, b.fileSize, b.fileUrl, b.formatTags, b.rating, b.readCount, b.totalUnits, b.description, b.relevanceScores, b.createdAt, b.conceptTags, b.readerNeedTags, b.targetReaderTags, b.toc, b.ratingCount, b.dimensionRatingCount, b.contentEmbedded, b.updatedAt) FROM Book b WHERE b.format = :format ORDER BY b.id")
    Page<BookProjection> findProjectedByFormat(@Param("format") String format, Pageable pageable);

    /**
     * 按标签分页查询投影
     */
    @Query("SELECT new com.kbook.dto.book.BookProjection(b.id, b.title, b.author, b.coverUrl, b.format, b.fileSize, b.fileUrl, b.formatTags, b.rating, b.readCount, b.totalUnits, b.description, b.relevanceScores, b.createdAt, b.conceptTags, b.readerNeedTags, b.targetReaderTags, b.toc, b.ratingCount, b.dimensionRatingCount, b.contentEmbedded, b.updatedAt) FROM Book b WHERE b.formatTags LIKE CONCAT('%', :tag, '%')")
    Page<BookProjection> findProjectedByTag(@Param("tag") String tag, Pageable pageable);

    /**
     * 搜索投影（标题/作者/简介模糊匹配）
     */
    @Query("SELECT new com.kbook.dto.book.BookProjection(b.id, b.title, b.author, b.coverUrl, b.format, b.fileSize, b.fileUrl, b.formatTags, b.rating, b.readCount, b.totalUnits, b.description, b.relevanceScores, b.createdAt, b.conceptTags, b.readerNeedTags, b.targetReaderTags, b.toc, b.ratingCount, b.dimensionRatingCount, b.contentEmbedded, b.updatedAt) FROM Book b WHERE " +
           "(:keyword IS NULL OR LOWER(b.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(b.author) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(b.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<BookProjection> searchProjectedBooks(@Param("keyword") String keyword,
                                              Pageable pageable);

    /**
     * 仅查询formatTags（用于标签统计）
     */
    @Query("SELECT b.formatTags FROM Book b WHERE b.formatTags IS NOT NULL")
    List<String> findAllFormatTags();

    @Query("SELECT b FROM Book b WHERE NOT EXISTS (SELECT 1 FROM BookSuggestedQuestion sq WHERE sq.bookId = b.id) ORDER BY b.rating DESC")
    List<Book> findBooksWithoutQuestions();

    // ==================== 统计查询（供 AI 管理员使用） ====================
    long countByFormat(String format);
    long countByRatingGreaterThanEqual(Double rating);
    long countByRatingBetween(Double min, Double max);
    long countByRatingLessThan(Double rating);
    long countByRatingIsNull();
}
