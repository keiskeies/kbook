package com.kbook.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页响应结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    /** 当前页数据列表 */
    private List<T> list;
    /** 总记录数 */
    private long total;
    /** 当前页码 */
    private int page;
    /** 每页条数 */
    private int size;

    /**
     * 构建分页结果
     *
     * @param list   当前页数据列表
     * @param total  总记录数
     * @param page   当前页码
     * @param size   每页条数
     * @return 分页结果对象
     */
    public static <T> PageResult<T> of(List<T> list, long total, int page, int size) {
        return new PageResult<>(list, total, page, size);
    }
}
