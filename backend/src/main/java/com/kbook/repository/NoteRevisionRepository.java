package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.NoteRevision;

import java.util.List;

/**
 * 笔记历史版本数据访问层
 */
public interface NoteRevisionRepository extends BaseRepository<NoteRevision, Long> {

    List<NoteRevision> findByNoteIdOrderByRevisionNoDesc(Long noteId);
}
