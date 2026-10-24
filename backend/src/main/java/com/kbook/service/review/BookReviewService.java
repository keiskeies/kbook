package com.kbook.service.review;

import com.kbook.common.api.ErrorCode;
import com.kbook.common.api.PageResult;
import com.kbook.common.exception.BusinessException;
import com.kbook.common.util.QueryBuilder;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.dto.review.ReviewCreateRequest;
import com.kbook.dto.review.ReviewUpdateRequest;
import com.kbook.dto.review.ReviewVO;
import com.kbook.entity.Book;
import com.kbook.entity.BookReview;
import com.kbook.entity.ReadingProgress;
import com.kbook.entity.User;
import com.kbook.repository.BookRepository;
import com.kbook.repository.BookReviewRepository;
import com.kbook.repository.ReadingProgressRepository;
import com.kbook.repository.UserRepository;
import com.kbook.service.note.NoteCaptureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.kbook.common.util.QueryBuilder.eq;

/**
 * 书评服务 — 一个用户对同一本书可写多篇，每篇记录写作时的阅读进度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogModule("书评")
public class BookReviewService {

    private final BookReviewRepository bookReviewRepository;
    private final BookRepository bookRepository;
    private final UserRepository userRepository;
    private final ReadingProgressRepository readingProgressRepository;
    private final NoteCaptureService noteCaptureService;

    private static final String SORT_HOT = "hot";
    private static final String SORT_RATING = "rating";

    /**
     * 写书评。进度与章节由服务端按当前阅读状态填充，不信任前端传值。
     */
    @Transactional(rollbackFor = Exception.class)
    @LogAction("写书评")
    public ReviewVO createReview(Long userId, Long bookId, ReviewCreateRequest req) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BOOK_NOT_FOUND));

        ReadingProgress progress = readingProgressRepository.query()
                .where(ReadingProgress::getUserId, eq(userId))
                .and(ReadingProgress::getBookId, eq(bookId))
                .list(1).stream().findFirst().orElse(null);

        BookReview review = BookReview.builder()
                .userId(userId)
                .bookId(bookId)
                .rating(req.getRating())
                .title(trimToNull(req.getTitle()))
                .contentMd(req.getContentMd())
                .isSpoiler(Boolean.TRUE.equals(req.getIsSpoiler()))
                .progressPercent(toPercent(progress))
                .chapterId(resolveChapterId(req.getChapterId(), progress))
                .build();
        bookReviewRepository.save(review);

        log.info("写书评: userId={}, bookId={}, reviewId={}, progress={}",
                userId, bookId, review.getId(), review.getProgressPercent());

        User author = userRepository.findOneById(userId);
        return toVO(review, book, author, userId);
    }

    /**
     * 编辑书评 — 仅作者本人。进度快照保持不变，它记录的是「当时读到哪」。
     */
    @Transactional(rollbackFor = Exception.class)
    @LogAction("编辑书评")
    public ReviewVO updateReview(Long userId, Long reviewId, ReviewUpdateRequest req) {
        BookReview review = loadOwnedReview(userId, reviewId);
        review.setRating(req.getRating());
        review.setTitle(trimToNull(req.getTitle()));
        review.setContentMd(req.getContentMd());
        review.setIsSpoiler(Boolean.TRUE.equals(req.getIsSpoiler()));
        bookReviewRepository.save(review);
        return getReview(reviewId, userId);
    }

    /**
     * 删除书评 — 仅作者本人，硬删除。
     * <p>Phase 2 起，笔记表存有完整快照，删除只解绑笔记的 source_id，笔记内容不受影响。
     */
    @Transactional(rollbackFor = Exception.class)
    @LogAction("删除书评")
    public void deleteReview(Long userId, Long reviewId) {
        BookReview review = loadOwnedReview(userId, reviewId);
        // 笔记存的是收录时的快照，这里只解绑指针，笔记内容不受影响
        long detached = noteCaptureService.detachReviewSources(reviewId);
        bookReviewRepository.delete(review);
        log.info("删除书评: userId={}, reviewId={}, bookId={}, detachedNotes={}",
                userId, reviewId, review.getBookId(), detached);
    }

    public ReviewVO getReview(Long reviewId, Long currentUserId) {
        BookReview review = bookReviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));
        Book book = bookRepository.findById(review.getBookId()).orElse(null);
        User author = userRepository.findOneById(review.getUserId());
        return toVO(review, book, author, currentUserId);
    }

    /**
     * 某本书的书评列表，按作者维度平铺（前端负责同人折叠）。
     *
     * @param sort latest（默认）/ hot / rating
     */
    public PageResult<ReviewVO> getBookReviews(Long bookId, String sort, int page, int size, Long currentUserId) {
        var query = bookReviewRepository.query().where(BookReview::getBookId, eq(bookId));        applySort(query, sort);
        Page<BookReview> result = query.page(page - 1, size);
        return toPageResult(result, page, size, currentUserId);
    }

    /**
     * 我的书评时间轴，跨书按时间倒序。
     */
    public PageResult<ReviewVO> getMyReviews(Long userId, int page, int size) {
        Page<BookReview> result = bookReviewRepository.query()
                .where(BookReview::getUserId, eq(userId))
                .orderByDesc(BookReview::getCreatedAt)
                .page(page - 1, size);
        return toPageResult(result, page, size, userId);
    }

    public long countBookReviews(Long bookId) {
        return bookReviewRepository.countByBookId(bookId);
    }

    public long countMyReviews(Long userId) {
        return bookReviewRepository.countByUserId(userId);
    }

    // ==================== 内部方法 ====================

    private void applySort(QueryBuilder<BookReview> query, String sort) {
        if (SORT_HOT.equalsIgnoreCase(sort)) {
            query.orderByDesc(BookReview::getCaptureCount).orderByDesc(BookReview::getCreatedAt);
        } else if (SORT_RATING.equalsIgnoreCase(sort)) {
            query.orderByDesc(BookReview::getRating).orderByDesc(BookReview::getCreatedAt);
        } else {
            query.orderByDesc(BookReview::getCreatedAt);
        }
    }

    /**
     * 批量装配 VO — 一次性取回所有用户，避免 N+1
     */
    private PageResult<ReviewVO> toPageResult(Page<BookReview> result, int page, int size, Long currentUserId) {
        List<BookReview> reviews = result.getContent();
        if (reviews.isEmpty()) {
            return PageResult.of(List.of(), result.getTotalElements(), page, size);
        }

        Set<Long> userIds = reviews.stream().map(BookReview::getUserId).collect(Collectors.toSet());
        Map<Long, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));

        // 同一本书的列表只需查一次 Book；跨书列表批量查
        Set<Long> bookIds = reviews.stream().map(BookReview::getBookId).collect(Collectors.toSet());
        Map<Long, Book> books = bookRepository.findAllById(bookIds).stream()
                .collect(Collectors.toMap(Book::getId, Function.identity(), (a, b) -> a));

        List<ReviewVO> list = reviews.stream()
                .map(r -> toVO(r, books.get(r.getBookId()), users.get(r.getUserId()), currentUserId))
                .toList();
        return PageResult.of(list, result.getTotalElements(), page, size);
    }

    private ReviewVO toVO(BookReview review, Book book, User author, Long currentUserId) {
        return ReviewVO.builder()
                .id(review.getId())
                .bookId(review.getBookId())
                .bookTitle(book != null ? book.getTitle() : null)
                .bookAuthor(book != null ? book.getAuthor() : null)
                .bookCoverUrl(book != null ? book.getCoverUrl() : null)
                .userId(review.getUserId())
                .nickname(author != null ? author.getNickname() : null)
                .avatar(author != null ? author.getAvatar() : null)
                .rating(review.getRating())
                .title(review.getTitle())
                .contentMd(review.getContentMd())
                .isSpoiler(review.getIsSpoiler())
                .captureCount(review.getCaptureCount())
                .progressPercent(review.getProgressPercent())
                .chapterId(review.getChapterId())
                .mine(currentUserId != null && currentUserId.equals(review.getUserId()))
                .createdAt(review.getCreatedAt())
                .updatedAt(review.getUpdatedAt())
                .build();
    }

    private BookReview loadOwnedReview(Long userId, Long reviewId) {
        BookReview review = bookReviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));
        if (!review.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能操作自己的书评");
        }
        return review;
    }

    private Integer toPercent(ReadingProgress progress) {
        if (progress == null || progress.getProgress() == null) {
            return null;
        }
        int percent = (int) Math.round(progress.getProgress() * 100);
        return Math.max(0, Math.min(100, percent));
    }

    private String resolveChapterId(String requested, ReadingProgress progress) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        return progress != null ? progress.getCurrentPosition() : null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
