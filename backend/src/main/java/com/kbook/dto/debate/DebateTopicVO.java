package com.kbook.dto.debate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 辩论辩题视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebateTopicVO {

    /** 辩题 */
    private String topic;

    /** 辩题来源：LLM / USER */
    private String source;

    /** 正方核心观点 */
    private String proArgument;

    /** 反方核心观点 */
    private String conArgument;
}
