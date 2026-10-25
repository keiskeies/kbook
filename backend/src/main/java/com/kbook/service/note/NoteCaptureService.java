package com.kbook.service.note;

import com.kbook.common.api.ErrorCode;
import com.kbook.common.exception.BusinessException;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.dto.note.NoteSourceSnapshot;
import com.kbook.dto.note.NoteVO;
import com.kbook.dto.note.ReviewCaptureRequest;
import com.kbook.entity.Book;
import com.kbook.entity.BookReview;
import com.kbook.common.enums.CaptureMode;
import com.kbook.entity.Note;
import com.kbook.common.enums.NoteSourceType;
import com.kbook.common.enums.NoteType;
import com.kbook.common.enums.SourceStatus;
import com.kbook.entity.User;
import com.kbook.repository.BookRepository;
import com.kbook.repository.BookReviewRepository;
import com.kbook.repository.NoteRepository;
import com.kbook.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.kbook.common.util.QueryBuilder.eq;

/**
 * 收录服务 — 把外部内容（当前仅书评）固化为私人笔记。
 * <p>
 * 核心语义：收录即复制。快照在收录那一刻冻结，来源后续的编辑/删除都不影响笔记。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogModule("笔记")
public class NoteCaptureService {

    private final NoteRepository noteRepository;
    private final BookReviewRepository bookReviewRepository;
    private final BookRepository bookRepository;
    private final UserRepository userRepository;
    private final NoteService noteService;
    private final NoteEmbeddingService noteEmbeddingService;

    @Transactional(rollbackFor = Exception.class)
    @LogAction("收录书评为笔记")
    public NoteVO captureReview(Long userId, Long reviewId, ReviewCaptureRequest req) {
        BookReview review = bookReviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));

        CaptureMode mode = parseMode(req.getMode());
        String excerpt = trimToNull(req.getExcerpt());
        if (mode == CaptureMode.EXCERPT && excerpt == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "摘录模式下请保留要截取的片段");
        }

        Book book = bookRepository.findById(review.getBookId()).orElse(null);
        User author = userRepository.findOneById(review.getUserId());

        NoteSourceSnapshot snapshot = NoteSourceSnapshot.builder()
                .authorId(review.getUserId())
                .authorName(author != null ? author.getNickname() : null)
                .authorAvatar(author != null ? author.getAvatar() : null)
                .sourceTitle(review.getTitle())
                .sourceContent(review.getContentMd())
                .rating(review.getRating())
                .isSpoiler(review.getIsSpoiler())
                .excerpt(excerpt)
                .sourceCreatedAt(review.getCreatedAt())
                .build();

        Note note = Note.builder()
                .userId(userId)
                .contentMd(trimToNull(req.getMyThought()))
                .noteType(NoteType.REVIEW_CAPTURE)
                .sourceType(NoteSourceType.REVIEW)
                .sourceId(review.getId())
                .sourceStatus(SourceStatus.ACTIVE)
                .sourceSnapshot(noteService.writeSnapshot(snapshot))
                .captureMode(mode)
                .bookId(review.getBookId())
                .bookTitle(book != null ? book.getTitle() : null)
                .bookCover(book != null ? book.getCoverUrl() : null)
                .occurredAt(LocalDateTime.now())
                .build();
        noteRepository.save(note);
        noteEmbeddingService.index(note);

        review.setCaptureCount((review.getCaptureCount() == null ? 0 : review.getCaptureCount()) + 1);
        bookReviewRepository.save(review);

        log.info("收录书评: userId={}, reviewId={}, noteId={}, mode={}", userId, reviewId, note.getId(), mode);
        return noteService.toVO(note);
    }

    /**
     * 书评删除时解绑所有引用它的笔记 — 只丢指针与状态，快照原地保留。
     * 必须在删除书评的同一事务内调用。
     */
    public long detachReviewSources(Long reviewId) {
        return noteRepository.update()
                .where(Note::getSourceType, eq(NoteSourceType.REVIEW))
                .and(Note::getSourceId, eq(reviewId))
                .execute((note, unused) -> {
                    note.setSourceId(null);
                    note.setSourceStatus(SourceStatus.DELETED);
                });
    }

    private CaptureMode parseMode(String mode) {
        try {
            return CaptureMode.valueOf(mode);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "未知的收录方式: " + mode);
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
