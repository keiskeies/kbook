package com.kbook.dto.tts;
import com.kbook.dto.tts.TtsStreamTicket;

/**
 * 渐进式播放凭据响应
 *
 * @param ticketId    凭据ID，用于拼接音源地址
 * @param contentType 音频容器类型（audio/mpeg 或 audio/wav）
 */
public record TtsStreamTicket(String ticketId, String contentType) {
}