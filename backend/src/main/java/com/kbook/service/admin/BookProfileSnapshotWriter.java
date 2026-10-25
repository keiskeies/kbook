package com.kbook.service.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.dto.admin.BookProfileTrendPointVO;
import com.kbook.common.exception.BusinessException;
import com.kbook.dto.admin.BookProfileSnapshotResultVO;
import com.kbook.dto.admin.BookProfileVO;
import com.kbook.entity.BookProfileDaily;
import com.kbook.repository.BookProfileDailyRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongPredicate;

/**
 * 快照的写路径，单独成 bean。
 *
 * 为什么不并进 {@link BookProfileSnapshotService}：定时任务在同一个类里调 {@code @Transactional} 方法
 * 属于 self-invocation，代理不经过，事务注解**静默失效**。拆开后调用跨 bean，事务才是真的。
 * 这也是它和"读路径"分开的第二个理由：写库与只读查询的失败影响不同。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookProfileSnapshotWriter {

    private final EntityManager em;
    private final BookProfileService bookProfileService;
    private final BookProfileDailyRepository repository;
    private final ObjectMapper objectMapper;

    @Value("${kbook.admin.book-profile.snapshot-max-books:500}")
    private int maxBooks;

    /** 逗号分隔的读者口径；默认只拍 DEEP（dev 里唯一有数据的口径，生产可改 READ,DEEP） */
    @Value("${kbook.admin.book-profile.snapshot-bases:DEEP}")
    private String bases;

    /** 同实例内串行化：定时任务与手工触发撞在一起时，第二个请求得到可读提示，而不是唯一键 500（附录 AL） */
    private final ReentrantLock runLock = new ReentrantLock();

    @Transactional(rollbackFor = Exception.class)
    public List<BookProfileSnapshotResultVO> snapshot(LocalDate date) {
        if (!runLock.tryLock()) {
            throw new BusinessException("快照正在执行，请稍后重试");
        }
        try {
            return snapshotLocked(date);
        } finally {
            runLock.unlock();
        }
    }

    private List<BookProfileSnapshotResultVO> snapshotLocked(LocalDate date) {
        List<BookProfileSnapshotResultVO> results = new ArrayList<>();
        for (String raw : bases.split(",")) {
            String token = raw.trim().toUpperCase(Locale.ROOT);
            if (token.isEmpty()) {
                continue;
            }
            results.add(snapshotBasis(date, BookProfileService.Basis.parse(token)));
        }
        return results;
    }

    /** 单书趋势：只回人数与填写数，分布请按日取 book-profile（响应体积与隐私都更稳） */
    public List<BookProfileTrendPointVO> trend(Long bookId, String basisParam, int days) {
        BookProfileService.Basis basis = BookProfileService.Basis.parse(basisParam);
        int span = Math.min(Math.max(days, 1), 366);
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(span - 1L);
        return repository
                .findByBookIdAndBasisAndSnapshotDateBetweenOrderBySnapshotDateAsc(bookId, basis.name(), from, to)
                .stream()
                .map(row -> BookProfileTrendPointVO.builder()
                        .date(row.getSnapshotDate().toString())
                        .readers(row.getReaders())
                        .profiledUsers(row.getProfiledUsers())
                        .sampleProtected(row.isSampleProtected())
                        .build())
                .toList();
    }

    private BookProfileSnapshotResultVO snapshotBasis(LocalDate date, BookProfileService.Basis basis) {
        List<Long> candidates = candidates(basis);
        int written = 0;
        int updated = 0;
        for (Long bookId : candidates) {
            BookProfileVO profile = bookProfileService.getBookProfile(bookId, 0, null, null, basis.name());
            BookProfileDaily row = repository
                    .findByBookIdAndSnapshotDateAndBasis(bookId, date, basis.name())
                    .orElse(null);
            if (row == null) {
                row = BookProfileDaily.builder()
                        .bookId(bookId)
                        .snapshotDate(date)
                        .basis(basis.name())
                        .createdAt(LocalDateTime.now())
                        .build();
                written++;
            } else {
                updated++;
            }
            row.setReaders((int) profile.getReaders());
            row.setProfiledUsers((int) profile.getProfiledUsers());
            row.setSampleProtected(profile.isSampleProtected());
            row.setPayload(profile.isSampleProtected() ? null : payload(profile));
            repository.save(row);
        }
        return new BookProfileSnapshotResultVO(date.toString(), basis.name(), candidates.size(), written, updated);
    }

    /** 候选：该口径下读者数达到 cohort 门槛的书，按读者数降序取前 max-books 本 */
    private List<Long> candidates(BookProfileService.Basis basis) {
        String sql = ProfileReaderSql.bookUserAggregate(basis, "1 = 1", "");
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql).getResultList();
        return pickCandidates(rows, maxBooks, readers -> bookProfileService.isCohortProtected(basis, readers));
    }

    /**
     * 纯函数：过滤掉零读者与 cohort 保护的书，按读者数降序取前 maxBooks 本。
     * 抽出来是为了能在单测里钉住"排序 + 截断 + 闸门"三件事，
     * 而不是只靠一次真实快照的返回条数去猜。
     */
    static List<Long> pickCandidates(List<Object[]> rows, int maxBooks, LongPredicate isProtected) {
        List<Object[]> ranked = new ArrayList<>();
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || !(row[0] instanceof Number id)) {
                continue;
            }
            long count = row[1] instanceof Number n ? n.longValue() : 0L;
            if (count <= 0 || isProtected.test(count)) {
                continue;
            }
            ranked.add(new Object[]{id.longValue(), count});
        }
        ranked.sort(Comparator.comparingLong((Object[] r) -> (Long) r[1]).reversed());
        List<Long> ids = new ArrayList<>();
        for (Object[] row : ranked.subList(0, Math.min(Math.max(maxBooks, 0), ranked.size()))) {
            ids.add((Long) row[0]);
        }
        return ids;
    }

    private String payload(BookProfileVO profile) {
        try {
            return objectMapper.writeValueAsString(profile.getDimensions());
        } catch (JsonProcessingException e) {
            // 分布序列化失败不该让整批快照失败：这一本留空，趋势仍能看人数
            log.warn("画像快照序列化失败 bookId={}", profile.getBookId(), e);
            return null;
        }
    }
}
