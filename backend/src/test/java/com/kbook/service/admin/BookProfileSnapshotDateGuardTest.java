package com.kbook.service.admin;

import com.kbook.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 手工触发快照不允许未来日期。
 *
 * 未来日期没有任何业务含义，却会在 `book_profile_daily` 里留下一行"还没发生"的快照；
 * 附录 BT 里趋势窗口缺上界时，正是这些行把"近 1 天"撑成了 5 个点。
 * 校验放在 service 入口：定时任务传的永远是今天，不受影响。
 */
class BookProfileSnapshotDateGuardTest {

    @Test
    @DisplayName("未来日期被拒绝，且文案直接说明原因")
    void futureDateIsRejected() {
        BookProfileSnapshotService service = new BookProfileSnapshotService(null);

        assertThatThrownBy(() -> service.snapshot(LocalDate.now().plusDays(1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("快照日期不能晚于今天");
    }

    @Test
    @DisplayName("今天与过去不受影响：拦的是未来，不是日期本身")
    void todayAndPastPassTheGuard() {
        BookProfileSnapshotService service = new BookProfileSnapshotService(null);

        // writer 为 null，所以合法日期会抛 NPE——但异常类型不是 BusinessException，
        // 说明校验已经放行、走到了写路径。
        assertThatThrownBy(() -> service.snapshot(LocalDate.now()))
                .isNotInstanceOf(BusinessException.class);
    }
}
