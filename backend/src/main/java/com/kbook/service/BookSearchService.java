package com.kbook.service;

import com.kbook.common.api.PageResult;
import com.kbook.document.BookDocument;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.repository.BookSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 图书搜索服务 — ES 全文检索 + JPA 降级
 * <p>
 * 双写策略：图书写入时同步更新 ES 索引
 * 搜索优先走 ES，ES 不可用时降级到 JPA LIKE 查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookSearchService {

    private final BookSearchRepository searchRepository;
    private final BookRepository bookRepository;

    /** ES 是否可用标志 */
    private volatile boolean esAvailable = true;

    /**
     * ES 全文搜索（带高亮），降级到 JPA
     */
    public PageResult<BookDocument> search(String keyword, String format, int page, int size) {
        if (!esAvailable) {
            return fallbackSearch(keyword, format, page, size);
        }

        try {
            Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "readCount"));
            Page<BookDocument> result;

            if (keyword != null && !keyword.isBlank()) {
                if (format != null && !format.isBlank()) {
                    result = searchRepository.searchWithFormat(keyword, format, pageable);
                } else {
                    result = searchRepository.searchWithHighlight(keyword, pageable);
                }
            } else if (format != null && !format.isBlank()) {
                result = searchRepository.findByFormat(format, pageable);
            } else {
                return fallbackSearch(keyword, format, page, size);
            }

            // 填充高亮内容
            List<BookDocument> docs = result.getContent();
            if (result instanceof org.springframework.data.elasticsearch.core.SearchPage) {
                // 高亮由 Spring Data Elasticsearch 自动处理
            }

            esAvailable = true;
            return PageResult.of(docs, result.getTotalElements(), page, size);
        } catch (Exception e) {
            log.warn("ES 搜索异常，降级到 JPA: {}", e.getMessage());
            esAvailable = false;
            return fallbackSearch(keyword, format, page, size);
        }
    }

    /**
     * 搜索建议（前缀匹配）
     */
    public List<String> suggest(String keyword) {
        if (!esAvailable || keyword == null || keyword.isBlank()) {
            return List.of();
        }
        try {
            Pageable limit = PageRequest.of(0, 8);
            List<BookDocument> docs = searchRepository.suggestByTitle(keyword, limit);
            return docs.stream().map(BookDocument::getTitle).distinct().collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("ES 搜索建议异常: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * JPA 降级搜索
     */
    private PageResult<BookDocument> fallbackSearch(String keyword, String format, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "readCount"));
        Page<Book> jpaResult = bookRepository.searchBooks(keyword, format, pageable);
        List<BookDocument> docs = jpaResult.getContent().stream()
                .map(this::toDocument)
                .collect(Collectors.toList());
        return PageResult.of(docs, jpaResult.getTotalElements(), page, size);
    }

    /**
     * 同步单本图书到 ES
     */
    public void indexBook(Book book) {
        if (!esAvailable) return;
        try {
            searchRepository.save(toDocument(book));
        } catch (Exception e) {
            log.warn("ES 索引失败 bookId={}: {}", book.getId(), e.getMessage());
            esAvailable = false;
        }
    }

    /**
     * 从 ES 删除图书
     */
    public void deleteIndex(Long bookId) {
        if (!esAvailable) return;
        try {
            searchRepository.deleteById(bookId);
        } catch (Exception e) {
            log.warn("ES 删除索引失败 bookId={}: {}", bookId, e.getMessage());
        }
    }

    /**
     * 全量重建索引
     */
    @Transactional(readOnly = true)
    public long rebuildIndex() {
        List<Book> allBooks = bookRepository.findAll();
        List<BookDocument> docs = allBooks.stream()
                .map(this::toDocument)
                .collect(Collectors.toList());
        searchRepository.saveAll(docs);
        esAvailable = true;
        log.info("ES 索引重建完成，共 {} 条", docs.size());
        return docs.size();
    }

    /**
     * Entity → Document
     */
    private BookDocument toDocument(Book book) {
        return BookDocument.builder()
                .id(book.getId())
                .title(book.getTitle())
                .author(book.getAuthor())
                .description(book.getDescription())
                .format(book.getFormat())
                .formatTags(book.getFormatTags())
                .coverUrl(book.getCoverUrl())
                .fileSize(book.getFileSize())
                .readCount(book.getReadCount())
                .rating(book.getRating())
                .totalUnits(book.getTotalUnits())
                .fileUrl(book.getFileUrl())
                .createdAt(book.getCreatedAt() != null ? book.getCreatedAt().toEpochSecond(java.time.ZoneOffset.of("+8")) : 0L)
                .build();
    }
}
