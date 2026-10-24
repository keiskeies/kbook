package com.kbook.dto.note;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 笔记问答结果
 */
@Data
@Builder
public class NoteAskVO {

    private String answer;
    private List<NoteAskSourceVO> sources;
}
