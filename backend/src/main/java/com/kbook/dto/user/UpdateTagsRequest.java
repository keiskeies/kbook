package com.kbook.dto.user;

import lombok.Data;

import java.util.List;

/**
 * 更新标签请求
 * 用于用户更新自己的兴趣标签列表
 */
@Data
public class UpdateTagsRequest {
    /** 标签名称列表 */
    private List<String> tags;
}
