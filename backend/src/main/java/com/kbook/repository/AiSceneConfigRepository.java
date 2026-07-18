package com.kbook.repository;

import com.kbook.entity.AiScene;
import com.kbook.entity.AiSceneConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * AI 场景配置映射仓库
 */
@Repository
public interface AiSceneConfigRepository extends JpaRepository<AiSceneConfig, Long> {

    /** 按场景键查询映射（唯一） */
    Optional<AiSceneConfig> findBySceneKey(AiScene sceneKey);

    /** 判断场景是否已配置 */
    boolean existsBySceneKey(AiScene sceneKey);
}
