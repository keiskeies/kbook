package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.TtsConfig;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * TTS 配置 Repository
 * <p>
 * 查询统一使用 BaseRepository.query() 的 Fluent API
 */
public interface TtsConfigRepository extends BaseRepository<TtsConfig, Long> {

    /**
     * 将其他配置的 isDefault 设为 false
     */
    @Modifying
    @Transactional
    @Query("UPDATE TtsConfig c SET c.isDefault = false WHERE c.id <> :excludeId")
    void clearDefaultForOthers(@Param("excludeId") Long excludeId);
}
