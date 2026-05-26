package com.kbook.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 上传文件实体
 * 记录用户上传的文件信息，用于聊天附件等场景
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "uploaded_files", indexes = {
        @Index(name = "idx_uploaded_file_filename", columnList = "filename"),
        @Index(name = "idx_uploaded_file_uploader", columnList = "uploader_id")
})
public class UploadedFile {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 存储文件名（唯一，UUID 生成） */
    @Column(name = "filename", nullable = false, length = 255, unique = true)
    private String filename;

    /** 原始文件名 */
    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    /** 上传者用户 ID */
    @Column(name = "uploader_id", nullable = false)
    private Long uploaderId;

    /** 文件 MIME 类型 */
    @Column(name = "content_type", length = 100)
    private String contentType;

    /** 文件大小（字节） */
    @Column(name = "file_size")
    private Long fileSize;

    /** 文件存储路径 */
    @Column(name = "file_path", length = 500)
    private String filePath;

    /** 创建时间 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
