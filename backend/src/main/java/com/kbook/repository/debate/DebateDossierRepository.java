package com.kbook.repository.debate;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.debate.DebateDossier;

import java.util.Optional;

/**
 * 辩论论点弹药库数据访问层。
 */
public interface DebateDossierRepository extends BaseRepository<DebateDossier, Long> {

    /** 按会话 ID 查询弹药库（session_id 有唯一约束） */
    Optional<DebateDossier> findBySessionId(String sessionId);
}
