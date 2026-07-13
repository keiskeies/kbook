package com.kbook.controller;
import com.kbook.service.rank.RankService;
import com.kbook.service.user.UserService;
import com.kbook.service.book.BookParserService;
import com.kbook.service.book.BookService;
import com.kbook.service.rank.RankService;
import com.kbook.service.recommend.RecommendService;
import com.kbook.service.user.UserService;

import com.kbook.common.api.PageResult;
import com.kbook.common.api.Result;
import com.kbook.common.util.CommonUtils;
import com.kbook.document.BookDocument;

import com.kbook.entity.Book;
import com.kbook.entity.User;
import com.kbook.dto.book.BookProjection;
import com.kbook.dto.book.BookSpeedReadVO;
import com.kbook.dto.book.CreateBookRequest;
import com.kbook.dto.recommend.RateRequest;
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
public class BookController extends BaseController {

    private final BookService bookService;
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
    public Result<BookProjection> getBook(@PathVariable Long id) {
        return Result.ok(bookService.getBookProjectionById(id));
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
     * 热度排行（综合阅读量、AI问答、圆桌讨论、辩论、书架收藏等多维度评分）
     */
    @Operation(summary = "热度排行")
    @GetMapping("/rank/hot")
    public Result<PageResult<BookProjection>> getHotRank(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(rankService.getHotRank(page, size));
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
    @Operation(summary = "图书入库（仅管理员）")
    @PostMapping
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public Result<Book> createBook(@Valid @RequestBody CreateBookRequest req) {
        Book book = Book.builder()
                .title(req.getTitle())
                .author(req.getAuthor())
                .coverUrl(req.getCoverUrl())
                .description(req.getDescription())
                .format(req.getFormat())
                .fileUrl(req.getFileUrl() != null ? Paths.get(req.getFileUrl()).getFileName().toString() : null)
                .fileSize(req.getFileSize())
                .formatTags(req.getFormatTags())
                .totalUnits(req.getTotalUnits())
                .build();
        return Result.ok(bookService.createBook(book));
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
        final Long uid = userId;
        return withSseLimit(uid, () -> bookParserService.streamSpeedRead(id, uid));
    }

}
