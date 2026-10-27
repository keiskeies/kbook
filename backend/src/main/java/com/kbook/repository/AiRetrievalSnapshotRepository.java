package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.AiRetrievalSnapshot;

import java.util.List;

public interface AiRetrievalSnapshotRepository extends BaseRepository<AiRetrievalSnapshot, Long> {

    List<AiRetrievalSnapshot> findByConversationIdOrderByChunkIndexAsc(Long conversationId);

    long deleteByConversationId(Long conversationId);
}
