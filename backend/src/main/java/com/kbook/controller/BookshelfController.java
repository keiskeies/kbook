package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.BookshelfItem;
import com.kbook.service.BookshelfService;
import com.kbook.service.RecommendCoefficientService;
import com.kbook.service.RecommendService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 书架控制器
 */
@RestController
@RequestMapping("/api/bookshelf")
@RequiredArgsConstructor
public class BookshelfController {

    private final BookshelfService bookshelfService;
    private final RecommendService recommendService;
    private final RecommendCoefficientService coefficientService;

    /**
     * 获取书架列表（含图书详情和阅读进度）
     */
    @GetMapping
    public Result<List<BookshelfItem>> getBookshelf(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(bookshelfService.getBookshelf(userId));
    }

    /**
     * 加入书架
     */
    @PostMapping("/{bookId}")
    public Result<Void> addToBookshelf(Authentication authentication, @PathVariable Long bookId) {
        Long userId = (Long) authentication.getPrincipal();
        bookshelfService.addToBookshelf(userId, bookId);
        // 记录收藏行为（用于协同过滤）
        recommendService.recordReadAction(userId, bookId, "FAVORITE", 3, null);
        // 记录推荐反馈（用于自动调参）
        coefficientService.recordFeedback(userId, bookId, "FAVORITE", 0.3,
                null, null, null, null);
        return Result.ok(null);
    }

    /**
     * 从书架移除
     */
    @DeleteMapping("/{bookId}")
    public Result<Void> removeFromBookshelf(Authentication authentication, @PathVariable Long bookId) {
        Long userId = (Long) authentication.getPrincipal();
        bookshelfService.removeFromBookshelf(userId, bookId);
        return Result.ok(null);
    }

    /**
     * 检查是否在书架中
     */
    @GetMapping("/check/{bookId}")
    public Result<Boolean> isInBookshelf(Authentication authentication, @PathVariable Long bookId) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(bookshelfService.isInBookshelf(userId, bookId));
    }

    /**
     * 获取书架数量
     */
    @GetMapping("/count")
    public Result<Long> getBookshelfCount(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(bookshelfService.getBookshelfCount(userId));
    }
}
