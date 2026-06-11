package com.kbook.repository;

import com.kbook.entity.UploadedFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 上传文件数据访问层
 */
@Repository
public interface UploadedFileRepository extends BaseRepository<UploadedFile, Long> {

    /**
     * 根据文件名查询上传文件记录
     */
    Optional<UploadedFile> findByFilename(String filename);

    /**
     * 根据文件名和上传者ID查询上传文件记录（用于权限校验）
     */
    @Query("SELECT uf FROM UploadedFile uf WHERE uf.filename = :filename AND uf.uploaderId = :uploaderId")
    Optional<UploadedFile> findByFilenameAndUploaderId(@Param("filename") String filename, @Param("uploaderId") Long uploaderId);
}
