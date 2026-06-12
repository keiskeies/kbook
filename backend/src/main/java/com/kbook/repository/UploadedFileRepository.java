package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.UploadedFile;
import org.springframework.stereotype.Repository;

/**
 * 上传文件数据访问层
 * <p>
 * 查询统一使用 BaseRepository.query() 的 Fluent API
 */
@Repository
public interface UploadedFileRepository extends BaseRepository<UploadedFile, Long> {
}
