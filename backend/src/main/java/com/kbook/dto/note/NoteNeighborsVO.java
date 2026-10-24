package com.kbook.dto.note;

import lombok.Builder;
import lombok.Data;

/**
 * 当前笔记的前后邻居；prev 更早，next 更新
 */
@Data
@Builder
public class NoteNeighborsVO {

    private NoteNeighborVO prev;
    private NoteNeighborVO next;
}
