package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.AiProviderConfig;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * AI 供应商配置 Repository
 */
public interface AiProviderConfigRepository extends BaseRepository<AiProviderConfig, Long> {

    /**
     * 查找指定用途的默认（激活）配置
     */
    Optional<AiProviderConfig> findByPurposeAndIsDefaultTrueAndEnabledTrue(String purpose);

    /**
     * 查找指定用途的所有配置，按 isDefault 降序、updatedAt 降序
     */
    List<AiProviderConfig> findByPurposeOrderByIsDefaultDescUpdatedAtDesc(String purpose);

    /**
     * 将指定用途的其他配置的 isDefault 设为 false
     */
    @Modifying
    @Transactional
    @Query("UPDATE AiProviderConfig c SET c.isDefault = false WHERE c.purpose = :purpose AND c.id <> :excludeId")
    void clearDefaultForPurpose(@Param("purpose") String purpose, @Param("excludeId") Long excludeId);

    /**
     * 将指定用途的所有配置的 isDefault 设为 false
     */
    @Modifying
    @Transactional
    @Query("UPDATE AiProviderConfig c SET c.isDefault = false WHERE c.purpose = :purpose")
    void clearAllDefaultsForPurpose(@Param("purpose") String purpose);
}
