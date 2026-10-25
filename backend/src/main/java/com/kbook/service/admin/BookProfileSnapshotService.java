package com.kbook.service.admin;

import com.kbook.common.exception.BusinessException;
import com.kbook.dto.admin.BookProfileSnapshotResultVO;
import com.kbook.dto.admin.BookProfileTrendPointVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 每日快照的入口：定时任务 + 管理端手工触发 + 趋势读取。
 *
 * 写库全部交给 {@link BookProfileSnapshotWriter}（独立 bean）。原因见那个类的注释：
 * 同一个类里调自己的 {@code @Transactional} 方法是 self-invocation，事务注解会静默失效。
 * 本类刻意不带 {@code @Transactional}，也不再持有 EntityManager——它只决定"什么时候拍"。
 *
 * 默认关闭（{@code snapshot-enabled=false}）：开发与测试环境不会因为类存在就开始写库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookProfileSnapshotService {

    private final BookProfileSnapshotWriter writer;

    @Value("${kbook.admin.book-profile.snapshot-enabled:false}")
    private boolean enabled;

    @Scheduled(cron = "${kbook.admin.book-profile.snapshot-cron:0 10 3 * * *}")
    public void scheduledSnapshot() {
        if (!enabled) {
            log.debug("画像每日快照已关闭（kbook.admin.book-profile.snapshot-enabled=false）");
            return;
        }
        try {
            List<BookProfileSnapshotResultVO> results = writer.snapshot(LocalDate.now());
            results.forEach(r -> log.info("画像快照 {} {}", r.getDate(), r));
        } catch (RuntimeException e) {
            log.error("画像每日快照失败，date={}", LocalDate.now(), e);
        }
    }

    /** 可手工触发（管理端接口），与定时任务走同一条写路径 */
    public List<BookProfileSnapshotResultVO> snapshot(LocalDate date) {
        // 未来日期没有任何业务含义，却会让趋势/对账多出一行“还没发生”的快照（附录 BT 的成因）
        if (date != null && date.isAfter(LocalDate.now())) {
            throw new BusinessException("快照日期不能晚于今天");
        }
        return writer.snapshot(date);
    }

    public List<BookProfileTrendPointVO> trend(Long bookId, String basisParam, int days) {
        if (bookId == null || bookId <= 0) {
            throw new BusinessException("缺少参数 bookId");
        }
        return writer.trend(bookId, basisParam, days);
    }
}
