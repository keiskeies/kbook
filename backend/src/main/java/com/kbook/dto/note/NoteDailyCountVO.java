package com.kbook.dto.note;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 单日笔记数量 — 笔记页「写作节奏」条形图
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NoteDailyCountVO {

    private LocalDate date;
    private long count;
}
