package com.kbook.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 每日快照的一次执行结果（每个读者口径一条）。
 *
 * `written` / `updated` 分开计数是为了让"重复跑是否幂等"能从响应本身看出来：
 * 同一天第二次执行应当 `written=0`、`updated=candidates`。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookProfileSnapshotResultVO {

    private String date;
    private String basis;
    private int candidates;
    private int written;
    private int updated;
}
