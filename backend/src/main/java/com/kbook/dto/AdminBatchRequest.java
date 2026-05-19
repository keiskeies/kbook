package com.kbook.dto;

import lombok.Data;

import java.util.List;

/**
 * 管理员批量操作请求
 * 用于批量处理用户（如批量审核、批量封禁等）
 */
@Data
public class AdminBatchRequest {
    /** 用户ID列表 */
    private List<Long> userIds;
}
