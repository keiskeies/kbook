package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.BookTrashItem;
import com.kbook.service.BookTrashService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/book-trash")
@RequiredArgsConstructor
public class BookTrashController {

    private final BookTrashService bookTrashService;

    @PostMapping("/{bookId}")
    public Result<Void> moveToTrash(Authentication authentication, @PathVariable Long bookId) {
        Long userId = (Long) authentication.getPrincipal();
        bookTrashService.moveToTrash(userId, bookId);
        return Result.ok(null);
    }

    @DeleteMapping("/{bookId}")
    public Result<Void> removeFromTrash(Authentication authentication, @PathVariable Long bookId) {
        Long userId = (Long) authentication.getPrincipal();
        bookTrashService.removeFromTrash(userId, bookId);
        return Result.ok(null);
    }

    @GetMapping("/check/{bookId}")
    public Result<Boolean> isInTrash(Authentication authentication, @PathVariable Long bookId) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(bookTrashService.isInTrash(userId, bookId));
    }

    @GetMapping
    public Result<List<BookTrashItem>> getTrashList(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(bookTrashService.getTrashList(userId));
    }

    @GetMapping("/count")
    public Result<Long> getTrashCount(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(bookTrashService.getTrashCount(userId));
    }
}
