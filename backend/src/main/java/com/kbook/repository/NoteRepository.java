package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.dto.note.NoteBookVO;
import com.kbook.entity.Note;
import com.kbook.common.enums.NoteType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 笔记数据访问层
 * <p>
 * 所有查询必须带 user_id 条件（笔记是隐私数据）；分页排序走 BaseRepository.query()
 */
public interface NoteRepository extends BaseRepository<Note, Long> {

    long countByUserIdAndDeletedAtIsNull(Long userId);

    long countByUserIdAndDeletedAtIsNullAndOccurredAtGreaterThanEqual(Long userId, LocalDateTime since);

    long countByUserIdAndDeletedAtIsNullAndNoteType(Long userId, NoteType noteType);

    /**
     * 涉及的不同书籍数量
     */
    @Query("SELECT COUNT(DISTINCT n.bookId) FROM Note n "
            + "WHERE n.userId = :userId AND n.deletedAt IS NULL AND n.bookId IS NOT NULL")
    long countDistinctBooks(@Param("userId") Long userId);

    /**
     * 取一段时间内的笔记发生时间，用于计算连续记录天数（在内存里按天去重）
     */
    @Query("SELECT n.occurredAt FROM Note n "
            + "WHERE n.userId = :userId AND n.deletedAt IS NULL AND n.occurredAt >= :since")
    List<LocalDateTime> findOccurredAtSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /**
     * 取本人所有笔记的标签串（逗号分隔），在内存里聚合成标签云
     */
    @Query("SELECT n.tags FROM Note n "
            + "WHERE n.userId = :userId AND n.deletedAt IS NULL AND n.tags IS NOT NULL")
    List<String> findTagsByUserId(@Param("userId") Long userId);

    long countByUserIdAndDeletedAtIsNullAndFavoritedAtIsNotNull(Long userId);

    /**
     * 按书聚合笔记数量与最近记录时间，供「按书回顾」视图使用
     */
    @Query("SELECT new com.kbook.dto.note.NoteBookVO(n.bookId, MAX(n.bookTitle), MAX(n.bookCover), COUNT(n), MAX(n.occurredAt)) "
            + "FROM Note n WHERE n.userId = :userId AND n.deletedAt IS NULL AND n.bookId IS NOT NULL "
            + "GROUP BY n.bookId ORDER BY MAX(n.occurredAt) DESC")
    List<NoteBookVO> aggregateByBook(@Param("userId") Long userId);

    /**
     * 在本人未删除的笔记里按标题/正文关键词搜索。
     * LIKE 足以支撑个人量级；后续量大再换 MySQL FULLTEXT ngram。
     */
    @Query("SELECT n FROM Note n WHERE n.userId = :userId AND n.deletedAt IS NULL "
            + "AND (LOWER(n.title) LIKE LOWER(CONCAT('%', :kw, '%')) "
            + "OR LOWER(n.contentMd) LIKE LOWER(CONCAT('%', :kw, '%')))")
    Page<Note> search(@Param("userId") Long userId, @Param("kw") String kw, Pageable pageable);
}
