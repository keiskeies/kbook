package com.kbook.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单书画像趋势的一日一点（P3 第三步）。
 *
 * 刻意不带 {@code payload}：趋势图只画人数与填写数；分布本身请按日取 book-profile。这样响应小，
 * 也避免把 15 个维度的历史分布一次性灌给前端。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookProfileTrendPointVO {

    private String date;
    private int readers;
    private int profiledUsers;
    private boolean sampleProtected;
}
