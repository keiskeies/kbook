package com.kbook.repository;

import com.kbook.entity.AiProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * AI 供应商配置 Repository
 */
public interface AiProviderConfigRepository extends JpaRepository<AiProviderConfig, Long> {

    /**
     * 根据用途查找已启用的配置
     */
    Optional<AiProviderConfig> findByPurposeAndEnabledTrue(String purpose);

    /**
     * 根据用途查找配置（不区分启用状态）
     */
    Optional<AiProviderConfig> findByPurpose(String purpose);
}
