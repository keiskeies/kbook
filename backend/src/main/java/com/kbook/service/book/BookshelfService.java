package com.kbook.service.book;

import com.kbook.service.recommend.RecommendService;

import com.kbook.common.exception.BusinessException;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.dto.book.BookshelfItem;
import com.kbook.entity.Book;
import com.kbook.entity.Bookshelf;
import com.kbook.entity.ReadingProgress;
import com.kbook.repository.BookRepository;
import com.kbook.repository.BookshelfRepository;
import com.kbook.repository.ReadingProgressRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.kbook.common.util.QueryBuilder.*;

/**
 * 书架服务
 * <p>
 * 管理用户书架的增删查操作，书架变更时自动清除推荐匹配度缓存。
 * 书架列表包含图书详情、阅读进度和匹配度得分。
 */
@Slf4j
@Service
@LogModule("书架")
public class BookshelfService {

    /** 书架数据仓库 */
    @Autowired
    private BookshelfRepository bookshelfRepository;
    /** 图书数据仓库 */
    @Autowired
    private BookRepository bookRepository;
    /** 阅读进度数据仓库 */
    @Autowired
    private ReadingProgressRepository progressRepository;
    @Autowired
    private BookService bookService;
    /** 推荐服务（用于计算书架书籍的匹配度） */
    @Autowired
    private RecommendService recommendService;
    /** 图书回收站服务 */
    @Autowired
    private BookTrashService bookTrashService;

    /**
     * 加入书架
     */
    @Transactional
    @LogAction("加入书架")
    public void addToBookshelf(Long userId, Long bookId) {
        if (!bookRepository.existsById(bookId)) {
            throw new BusinessException("图书不存在");
        }
        if (bookshelfRepository.query()
                .where(Bookshelf::getUserId, eq(userId))
                .and(Bookshelf::getBookId, eq(bookId))
                .exists()) {
            throw new BusinessException("已在书架中");
        }
        Bookshelf item = Bookshelf.builder()
                .userId(userId)
                .bookId(bookId)
                .build();
        bookshelfRepository.save(item);
        bookTrashService.updateDimensionScoresOnBookshelf(userId, bookId);
        log.info("加入书架: userId={}, bookId={}", userId, bookId);
    }

    @Transactional
    @LogAction("移出书架")
    public void removeFromBookshelf(Long userId, Long bookId) {
        Bookshelf item = bookshelfRepository.query()
                .where(Bookshelf::getUserId, eq(userId))
                .and(Bookshelf::getBookId, eq(bookId))
                .list(1)
                .stream().findFirst().orElse(null);
        if (item != null) {
            bookshelfRepository.deleteById(item.getId());
        }
        bookTrashService.reverseDimensionScoresOnBookshelf(userId, bookId);
        log.info("移出书架: userId={}, bookId={}", userId, bookId);
    }

    @LogAction("检查书架状态")
    public boolean isInBookshelf(Long userId, Long bookId) {
        return bookshelfRepository.query()
                .where(Bookshelf::getUserId, eq(userId))
                .and(Bookshelf::getBookId, eq(bookId))
                .exists();
    }

    @LogAction("获取书架列表")
    public List<BookshelfItem> getBookshelf(Long userId) {
        List<Bookshelf> items = bookshelfRepository.query()
                .where(Bookshelf::getUserId, eq(userId))
                .orderByDesc(Bookshelf::getSortOrder)
                .orderByDesc(Bookshelf::getAddedAt)
                .list();
        if (items.isEmpty()) return new ArrayList<>();

        List<Long> bookIds = items.stream().map(Bookshelf::getBookId).collect(Collectors.toList());
        Map<Long, Book> bookMap = bookRepository.findAllById(bookIds)
                .stream().collect(Collectors.toMap(Book::getId, b -> b));

        // 批量获取进度
        List<ReadingProgress> progresses = progressRepository.query()
                .where(ReadingProgress::getUserId, eq(userId))
                .and(ReadingProgress::getBookId, in(bookIds))
                .list();
        Map<Long, ReadingProgress> progressMap = progresses.stream()
                .collect(Collectors.toMap(ReadingProgress::getBookId, p -> p));

        // 批量计算匹配度
        Map<Long, Double> matchScores = recommendService.batchCalculateMatchScores(userId, bookIds);

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
                    .rating(book != null ? book.getRating() : 0.0)
                    .matchScore(matchScores.getOrDefault(item.getBookId(), 0.0))
                    .build();
        }).collect(Collectors.toList());
    }

    /**
     * 获取书架数量
     */
    @LogAction("获取书架数量")
    public long getBookshelfCount(Long userId) {
        return bookshelfRepository.query()
                .where(Bookshelf::getUserId, eq(userId))
                .value();
    }

}
