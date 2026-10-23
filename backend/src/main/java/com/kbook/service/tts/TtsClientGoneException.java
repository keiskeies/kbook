package com.kbook.service.tts;

/**
 * 客户端已断开连接的信号
 * <p>
 * 与真实的合成失败区分开：用户切歌、关页面、锁屏切后台都会导致写响应体抛 IO 异常，
 * 这不是服务端故障，不应记录为 error 级别，也不应触发降级逻辑。
 * <p>
 * 声明为独立类型是为了能穿透各引擎的 try-catch 包装——
 * 引擎内部若catch Exception后统一包装成 RuntimeException，
 * 需显式放行本异常，否则中断信号会被误判为合成失败。
 */
public class TtsClientGoneException extends RuntimeException {

    public TtsClientGoneException(Throwable cause) {
        super(cause);
    }
}