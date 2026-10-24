package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.Note;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 笔记数据访问层
 * <p>
 * 所有查询必须带 user_id 条件（笔记是隐私数据）；分页排序走 BaseRepository.query()
 */
public interface NoteRepository extends BaseRepository<Note, Long> {

    long countByUserIdAndDeletedAtIsNull(Long userId);

    /**
     * 在本人未删除的笔记里按标题/正文关键词搜索。
     * LIKE 足以支撑个人量级；后续量大再换 MySQL FULLTEXT ngram。
     */
    @Query("SELECT n FROM Note n WHERE n.userId = :userId AND n.deletedAt IS NULL "
            + "AND (LOWER(n.title) LIKE LOWER(CONCAT('%', :kw, '%')) "
            + "OR LOWER(n.contentMd) LIKE LOWER(CONCAT('%', :kw, '%')))")
    Page<Note> search(@Param("userId") Long userId, @Param("kw") String kw, Pageable pageable);
}
