package com.kbook.dto;

import lombok.*;
import java.util.List;

/**
 * 首页数据聚合对象
 * 包含首页所需的所有数据，一次请求返回，减少前端请求次数
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HomeData {
    /** 阅读统计数据 */
    private ReadingStatsVO stats;
    
    /** 最近阅读的图书列表 */
    private List<RecentBookVO> recentBooks;
    
    /** 个性化推荐图书列表 */
    private List<RecommendedBook> personalizedBooks;
    
    /** 高分佳作图书列表 */
    private List<SimpleBookVO> topRatedBooks;
    
    /** 新书速递图书列表 */
    private List<SimpleBookVO> newBooks;
    
    /** 热门榜单图书列表 */
    private List<SimpleBookVO> popularBooks;
    
    /** 热门标签列表 */
    private List<TagStat> categories;
}
