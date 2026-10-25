package com.kbook.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 单书画像的每日快照（P3 第三步，口径见设计文档附录 Z，表设计见附录 AA.3）。
 *
 * 一行 = 一本书 × 一天 × 一个读者口径。保护态也存（趋势要看人数），但 {@code payload} 为空——
 * 人数不是分布，不构成反推。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "book_profile_daily",
        uniqueConstraints = @UniqueConstraint(name = "uk_profile_daily",
                columnNames = {"book_id", "snapshot_date", "basis"}),
        indexes = @Index(name = "idx_profile_daily_date", columnList = "snapshot_date"))
public class BookProfileDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(nullable = false, length = 8)
    private String basis;

    @Column(nullable = false)
    private int readers;

    @Column(name = "profiled_users", nullable = false)
    private int profiledUsers;

    @Column(name = "sample_protected", nullable = false)
    private boolean sampleProtected;

    /** 维度分布的 JSON，结构与 {@code BookProfileVO.dimensions} 相同；保护态为 null */
    @Column(columnDefinition = "LONGTEXT")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
