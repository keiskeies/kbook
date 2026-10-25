package com.kbook.repository;

import com.kbook.entity.BookProfileDaily;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BookProfileDailyRepository extends JpaRepository<BookProfileDaily, Long> {

    Optional<BookProfileDaily> findByBookIdAndSnapshotDateAndBasis(Long bookId, LocalDate snapshotDate, String basis);

    /** 趋势窗口是闭区间 [from, to]：to 固定为今天，未来日期的行不该出现在“近 N 天”里 */
    List<BookProfileDaily> findByBookIdAndBasisAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            Long bookId, String basis, LocalDate from, LocalDate to);
}
