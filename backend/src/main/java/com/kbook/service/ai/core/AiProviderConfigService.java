package com.kbook.service.ai.core;

import com.kbook.common.util.TransactionUtils;
import com.kbook.config.AiConfigCacheInvalidator;
import com.kbook.config.ChatModelFactory;
import com.kbook.constants.AiPromptConstants;
import com.kbook.entity.AiProviderConfig;
import com.kbook.entity.AiScene;
import com.kbook.repository.AiProviderConfigRepository;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import com.kbook.service.ai.admin.BookAdminChatService;
import com.kbook.service.ai.AiAssistant;
import com.kbook.service.ai.AiChatMemory;
import com.kbook.service.ai.AiToolService;

/**
 * AI 配置服务 — 全部从数据库读取，不再回退 yml
 * <p>
 * CHAT 用途分两个角色：
 * - QA: 大型问答（图书问答、AI助理、圆桌派、奇葩说）
 * - TOOL: 小型工具（元数据推断、内容压缩、查询扩展等）
 * <p>
 * 缓存策略：配置解析结果由 {@link com.kbook.service.ai.core.AiSceneConfigService} 缓存，
 * Assistant 实例由本类的 {@code chatAssistantCache} 缓存；
 * 所有缓存在配置变更后由 {@link AiConfigCacheInvalidator} 统一失效（跨副本生效）。
 * 此前的 {@code kbook:ai:config:*} Redis 键为写后从不读取的死代码，已移除。
 */
@Slf4j
@Service
public class AiProviderConfigService {

    private final AiChatMemory chatMemoryStore;
    private final ObjectProvider<AiToolService> toolServiceProvider;
    private final ChatModelFactory chatModelFactory;
    private final AiProviderConfigRepository configRepository;
    private final ObjectProvider<BookAdminChatService> bookAdminChatServiceProvider;
    private final AiSceneConfigService sceneConfigService;
    private final AiConfigCacheInvalidator cacheInvalidator;

    /** AI 助理 AiAssistant 缓存，key = configId，配置变更时清空 */
    private final ConcurrentHashMap<Long, AiAssistant> chatAssistantCache = new ConcurrentHashMap<>();

    public AiProviderConfigService(
            AiChatMemory chatMemoryStore,
            ObjectProvider<AiToolService> toolServiceProvider,
            ChatModelFactory chatModelFactory,
            AiProviderConfigRepository configRepository,
            ObjectProvider<BookAdminChatService> bookAdminChatServiceProvider,
            @Lazy AiSceneConfigService sceneConfigService,
            AiConfigCacheInvalidator cacheInvalidator) {
        this.chatMemoryStore = chatMemoryStore;
        this.toolServiceProvider = toolServiceProvider;
        this.chatModelFactory = chatModelFactory;
        this.configRepository = configRepository;
        this.bookAdminChatServiceProvider = bookAdminChatServiceProvider;
        this.sceneConfigService = sceneConfigService;
        this.cacheInvalidator = cacheInvalidator;
    }

    /** 向失效协调器注册本服务的本地缓存清空回调 */
    @jakarta.annotation.PostConstruct
    void registerCacheInvalidation() {
        cacheInvalidator.register(this::invalidateAllCacheLocal);
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

    /** 本副本缓存清空（失效协调器回调 + 兼容入口） */
    private void invalidateAllCacheLocal() {
        invalidateChatAssistantCache();
        bookAdminChatServiceProvider.ifAvailable(BookAdminChatService::clearCache);
    }

    /**
     * 使对话 Assistant 缓存失效
     */
    public void invalidateChatAssistantCache() {
        chatAssistantCache.clear();
        log.debug("对话 Assistant 缓存已清空");
    }

    /**
     * 清理当前 Assistant 缓存（错误恢复时调用，使下一个请求重新创建 Assistant）。
     * <p>
     * 同时清空 {@link ChatModelFactory} 的本副本模型缓存 — 与改动前"每次重建 Assistant
     * 都会新建模型实例"的恢复语义保持一致（重置底层 HTTP 客户端），
     * 但<b>不</b>递增 Redis 版本号：数据库配置未变化，无需通知其他副本。
     */
    public void clearAssistantCache() {
        invalidateChatAssistantCache();
        chatModelFactory.invalidateCaches();
    }

    // ==================== 对话 AI ====================

    /**
     * 获取 AI 助理的 AiAssistant（带缓存）。
     * <p>
     * 配置解析走 {@link AiSceneConfigService#resolveConfig(AiScene)} 场景路由：
     * 优先使用 AiScene.AI_ASSISTANT 显式绑定的配置，回退到 Category.QA 默认配置。
     */
    public AiAssistant getChatAssistant() {
        AiProviderConfig config = sceneConfigService.resolveConfig(AiScene.AI_ASSISTANT);
        if (config == null) {
            throw new IllegalStateException("未找到可用 AI 助理对话配置（AiScene.AI_ASSISTANT 未绑定且无 QA 默认配置）");
        }
        return chatAssistantCache.computeIfAbsent(config.getId(),
                id -> buildChatAssistant("AI_ASSISTANT", config));
    }

    /**
     * 构建指定角色的 AiAssistant
     */
    private AiAssistant buildChatAssistant(String role, AiProviderConfig config) {

        boolean toolsSupported = chatModelFactory.isToolsSupported(config);

        log.info("构建对话 AI Assistant: role={}, model={}, toolsEnabled={}, baseUrl={}",
                role, config.getModelName(), toolsSupported, config.getBaseUrl());

        // AI 助理用自己的场景配置（AI_ASSISTANT），不再误用 BOOK_QA 场景
        ChatModel chatModel = chatModelFactory.buildForScene(AiScene.AI_ASSISTANT);
        StreamingChatModel streamingModel = chatModelFactory.buildStreamingForScene(AiScene.AI_ASSISTANT);

        var builder = AiServices.builder(AiAssistant.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingModel)
                .chatMemoryProvider(sessionId -> dev.langchain4j.memory.chat.MessageWindowChatMemory.builder()
                        .id(sessionId)
                        .maxMessages(AiPromptConstants.ADMIN_MAX_MESSAGES)
                        .chatMemoryStore(chatMemoryStore)
                        .build());

        if (toolsSupported) {
            Object toolObj = toolServiceProvider.getObject();
            builder.tools(toolObj);
            log.info("  已注册 AI 工具: class={}", toolObj.getClass().getName());
        }

        return builder.build();
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
