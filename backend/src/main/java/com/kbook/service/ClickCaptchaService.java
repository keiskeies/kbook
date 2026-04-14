package com.kbook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 点击验证码服务
 *
 * 原理：
 * 1. 后端生成一组随机图形（形状+颜色+大小），从中随机选择一个目标图形
 * 2. 前端展示图形网格，用户点击与目标匹配的图形
 * 3. 后端校验点击位置是否正确
 * 4. 验证通过后标记 captchaId 为已验证
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickCaptchaService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String CAPTCHA_PREFIX = "click-captcha:";
    private static final String VERIFIED_PREFIX = "click-captcha-verified:";
    private static final int EXPIRE_SECONDS = 300;     // 验证码5分钟过期
    private static final int VERIFIED_EXPIRE_SECONDS = 600; // 已验证状态10分钟有效

    private static final int GRID_SIZE = 3; // 3x3=9个图形
    private static final int TARGET_COUNT = 2; // 需要点击的目标数量

    private static final String[] SHAPES = {"circle", "triangle", "square", "diamond", "star", "heart"};
    private static final String[] COLORS = {"red", "blue", "green", "orange", "purple", "cyan"};
    private static final String[] SIZES = {"small", "medium", "large"};

    private static final Map<String, String> COLOR_HEX = Map.of(
            "red", "#ef4444",
            "blue", "#3b82f6",
            "green", "#22c55e",
            "orange", "#f97316",
            "purple", "#a855f7",
            "cyan", "#06b6d4"
    );

    private static final Map<String, String> SHAPE_CN = Map.of(
            "circle", "圆形",
            "triangle", "三角形",
            "square", "正方形",
            "diamond", "菱形",
            "star", "五角星",
            "heart", "心形"
    );

    private static final Map<String, String> COLOR_CN = Map.of(
            "red", "红色",
            "blue", "蓝色",
            "green", "绿色",
            "orange", "橙色",
            "purple", "紫色",
            "cyan", "青色"
    );

    private static final Map<String, String> SIZE_CN = Map.of(
            "small", "小",
            "medium", "中",
            "large", "大"
    );

    private final SecureRandom random = new SecureRandom();

    /**
     * 生成点击验证码
     * 返回 captchaId、提示文字、图形列表
     */
    public CaptchaData generateCaptcha() {
        String captchaId = UUID.randomUUID().toString().replace("-", "");

        // 随机选择目标属性组合（如"红色三角形"）
        String targetShape = SHAPES[random.nextInt(SHAPES.length)];
        String targetColor = COLORS[random.nextInt(COLORS.length)];
        String targetSize = SIZES[random.nextInt(SIZES.length)];

        // 生成提示
        String hint = COLOR_CN.get(targetColor) + SIZE_CN.get(targetSize) + SHAPE_CN.get(targetShape);

        // 生成图形网格
        List<CaptchaItem> items = new ArrayList<>();
        Set<String> usedCombos = new HashSet<>();
        String targetCombo = targetShape + ":" + targetColor + ":" + targetSize;
        usedCombos.add(targetCombo);

        // 先放置目标图形（在随机位置）
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < GRID_SIZE * GRID_SIZE; i++) positions.add(i);
        Collections.shuffle(positions);

        // 放置 TARGET_COUNT 个目标
        List<Integer> targetPositions = new ArrayList<>();
        for (int i = 0; i < TARGET_COUNT; i++) {
            targetPositions.add(positions.get(i));
        }

        // 填充所有9个格子
        for (int i = 0; i < GRID_SIZE * GRID_SIZE; i++) {
            if (targetPositions.contains(i)) {
                items.add(new CaptchaItem(i, targetShape, targetColor, targetSize,
                        COLOR_HEX.getOrDefault(targetColor, "#999999"), true));
            } else {
                // 生成非目标图形（至少有一个属性不同）
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

        // 按 index 排序，确保前端展示顺序正确
        items.sort(Comparator.comparingInt(CaptchaItem::index));

        CaptchaData data = new CaptchaData(captchaId, hint, items);

        // 存储答案到 Redis（只存目标位置索引）
        List<Integer> answer = targetPositions;
        try {
            String json = objectMapper.writeValueAsString(new CaptchaAnswer(captchaId, answer));
            redisTemplate.opsForValue().set(CAPTCHA_PREFIX + captchaId, json, EXPIRE_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new BusinessException("验证码生成失败");
        }

        log.debug("点击验证码生成: captchaId={}, hint={}, answer={}", captchaId, hint, answer);
        return data;
    }

    /**
     * 验证点击结果
     */
    public void verifyClick(String captchaId, List<Integer> clickedPositions) {
        String key = CAPTCHA_PREFIX + captchaId;
        String json = redisTemplate.opsForValue().get(key);

        if (json == null) {
            throw new BusinessException("验证码已过期，请重试");
        }

        try {
            CaptchaAnswer answer = objectMapper.readValue(json, CaptchaAnswer.class);
            if (!answer.captchaId().equals(captchaId)) {
                throw new BusinessException("验证码校验失败");
            }

            // 校验点击位置：必须恰好点击所有目标位置
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

    public record CaptchaData(String captchaId, String hint, List<CaptchaItem> items) {}

    public record CaptchaItem(int index, String shape, String color, String size, String colorHex, boolean isTarget) {}

    record CaptchaAnswer(String captchaId, List<Integer> positions) {}
}
