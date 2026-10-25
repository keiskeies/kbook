package com.kbook.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 画像反查榜单的一项：某个读者分桶在哪些书上出现得最多 / 最集中。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileBookVO {

    private Long bookId;
    private String title;
    private String author;
    /** 属于该分桶、且与该书有对应口径行为的读者数 */
    private long count;
    /** 该书在同口径下的总读者数 */
    private long readers;
    /** count / readers：这本书的读者里有多少落在该分桶 */
    private double ratio;
    /** 全站该分桶占有效样本的比例，与单书画像同一口径 */
    private Double siteRatio;
    /** ratio / siteRatio；siteRatio 为 0 时为 null */
    private Double lift;
}
