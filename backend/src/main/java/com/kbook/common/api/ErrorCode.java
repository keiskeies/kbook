package com.kbook.common.api;

/**
 * 业务错误码统一常量。
 * <p>
 * 编码空间划分：
 * - 0          成功
 * - 1          通用业务错误
 * - 1000-1999  用户相关
 * - 2000-2999  图书/书架/阅读
 * - 3000-3999  评论/社交
 * - 4000-4999  AI/推荐
 * - 5000-5999  系统/配置
 * <p>
 * 特殊语义码（前端需要识别）：
 * - 1001  用户待审核
 * - 1002  用户已封禁
 */
public enum ErrorCode {

    SUCCESS(0, "success"),

    GENERIC_ERROR(1, "操作失败"),

    /** 用户待审核 */
    USER_PENDING(1001, "账号待审核"),
    /** 用户已封禁 */
    USER_BANNED(1002, "账号已被封禁"),
    /** 未登录 */
    UNAUTHORIZED(1003, "未登录或登录已过期"),
    /** 权限不足 */
    FORBIDDEN(1004, "权限不足"),
    /** 资源不存在 */
    NOT_FOUND(1005, "资源不存在"),
    /** 参数无效 */
    PARAM_INVALID(1006, "参数无效"),
    /** 重复操作 */
    DUPLICATE(1007, "重复操作"),

    /** 验证码错误 */
    VERIFY_CODE_INVALID(1100, "验证码错误或已过期"),
    /** 验证码发送频率超限 */
    VERIFY_CODE_RATE_LIMIT(1101, "验证码请求过于频繁"),

    /** 图书不存在 */
    BOOK_NOT_FOUND(2001, "图书不存在"),
    /** 书架已存在 */
    BOOKSHELF_DUPLICATE(2010, "图书已在书架中"),
    /** 阅读进度不存在 */
    PROGRESS_NOT_FOUND(2020, "阅读进度不存在"),

    /** 评论不存在 */
    COMMENT_NOT_FOUND(3001, "评论不存在"),
    /** 已点赞/已收藏 */
    ALREADY_LIKED(3002, "已点赞"),
    ALREADY_FAVORITED(3003, "已收藏"),

    /** AI 服务异常 */
    AI_SERVICE_ERROR(4001, "AI 服务异常"),
    /** AI 超时 */
    AI_TIMEOUT(4002, "AI 响应超时"),
    /** 推荐服务异常 */
    RECOMMEND_ERROR(4010, "推荐服务异常"),

    /** 限流 */
    RATE_LIMIT(5001, "请求过于频繁"),
    /** 系统繁忙 */
    SYSTEM_ERROR(5000, "系统繁忙，请稍后重试");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
