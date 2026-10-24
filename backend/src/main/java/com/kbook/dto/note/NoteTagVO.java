package com.kbook.dto.note;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 标签及笔记数量
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NoteTagVO {

    private String tag;
    private long count;
}
