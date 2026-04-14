package com.kbook.common.api;

import lombok.Data;

/**
 * 分页请求参数
 */
@Data
public class PageRequest {

    private int page = 1;

    private int size = 10;

    public int getOffset() {
        return (page - 1) * size;
    }
}
