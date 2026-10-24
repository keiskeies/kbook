package com.kbook.dto.note;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新笔记标签
 */
@Data
public class NoteTagsRequest {

    @Size(max = 8, message = "最多 8 个标签")
    private List<@Size(max = 20, message = "单个标签不能超过 20 字") String> tags;
}
