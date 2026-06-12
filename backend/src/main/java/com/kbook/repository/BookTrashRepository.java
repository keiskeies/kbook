package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.BookTrash;

/**
 * 图书回收站 Repository
 * <p>
 * 查询统一使用 BaseRepository.query() 的 Fluent API
 */
public interface BookTrashRepository extends BaseRepository<BookTrash, Long> {
}
