package com.kbook.common.api;

import lombok.Data;

/**
 * 分页请求参数
 */
@Data
public class PageRequest {

    /** 当前页码，从1开始 */
    private int page = 1;

    /** 每页条数 */
    private int size = 10;

    /**
     * 计算数据库查询偏移量
     *
     * @return 偏移量
     */
    public int getOffset() {
        return (page - 1) * size;
    }
}
