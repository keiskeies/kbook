package com.kbook.dto.roundtable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 圆桌派下一发言人选择结果
 * <p>
 * 由 LLM 同时判断"下一发言人"和"讨论是否应该结束"，
 * 避免额外发起一次 LLM 调用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NextSpeakerResult {

    /** 下一发言人的角色 key（如 HOST、PHILOSOPHER） */
    private String nextSpeaker;

    /** 是否应该结束讨论：true 时 nextSpeaker 必为 HOST，由 HOST 做谢幕发言 */
    private boolean shouldEnd;

    /** 谢幕要点提示词：shouldEnd=true 时给 HOST 的总结要点，前端会作为 topic 传给 streamCharacterSpeak */
    private String closingSummary;
}
