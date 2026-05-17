package com.kbook.controller;

import com.kbook.common.api.PageResult;
import com.kbook.common.api.Result;
import com.kbook.common.util.CommonUtils;
import com.kbook.document.BookDocument;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.BookSearchService;
import com.kbook.service.BookService;
import com.kbook.service.RecommendService;
import com.kbook.config.properties.BookStorageProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * 图书控制器
 */
@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookController {

    private final BookService bookService;
    private final BookSearchService bookSearchService;
    private final BookRepository bookRepository;
    private final RecommendService recommendService;
    private final BookStorageProperties storageProps;

    /**
     * 获取封面图片
     * 支持回退：当请求的临时文件名（book_new_*）不存在时，自动查找正式文件名（book_{id}_*）
     */
    @GetMapping(value = "/cover/{filename}")
    public ResponseEntity<Resource> getCover(@PathVariable String filename) {
        Path coverDir = Paths.get(storageProps.getCoverPath());
        
        // 安全检查并解析路径
        Path imagePath = CommonUtils.safeResolvePath(coverDir, filename);
        if (imagePath == null || !Files.exists(imagePath)) {
            // 回退逻辑：如果是临时文件名，尝试查找正式文件
            if (filename.startsWith("book_new_")) {
                return tryFallbackCover(coverDir, filename);
            }
            return ResponseEntity.notFound().build();
        }

        return CommonUtils.buildImageResponse(imagePath, filename);
    }

    /**
     * 尝试回退查找封面文件
     */
    private ResponseEntity<Resource> tryFallbackCover(Path coverDir, String filename) {
        String tempCoverUrl = "/api/books/cover/" + filename;
        Optional<Book> bookOpt = bookRepository.findByCoverUrl(tempCoverUrl);
        
        if (bookOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Book book = bookOpt.get();
        
        // 尝试用正式文件名查找
        if (book.getCoverUrl() != null && !book.getCoverUrl().equals(tempCoverUrl)) {
            String realFilename = book.getCoverUrl().substring(book.getCoverUrl().lastIndexOf('/') + 1);
            Path realPath = CommonUtils.safeResolvePath(coverDir, realFilename);
            if (realPath != null && Files.exists(realPath)) {
                return CommonUtils.buildImageResponse(realPath, realFilename);
            }
        }
        
        // 尝试按 bookId 构建文件名查找
        String ext = filename.substring(filename.lastIndexOf('.'));
        String idFilename = "book_" + book.getId() + "_cover" + ext;
        Path idPath = CommonUtils.safeResolvePath(coverDir, idFilename);
        
        if (idPath != null && Files.exists(idPath)) {
            // 同时修复数据库中的封面URL
            book.setCoverUrl("/api/books/cover/" + idFilename);
            bookService.updateBook(book.getId(), book);
            return CommonUtils.buildImageResponse(idPath, idFilename);
        }

        return ResponseEntity.notFound().build();
    }

    /**
     * 获取图书详情
     */
    @GetMapping("/{id}")
    public Result<Book> getBook(@PathVariable Long id) {
        return Result.ok(bookService.getBookById(id));
    }

    /**
     * 搜索图书（ES 全文检索，带高亮）
     */
    @GetMapping("/search")
    public Result<PageResult<BookDocument>> searchBooks(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String format,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(bookService.searchBooksEs(keyword, format, page, size));
    }

    /**
     * 搜索建议（前缀匹配标题）
     */
    @GetMapping("/suggest")
    public Result<List<String>> suggestBooks(
            @RequestParam String keyword) {
        return Result.ok(bookService.suggestBooks(keyword));
    }

    /**
     * 阅读排行
     */
    @GetMapping("/rank/read")
    public Result<PageResult<Book>> getReadRank(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(bookService.getReadRank(page, size));
    }

    /**
     * 评分排行
     */
    @GetMapping("/rank/rating")
    public Result<PageResult<Book>> getRatingRank(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(bookService.getRatingRank(page, size));
    }

    /**
     * 新书榜
     */
    @GetMapping("/rank/new")
    public Result<PageResult<Book>> getNewBooksRank(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(bookService.getNewBooksRank(page, size));
    }

    /**
     * 按格式筛选
     */
    @GetMapping("/format/{format}")
    public Result<PageResult<Book>> getBooksByFormat(
            @PathVariable String format,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(bookService.getBooksByFormat(format, page, size));
    }

    /**
     * 按标签筛选
     */
    @GetMapping("/tag/{tag}")
    public Result<PageResult<Book>> getBooksByTag(
            @PathVariable String tag,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(bookService.getBooksByTag(tag, page, size));
    }

    /**
     * 图书入库（管理员）
     */
    @PostMapping
    public Result<Book> createBook(@RequestBody CreateBookRequest req) {
        Book book = Book.builder()
                .title(req.getTitle())
                .author(req.getAuthor())
                .coverUrl(req.getCoverUrl())
                .description(req.getDescription())
                .format(req.getFormat())
                .fileUrl(req.getFileUrl())
                .fileSize(req.getFileSize())
                .formatTags(req.getFormatTags())
                .totalUnits(req.getTotalUnits())
                .build();
        return Result.ok(bookService.createBook(book));
    }

    /**
     * 更新格式标签（管理员）
     */
    @PutMapping("/{id}/tags")
    public Result<Book> updateFormatTags(@PathVariable Long id, @RequestBody UpdateTagsRequest req) {
        return Result.ok(bookService.updateFormatTags(id, req.getTags()));
    }

    /**
     * 用户评分
     */
    @PostMapping("/{id}/rate")
    public Result<Book> rateBook(@PathVariable Long id, @RequestBody RateRequest req,
                                  Authentication authentication) {
        Double rating = req.getRating();
        if (rating == null || rating < 1.0 || rating > 5.0) {
            return Result.fail("评分范围 1.0-5.0");
        }
        rating = Math.round(rating * 10.0) / 10.0;

        // 更新书籍评分
        Book updated = bookService.rateBook(id, rating);

        // 记录用户评分到阅读历史（用于协同过滤和推荐）
        Long userId = (Long) authentication.getPrincipal();
        recommendService.recordReadAction(userId, id, "RATE", 2, String.valueOf(rating));

        return Result.ok(updated);
    }

    /**
     * 重建 ES 索引（管理员）
     */
    @PostMapping("/reindex")
    public Result<Long> rebuildIndex() {
        return Result.ok(bookSearchService.rebuildIndex());
    }

    @Data
    public static class CreateBookRequest {
        private String title;
        private String author;
        private String coverUrl;
        private String description;
        private String format;
        private String fileUrl;
        private Long fileSize;
        private String formatTags;
        private Long totalUnits;
    }

    @Data
    public static class UpdateTagsRequest {
        private List<String> tags;
    }

    @Data
    public static class RateRequest {
        private Double rating;
    }
}
