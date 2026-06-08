package com.kbook.dto.admin;

import lombok.Data;

/**
 * 管理员批量操作结果
 * 返回批量操作的处理数量
 */
@Data
public class AdminBatchResult {
    /** 处理的数量 */
    private int count;
    
    public AdminBatchResult(int count) { 
        this.count = count; 
    }
}
