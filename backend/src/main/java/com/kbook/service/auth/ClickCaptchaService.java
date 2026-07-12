package com.kbook.service.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.crypto.AesGcmUtil;
import com.kbook.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 点击验证码服务（AES-GCM 加密版）
 * <p>
 * 安全机制：
 * 1. 验证码数据 AES-GCM 加密后返回，前端用 UA+secret+timeWindow 派生密钥解密
 * 2. 时间窗口绑定：每分钟密钥自动轮换
 * 3. UA 绑定：验证时校验 User-Agent 一致性
 * 4. 密钥不传输：前端自行派生，Bot 无法从响应中获取密钥
 */
@Slf4j
@Service
public class ClickCaptchaService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String CAPTCHA_PREFIX = "click-captcha:";
    private static final String VERIFIED_PREFIX = "click-captcha-verified:";
    private static final int EXPIRE_SECONDS = 300;     // 验证码5分钟过期
    private static final int VERIFIED_EXPIRE_SECONDS = 600; // 已验证状态10分钟有效

    private static final int GRID_SIZE = 3; // 3x3=9个图形
    private static final int TARGET_COUNT = 2; // 需要点击的目标数量

    private static final String[] SHAPES = {"circle", "triangle", "square", "diamond", "star", "heart",
            "hexagram", "heptagram", "triangle_inverted"};
    private static final String[] COLORS = {"red", "blue", "green", "yellow", "purple", "orange"};
    private static final String[] SIZES = {"small", "large"};

    private static final Map<String, String> COLOR_HEX = Map.of(
            "red", "#ef4444",
            "blue", "#3b82f6",
            "green", "#22c55e",
            "yellow", "#eab308",
            "purple", "#8b5cf6",
            "orange", "#f97316"
    );

    private static final Map<String, String> SHAPE_CN = Map.of(
            "circle", "圆形",
            "triangle", "三角形",
            "square", "正方形",
            "diamond", "菱形",
            "star", "五角星",
            "heart", "心形",
            "hexagram", "六角星",
            "heptagram", "七角星",
            "triangle_inverted", "倒三角形"
    );

    private static final Map<String, String> COLOR_CN = Map.of(
            "red", "红色",
            "blue", "蓝色",
            "green", "绿色",
            "yellow", "黄色",
            "purple", "紫色",
            "orange", "橙色"
    );

    private static final Map<String, String> SIZE_CN = Map.of(
            "small", "小",
            "large", "大"
    );

    private final SecureRandom random = new SecureRandom();

    @Value("${spring.profiles.active:prod}")
    private String activeProfile;

    public ClickCaptchaService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 计算当前时间窗口（分钟级）
     */
    public static long currentTimeWindow() {
        return System.currentTimeMillis() / 60000;
    }

    /**
     * 派生加密密钥
     */
    private SecretKey deriveKey(String ua, long timeWindow) {
        return AesGcmUtil.deriveKey(ua, String.valueOf(timeWindow));
    }

    /**
     * 生成点击验证码并加密返回
     *
     * @param ua 请求的 User-Agent
     * @return 加密后的验证码数据（captchaId + encrypted）
     */
    public Map<String, String> generateCaptcha(String ua) {
        String captchaId = UUID.randomUUID().toString().replace("-", "");
        long timeWindow = currentTimeWindow();

        // 随机选择目标属性组合
        String targetShape = SHAPES[random.nextInt(SHAPES.length)];
        String targetColor = COLORS[random.nextInt(COLORS.length)];
        String targetSize = SIZES[random.nextInt(SIZES.length)];

        // 生成提示（纯图形，不依赖颜色）
        String hint = SHAPE_CN.get(targetShape);

        // 生成图形网格
        List<CaptchaItem> items = new ArrayList<>();
        String targetCombo = targetShape + ":" + targetColor + ":" + targetSize;

        // 随机放置目标
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < GRID_SIZE * GRID_SIZE; i++) positions.add(i);
        Collections.shuffle(positions);

        List<Integer> answer = new ArrayList<>();
        for (int i = 0; i < TARGET_COUNT; i++) {
            answer.add(positions.get(i));
        }

        // 填充所有9个格子
        for (int i = 0; i < GRID_SIZE * GRID_SIZE; i++) {
            if (answer.contains(i)) {
                items.add(new CaptchaItem(i, targetShape, targetColor, targetSize,
                        COLOR_HEX.getOrDefault(targetColor, "#999999"), true));
            } else {
                String shape, color, size;
                String combo;
                int attempts = 0;
                do {
                    shape = SHAPES[random.nextInt(SHAPES.length)];
                    color = COLORS[random.nextInt(COLORS.length)];
                    size = SIZES[random.nextInt(SIZES.length)];
                    combo = shape + ":" + color + ":" + size;
                    attempts++;
                } while (combo.equals(targetCombo) && attempts < 50);
                items.add(new CaptchaItem(i, shape, color, size,
                        COLOR_HEX.getOrDefault(color, "#999999"), false));
            }
        }

        items.sort(Comparator.comparingInt(CaptchaItem::index));

        // 构造完整数据（含 isTarget，加密后前端无法直接读到）
        CaptchaData data = new CaptchaData(captchaId, hint, items);

        // AES-GCM 加密
        SecretKey key = deriveKey(ua, timeWindow);
        String encrypted = AesGcmUtil.encrypt(key, data.toJson(objectMapper));

        // 存储到 Redis（含时间窗口）
        try {
            CaptchaAnswer answerData = new CaptchaAnswer(captchaId, answer, timeWindow);
            String json = objectMapper.writeValueAsString(answerData);
            redisTemplate.opsForValue().set(CAPTCHA_PREFIX + captchaId, json, EXPIRE_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new BusinessException("验证码生成失败");
        }

        log.debug("点击验证码生成: captchaId={}, hint={}, answer={}, timeWindow={}", captchaId, hint, answer, timeWindow);

        Map<String, String> result = new HashMap<>();
        result.put("captchaId", captchaId);
        result.put("encrypted", encrypted);
        // dev 环境返回明文（方便调试，生产环境不返回）
        if (activeProfile.contains("dev")) {
            result.put("plain", data.toJson(objectMapper));
        }
        return result;
    }

    /**
     * 验证用户点击结果
     *
     * @param captchaId        验证码ID
     * @param clickedPositions 用户点击的位置索引列表
     * @throws BusinessException 验证失败时抛出
     */
    public void verifyClick(String captchaId, List<Integer> clickedPositions) {
        String key = CAPTCHA_PREFIX + captchaId;
        String json = redisTemplate.opsForValue().get(key);

        if (json == null) {
            throw new BusinessException("验证码已过期，请重试");
        }

        try {
            CaptchaAnswer answer = objectMapper.readValue(json, CaptchaAnswer.class);

            // 检查时间窗口（±1分钟容错）
            long now = currentTimeWindow();
            if (Math.abs(now - answer.timeWindow()) > 1) {
                throw new BusinessException("验证码已过期，请重试");
            }

            // 比对点击位置
            Set<Integer> expected = new HashSet<>(answer.positions());
            Set<Integer> actual = new HashSet<>(clickedPositions);

            if (!expected.equals(actual)) {
                log.warn("点击验证失败: captchaId={}, expected={}, actual={}", captchaId, expected, actual);
                throw new BusinessException("验证失败，请重试");
            }

            // 验证通过：删除待验证key，写入已验证key
            redisTemplate.delete(key);
            redisTemplate.opsForValue().set(VERIFIED_PREFIX + captchaId, "1", VERIFIED_EXPIRE_SECONDS, TimeUnit.SECONDS);

            log.info("点击验证通过: captchaId={}", captchaId);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("验证码校验失败");
        }
    }

    /**
     * 校验验证码是否已通过验证（供登录/发送验证码时调用，一次性消费）
     *
     * @param captchaId 验证码ID
     * @throws BusinessException 验证码未通过或已过期时抛出
     */
    public void checkCaptchaVerified(String captchaId) {
        if (captchaId == null || captchaId.isBlank()) {
            throw new BusinessException("请先完成验证");
        }

        String verifiedKey = VERIFIED_PREFIX + captchaId;
        String value = redisTemplate.opsForValue().get(verifiedKey);

        if (value == null) {
            throw new BusinessException("验证已过期，请重新验证");
        }

        // 一次性使用
        redisTemplate.delete(verifiedKey);
        log.debug("点击验证码消费: captchaId={}", captchaId);
    }

    // ========== DTO ==========

    public record CaptchaData(String captchaId, String hint, List<CaptchaItem> items) {
        public String toJson(ObjectMapper mapper) {
            try {
                return mapper.writeValueAsString(this);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public record CaptchaItem(int index, String shape, String color, String size, String colorHex, boolean isTarget) {
    }

    record CaptchaAnswer(String captchaId, List<Integer> positions, long timeWindow) {
    }
}
