package com.kbook.repository;

import com.kbook.entity.TtsConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface TtsConfigRepository extends BaseRepository<TtsConfig, Long> {

    Optional<TtsConfig> findByIsDefaultTrueAndEnabledTrue();

    List<TtsConfig> findByOrderByIsDefaultDescUpdatedAtDesc();

    @Modifying
    @Transactional
    @Query("UPDATE TtsConfig c SET c.isDefault = false WHERE c.id <> :excludeId")
    void clearDefaultForOthers(@Param("excludeId") Long excludeId);

    Optional<TtsConfig> findByEnabledTrueAndTtsType(TtsConfig.TtsType ttsType);
}
