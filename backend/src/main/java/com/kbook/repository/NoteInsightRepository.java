package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.NoteInsight;

import java.util.List;

/**
 * 笔记成长总结数据访问层
 */
public interface NoteInsightRepository extends BaseRepository<NoteInsight, Long> {

    List<NoteInsight> findByUserIdOrderByCreatedAtDesc(Long userId);
}
