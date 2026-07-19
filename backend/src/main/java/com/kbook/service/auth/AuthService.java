package com.kbook.service.auth;
import com.kbook.service.notification.EmailNotificationService;
import com.kbook.service.notification.NotificationService;

import com.kbook.common.exception.BusinessException;
import com.kbook.config.JwtUtil;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.config.properties.VerificationProperties;
import com.kbook.dto.auth.LoginResult;
import com.kbook.dto.user.UserInfo;
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

import static com.kbook.common.util.QueryBuilder.*;

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
@LogModule("认证")
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
    /** Refresh Token 刷新宽限期 Redis Key 前缀（解决刷新响应丢失导致旧 token 不可用的问题） */
    private static final String TOKEN_GRACE_PREFIX = "token:grace:";
    /** Refresh Token 宽限期秒数：30 秒内旧 refresh token 仍可换新，避免响应丢失陷阱 */
    private static final long REFRESH_GRACE_SECONDS = 30;
    private static final String CODE_ATTEMPT_PREFIX = "verify:attempt:";
    private static final int MAX_CODE_ATTEMPTS = 5;

    /** 账户锁定相关常量 */
    private static final String LOGIN_FAIL_PREFIX = "login:fail:";
    private static final String LOGIN_LOCK_PREFIX = "login:lock:";
    private static final int MAX_LOGIN_FAILURES = 5;
    private static final int LOCK_MINUTES = 30;
    /** 密码重置场景的发送频率限制（秒）— 5分钟 */
    private static final int RESET_RATE_LIMIT_SECONDS = 300;
    /** 用于消除时序差异的 dummy BCrypt 哈希（固定字符串的 BCrypt 编码） */
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /**
     * 发送验证码
     * - 场景：register(注册) / login(登录) / reset(重置密码) / bind(绑定邮箱)
     * - 限频：reset 场景 5 分钟内不可重复发送，其他场景 60 秒
     * - 日限：每天最多10次
     * - 有效期：5分钟
     * - 安全：不泄露邮箱是否已注册（统一返回成功）
     *
     * @param captchaId 点击验证码ID（需先通过点击验证）
     */
    @Transactional
    @LogAction("发送验证码")
    public void sendVerificationCode(String email, String scene, String captchaId) {
        // 校验点击验证码（在任何场景检查之前执行，防止通过场景选择绕过验证码）
        if (captchaId != null && !captchaId.isBlank()) {
            clickCaptchaService.checkCaptchaVerified(captchaId);
        }

        // 场景校验 — 不再抛出"已注册"/"未注册"错误，防止账户枚举
        // 对于不符合条件的邮箱，静默跳过发送（对外仍返回成功）
        boolean shouldSendCode = true;
        if ("register".equals(scene)) {
            // 注册场景：邮箱已注册时不发送验证码（但不暴露存在性）
            shouldSendCode = !userRepository.query()
                    .where(User::getEmail, eq(email)).exists();
        } else if ("reset".equals(scene)) {
            // 重置场景：邮箱未注册时不发送验证码（但不暴露存在性）
            shouldSendCode = userRepository.query()
                    .where(User::getEmail, eq(email)).exists();
        }

        // 限频检查 — reset 场景使用 5 分钟间隔，其他场景使用配置默认值
        int rateLimitSeconds = "reset".equals(scene)
                ? RESET_RATE_LIMIT_SECONDS
                : verificationProps.getRateLimitSeconds();
        String rateKey = RATE_KEY_PREFIX + scene + ":" + email;
        String dailyKey = DAILY_KEY_PREFIX + scene + ":" + email;

        if (Boolean.TRUE.equals(redisTemplate.hasKey(rateKey))) {
            Long ttl = redisTemplate.getExpire(rateKey, TimeUnit.SECONDS);
            throw new BusinessException("发送太频繁，请" + ttl + "秒后再试");
        }

        // 日限检查
        String dailyCount = redisTemplate.opsForValue().get(dailyKey);
        if (dailyCount != null && Integer.parseInt(dailyCount) >= verificationProps.getDailyLimit()) {
            throw new BusinessException("今日发送次数已达上限");
        }

        // 无论是否发送验证码，都设置限频和日限计数（防止通过响应时间/行为差异枚举账户）
        redisTemplate.opsForValue().set(rateKey, "1", rateLimitSeconds, TimeUnit.SECONDS);
        Long count = redisTemplate.opsForValue().increment(dailyKey);
        if (count != null && count == 1) {
            redisTemplate.expire(dailyKey, 1, TimeUnit.DAYS);
        }

        if (!shouldSendCode) {
            // 邮箱不符合场景条件，静默返回（不发送邮件，但对外表现为已发送）
            log.info("验证码发送跳过（场景条件不满足）: email={}, scene={}", email, scene);
            return;
        }

        // 生成验证码
        String code = generateCode();

        // 存储验证码（按场景隔离）
        String codeKey = CODE_KEY_PREFIX + scene + ":" + email;
        redisTemplate.opsForValue().set(codeKey, code, verificationProps.getExpireMinutes(), TimeUnit.MINUTES);

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
    @LogAction("验证码登录")
    public LoginResult loginByCode(String email, String code) {
        log.info("验证码登录: email={}", email);
        validateCode(email, code, "login");

        User user = userRepository.query()
                .where(User::getEmail, eq(email))
                .list(1)
                .stream().findFirst()
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
     * <p>
     * 安全措施：
     * - 统一错误消息"邮箱或密码错误"，不泄露账户存在性（P1 #5）
     * - 账户锁定机制：5次失败后锁定30分钟（P2 #6）
     * - 消除时序差异：用户不存在时也执行 BCrypt 比较（P2 #9）
     *
     * @param captchaId 点击验证码ID（需先通过点击验证）
     */
    @LogAction("密码登录")
    public LoginResult loginByPassword(String email, String password, String captchaId) {
        // 校验点击验证码
        if (captchaId != null && !captchaId.isBlank()) {
            clickCaptchaService.checkCaptchaVerified(captchaId);
        }

        // 账户锁定检查
        String lockKey = LOGIN_LOCK_PREFIX + email;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
            Long ttl = redisTemplate.getExpire(lockKey, TimeUnit.MINUTES);
            log.warn("账户已锁定: email={}", email);
            throw new BusinessException("账户已锁定，请" + (ttl != null ? ttl : LOCK_MINUTES) + "分钟后再试");
        }

        log.info("密码登录: email={}", email);
        User user = userRepository.query()
                .where(User::getEmail, eq(email))
                .list(1)
                .stream().findFirst()
                .orElse(null);

        // 消除时序差异：用户不存在时也执行 BCrypt 比较（使用 dummy hash）
        boolean passwordMatched;
        if (user != null && user.getPassword() != null) {
            passwordMatched = passwordEncoder.matches(password, user.getPassword());
        } else {
            // 用户不存在时执行 dummy BCrypt 比较，消除时序差异
            passwordEncoder.matches(password, DUMMY_PASSWORD_HASH);
            passwordMatched = false;
        }

        if (!passwordMatched) {
            // 记录登录失败次数
            recordLoginFailure(email);
            log.warn("登录失败: email={}", email);
            throw new BusinessException("邮箱或密码错误");
        }

        // 登录成功，清除失败计数
        clearLoginFailures(email);

        if ("BANNED".equals(user.getStatus())) {
            log.warn("账号已被封禁: userId={}", user.getId());
            throw new BusinessException(1002, "账号已被封禁");
        }

        log.info("登录成功: userId={}, status={}, role={}", user.getId(), user.getStatus(), user.getRole());
        return generateLoginResult(user);
    }

    /**
     * 记录登录失败次数，达到阈值时锁定账户
     */
    private void recordLoginFailure(String email) {
        String failKey = LOGIN_FAIL_PREFIX + email;
        Long count = redisTemplate.opsForValue().increment(failKey);
        if (count != null && count == 1) {
            redisTemplate.expire(failKey, LOCK_MINUTES, TimeUnit.MINUTES);
        }
        if (count != null && count >= MAX_LOGIN_FAILURES) {
            // 达到阈值，锁定账户
            String lockKey = LOGIN_LOCK_PREFIX + email;
            redisTemplate.opsForValue().set(lockKey, "1", LOCK_MINUTES, TimeUnit.MINUTES);
            redisTemplate.delete(failKey);
            log.warn("账户已锁定（连续{}次登录失败）: email={}", MAX_LOGIN_FAILURES, email);
        }
    }

    /**
     * 登录成功后清除失败计数
     */
    private void clearLoginFailures(String email) {
        redisTemplate.delete(LOGIN_FAIL_PREFIX + email);
    }

    /**
     * 注册（含可选画像信息）
     */
    @Transactional
    @LogAction("用户注册")
    public LoginResult register(String email, String code, String password,
                                LocalDate birthday, String gender, Boolean married,
                                Boolean hasChildren, String mbti) {
        validateCode(email, code, "register");

        if (userRepository.query()
                .where(User::getEmail, eq(email)).exists()) {
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
     * <p>
     * 宽限期机制：每次刷新会把旧 refresh token 拉黑并标记 30 秒宽限期。
     * 宽限期内旧 token 仍可换新（不再重复拉黑），解决"后端已签新 token + 拉黑旧 token，
     * 但响应未到达前端（浏览器关闭/网络抖动）"导致用户下次打开无法续签的陷阱。
     */
    @LogAction("刷新Token")
    public LoginResult refreshToken(String refreshToken) {
        log.debug("刷新 Token");
        if (!jwtUtil.validateToken(refreshToken) || !jwtUtil.isRefreshToken(refreshToken)) {
            log.warn("Refresh Token 无效或已过期");
            throw new BusinessException("Refresh Token 无效或已过期");
        }

        // 检查是否在宽限期内（30 秒内刚被刷新过，允许复用）
        boolean inGrace = isInGracePeriod(refreshToken);

        // 宽限期外才检查黑名单
        if (!inGrace && isTokenBlacklisted(refreshToken)) {
            log.warn("Refresh Token 已在黑名单中");
            throw new BusinessException("Refresh Token 已失效");
        }

        Long userId = jwtUtil.getUserId(refreshToken);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));

        // 仅在非宽限期时拉黑 + 标记宽限期（避免宽限期内重复拉黑）
        if (!inGrace) {
            blacklistToken(refreshToken);
            markGracePeriod(refreshToken);
        }

        log.info("Token 刷新成功: userId={}, inGrace={}", userId, inGrace);
        return generateLoginResult(user);
    }

    /**
     * 修改密码
     */
    @Transactional
    @LogAction("修改密码")
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
    @LogAction("重置密码")
    public void resetPassword(String email, String code, String newPassword) {
        validateCode(email, code, "reset");

        User user = userRepository.query()
                .where(User::getEmail, eq(email))
                .list(1)
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException("邮箱未注册"));

        validatePassword(newPassword);

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("用户重置密码: email={}", email);
    }

    /**
     * 登出 - 将当前 Access Token 加入黑名单
     */
    @LogAction("用户登出")
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
    @LogAction("校验绑定邮箱验证码")
    public void validateBindCode(String email, String code) {
        validateCode(email, code, "bind");
    }

    // ==================== 私有方法 ====================

    /**
     * 校验验证码（内部方法，支持多场景隔离）
     * @param email 邮箱地址
     * @param code 用户输入的验证码
     * @param scene 场景（register/login/reset/bind）
     * @throws BusinessException 验证码错误、过期或错误次数过多时抛出
     */
    void validateCode(String email, String code, String scene) {
        String codeKey = CODE_KEY_PREFIX + scene + ":" + email;
        String attemptKey = CODE_ATTEMPT_PREFIX + scene + ":" + email;

        String attemptCount = redisTemplate.opsForValue().get(attemptKey);
        if (attemptCount != null && Integer.parseInt(attemptCount) >= MAX_CODE_ATTEMPTS) {
            redisTemplate.delete(codeKey);
            redisTemplate.delete(attemptKey);
            throw new BusinessException("验证码错误次数过多，请重新获取");
        }

        String storedCode = redisTemplate.opsForValue().get(codeKey);
        if (storedCode == null) {
            throw new BusinessException("验证码已过期，请重新获取");
        }
        if (!storedCode.equals(code)) {
            Long count = redisTemplate.opsForValue().increment(attemptKey);
            if (count != null && count == 1) {
                redisTemplate.expire(attemptKey, verificationProps.getExpireMinutes(), TimeUnit.MINUTES);
            }
            throw new BusinessException("验证码错误");
        }
        redisTemplate.delete(codeKey);
        redisTemplate.delete(attemptKey);
    }

    /**
     * 生成登录结果（包含AccessToken和RefreshToken）
     * @param user 用户实体
     * @return 登录结果DTO
     */
    private LoginResult generateLoginResult(User user) {
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getEmail(), user.getRole());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());

        return LoginResult.builder()
                .token(accessToken)
                .refreshToken(refreshToken)
                .userInfo(UserInfo.from(user))
                .build();
    }

    /**
     * 生成随机数字验证码
     * @return 指定长度的数字字符串
     */
    private String generateCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(verificationProps.getCodeLength());
        for (int i = 0; i < verificationProps.getCodeLength(); i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    /**
     * 发送验证码邮件
     * @param email 收件人邮箱
     * @param code 验证码
     * @param scene 场景（用于邮件标题显示）
     */
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

    /**
     * 校验密码强度
     * @param password 密码
     * @throws BusinessException 密码长度不符合要求或缺少字母/数字时抛出
     */
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

    /**
     * 将Token加入黑名单（用于登出/刷新时作废旧Token）
     * @param token JWT Token
     */
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

    /**
     * 检查Token是否在黑名单中
     * @param token JWT Token
     * @return true=已在黑名单中（已失效）
     */
    private boolean isTokenBlacklisted(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_BLACKLIST_PREFIX + token));
    }

    /**
     * 标记 Refresh Token 进入宽限期（30 秒内仍可被用于刷新，避免响应丢失陷阱）
     * <p>
     * 场景：刷新请求已发出，后端已拉黑旧 token 并签发新 token，但响应未到达前端
     * （浏览器关闭、网络抖动）。下次打开浏览器用旧 token 刷新时，宽限期允许继续刷新。
     *
     * @param refreshToken 旧的 Refresh Token
     */
    private void markGracePeriod(String refreshToken) {
        try {
            redisTemplate.opsForValue().set(
                    TOKEN_GRACE_PREFIX + refreshToken,
                    "1",
                    REFRESH_GRACE_SECONDS,
                    TimeUnit.SECONDS
            );
        } catch (Exception e) {
            log.warn("Token 宽限期标记失败: {}", e.getMessage());
        }
    }

    /**
     * 检查 Refresh Token 是否在宽限期内
     *
     * @param refreshToken Refresh Token
     * @return true=在宽限期内（允许复用），false=已过宽限期
     */
    private boolean isInGracePeriod(String refreshToken) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_GRACE_PREFIX + refreshToken));
    }

}
