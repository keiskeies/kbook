package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.AiProviderConfig;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * AI 供应商配置 Repository
 */
public interface AiProviderConfigRepository extends BaseRepository<AiProviderConfig, Long> {

    /**
     * 查找指定用途中启用且 roles 包含指定角色的配置（取第一条）
     */
    @Query("SELECT c FROM AiProviderConfig c WHERE c.purpose = :purpose AND c.enabled = true AND c.roles LIKE %:role% ORDER BY c.updatedAt DESC")
    Optional<AiProviderConfig> findByPurposeAndEnabledAndRolesContaining(@Param("purpose") String purpose, @Param("role") String role);

    /**
     * 查找指定用途中 roles 包含指定角色的所有配置（用于唯一性校验）
     */
    @Query("SELECT c FROM AiProviderConfig c WHERE c.purpose = :purpose AND c.roles LIKE %:role%")
    List<AiProviderConfig> findAllByPurposeAndRolesContaining(@Param("purpose") String purpose, @Param("role") String role);

    /**
     * 查找指定用途中首个启用的配置
     */
    Optional<AiProviderConfig> findFirstByPurposeAndEnabledTrueOrderByUpdatedAtDesc(String purpose);

    /**
     * 查找指定用途的所有配置，按 createdAt 降序（最新创建在前，顺序稳定）
     */
    List<AiProviderConfig> findByPurposeOrderByCreatedAtDesc(String purpose);

    /**
     * 查找指定用途中所有启用的配置
     */
    List<AiProviderConfig> findByPurposeAndEnabledTrueOrderByCreatedAtDesc(String purpose);
}
