package com.kbook.service;

import com.kbook.common.api.PageResult;
import com.kbook.common.exception.BusinessException;
import com.kbook.document.BookDocument;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 图书服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookService {

    private final BookRepository bookRepository;
    private final BookSearchService bookSearchService;

    /**
     * 图书入库（JPA + ES 双写）
     */
    @Transactional
    public Book createBook(Book book) {
        log.info("图书入库: title={}, author={}, format={}", book.getTitle(), book.getAuthor(), book.getFormat());
        List<String> validFormats = Arrays.asList("TXT", "EPUB", "PDF");
        if (!validFormats.contains(book.getFormat())) {
            log.warn("不支持的图书格式: {}", book.getFormat());
            throw new BusinessException("不支持的图书格式: " + book.getFormat());
        }
        Book saved = bookRepository.save(book);
        bookSearchService.indexBook(saved);
        log.info("图书入库成功: id={}, title={}", saved.getId(), saved.getTitle());
        return saved;
    }

    /**
     * 更新图书信息（JPA + ES 双写）
     */
    @Transactional
    public Book updateBook(Long id, Book updates) {
        log.debug("更新图书: id={}", id);
        Book book = getBookById(id);
        if (updates.getTitle() != null) book.setTitle(updates.getTitle());
        if (updates.getAuthor() != null) book.setAuthor(updates.getAuthor());
        if (updates.getCoverUrl() != null) book.setCoverUrl(updates.getCoverUrl());
        if (updates.getDescription() != null) book.setDescription(updates.getDescription());
        if (updates.getFormatTags() != null) book.setFormatTags(updates.getFormatTags());
        if (updates.getTotalUnits() != null) book.setTotalUnits(updates.getTotalUnits());
        Book saved = bookRepository.save(book);
        bookSearchService.indexBook(saved);
        log.info("图书更新成功: id={}, title={}", saved.getId(), saved.getTitle());
        return saved;
    }

    /**
     * 获取图书详情
     */
    public Book getBookById(Long id) {
        return bookRepository.findById(id)
                .orElseThrow(() -> new BusinessException("图书不存在"));
    }

    /**
     * ES 全文搜索（优先 ES，降级 JPA）
     */
    public PageResult<BookDocument> searchBooksEs(String keyword, String format, int page, int size) {
        log.debug("ES 搜索图书: keyword={}, format={}, page={}, size={}", keyword, format, page, size);
        return bookSearchService.search(keyword, format, page, size);
    }

    /**
     * 搜索图书（JPA 原始方法，保留兼容）
     */
    public PageResult<Book> searchBooks(String keyword, String format, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "readCount"));
        Page<Book> pageData = bookRepository.searchBooks(keyword, format, pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 搜索建议
     */
    public List<String> suggestBooks(String keyword) {
        return bookSearchService.suggest(keyword);
    }

    /**
     * 阅读排行
     */
    public PageResult<Book> getReadRank(int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Book> pageData = bookRepository.findAllByOrderByReadCountDesc(pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 评分排行
     */
    public PageResult<Book> getRatingRank(int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Book> pageData = bookRepository.findAllByOrderByRatingDesc(pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 新书榜
     */
    public PageResult<Book> getNewBooksRank(int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Book> pageData = bookRepository.findAllByOrderByCreatedAtDesc(pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 按格式筛选
     */
    public PageResult<Book> getBooksByFormat(String format, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "readCount"));
        Page<Book> pageData = bookRepository.findByFormat(format, pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 增加阅读计数（JPA + ES 双写）
     */
    @Transactional
    public void incrementReadCount(Long bookId) {
        Book book = getBookById(bookId);
        book.setReadCount(book.getReadCount() + 1);
        Book saved = bookRepository.save(book);
        bookSearchService.indexBook(saved);
        log.debug("阅读计数增加: bookId={}, readCount={}", bookId, saved.getReadCount());
    }

    /**
     * 管理格式标签（JPA + ES 双写）
     */
    @Transactional
    public Book updateFormatTags(Long bookId, List<String> tags) {
        Book book = getBookById(bookId);
        String tagsJson = tags.stream()
                .map(t -> "\"" + t + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        book.setFormatTags(tagsJson);
        Book saved = bookRepository.save(book);
        bookSearchService.indexBook(saved);
        return saved;
    }

    /**
     * 更新8维度相关度得分
     */
    @Transactional
    public Book updateRelevanceScores(Long bookId, String scoresJson) {
        Book book = getBookById(bookId);
        book.setRelevanceScores(scoresJson);
        Book saved = bookRepository.save(book);
        bookSearchService.indexBook(saved);
        return saved;
    }

    /**
     * 更新图书评分（AI 初评或用户评分后重算）
     */
    @Transactional
    public Book updateRating(Long bookId, Double rating) {
        Book book = getBookById(bookId);
        book.setRating(rating);
        Book saved = bookRepository.save(book);
        bookSearchService.indexBook(saved);
        log.info("图书评分更新: bookId={}, rating={}", bookId, rating);
        return saved;
    }

    /**
     * 删除图书（JPA + ES 双删）
     */
    @Transactional
    public void deleteBook(Long id) {
        if (!bookRepository.existsById(id)) {
            throw new BusinessException("图书不存在");
        }
        bookRepository.deleteById(id);
        bookSearchService.deleteIndex(id);
        log.info("图书删除成功: id={}", id);
    }
}
