package com.kbook.config;

import com.kbook.entity.User;
import com.kbook.repository.UserRepository;
import com.kbook.service.RecommendCoefficientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RecommendCoefficientService coefficientService;

    @Value("${kbook.admin.email:admin@kbook.com}")
    private String adminEmail;

    @Value("${kbook.admin.password:admin123456}")
    private String adminPassword;

    @Value("${kbook.admin.nickname:系统管理员}")
    private String adminNickname;

    @Override
    public void run(String... args) {
        initAdmin();
        initRecommendCoefficients();
    }

    /**
     * 初始化管理员账号
     * - 如果指定邮箱的管理员不存在，自动创建
     * - 管理员状态为 APPROVED，但 emailBound=false（需首次登录绑定邮箱）
     */
    private void initAdmin() {
        // 检查是否已存在任意管理员
        boolean adminExists = userRepository.findByRole("ADMIN").stream()
                .anyMatch(u -> u.getEmail().equals(adminEmail));

        if (!adminExists) {
            // 检查该邮箱是否已被普通用户占用
            userRepository.findByEmail(adminEmail).ifPresent(existing -> {
                log.warn("管理员邮箱 {} 已被普通用户占用，跳过管理员初始化", adminEmail);
                return;
            });

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
            log.info("  密码: {}", adminPassword);
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
