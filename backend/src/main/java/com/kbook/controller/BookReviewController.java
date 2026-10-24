package com.kbook.controller;

import com.kbook.common.api.PageResult;
import com.kbook.common.api.Result;
import com.kbook.dto.review.ReviewCreateRequest;
import com.kbook.dto.review.ReviewUpdateRequest;
import com.kbook.dto.review.ReviewVO;
import com.kbook.service.review.BookReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 书评接口 — 全开放：任何登录用户可读可写，无点赞、无收录授权开关。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "书评")
public class BookReviewController extends BaseController {

    private final BookReviewService bookReviewService;

    @Operation(summary = "写书评")
    @PostMapping("/books/{bookId}/reviews")
    public Result<ReviewVO> createReview(@PathVariable Long bookId,
                                         @Valid @RequestBody ReviewCreateRequest req) {
        return Result.ok(bookReviewService.createReview(extractUserId(), bookId, req));
    }

    @Operation(summary = "某本书的书评列表")
    @GetMapping("/books/{bookId}/reviews")
    public Result<PageResult<ReviewVO>> getBookReviews(@PathVariable Long bookId,
                                                       @RequestParam(defaultValue = "latest") String sort,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "10") int size) {
        return Result.ok(bookReviewService.getBookReviews(bookId, sort, page, size, extractUserId()));
    }

    @Operation(summary = "书评详情")
    @GetMapping("/reviews/{reviewId}")
    public Result<ReviewVO> getReview(@PathVariable Long reviewId) {
        return Result.ok(bookReviewService.getReview(reviewId, extractUserId()));
    }

    @Operation(summary = "我的书评时间轴")
    @GetMapping("/users/me/reviews")
    public Result<PageResult<ReviewVO>> getMyReviews(@RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "10") int size) {
        return Result.ok(bookReviewService.getMyReviews(extractUserId(), page, size));
    }

    @Operation(summary = "编辑书评")
    @PutMapping("/reviews/{reviewId}")
    public Result<ReviewVO> updateReview(@PathVariable Long reviewId,
                                         @Valid @RequestBody ReviewUpdateRequest req) {
        return Result.ok(bookReviewService.updateReview(extractUserId(), reviewId, req));
    }

    @Operation(summary = "删除书评")
    @DeleteMapping("/reviews/{reviewId}")
    public Result<Void> deleteReview(@PathVariable Long reviewId) {
        bookReviewService.deleteReview(extractUserId(), reviewId);
        return Result.ok();
    }

    @Operation(summary = "书评数量")
    @GetMapping("/books/{bookId}/reviews/count")
    public Result<Long> countBookReviews(@PathVariable Long bookId) {
        return Result.ok(bookReviewService.countBookReviews(bookId));
    }
}
