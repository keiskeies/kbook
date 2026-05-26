package com.kbook.repository;

import com.kbook.entity.BookTrash;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookTrashRepository extends JpaRepository<BookTrash, Long> {

    List<BookTrash> findByUserIdOrderByCreatedAtDesc(Long userId);

    boolean existsByUserIdAndBookId(Long userId, Long bookId);

    void deleteByUserIdAndBookId(Long userId, Long bookId);

    Optional<BookTrash> findByUserIdAndBookId(Long userId, Long bookId);

    List<BookTrash> findByUserIdAndBookIdIn(Long userId, List<Long> bookIds);

    long countByUserId(Long userId);
}
