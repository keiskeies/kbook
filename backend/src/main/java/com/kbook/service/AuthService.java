package com.kbook.service;

import com.kbook.common.exception.BusinessException;
import com.kbook.config.JwtUtil;
import com.kbook.config.properties.VerificationProperties;
import com.kbook.entity.User;
import com.kbook.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

/**
 * 认证服务 - 注册、登录、验证码、Token 刷新
 * <p>
 * 验证码场景：
 * - register: 注册，检查邮箱未注册
 * - login: 登录，不检查注册状态
 * - reset: 重置密码，检查邮箱已注册
 * - bind: 管理员绑定邮箱
 * <p>
 * 审核状态机：
 * - 注册 → PENDING(待审核)
 * - 管理员通过 → APPROVED(已通过)
 * - 管理员拒绝 → BANNED(封禁)
 * - PENDING 用户可验证码登录但被拦截，返回审核提示
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final EmailNotificationService emailNotificationService;
    private final ClickCaptchaService clickCaptchaService;
    private final VerificationProperties verificationProps;

    private static final String CODE_KEY_PREFIX = "verify:code:";
    private static final String RATE_KEY_PREFIX = "verify:rate:";
    private static final String DAILY_KEY_PREFIX = "verify:daily:";
    private static final String TOKEN_BLACKLIST_PREFIX = "token:blacklist:";

    /**
     * 发送验证码
     * - 场景：register(注册) / login(登录) / reset(重置密码) / bind(绑定邮箱)
     * - 限频：60秒内不可重复发送
     * - 日限：每天最多10次
     * - 有效期：5分钟
     *
     * @param captchaId 点击验证码ID（需先通过点击验证）
     */
    @Transactional
    public void sendVerificationCode(String email, String scene, String captchaId) {
        // 场景校验
        if ("register".equals(scene) && userRepository.existsByEmail(email)) {
            throw new BusinessException("该邮箱已注册");
        }
        if ("reset".equals(scene) && !userRepository.existsByEmail(email)) {
            throw new BusinessException("该邮箱未注册");
        }

        // 校验点击验证码
        if (captchaId != null && !captchaId.isBlank()) {
            clickCaptchaService.checkCaptchaVerified(captchaId);
        }

        String rateKey = RATE_KEY_PREFIX + scene + ":" + email;
        String dailyKey = DAILY_KEY_PREFIX + scene + ":" + email;

        // 限频检查
        if (Boolean.TRUE.equals(redisTemplate.hasKey(rateKey))) {
            Long ttl = redisTemplate.getExpire(rateKey, TimeUnit.SECONDS);
            throw new BusinessException("发送太频繁，请" + ttl + "秒后再试");
        }

        // 日限检查
        String dailyCount = redisTemplate.opsForValue().get(dailyKey);
        if (dailyCount != null && Integer.parseInt(dailyCount) >= verificationProps.getDailyLimit()) {
            throw new BusinessException("今日发送次数已达上限");
        }

        // 生成验证码
        String code = generateCode();

        // 存储验证码（按场景隔离）
        String codeKey = CODE_KEY_PREFIX + scene + ":" + email;
        redisTemplate.opsForValue().set(codeKey, code, verificationProps.getExpireMinutes(), TimeUnit.MINUTES);

        // 设置限频
        redisTemplate.opsForValue().set(rateKey, "1", verificationProps.getRateLimitSeconds(), TimeUnit.SECONDS);

        // 更新日限计数
        Long count = redisTemplate.opsForValue().increment(dailyKey);
        if (count != null && count == 1) {
            redisTemplate.expire(dailyKey, 1, TimeUnit.DAYS);
        }

        // 发送邮件
        sendCodeEmail(email, code, scene);

        log.info("验证码已发送: email={}, scene={}", email, scene);
    }

    /**
     * 验证码登录
     * - 如果用户不存在，自动注册（状态为PENDING）
     * - 待审核用户也返回Token，前端根据状态显示审核提示
     */
    @Transactional
    public LoginResult loginByCode(String email, String code) {
        log.info("验证码登录: email={}", email);
        validateCode(email, code, "login");

        User user = userRepository.findByEmail(email)
                .orElseGet(() -> {
                    log.info("用户不存在，自动注册: email={}", email);
                    User newUser = User.builder()
                            .email(email)
                            .nickname("读者" + System.currentTimeMillis() % 100000)
                            .status("PENDING")
                            .role("USER")
                            .build();
                    return userRepository.save(newUser);
                });

        log.info("登录成功: userId={}, status={}, role={}", user.getId(), user.getStatus(), user.getRole());
        return generateLoginResult(user);
    }

    /**
     * 密码登录
     *
     * @param captchaId 点击验证码ID（需先通过点击验证）
     */
    public LoginResult loginByPassword(String email, String password, String captchaId) {
        // 校验点击验证码
        if (captchaId != null && !captchaId.isBlank()) {
            clickCaptchaService.checkCaptchaVerified(captchaId);
        }

        log.info("密码登录: email={}", email);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("邮箱未注册"));

        if (user.getPassword() == null || !passwordEncoder.matches(password, user.getPassword())) {
            log.warn("密码错误: email={}", email);
            throw new BusinessException("密码错误");
        }

        if ("BANNED".equals(user.getStatus())) {
            log.warn("账号已被封禁: userId={}", user.getId());
            throw new BusinessException(1002, "账号已被封禁");
        }

        log.info("登录成功: userId={}, status={}, role={}", user.getId(), user.getStatus(), user.getRole());
        return generateLoginResult(user);
    }

    /**
     * 注册（含可选画像信息）
     */
    @Transactional
    public LoginResult register(String email, String code, String password,
                                LocalDate birthday, String gender, Boolean married,
                                Boolean hasChildren, String mbti) {
        validateCode(email, code, "register");

        if (userRepository.existsByEmail(email)) {
            throw new BusinessException("该邮箱已注册");
        }

        validatePassword(password);

        User user = User.builder()
                .email(email)
                .password(passwordEncoder.encode(password))
                .nickname("读者" + System.currentTimeMillis() % 100000)
                .status("PENDING")
                .role("USER")
                .emailBound(true)
                .birthday(birthday)
                .gender(gender)
                .married(married)
                .hasChildren(hasChildren)
                .mbti(mbti != null ? mbti.toUpperCase() : null)
                .build();

        userRepository.save(user);
        log.info("用户注册成功: {}", email);

        return generateLoginResult(user);
    }

    /**
     * 刷新 Token
     */
    public LoginResult refreshToken(String refreshToken) {
        log.debug("刷新 Token");
        if (!jwtUtil.validateToken(refreshToken) || !jwtUtil.isRefreshToken(refreshToken)) {
            log.warn("Refresh Token 无效或已过期");
            throw new BusinessException("Refresh Token 无效或已过期");
        }

        if (isTokenBlacklisted(refreshToken)) {
            log.warn("Refresh Token 已在黑名单中");
            throw new BusinessException("Refresh Token 已失效");
        }

        Long userId = jwtUtil.getUserId(refreshToken);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));

        blacklistToken(refreshToken);

        log.info("Token 刷新成功: userId={}", userId);
        return generateLoginResult(user);
    }

    /**
     * 修改密码
     */
    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));

        if (user.getPassword() != null && !user.getPassword().isEmpty()) {
            if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
                throw new BusinessException("原密码错误");
            }
        }

        validatePassword(newPassword);

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("用户修改密码: userId={}", userId);
    }

    /**
     * 重置密码（通过验证码）
     */
    @Transactional
    public void resetPassword(String email, String code, String newPassword) {
        validateCode(email, code, "reset");

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("邮箱未注册"));

        validatePassword(newPassword);

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("用户重置密码: email={}", email);
    }

    /**
     * 登出 - 将当前 Access Token 加入黑名单
     */
    public void logout(String token) {
        if (token != null && jwtUtil.validateToken(token)) {
            Long userId = jwtUtil.getUserId(token);
            blacklistToken(token);
            log.info("用户登出: userId={}, Token 已加入黑名单", userId);
        }
    }

    /**
     * 校验绑定邮箱验证码（供 AdminController 调用）
     */
    public void validateBindCode(String email, String code) {
        validateCode(email, code, "bind");
    }

    // ==================== 私有方法 ====================

    void validateCode(String email, String code, String scene) {
        String codeKey = CODE_KEY_PREFIX + scene + ":" + email;
        String storedCode = redisTemplate.opsForValue().get(codeKey);
        if (storedCode == null) {
            throw new BusinessException("验证码已过期，请重新获取");
        }
        if (!storedCode.equals(code)) {
            throw new BusinessException("验证码错误");
        }
        redisTemplate.delete(codeKey);
    }

    private LoginResult generateLoginResult(User user) {
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getEmail(), user.getRole());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());

        return LoginResult.builder()
                .token(accessToken)
                .refreshToken(refreshToken)
                .userInfo(UserInfo.from(user))
                .build();
    }

    private String generateCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(verificationProps.getCodeLength());
        for (int i = 0; i < verificationProps.getCodeLength(); i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    private void sendCodeEmail(String email, String code, String scene) {
        String sceneName = switch (scene) {
            case "register" -> "注册";
            case "login" -> "登录";
            case "reset" -> "重置密码";
            case "bind" -> "绑定邮箱";
            default -> "验证";
        };
        emailNotificationService.sendVerificationCode(email, sceneName, code, verificationProps.getExpireMinutes());
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 6 || password.length() > 20) {
            throw new BusinessException("密码长度应为6-20位");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new BusinessException("密码必须包含字母和数字");
        }
    }

    private void blacklistToken(String token) {
        try {
            var claims = jwtUtil.parseToken(token);
            long expiration = claims.getExpiration().getTime() - System.currentTimeMillis();
            if (expiration > 0) {
                redisTemplate.opsForValue().set(
                        TOKEN_BLACKLIST_PREFIX + token,
                        "1",
                        expiration,
                        TimeUnit.MILLISECONDS
                );
            }
        } catch (Exception e) {
            log.warn("Token 黑名单添加失败: {}", e.getMessage());
        }
    }

    private boolean isTokenBlacklisted(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_BLACKLIST_PREFIX + token));
    }

    /**
     * 登录结果
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class LoginResult {
        private String token;
        private String refreshToken;
        private UserInfo userInfo;
    }

    /**
     * 用户信息 DTO
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class UserInfo {
        private Long id;
        private String email;
        private String nickname;
        private String avatar;
        private String role;
        private String status;
        private Boolean emailBound;
        private LocalDate birthday;
        private String gender;
        private Boolean married;
        private Boolean hasChildren;
        private String mbti;
        private String bio;
        private Integer followerCount;
        private Integer followingCount;

        public static UserInfo from(User user) {
            return UserInfo.builder()
                    .id(user.getId())
                    .email(user.getEmail())
                    .nickname(user.getNickname())
                    .avatar(user.getAvatar())
                    .role(user.getRole())
                    .status(user.getStatus())
                    .emailBound(user.getEmailBound())
                    .birthday(user.getBirthday())
                    .gender(user.getGender())
                    .married(user.getMarried())
                    .hasChildren(user.getHasChildren())
                    .mbti(user.getMbti())
                    .bio(user.getBio())
                    .followerCount(user.getFollowerCount())
                    .followingCount(user.getFollowingCount())
                    .build();
        }
    }
}
