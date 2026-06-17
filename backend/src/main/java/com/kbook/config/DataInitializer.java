package com.kbook.config;

import com.kbook.entity.AiProviderConfig;
import com.kbook.entity.User;
import com.kbook.repository.AiProviderConfigRepository;
import com.kbook.repository.UserRepository;
import com.kbook.service.recommend.RecommendCoefficientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import static com.kbook.common.util.QueryBuilder.*;

/**
 * 初始管理员账号注入方案
 * <p>
 * 方案一（默认）：环境变量注入
 * KBOOK_ADMIN_EMAIL / KBOOK_ADMIN_PASSWORD
 * <p>
 * 方案二：初始化脚本
 * 通过 application.yml 中的 kbook.admin 配置
 * <p>
 * 首次启动时自动创建管理员账号，管理员登录后强制引导绑定邮箱
 * 绑定邮箱后开启密码重置功能
 * <p>
 * 同时负责首次启动时迁移 YML AI 配置到数据库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    /** 用户仓库 */
    private final UserRepository userRepository;
    /** 密码编码器 */
    private final PasswordEncoder passwordEncoder;
    /** 推荐系数服务 */
    private final RecommendCoefficientService coefficientService;
    /** AI 配置仓库 */
    private final AiProviderConfigRepository aiConfigRepository;

    /** 管理员邮箱 */
    @Value("${kbook.admin.email:admin@kbook.com}")
    private String adminEmail;

    /** 管理员密码 */
    @Value("${kbook.admin.password:admin123456}")
    private String adminPassword;

    /** 管理员昵称 */
    @Value("${kbook.admin.nickname:系统管理员}")
    private String adminNickname;

    /**
     * 应用启动后执行初始化
     */
    @Override
    public void run(String... args) {
        initAdmin();
        initRecommendCoefficients();
        initAiConfigs();
    }

    /**
     * 初始化 AI 配置 — 首次启动时从 YML 默认值迁移到数据库
     * <p>
     * 如果数据库中没有任何 AI 配置记录，则创建默认配置。
     * 注意：这些只是占位配置，管理员需在管理后台修改为实际值。
     */
    private void initAiConfigs() {
        if (aiConfigRepository.count() > 0) {
            log.info("AI 配置已存在数据库，跳过 YML 迁移");
            return;
        }

        log.warn("============================================================");
        log.warn("  未检测到数据库 AI 配置，正在创建默认占位配置");
        log.warn("  请务必在管理后台修改以下配置的实际值：");
        log.warn("    - 对话模型(QA): 用于图书问答、AI助理等大型问答");
        log.warn("    - 对话模型(TOOL): 用于元数据推断、内容压缩等后台任务");
        log.warn("    - 嵌入模型: 用于向量生成（如 bge-m3、qwen3-embedding）");
        log.warn("============================================================");

        // 1. CHAT-QA 模型（大型问答）
        AiProviderConfig qaConfig = AiProviderConfig.builder()
                .name("默认 Ollama (QA)")
                .purpose("CHAT")
                .roles("QA")
                .provider(AiProviderConfig.Provider.OLLAMA)
                .baseUrl("http://localhost:11434")
                .modelName("gemma4:12b")
                .temperature(0.7)
                .timeout(600)
                .enabled(true)
                .ragTopK(5)
                .maxTokens(32768)
                .build();
        aiConfigRepository.save(qaConfig);
        log.info("已创建默认 CHAT-QA 配置: model={}", qaConfig.getModelName());

        // 2. CHAT-TOOL 模型（小型工具）
        AiProviderConfig toolConfig = AiProviderConfig.builder()
                .name("默认 Ollama (TOOL)")
                .purpose("CHAT")
                .roles("TOOL")
                .provider(AiProviderConfig.Provider.OLLAMA)
                .baseUrl("http://localhost:11434")
                .modelName("gemma4:12b")
                .temperature(0.3)
                .timeout(300)
                .enabled(true)
                .toolsEnabled(false)
                .build();
        aiConfigRepository.save(toolConfig);
        log.info("已创建默认 CHAT-TOOL 配置: model={}", toolConfig.getModelName());

        // 3. EMBEDDING 模型
        AiProviderConfig embeddingConfig = AiProviderConfig.builder()
                .name("默认 Ollama (Embedding)")
                .purpose("EMBEDDING")
                .provider(AiProviderConfig.Provider.OLLAMA)
                .baseUrl("http://localhost:11434")
                .modelName("bge-m3:latest")
                .timeout(300)
                .enabled(true)
                .embeddingDimension(1024)
                .build();
        aiConfigRepository.save(embeddingConfig);
        log.info("已创建默认 EMBEDDING 配置: model={}, dim={}", embeddingConfig.getModelName(), embeddingConfig.getEmbeddingDimension());
    }

    /**
     * 初始化管理员账号
     * - 如果指定邮箱的管理员不存在，自动创建
     * - 管理员状态为 APPROVED，但 emailBound=false（需首次登录绑定邮箱）
     */
    private void initAdmin() {
        // 检查是否已存在任意管理员
        boolean adminExists = userRepository.query()
                .where(User::getRole, eq("ADMIN"))
                .list()
                .stream()
                .anyMatch(u -> u.getEmail().equals(adminEmail));

        if (!adminExists) {
            // 检查该邮箱是否已被普通用户占用
            boolean emailUsed = userRepository.query()
                    .where(User::getEmail, eq(adminEmail))
                    .exists();
            if (emailUsed) {
                log.warn("管理员邮箱 {} 已被普通用户占用，跳过管理员初始化", adminEmail);
                return;
            }

            User admin = User.builder()
                    .email(adminEmail)
                    .password(passwordEncoder.encode(adminPassword))
                    .nickname(adminNickname)
                    .role("ADMIN")
                    .status("APPROVED")
                    .emailBound(false) // 首次登录需绑定邮箱
                    .build();
            userRepository.save(admin);

            log.info("============================================");
            log.info("  初始管理员账号已创建");
            log.info("  邮箱: {}", adminEmail);
            log.info("  ⚠️  请尽快登录并绑定邮箱、修改密码！");
            log.info("============================================");
        } else {
            log.info("管理员账号已存在，跳过初始化");
        }
    }

    /**
     * 初始化推荐算法系数
     * 将代码中定义的默认系数写入数据库（已存在则跳过），
     * 确保数据库中有完整的系数数据，防止数据丢失
     */
    private void initRecommendCoefficients() {
        try {
            coefficientService.initializeCoefficients();
        } catch (Exception e) {
            log.warn("推荐系数初始化失败，将在首次访问时重试: {}", e.getMessage());
        }
    }
}
