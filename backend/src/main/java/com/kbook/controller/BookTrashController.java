package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.BookTrashItem;
import com.kbook.service.BookTrashService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/book-trash")
@RequiredArgsConstructor
@Tag(name = "回收站")
public class BookTrashController {

    private final BookTrashService bookTrashService;

    @Operation(summary = "移入回收站")
    @PostMapping("/{bookId}")
    public Result<Void> moveToTrash(Authentication authentication, @PathVariable Long bookId) {
        Long userId = (Long) authentication.getPrincipal();
        bookTrashService.moveToTrash(userId, bookId);
        return Result.ok(null);
    }

    @Operation(summary = "永久删除")
    @DeleteMapping("/{bookId}")
    public Result<Void> removeFromTrash(Authentication authentication, @PathVariable Long bookId) {
        Long userId = (Long) authentication.getPrincipal();
        bookTrashService.removeFromTrash(userId, bookId);
        return Result.ok(null);
    }

    @Operation(summary = "检查是否在回收站")
    @GetMapping("/check/{bookId}")
    public Result<Boolean> isInTrash(Authentication authentication, @PathVariable Long bookId) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(bookTrashService.isInTrash(userId, bookId));
    }

    @Operation(summary = "获取回收站列表")
    @GetMapping
    public Result<List<BookTrashItem>> getTrashList(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(bookTrashService.getTrashList(userId));
    }

    @Operation(summary = "获取回收站数量")
    @GetMapping("/count")
    public Result<Long> getTrashCount(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(bookTrashService.getTrashCount(userId));
    }
}
