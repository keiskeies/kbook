package com.kbook.service.notification;

import com.kbook.config.properties.NotificationProperties;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import freemarker.template.Configuration;
import freemarker.template.TemplateException;
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

import java.io.IOException;
import java.io.StringWriter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 邮件通知服务 — 统一管理所有邮件发送
 * <p>
 * 负责平台所有邮件通知的构建和发送，包括：
 * - 验证码邮件（注册、登录、修改密码等场景）
 * - 邀请邮件（邀请好友加入平台）
 * - 书评回复通知邮件
 * - 书评点赞通知邮件
 * - 书评里程碑达标邮件（回复数/点赞数达到阈值时触发）
 * - 审核通过/封禁/解封通知邮件（管理员操作用户状态变更时触发）
 * <p>
 * 设计特点：
 * - 使用异步发送（@Async）避免阻塞主流程
 * - 支持开发模式（sendEnabled=false）渲染模板并打印完整 HTML，方便预览
 * - 通过 Redis 防止重复发送达标通知
 * - FreeMarker 模板渲染，模板文件位于 resources/templates/mail/，可直接浏览器打开预览
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogModule("邮件通知")
public class EmailNotificationService {

    /** Spring Boot 自带的邮件发送器，负责 SMTP 协议的邮件投递 */
    private final JavaMailSender mailSender;
    /** Redis 操作模板，用于防重复发送标记和邀请码存储 */
    private final StringRedisTemplate redisTemplate;
    /** 通知相关配置属性（邮件开关、邀请过期时间、阈值等） */
    private final NotificationProperties notificationProps;
    /** FreeMarker 模板引擎，用于渲染 HTML 邮件模板 */
    private final Configuration freemarkerConfig;

    /** 发件人邮箱地址，从 spring.mail.username 读取 */
    @Value("${spring.mail.username:}")
    private String mailFrom;

    /** Redis Key 前缀：邀请码存储，格式为 invite:code:{code} */
    private static final String INVITE_CODE_PREFIX = "invite:code:";
    /** Redis Key 前缀：回复达标通知防重标记，格式为 notified:reply:{commentId}:{threshold} */
    private static final String REPLY_NOTIFIED_PREFIX = "notified:reply:";
    /** Redis Key 前缀：点赞达标通知防重标记，格式为 notified:like:{commentId}:{threshold} */
    private static final String LIKE_NOTIFIED_PREFIX = "notified:like:";

    /**
     * 邮件类型枚举
     * <p>
     * 每种类型对应一个 FreeMarker 模板文件（resources/templates/mail/{templateId}.ftl），
     * templateId 用于定位模板，displayName 用于日志输出
     */
    @Getter
    public enum EmailType {
        /** 验证码邮件：注册、登录、修改密码等场景使用 */
        VERIFICATION_CODE("验证码", "verification"),
        /** 邀请邮件：邀请好友加入平台阅读 */
        INVITATION("邀请通知", "invitation"),
        /** 评论回复通知：有人回复了用户的书评 */
        COMMENT_REPLY("回复通知", "comment-reply"),
        /** 评论点赞通知：有人点赞了用户的书评 */
        COMMENT_LIKE("点赞通知", "comment-like"),
        /** 书评里程碑达标通知：书评回复数或点赞数达到配置阈值 */
        BOOK_REVIEW_THRESHOLD("书评达标通知", "book-review-threshold"),
        /** 审核通过通知：管理员审核通过用户注册申请 */
        ACCOUNT_APPROVED("审核通过通知", "account-approved"),
        /** 账号封禁通知：管理员封禁或拒绝用户账号 */
        ACCOUNT_BANNED("账号封禁通知", "account-banned"),
        /** 账号解封通知：管理员解封用户账号 */
        ACCOUNT_UNBANNED("账号解封通知", "account-unbanned");

        private final String displayName;
        private final String templateId;

        EmailType(String displayName, String templateId) {
            this.displayName = displayName;
            this.templateId = templateId;
        }
    }

    // ================================================================
    // 公共发送方法
    // ================================================================

    /**
     * 发送验证码邮件
     *
     * @param toEmail       收件人邮箱地址
     * @param sceneName     场景名称（如"注册"、"登录"、"修改密码"）
     * @param code          验证码字符串
     * @param expireMinutes 验证码有效期（分钟）
     */
    @LogAction("发送验证码邮件")
    public void sendVerificationCode(String toEmail, String sceneName, String code, int expireMinutes) {
        // 场景图标
        String sceneIcon = switch (sceneName) {
            case "注册" -> "📝";
            case "登录" -> "🔑";
            case "重置密码", "修改密码" -> "🔒";
            case "绑定邮箱", "绑定" -> "📧";
            default -> "✉️";
        };
        Map<String, Object> data = new HashMap<>();
        data.put("sceneName", sceneName);
        data.put("sceneIcon", sceneIcon);
        data.put("code", code);
        data.put("expireMinutes", expireMinutes);
        sendHtmlEmail(toEmail, "【KBook】" + sceneName + "验证码", EmailType.VERIFICATION_CODE, data);
    }

    /**
     * 发送邀请邮件
     *
     * @param toEmail     被邀请人的邮箱地址
     * @param inviterName 邀请人昵称
     * @param bookTitle   被分享的书籍标题
     * @return 生成的邀请码（12位大写字母数字）
     */
    @LogAction("发送邀请邮件")
    public String sendInvitation(String toEmail, String inviterName, String bookTitle) {
        String inviteCode = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String inviteLink = notificationProps.getBaseUrl() + "/invite/" + inviteCode;

        String redisKey = INVITE_CODE_PREFIX + inviteCode;
        String inviteData = inviterName + "|" + bookTitle + "|" + toEmail;
        redisTemplate.opsForValue().set(redisKey, inviteData,
                Duration.ofHours(notificationProps.getInvitation().getExpireHours()));

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
     * 发送审核通过通知邮件
     */
    @LogAction("发送审核通过邮件")
    public void sendAccountApprovedEmail(String toEmail, String userName) {
        Map<String, Object> data = Map.of(
                "userName", userName != null && !userName.isBlank() ? userName : toEmail.split("@")[0]
        );
        sendHtmlEmail(toEmail, "【KBook】账号审核通过通知", EmailType.ACCOUNT_APPROVED, data);
    }

    /**
     * 发送账号封禁通知邮件
     */
    @LogAction("发送账号封禁邮件")
    public void sendAccountBannedEmail(String toEmail, String userName) {
        Map<String, Object> data = Map.of(
                "userName", userName != null && !userName.isBlank() ? userName : toEmail.split("@")[0]
        );
        sendHtmlEmail(toEmail, "【KBook】账号状态变更通知", EmailType.ACCOUNT_BANNED, data);
    }

    /**
     * 发送账号解封通知邮件
     */
    @LogAction("发送账号解封邮件")
    public void sendAccountUnbannedEmail(String toEmail, String userName) {
        Map<String, Object> data = Map.of(
                "userName", userName != null && !userName.isBlank() ? userName : toEmail.split("@")[0]
        );
        sendHtmlEmail(toEmail, "【KBook】账号已恢复通知", EmailType.ACCOUNT_UNBANNED, data);
    }

    // ================================================================
    // 阈值达标通知（带 Redis 防重）
    // ================================================================

    /**
     * 检查并发送书评回复达标通知
     */
    @LogAction("检查并发送回复达标通知")
    public void checkAndSendReplyThresholdNotification(
            Long commentId, String toEmail, String replierName,
            String bookTitle, String commentPreview, int replyCount) {

        Set<Integer> thresholds = parseThresholds(notificationProps.getCommentReplyThresholds());
        for (int threshold : thresholds) {
            if (replyCount == threshold) {
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

                redisTemplate.opsForValue().set(notifiedKey, "1");
                log.info("发送书评回复达标通知: commentId={}, threshold={}", commentId, threshold);
                break;
            }
        }
    }

    /**
     * 检查并发送书评点赞达标通知
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

    // ================================================================
    // 核心发送 + 模板渲染
    // ================================================================

    /**
     * 通用 HTML 邮件发送（异步执行）
     * <p>
     * 通过 FreeMarker 模板引擎渲染 HTML 邮件内容。
     * 开发模式下（sendEnabled=false）渲染完整 HTML 并打印日志，方便预览效果。
     *
     * @param to      收件人邮箱地址
     * @param subject 邮件主题
     * @param type    邮件类型，决定使用哪个 .ftl 模板文件
     * @param data    模板变量数据
     */
    @LogAction("发送HTML邮件")
    @Async
    public void sendHtmlEmail(String to, String subject, EmailType type, Map<String, Object> data) {
        // 渲染模板（开发模式也需要渲染，方便预览）
        String htmlContent;
        try {
            htmlContent = renderTemplate(type.getTemplateId(), data);
        } catch (Exception e) {
            log.error("邮件模板渲染失败: type={}, to={}", type.getTemplateId(), to, e);
            return;
        }

        // 开发模式下不实际发送，仅打印完整 HTML 便于预览
        if (!notificationProps.isSendEnabled()) {
            log.info("====== 邮件预览（开发模式，未实际发送）======");
            log.info("收件人: {}  主题: {}  类型: {}", to, subject, type.getDisplayName());
            log.info("====== HTML 内容预览 ======\n{}", htmlContent);
            log.info("====== 邮件预览结束 ======");
            return;
        }

        String from = mailFrom;
        if (from == null || from.isBlank()) {
            log.warn("邮件发送跳过: 未配置发件人邮箱 (spring.mail.username)");
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("邮件发送成功: to={}, subject={}", to, subject);
        } catch (Exception e) {
            log.error("邮件发送失败: to={}", to, e);
        }
    }

    /**
     * 使用 FreeMarker 渲染邮件模板
     * <p>
     * 模板路径：resources/templates/mail/{templateName}.ftl
     * 所有模板自动注入 baseUrl 和 sendTime 变量。
     *
     * @param templateName 模板文件名（不含路径和后缀）
     * @param data         业务数据
     * @return 渲染后的完整 HTML
     */
    private String renderTemplate(String templateName, Map<String, Object> data)
            throws IOException, TemplateException {
        // 注入公共变量
        Map<String, Object> model = new HashMap<>(data);
        model.put("baseUrl", notificationProps.getBaseUrl());
        model.put("sendTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

        StringWriter writer = new StringWriter();
        freemarkerConfig.getTemplate("mail/" + templateName + ".ftl").process(model, writer);
        return writer.toString();
    }

    /**
     * 解析阈值配置字符串为有序整数集合
     */
    private Set<Integer> parseThresholds(String thresholdsStr) {
        return Arrays.stream(thresholdsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
