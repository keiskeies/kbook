package com.kbook.repository;

import com.kbook.entity.AiProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * AI 提供商配置 Repository（全局配置）
 */
public interface AiProviderConfigRepository extends JpaRepository<AiProviderConfig, Long> {

    /** 查找所有启用的配置 */
    List<AiProviderConfig> findByEnabledTrue();

    /** 查找启用的指定类型配置 */
    Optional<AiProviderConfig> findByProviderAndEnabledTrue(String provider);

    /** 查找指定类型的所有配置 */
    List<AiProviderConfig> findByProvider(String provider);

    /** 查找启用的配置（优先返回第一个） */
    default Optional<AiProviderConfig> findActiveConfig() {
        List<AiProviderConfig> enabled = findByEnabledTrue();
        return enabled.isEmpty() ? Optional.empty() : Optional.of(enabled.get(0));
    }
}
