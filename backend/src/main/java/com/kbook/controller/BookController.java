package com.kbook.controller;

import com.kbook.common.api.PageResult;
import com.kbook.common.api.Result;
import com.kbook.common.util.CommonUtils;
import com.kbook.document.BookDocument;
import com.kbook.dto.*;
import com.kbook.entity.Book;
import com.kbook.entity.User;
import com.kbook.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
@Tag(name = "图书")
public class BookController {

    private final BookService bookService;
    private final BookSearchService bookSearchService;
    private final RankService rankService;
    private final RecommendService recommendService;
    private final BookParserService bookParserService;
    private final UserService userService;

    /**
     * 封面图片存储路径
     */
    @Value("${kbook.cover-path:./covers}")
    private String coverPath;

    /**
     * 获取封面图片
     *
     * @param filename 封面文件名
     * @return 图片资源响应
     */
//    @Operation(summary = "获取封面图片")
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
    @Operation(summary = "获取图书详情")
    @GetMapping("/{id}")
    public Result<Book> getBook(@PathVariable Long id) {
        return Result.ok(bookService.getBookById(id));
    }

    /**
     * 搜索图书（关键词优先，书名/作者匹配排前）
     */
    @Operation(summary = "搜索图书")
    @GetMapping("/search")
    public Result<PageResult<BookDocument>> searchBooks(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(bookService.searchBooksByKeyword(keyword, tag, page, size));
    }

    /**
     * 搜索建议（前缀匹配标题）
     */
    @Operation(summary = "搜索建议")
    @GetMapping("/suggest")
    public Result<List<String>> suggestBooks(
            @RequestParam String keyword) {
        return Result.ok(bookService.suggestBooks(keyword));
    }

    /**
     * 阅读排行
     */
    @Operation(summary = "阅读排行")
    @GetMapping("/rank/read")
    public Result<PageResult<BookProjection>> getReadRank(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(rankService.getReadRank(page, size));
    }

    /**
     * 评分排行
     */
    @Operation(summary = "评分排行")
    @GetMapping("/rank/rating")
    public Result<PageResult<BookProjection>> getRatingRank(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(rankService.getRatingRank(page, size));
    }

    /**
     * 新书榜
     */
    @Operation(summary = "新书榜")
    @GetMapping("/rank/new")
    public Result<PageResult<BookProjection>> getNewBooksRank(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(rankService.getNewBooksRank(page, size));
    }

    /**
     * 按格式筛选
     */
    @Operation(summary = "按格式筛选图书")
    @GetMapping("/format/{format}")
    public Result<PageResult<BookProjection>> getBooksByFormat(
            @PathVariable String format,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(bookService.getBooksByFormat(format, page, size));
    }

    /**
     * 按标签筛选
     */
    @Operation(summary = "按标签筛选图书")
    @GetMapping("/tag/{tag}")
    public Result<PageResult<BookProjection>> getBooksByTag(
            @PathVariable String tag,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(bookService.getBooksByTag(tag, page, size));
    }

    /**
     * 图书入库（管理员）
     */
    @Operation(summary = "图书入库")
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
    @Operation(summary = "更新格式标签")
    @PutMapping("/{id}/tags")
    public Result<Book> updateFormatTags(@PathVariable Long id, @RequestBody UpdateTagsRequest req) {
        return Result.ok(bookService.updateFormatTags(id, req.getTags()));
    }

    /**
     * 用户评分
     */
    @Operation(summary = "评分")
    @PostMapping("/{id}/rate")
    public Result<Book> rateBook(@PathVariable Long id, @Valid @RequestBody RateRequest req,
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
    @Operation(summary = "重建ES索引")
    @PostMapping("/reindex")
    public Result<Long> rebuildIndex() {
        return Result.ok(bookSearchService.rebuildIndex());
    }

    @Operation(summary = "获取速读摘要")
    @GetMapping("/{id}/speed-read")
    public Result<BookSpeedReadVO> getSpeedRead(@PathVariable Long id, Authentication authentication) {
        Book book = bookService.getBookById(id);
        if (book == null) {
            return Result.fail("图书不存在");
        }

        // 获取当前用户画像（用于个性化速读）
        User currentUser = null;
        if (authentication != null && authentication.getPrincipal() != null) {
            try {
                Long userId = (Long) authentication.getPrincipal();
                currentUser = userService.getUserById(userId);
            } catch (Exception e) {
                log.debug("获取当前用户失败: {}", e.getMessage());
            }
        }

        // 生成个性化速读（每次根据用户画像实时生成，不缓存，因为不同用户结果不同）
        BookSpeedReadVO vo = bookParserService.generateSpeedRead(book, currentUser);
        if (vo != null) {
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

    @Operation(summary = "流式获取速读摘要")
    @PostMapping(value = "/{id}/speed-read/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSpeedRead(@PathVariable Long id, Authentication authentication) {
        Long userId = null;
        if (authentication != null && authentication.getPrincipal() != null) {
            try {
                userId = (Long) authentication.getPrincipal();
            } catch (Exception e) {
                log.debug("获取当前用户ID失败: {}", e.getMessage());
            }
        }
        return bookParserService.streamSpeedRead(id, userId);
    }

}
