package com.kbook.service.ai.core;

import com.kbook.common.util.TransactionUtils;
import com.kbook.config.AiConfigCacheInvalidator;
import com.kbook.entity.AiProviderConfig;
import com.kbook.repository.AiProviderConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * AI 配置服务 — 全部从数据库读取，不再回退 yml。纯数据职责：查询 + 写入 + 版本失效触发。
 * <p>
 * CHAT 用途分两个角色：
 * - QA: 大型问答（图书问答、AI助理、圆桌派、奇葩说）
 * - TOOL: 小型工具（元数据推断、内容压缩、查询扩展等）
 * <p>
 * 缓存策略：本服务不再持有任何本地缓存（M4 解循环依赖后，Assistant 实例缓存
 * 移至 {@link AiAssistantProvider}，各缓存持有方自行向 {@link AiConfigCacheInvalidator}
 * 注册失效回调）；写入操作提交后递增版本号触发全副本失效。
 * 此前的 {@code kbook:ai:config:*} Redis 键为写后从不读取的死代码，已移除。
 */
@Slf4j
@Service
public class AiProviderConfigService {

    private final AiProviderConfigRepository configRepository;
    private final AiConfigCacheInvalidator cacheInvalidator;

    public AiProviderConfigService(
            AiProviderConfigRepository configRepository,
            AiConfigCacheInvalidator cacheInvalidator) {
        this.configRepository = configRepository;
        this.cacheInvalidator = cacheInvalidator;
    }

    // ==================== 查询 ====================

    /**
     * 获取 CHAT 用途中 roles 包含指定角色的启用配置
     */
    public AiProviderConfig getChatConfigByRole(String role) {
        return configRepository.findByPurposeAndEnabledAndRolesContaining(
                        AiProviderConfig.Purpose.CHAT.name(), "%" + role + "%")
                .orElse(null);
    }

    /**
     * 获取指定用途的所有配置（按更新时间降序）
     */
    public List<AiProviderConfig> getConfigsByPurpose(String purpose) {
        return configRepository.findByPurposeOrderByCreatedAtDesc(purpose);
    }

    /**
     * 获取指定用途的所有启用配置（按更新时间降序）
     */
    public List<AiProviderConfig> getEnabledConfigsByPurpose(String purpose) {
        return configRepository.findByPurposeAndEnabledTrueOrderByCreatedAtDesc(purpose);
    }

    /**
     * 获取指定用途的首个启用配置
     */
    public AiProviderConfig getFirstEnabledByPurpose(String purpose) {
        return configRepository.findFirstByPurposeAndEnabledTrueOrderByUpdatedAtDesc(purpose).orElse(null);
    }

    // ==================== 缓存管理 ====================

    /**
     * 使所有 AI 相关缓存失效（本副本 + 通过 Redis 版本号通知其他副本）。
     * 配置变更时调用。
     */
    public void invalidateAllCache() {
        cacheInvalidator.bumpVersion();
    }

    // ==================== 写入 ====================

    /**
     * 新增/更新配置。提交后统一失效所有 AI 缓存（本副本 + 其他副本）。
     */
    @Transactional(rollbackFor = Exception.class)
    public AiProviderConfig saveConfig(AiProviderConfig config) {
        AiProviderConfig saved = configRepository.save(config);
        // 提交后再失效：避免事务回滚时误清缓存、或用未提交的数据重建缓存
        TransactionUtils.afterCommit(cacheInvalidator::bumpVersion);
        return saved;
    }

    /**
     * 删除配置。提交后统一失效所有 AI 缓存。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteConfig(Long id) {
        if (!configRepository.existsById(id)) {
            return;
        }
        configRepository.deleteById(id);
        TransactionUtils.afterCommit(cacheInvalidator::bumpVersion);
    }

    /**
     * 切换配置的 QA/TOOL 角色（业务逻辑：角色全局唯一）。
     * <p>
     * 为指定配置添加角色时，先从所有其他 CHAT 配置中移除该角色，保证同一角色
     * 只有一个启用配置；移除角色时仅作用于当前配置。提交后统一失效缓存。
     *
     * @param id   配置 ID
     * @param role "QA" 或 "TOOL"
     * @return 更新后的配置
     */
    @Transactional(rollbackFor = Exception.class)
    public AiProviderConfig setRole(Long id, String role) {
        AiProviderConfig config = configRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));

        String upperRole = role.toUpperCase();
        String currentRoles = config.getRoles();
        if (currentRoles != null && currentRoles.contains(upperRole)) {
            // 移除该角色（仅从当前配置中移除）
            config.setRoles(currentRoles
                    .replace(upperRole, "")
                    .replace(",,", ",")
                    .replaceAll("^,|,$", "")
                    .trim());
        } else {
            // 添加该角色 — 先从所有其他 CHAT 配置中移除该角色，保证唯一
            List<AiProviderConfig> others = configRepository
                    .findAllByPurposeAndRolesContaining(AiProviderConfig.Purpose.CHAT.name(), "%" + upperRole + "%")
                    .stream()
                    .filter(c -> !c.getId().equals(id))
                    .toList();
            for (AiProviderConfig other : others) {
                String otherRoles = other.getRoles();
                if (otherRoles != null) {
                    other.setRoles(otherRoles
                            .replace(upperRole, "")
                            .replace(",,", ",")
                            .replaceAll("^,|,$", "")
                            .trim());
                    configRepository.save(other);
                }
            }

            String newRoles = (currentRoles != null && !currentRoles.isBlank())
                    ? currentRoles + "," + upperRole
                    : upperRole;
            config.setRoles(newRoles);
        }

        AiProviderConfig saved = configRepository.save(config);
        // 本方法内的多个写入在同一事务中提交后，统一失效一次
        TransactionUtils.afterCommit(cacheInvalidator::bumpVersion);
        log.info("已切换配置角色: id={}, name={}, roles={}", id, config.getName(), saved.getRoles());
        return saved;
    }
}
