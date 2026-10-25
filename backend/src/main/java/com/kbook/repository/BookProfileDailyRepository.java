package com.kbook.repository;

import com.kbook.entity.BookProfileDaily;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BookProfileDailyRepository extends JpaRepository<BookProfileDaily, Long> {

    Optional<BookProfileDaily> findByBookIdAndSnapshotDateAndBasis(Long bookId, LocalDate snapshotDate, String basis);

    List<BookProfileDaily> findByBookIdAndBasisAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(
            Long bookId, String basis, LocalDate from);
}
