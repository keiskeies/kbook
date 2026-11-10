package com.kbook.service.ai;

import com.kbook.common.api.PageResult;
import com.kbook.dto.bookqa.BookQaFeedbackDTO;
import com.kbook.entity.AiConversation;
import com.kbook.entity.AnswerFeedback;
import com.kbook.repository.AiConversationRepository;
import com.kbook.repository.AiRetrievalSnapshotRepository;
import com.kbook.repository.AnswerFeedbackRepository;
import com.kbook.repository.BookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** P2-5：反馈闭环的归属校验、幂等改反馈、差评入队与标注流转。 */
class AnswerFeedbackServiceTest {

    private static final Long USER = 1L;
    private static final Long BOOK = 2L;
    private static final Long CONV = 7L;

    private AnswerFeedbackRepository feedbackRepository;
    private AiConversationRepository conversationRepository;
    private AiRetrievalSnapshotRepository snapshotRepository;
    private BookRepository bookRepository;
    private AnswerFeedbackService service;

    @BeforeEach
    void setUp() {
        feedbackRepository = mock(AnswerFeedbackRepository.class);
        conversationRepository = mock(AiConversationRepository.class);
        snapshotRepository = mock(AiRetrievalSnapshotRepository.class);
        bookRepository = mock(BookRepository.class);

        AiConversation answer = AiConversation.builder()
                .id(CONV).userId(USER).bookId(BOOK).role("assistant").sessionId("s1")
                .content("一段回答").build();
        when(conversationRepository.findById(CONV)).thenReturn(Optional.of(answer));
        when(snapshotRepository.findByConversationIdOrderByChunkIndexAsc(CONV)).thenReturn(List.of());
        when(feedbackRepository.findByConversationIdAndUserId(CONV, USER)).thenReturn(Optional.empty());
        when(feedbackRepository.save(any(AnswerFeedback.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service = new AnswerFeedbackService(feedbackRepository, conversationRepository,
                snapshotRepository, bookRepository);
    }

    private static BookQaFeedbackDTO.SubmitRequest req(String rating, String reason) {
        return new BookQaFeedbackDTO.SubmitRequest(rating, reason, "补充说明");
    }

    @Test
    void downFeedbackEntersAnnotationQueue() {
        BookQaFeedbackDTO.VO vo = service.submit(USER, BOOK, CONV, req("DOWN", "WRONG"));

        assertEquals("DOWN", vo.getRating());
        assertEquals("WRONG", vo.getReason());
        ArgumentCaptor<AnswerFeedback> captor = ArgumentCaptor.forClass(AnswerFeedback.class);
        verify(feedbackRepository).save(captor.capture());
        AnswerFeedback saved = captor.getValue();
        assertEquals(AnswerFeedback.STATUS_QUEUED, saved.getReviewStatus());
        assertEquals("s1", saved.getSessionId());
        assertEquals("unknown", saved.getContextMode());
    }

    @Test
    void upFeedbackDoesNotEnterQueue() {
        service.submit(USER, BOOK, CONV, req("UP", null));
        ArgumentCaptor<AnswerFeedback> captor = ArgumentCaptor.forClass(AnswerFeedback.class);
        verify(feedbackRepository).save(captor.capture());
        assertEquals(AnswerFeedback.STATUS_PENDING, captor.getValue().getReviewStatus());
        assertNull(captor.getValue().getReason());
    }

    @Test
    void repeatedClickUpdatesExistingRowInsteadOfInserting() {
        AnswerFeedback existing = AnswerFeedback.builder()
                .id(99L).userId(USER).bookId(BOOK).conversationId(CONV)
                .rating("UP").reviewStatus(AnswerFeedback.STATUS_PENDING).build();
        when(feedbackRepository.findByConversationIdAndUserId(CONV, USER))
                .thenReturn(Optional.of(existing));

        service.submit(USER, BOOK, CONV, req("DOWN", "NOT_GROUNDED"));

        assertEquals(99L, existing.getId());
        assertEquals("DOWN", existing.getRating());
        assertEquals(AnswerFeedback.STATUS_QUEUED, existing.getReviewStatus());
        verify(feedbackRepository, times(1)).save(existing);
    }

    @Test
    void annotatedSampleIsNotRequeuedOnRepeatedDownvote() {
        AnswerFeedback existing = AnswerFeedback.builder()
                .id(99L).userId(USER).bookId(BOOK).conversationId(CONV)
                .rating("DOWN").reviewStatus(AnswerFeedback.STATUS_ANNOTATED).build();
        when(feedbackRepository.findByConversationIdAndUserId(CONV, USER))
                .thenReturn(Optional.of(existing));

        service.submit(USER, BOOK, CONV, req("DOWN", "WRONG"));
        assertEquals(AnswerFeedback.STATUS_ANNOTATED, existing.getReviewStatus());
    }

    @Test
    void foreignConversationIsRejected() {
        AiConversation other = AiConversation.builder()
                .id(CONV).userId(999L).bookId(BOOK).role("assistant").build();
        when(conversationRepository.findById(CONV)).thenReturn(Optional.of(other));
        assertThrows(IllegalArgumentException.class,
                () -> service.submit(USER, BOOK, CONV, req("DOWN", "WRONG")));
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void invalidRatingAndReasonAreNormalized() {
        assertThrows(IllegalArgumentException.class,
                () -> service.submit(USER, BOOK, CONV, req("MAYBE", null)));
        BookQaFeedbackDTO.VO vo = service.submit(USER, BOOK, CONV, req("down", "自由填写的原因"));
        assertEquals("DOWN", vo.getRating());
        assertEquals("OTHER", vo.getReason());
    }

    @Test
    void reviewMovesSampleThroughWorkflow() {
        AnswerFeedback row = AnswerFeedback.builder()
                .id(5L).userId(USER).bookId(BOOK).conversationId(CONV)
                .rating("DOWN").reviewStatus(AnswerFeedback.STATUS_QUEUED).build();
        when(feedbackRepository.findById(5L)).thenReturn(Optional.of(row));

        service.review(5L, new BookQaFeedbackDTO.ReviewRequest("ANNOTATED", "补进黄金集"));
        assertEquals(AnswerFeedback.STATUS_ANNOTATED, row.getReviewStatus());
        assertEquals("补进黄金集", row.getReviewNote());
        assertThrows(IllegalArgumentException.class,
                () -> service.review(5L, new BookQaFeedbackDTO.ReviewRequest("BOGUS", null)));
    }

    @Test
    void queueAndStatsArePageSafe() {
        when(feedbackRepository.findByReviewStatusOrderByCreatedAtDesc(eq("QUEUED"), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        PageResult<BookQaFeedbackDTO.QueueItem> page = service.queue(null, -3, 9999);
        assertEquals(0, page.getTotal());
        assertEquals(0, page.getPage());
        assertEquals(100, page.getSize(), "size 必须封顶，防止后台拉爆内存");
    }
}
