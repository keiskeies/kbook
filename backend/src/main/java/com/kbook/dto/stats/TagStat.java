package com.kbook.dto.stats;

import lombok.*;

/**
 * 标签统计对象
 * 用于展示标签名称及其对应的图书数量
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TagStat {
    /** 标签名称 */
    private String name;
    /** 该标签下的图书数量 */
    private Long count;
}
