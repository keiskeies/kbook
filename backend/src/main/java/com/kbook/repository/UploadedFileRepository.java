package com.kbook.repository;

import com.kbook.entity.UploadedFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UploadedFileRepository extends JpaRepository<UploadedFile, Long> {

    Optional<UploadedFile> findByFilename(String filename);

    @Query("SELECT uf FROM UploadedFile uf WHERE uf.filename = :filename AND uf.uploaderId = :uploaderId")
    Optional<UploadedFile> findByFilenameAndUploaderId(@Param("filename") String filename, @Param("uploaderId") Long uploaderId);
}
