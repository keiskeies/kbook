package com.kbook.controller;

import com.kbook.common.api.PageResult;
import com.kbook.common.api.Result;
import com.kbook.document.BookDocument;
import com.kbook.dto.BookSpeedReadVO;
import com.kbook.entity.Book;
import com.kbook.service.BookParserService;
import com.kbook.service.BookSearchService;
import com.kbook.service.BookService;
import com.kbook.service.RankService;
import com.kbook.service.RecommendService;
import com.kbook.common.util.CommonUtils;
import com.kbook.config.properties.BookStorageProperties;
import com.kbook.dto.CreateBookRequest;
import com.kbook.dto.RateRequest;
import com.kbook.dto.UpdateTagsRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 图书控制器
 */
@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
@Slf4j
public class BookController {

    private final BookService bookService;
    private final BookSearchService bookSearchService;
    private final RankService rankService;
    private final RecommendService recommendService;
    private final BookParserService bookParserService;
    private final ObjectMapper objectMapper;

    /** 封面图片存储路径 */
    @Value("${kbook.cover-path:./covers}")
    private String coverPath;

    /**
     * 获取封面图片
     * @param filename 封面文件名
     * @return 图片资源响应
     */
    @GetMapping(value = "/cover/{filename:.+}")
    public ResponseEntity<Resource> getCover(@PathVariable String filename) {
        Path coverDir = Paths.get(coverPath);
        Path imagePath = CommonUtils.safeResolvePath(coverDir, filename);

        if (imagePath == null || !Files.exists(imagePath)) {
            return ResponseEntity.notFound().build();
        }

        return CommonUtils.buildImageResponse(imagePath, filename);
    }

    /**
     * 获取图书详情
     */
    @GetMapping("/{id}")
    public Result<Book> getBook(@PathVariable Long id) {
        return Result.ok(bookService.getBookById(id));
    }

    /**
     * 搜索图书（关键词优先，书名/作者匹配排前）
     */
    @GetMapping("/search")
    public Result<PageResult<BookDocument>> searchBooks(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(bookService.searchBooksByKeyword(keyword, format, tag, page, size));
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
        return Result.ok(rankService.getReadRank(page, size));
    }

    /**
     * 评分排行
     */
    @GetMapping("/rank/rating")
    public Result<PageResult<Book>> getRatingRank(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(rankService.getRatingRank(page, size));
    }

    /**
     * 新书榜
     */
    @GetMapping("/rank/new")
    public Result<PageResult<Book>> getNewBooksRank(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(rankService.getNewBooksRank(page, size));
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
        Long userId = (Long) authentication.getPrincipal();
        Book updated = bookService.rateBook(id, rating, userId);

        // 记录用户评分到阅读历史（用于协同过滤和推荐）
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

    @GetMapping("/{id}/speed-read")
    public Result<BookSpeedReadVO> getSpeedRead(@PathVariable Long id) {
        Book book = bookService.getBookById(id);
        if (book == null) {
            return Result.fail("图书不存在");
        }

        if (book.getSpeedRead() != null && !book.getSpeedRead().isBlank()) {
            try {
                BookSpeedReadVO vo = objectMapper.readValue(book.getSpeedRead(), BookSpeedReadVO.class);
                return Result.ok(vo);
            } catch (Exception e) {
                log.warn("解析速读摘要失败: bookId={} - {}", id, e.getMessage());
            }
        }

        BookSpeedReadVO vo = bookParserService.generateSpeedRead(book);
        if (vo != null) {
            try {
                book.setSpeedRead(objectMapper.writeValueAsString(vo));
                book.setSpeedReadGenerated(true);
                bookService.updateBook(id, book);
            } catch (Exception e) {
                log.warn("保存速读摘要失败: bookId={} - {}", id, e.getMessage());
            }
            return Result.ok(vo);
        }

        return Result.ok(BookSpeedReadVO.builder()
                .bookId(id)
                .corePoints(List.of())
                .suitableFor(List.of())
                .notSuitableFor(List.of())
                .takeaways(List.of())
                .difficulty("未知")
                .build());
    }

}
