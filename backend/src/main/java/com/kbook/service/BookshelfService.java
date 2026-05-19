package com.kbook.service;

import com.kbook.common.exception.BusinessException;
import com.kbook.dto.BookshelfItem;
import com.kbook.entity.Book;
import com.kbook.entity.Bookshelf;
import com.kbook.entity.ReadingProgress;
import com.kbook.repository.BookRepository;
import com.kbook.repository.BookshelfRepository;
import com.kbook.repository.ReadingProgressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 书架服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookshelfService {

    private final BookshelfRepository bookshelfRepository;
    private final BookRepository bookRepository;
    private final ReadingProgressRepository progressRepository;
    private final MatchScoreCacheService matchScoreCacheService;

    /**
     * 加入书架
     */
    @Transactional
    public void addToBookshelf(Long userId, Long bookId) {
        if (!bookRepository.existsById(bookId)) {
            throw new BusinessException("图书不存在");
        }
        if (bookshelfRepository.existsByUserIdAndBookId(userId, bookId)) {
            throw new BusinessException("已在书架中");
        }
        Bookshelf item = Bookshelf.builder()
                .userId(userId)
                .bookId(bookId)
                .build();
        bookshelfRepository.save(item);
        matchScoreCacheService.evictUser(userId); // 书架变化，清除用户画像缓存
        log.info("加入书架: userId={}, bookId={}", userId, bookId);
    }

    /**
     * 从书架移除
     */
    @Transactional
    public void removeFromBookshelf(Long userId, Long bookId) {
        bookshelfRepository.deleteByUserIdAndBookId(userId, bookId);
        matchScoreCacheService.evictUser(userId); // 书架变化，清除用户画像缓存
        log.info("移出书架: userId={}, bookId={}", userId, bookId);
    }

    /**
     * 检查是否在书架中
     */
    public boolean isInBookshelf(Long userId, Long bookId) {
        return bookshelfRepository.existsByUserIdAndBookId(userId, bookId);
    }

    /**
     * 获取书架列表（含图书详情和阅读进度）
     */
    public List<BookshelfItem> getBookshelf(Long userId) {
        List<Bookshelf> items = bookshelfRepository.findByUserIdOrderBySortOrderDescAddedAtDesc(userId);
        if (items.isEmpty()) return new ArrayList<>();

        List<Long> bookIds = items.stream().map(Bookshelf::getBookId).collect(Collectors.toList());
        Map<Long, Book> bookMap = bookRepository.findAllById(bookIds)
                .stream().collect(Collectors.toMap(Book::getId, b -> b));

        // 批量获取进度
        List<ReadingProgress> progresses = progressRepository.findByUserIdAndBookIdIn(userId, bookIds);
        Map<Long, ReadingProgress> progressMap = progresses.stream()
                .collect(Collectors.toMap(ReadingProgress::getBookId, p -> p));

        return items.stream().map(item -> {
            Book book = bookMap.get(item.getBookId());
            ReadingProgress progress = progressMap.get(item.getBookId());
            return BookshelfItem.builder()
                    .bookshelfId(item.getId())
                    .bookId(item.getBookId())
                    .title(book != null ? book.getTitle() : "未知")
                    .author(book != null ? book.getAuthor() : null)
                    .coverUrl(book != null ? book.getCoverUrl() : null)
                    .format(book != null ? book.getFormat() : null)
                    .formatTags(book != null ? book.getFormatTags() : null)
                    .fileSize(book != null ? book.getFileSize() : null)
                    .progress(progress != null ? progress.getProgress() : 0.0)
                    .currentPosition(progress != null ? progress.getCurrentPosition() : null)
                    .lastReadAt(progress != null ? progress.getUpdatedAt() : null)
                    .addedAt(item.getAddedAt())
                    .build();
        }).collect(Collectors.toList());
    }

    /**
     * 获取书架数量
     */
    public long getBookshelfCount(Long userId) {
        return bookshelfRepository.countByUserId(userId);
    }

}
