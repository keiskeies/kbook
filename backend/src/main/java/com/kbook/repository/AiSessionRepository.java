package com.kbook.repository;

import com.kbook.entity.AiSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiSessionRepository extends JpaRepository<AiSession, Long> {

    Optional<AiSession> findBySessionId(String sessionId);

    List<AiSession> findByUserIdAndTypeOrderByUpdatedAtDesc(Long userId, String type);

    List<AiSession> findByUserIdAndTypeAndBookIdOrderByUpdatedAtDesc(Long userId, String type, Long bookId);

    List<AiSession> findByUserIdOrderByUpdatedAtDesc(Long userId);

    void deleteByUserIdAndSessionId(Long userId, String sessionId);
}
