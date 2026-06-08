package com.kbook.service.notification;

import com.kbook.config.properties.NotificationProperties;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import jakarta.mail.internet.MimeMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 邮件通知服务 - 统一管理所有邮件发送
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogModule("邮件通知")
public class EmailNotificationService {

    private final JavaMailSender mailSender;
    private final StringRedisTemplate redisTemplate;
    private final NotificationProperties notificationProps;

    @Value("${spring.mail.username:}")
    private String mailFrom;

    private static final String INVITE_CODE_PREFIX = "invite:code:";
    private static final String REPLY_NOTIFIED_PREFIX = "notified:reply:";
    private static final String LIKE_NOTIFIED_PREFIX = "notified:like:";

    /**
     * 邮件类型枚举
     */
    @Getter
    public enum EmailType {
        VERIFICATION_CODE("验证码", "verification"),
        INVITATION("邀请通知", "invitation"),
        COMMENT_REPLY("回复通知", "comment_reply"),
        COMMENT_LIKE("点赞通知", "comment_like"),
        BOOK_REVIEW_THRESHOLD("书评达标通知", "book_review_threshold");

        private final String displayName;
        private final String templateId;

        EmailType(String displayName, String templateId) {
            this.displayName = displayName;
            this.templateId = templateId;
        }

    }

    /**
     * 发送验证码邮件
     */
    @LogAction("发送验证码邮件")
    public void sendVerificationCode(String toEmail, String sceneName, String code, int expireMinutes) {
        Map<String, Object> data = Map.of(
                "sceneName", sceneName,
                "code", code,
                "expireMinutes", expireMinutes
        );
        sendHtmlEmail(toEmail, "【KBook】" + sceneName + "验证码", EmailType.VERIFICATION_CODE, data);
    }

    /**
     * 生成邀请链接并发送邀请邮件
     *
     * @return 邀请码
     */
    @LogAction("发送邀请邮件")
    public String sendInvitation(String toEmail, String inviterName, String bookTitle) {
        // 生成邀请码
        String inviteCode = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String inviteLink = notificationProps.getBaseUrl() + "/invite/" + inviteCode;

        // 存储邀请信息到 Redis
        String redisKey = INVITE_CODE_PREFIX + inviteCode;
        String inviteData = inviterName + "|" + bookTitle + "|" + toEmail;
        redisTemplate.opsForValue().set(redisKey, inviteData, Duration.ofHours(notificationProps.getInvitation().getExpireHours()));

        Map<String, Object> data = Map.of(
                "inviterName", inviterName,
                "bookTitle", bookTitle,
                "inviteLink", inviteLink,
                "inviteCode", inviteCode,
                "expireHours", notificationProps.getInvitation().getExpireHours(),
                "actionText", "接受邀请"
        );
        sendHtmlEmail(toEmail, "【KBook】" + inviterName + " 邀请你加入阅读", EmailType.INVITATION, data);

        return inviteCode;
    }

    /**
     * 检查并发送书评回复达标通知
     *
     * @param commentId      书评ID
     * @param toEmail        收件人邮箱
     * @param replierName    回复者昵称
     * @param bookTitle      图书标题
     * @param commentPreview 书评预览
     * @param replyCount     当前回复数
     */
    @LogAction("检查并发送回复达标通知")
    public void checkAndSendReplyThresholdNotification(
            Long commentId, String toEmail, String replierName,
            String bookTitle, String commentPreview, int replyCount) {

        Set<Integer> thresholds = parseThresholds(notificationProps.getCommentReplyThresholds());
        for (int threshold : thresholds) {
            if (replyCount == threshold) {
                // 检查是否已发送过该阈值通知
                String notifiedKey = REPLY_NOTIFIED_PREFIX + commentId + ":" + threshold;
                if (Boolean.TRUE.equals(redisTemplate.hasKey(notifiedKey))) {
                    continue;
                }

                Map<String, Object> data = Map.of(
                        "thresholdType", "回复数",
                        "thresholdValue", threshold,
                        "bookTitle", bookTitle,
                        "content", commentPreview,
                        "actionText", "查看书评"
                );
                sendHtmlEmail(toEmail, "【KBook】你的书评回复数突破" + threshold + "了！",
                        EmailType.BOOK_REVIEW_THRESHOLD, data);

                // 标记已发送（永久有效）
                redisTemplate.opsForValue().set(notifiedKey, "1");
                log.info("发送书评回复达标通知: commentId={}, threshold={}", commentId, threshold);
                break;
            }
        }
    }

    /**
     * 检查并发送书评点赞达标通知
     *
     * @param commentId      书评ID
     * @param toEmail        收件人邮箱
     * @param likerName      点赞者昵称
     * @param bookTitle      图书标题
     * @param commentPreview 书评预览
     * @param likeCount      当前点赞数
     */
    @LogAction("检查并发送点赞达标通知")
    public void checkAndSendLikeThresholdNotification(
            Long commentId, String toEmail, String likerName,
            String bookTitle, String commentPreview, int likeCount) {

        Set<Integer> thresholds = parseThresholds(notificationProps.getCommentLikeThresholds());
        for (int threshold : thresholds) {
            if (likeCount == threshold) {
                String notifiedKey = LIKE_NOTIFIED_PREFIX + commentId + ":" + threshold;
                if (Boolean.TRUE.equals(redisTemplate.hasKey(notifiedKey))) {
                    continue;
                }

                Map<String, Object> data = Map.of(
                        "thresholdType", "点赞数",
                        "thresholdValue", threshold,
                        "bookTitle", bookTitle,
                        "content", commentPreview,
                        "actionText", "查看书评"
                );
                sendHtmlEmail(toEmail, "【KBook】你的书评点赞数突破" + threshold + "了！",
                        EmailType.BOOK_REVIEW_THRESHOLD, data);

                redisTemplate.opsForValue().set(notifiedKey, "1");
                log.info("发送书评点赞达标通知: commentId={}, threshold={}", commentId, threshold);
                break;
            }
        }
    }

    /**
     * 通用HTML邮件发送
     */
    @LogAction("发送HTML邮件")
    @Async
    public void sendHtmlEmail(String to, String subject, EmailType type, Map<String, Object> data) {
        if (!notificationProps.isSendEnabled()) {
            log.info("====== 邮件发送（开发模式，未实际发送）====== 收件人:{} 主题:{}", to, subject);
            log.info("====== 邮件内容类型: {} ======", type.getDisplayName());
            log.info("====== 邮件数据: {} ======", data);
            return;
        }

        String from = mailFrom;
        if (from == null || from.isBlank()) {
            log.warn("邮件发送跳过: 未配置发件人邮箱 (notification.mail-username)");
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);

            String htmlContent = buildEmailHtml(type, data);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("邮件发送成功: to={}, subject={}", to, subject);
        } catch (Exception e) {
            log.error("邮件发送失败: to={}", to, e);
        }
    }

    /**
     * 解析阈值字符串为有序集合
     */
    private Set<Integer> parseThresholds(String thresholdsStr) {
        return Arrays.stream(thresholdsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 构建统一的HTML邮件模板
     */
    private String buildEmailHtml(EmailType type, Map<String, Object> data) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                </head>
                <body style="margin: 0; padding: 0; font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif; background-color: #f0f2f5;">
                    <div style="max-width: 520px; margin: 40px auto; background: #ffffff; border-radius: 12px; box-shadow: 0 2px 12px rgba(0,0,0,0.08); overflow: hidden;">
                        <!-- Header -->
                        <div style="background: linear-gradient(135deg, #6366f1 0%%, #8b5cf6 100%%); padding: 28px 24px; text-align: center;">
                            <h1 style="color: #ffffff; margin: 0; font-size: 26px; font-weight: 600; letter-spacing: 3px;">KBook</h1>
                            <p style="color: rgba(255,255,255,0.85); margin: 6px 0 0; font-size: 13px;">智能阅读平台</p>
                        </div>
                
                        <!-- Content Area -->
                        <div style="padding: 28px 24px;">
                            %s
                        </div>
                
                        <!-- Footer -->
                        <div style="border-top: 1px solid #e5e7eb; padding: 20px 24px; background: #f9fafb;">
                            <p style="color: #9ca3af; margin: 0; font-size: 12px; text-align: center; line-height: 1.8;">
                                此邮件由系统自动发送 · 请勿直接回复<br>
                                发送时间：%s<br>
                                <a href="%s" style="color: #6366f1; text-decoration: none;">访问 KBook</a>
                            </p>
                        </div>
                    </div>
                </body>
                </html>
                """.formatted(getEmailBody(type, data), now, notificationProps.getBaseUrl());
    }

    /**
     * 根据邮件类型获取内容区域HTML
     */
    private String getEmailBody(EmailType type, Map<String, Object> data) {
        return switch (type) {
            case VERIFICATION_CODE -> buildVerificationCodeBody(data);
            case INVITATION -> buildInvitationBody(data);
            case COMMENT_REPLY -> buildCommentReplyBody(data);
            case COMMENT_LIKE -> buildCommentLikeBody(data);
            case BOOK_REVIEW_THRESHOLD -> buildThresholdBody(data);
        };
    }

    /**
     * 验证码邮件内容
     */
    private String buildVerificationCodeBody(Map<String, Object> data) {
        return """
                <h2 style="color: #1f2937; margin: 0 0 12px; font-size: 20px; font-weight: 600;">%s验证</h2>
                <p style="color: #6b7280; margin: 0 0 24px; font-size: 14px; line-height: 1.7;">
                    您好！您正在执行 <strong style="color: #374151;">%s</strong> 操作，请使用下方验证码完成验证。
                </p>
                <div style="background: linear-gradient(135deg, #f5f3ff 0%%, #ede9fe 100%%); border-radius: 12px; padding: 24px; text-align: center; margin-bottom: 24px; border: 1px solid #ddd6fe;">
                    <p style="color: #7c3aed; margin: 0 0 8px; font-size: 12px; text-transform: uppercase; letter-spacing: 2px; font-weight: 500;">验证码</p>
                    <p style="color: #6d28d9; margin: 0; font-size: 38px; font-weight: 700; letter-spacing: 10px; font-family: 'Consolas', 'Courier New', monospace;">%s</p>
                </div>
                <div style="background: #fef3c7; border-radius: 8px; padding: 14px 16px; margin-bottom: 8px;">
                    <p style="color: #92400e; margin: 0; font-size: 13px; line-height: 1.7;">
                        <strong>⚠ 安全提示</strong><br>
                        • 验证码 <strong>%d分钟</strong> 内有效<br>
                        • 请勿将验证码告知他人<br>
                        • 如非本人操作，请忽略此邮件
                    </p>
                </div>
                """.formatted(
                data.get("sceneName"),
                data.get("sceneName"),
                data.get("code"),
                data.get("expireMinutes")
        );
    }

    /**
     * 邀请邮件内容
     */
    private String buildInvitationBody(Map<String, Object> data) {
        return """
                <h2 style="color: #1f2937; margin: 0 0 12px; font-size: 20px; font-weight: 600;">📚 阅读邀请</h2>
                <p style="color: #6b7280; margin: 0 0 20px; font-size: 14px; line-height: 1.7;">
                    <strong style="color: #374151;">%s</strong> 邀请你一起阅读 <strong style="color: #6366f1;">《%s》</strong>
                </p>
                <div style="background: linear-gradient(135deg, #ecfdf5 0%%, #d1fae5 100%%); border-radius: 12px; padding: 20px; margin-bottom: 16px; border: 1px solid #a7f3d0;">
                    <p style="color: #065f46; margin: 0 0 12px; font-size: 13px;">
                        ⏰ 邀请链接 <strong>%d小时</strong> 内有效
                    </p>
                    <p style="color: #047857; margin: 0; font-size: 14px; word-break: break-all;">%s</p>
                </div>
                <a href="%s" style="display: inline-block; background: linear-gradient(135deg, #6366f1 0%%, #8b5cf6 100%%); color: #ffffff; padding: 14px 32px; border-radius: 8px; text-decoration: none; font-size: 15px; font-weight: 600; box-shadow: 0 2px 8px rgba(99,102,241,0.3);">%s</a>
                """.formatted(
                data.get("inviterName"),
                data.get("bookTitle"),
                data.get("expireHours"),
                data.get("inviteLink"),
                data.get("inviteLink"),
                data.get("actionText")
        );
    }

    /**
     * 评论回复邮件内容
     */
    private String buildCommentReplyBody(Map<String, Object> data) {
        return """
                <h2 style="color: #1f2937; margin: 0 0 12px; font-size: 20px; font-weight: 600;">💬 新回复提醒</h2>
                <p style="color: #6b7280; margin: 0 0 16px; font-size: 14px; line-height: 1.7;">
                    <strong style="color: #374151;">%s</strong> 回复了你的书评
                </p>
                <div style="background: #f9fafb; border-left: 4px solid #6366f1; border-radius: 0 8px 8px 0; padding: 16px; margin-bottom: 12px;">
                    <p style="color: #6b7280; margin: 0 0 6px; font-size: 12px;">《%s》</p>
                    <p style="color: #374151; margin: 0; font-size: 14px; line-height: 1.7;">%s</p>
                </div>
                <a href="%s/comment" style="display: inline-block; background: #f3f4f6; color: #374151; padding: 12px 24px; border-radius: 8px; text-decoration: none; font-size: 14px; font-weight: 500;">查看回复</a>
                """.formatted(
                data.get("userName"),
                data.get("bookTitle"),
                data.get("content"),
                notificationProps.getBaseUrl()
        );
    }

    /**
     * 评论点赞邮件内容
     */
    private String buildCommentLikeBody(Map<String, Object> data) {
        return """
                <h2 style="color: #1f2937; margin: 0 0 12px; font-size: 20px; font-weight: 600;">❤️ 书评被赞</h2>
                <p style="color: #6b7280; margin: 0 0 16px; font-size: 14px; line-height: 1.7;">
                    <strong style="color: #374151;">%s</strong> 等人赞了你的书评
                </p>
                <div style="background: #fef2f2; border-radius: 10px; padding: 16px; text-align: center; margin-bottom: 16px;">
                    <p style="color: #ef4444; margin: 0; font-size: 32px; font-weight: 700;">%d</p>
                    <p style="color: #dc2626; margin: 4px 0 0; font-size: 13px;">收获点赞</p>
                </div>
                <div style="background: #f9fafb; border-left: 4px solid #ef4444; border-radius: 0 8px 8px 0; padding: 14px; margin-bottom: 20px;">
                    <p style="color: #6b7280; margin: 0 0 6px; font-size: 12px;">《%s》</p>
                    <p style="color: #374151; margin: 0; font-size: 14px; line-height: 1.6;">%s</p>
                </div>
                <a href="%s/comment" style="display: inline-block; background: linear-gradient(135deg, #f43f5e 0%%, #e11d48 100%%); color: #ffffff; padding: 12px 24px; border-radius: 8px; text-decoration: none; font-size: 14px; font-weight: 600;">%s</a>
                """.formatted(
                data.get("userName"),
                data.get("count"),
                data.get("bookTitle"),
                data.get("content"),
                notificationProps.getBaseUrl(),
                data.get("actionText")
        );
    }

    /**
     * 书评达标邮件内容
     */
    private String buildThresholdBody(Map<String, Object> data) {
        return """
                <h2 style="color: #1f2937; margin: 0 0 12px; font-size: 20px; font-weight: 600;">🎉 书评里程碑</h2>
                <p style="color: #6b7280; margin: 0 0 20px; font-size: 14px; line-height: 1.7;">
                    恭喜！你的书评 <strong style="color: #6366f1;">%s突破%d</strong> 了！
                </p>
                <div style="background: linear-gradient(135deg, #fef3c7 0%%, #fde68a 100%%); border-radius: 12px; padding: 24px; text-align: center; margin-bottom: 20px; border: 1px solid #fcd34d;">
                    <p style="color: #92400e; margin: 0; font-size: 36px; font-weight: 700;">🏆</p>
                    <p style="color: #a16207; margin: 8px 0 0; font-size: 16px; font-weight: 600;">继续保持，期待更多精彩评论！</p>
                </div>
                <div style="background: #f9fafb; border-left: 4px solid #f59e0b; border-radius: 0 8px 8px 0; padding: 14px; margin-bottom: 20px;">
                    <p style="color: #6b7280; margin: 0 0 6px; font-size: 12px;">《%s》</p>
                    <p style="color: #374151; margin: 0; font-size: 14px; line-height: 1.6;">%s</p>
                </div>
                <a href="%s/comment" style="display: inline-block; background: linear-gradient(135deg, #f59e0b 0%%, #d97706 100%%); color: #ffffff; padding: 12px 24px; border-radius: 8px; text-decoration: none; font-size: 14px; font-weight: 600;">%s</a>
                """.formatted(
                data.get("thresholdType"),
                data.get("thresholdValue"),
                data.get("bookTitle"),
                data.get("content"),
                notificationProps.getBaseUrl(),
                data.get("actionText")
        );
    }
}
