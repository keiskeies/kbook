package com.kbook.service.ai;

import com.kbook.common.api.PageResult;
import com.kbook.dto.bookqa.BookQaFeedbackDTO.QueueItem;
import com.kbook.dto.bookqa.BookQaFeedbackDTO.ReviewRequest;
import com.kbook.dto.bookqa.BookQaFeedbackDTO.Stats;
import com.kbook.dto.bookqa.BookQaFeedbackDTO.SubmitRequest;
import com.kbook.dto.bookqa.BookQaFeedbackDTO.VO;
import com.kbook.entity.AiConversation;
import com.kbook.entity.AiRetrievalSnapshot;
import com.kbook.entity.AnswerFeedback;
import com.kbook.entity.Book;
import com.kbook.repository.AiConversationRepository;
import com.kbook.repository.AiRetrievalSnapshotRepository;
import com.kbook.repository.AnswerFeedbackRepository;
import com.kbook.repository.BookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 图书问答反馈闭环（方案 P2-5）。
 * <p>
 * 前端每条 assistant 回答加「有用 / 没用」→ 差评自动进 {@code QUEUED} 待标注队列 →
 * 管理员标注为 {@code ANNOTATED} 后进黄金集，形成「生产回流 → 标注 → 评测」的迭代闭环。
 * 反馈行唯一键是 (conversation_id, user_id)，重复点击是改反馈而不是刷行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerFeedbackService {

    private static final Set<String> VALID_REASONS = Set.of(
            "WRONG", "NOT_GROUNDED", "OFF_TOPIC", "TOO_LONG", "OTHER");
    private static final Set<String> VALID_REVIEW_STATUS = Set.of(
            AnswerFeedback.STATUS_QUEUED, AnswerFeedback.STATUS_ANNOTATED, AnswerFeedback.STATUS_DISMISSED);
    private static final int MAX_COMMENT = 500;
    private static final int ANSWER_PREVIEW = 200;
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final AnswerFeedbackRepository feedbackRepository;
    private final AiConversationRepository conversationRepository;
    private final AiRetrievalSnapshotRepository snapshotRepository;
    private final BookRepository bookRepository;

    /** 提交 / 修改反馈。返回该回答最新的反馈状态。 */
    @Transactional
    public VO submit(Long userId, Long bookId, Long conversationId, SubmitRequest request) {
        if (request == null) throw new IllegalArgumentException("反馈内容不能为空");
        String rating = normalizeRating(request.getRating());
        String reason = normalizeReason(request.getReason(), rating);
        String comment = truncate(request.getComment(), MAX_COMMENT);

        AiConversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("该回答不存在"));
        if (!userId.equals(conversation.getUserId())
                || !bookId.equals(conversation.getBookId())
                || !"assistant".equals(conversation.getRole())) {
            throw new IllegalArgumentException("无权对该回答提交反馈");
        }

        AnswerFeedback feedback = feedbackRepository
                .findByConversationIdAndUserId(conversationId, userId)
                .orElseGet(() -> AnswerFeedback.builder()
                        .userId(userId)
                        .bookId(bookId)
                        .conversationId(conversationId)
                        .build());

        feedback.setSessionId(conversation.getSessionId());
        feedback.setRating(rating);
        feedback.setReason(reason);
        feedback.setComment(comment);
        feedback.setContextMode(resolveContextMode(conversationId));
        // 差评入队；好评不进队列。已标注的样本不因用户重复点差评被重新排队。
        if (AnswerFeedback.RATING_UP.equals(rating)) {
            feedback.setReviewStatus(AnswerFeedback.STATUS_PENDING);
            feedback.setReviewNote(null);
        } else if (!AnswerFeedback.STATUS_ANNOTATED.equals(feedback.getReviewStatus())) {
            feedback.setReviewStatus(AnswerFeedback.STATUS_QUEUED);
        }

        AnswerFeedback saved = feedbackRepository.save(feedback);
        log.info("[AnswerFeedback] bookId={}, convId={}, rating={}, reason={}, mode={}, status={}",
                bookId, conversationId, rating, reason, saved.getContextMode(), saved.getReviewStatus());
        return toVO(saved);
    }

    /** 当前用户在该回答上的反馈；未反馈返回 null。 */
    @Transactional(readOnly = true)
    public VO get(Long userId, Long conversationId) {
        return feedbackRepository.findByConversationIdAndUserId(conversationId, userId)
                .map(this::toVO).orElse(null);
    }

    /** 打开历史会话时批量取反馈，key 是 conversationId。 */
    @Transactional(readOnly = true)
    public java.util.Map<Long, VO> batch(Long userId, List<Long> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) return java.util.Map.of();
        List<Long> safe = conversationIds.stream().filter(java.util.Objects::nonNull).distinct().limit(200).toList();
        if (safe.isEmpty()) return java.util.Map.of();
        java.util.Map<Long, VO> out = new java.util.HashMap<>();
        for (AnswerFeedback f : feedbackRepository.findByUserIdAndConversationIdIn(userId, safe)) {
            out.put(f.getConversationId(), toVO(f));
        }
        return out;
    }

    /** 待标注队列。默认只看 QUEUED，可传 ANNOTATED / DISMISSED / PENDING 回看。 */
    @Transactional(readOnly = true)
    public PageResult<QueueItem> queue(String status, int page, int size) {
        String normalized = (status == null || status.isBlank())
                ? AnswerFeedback.STATUS_QUEUED : status.toUpperCase(Locale.ROOT);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Page<AnswerFeedback> result = feedbackRepository
                .findByReviewStatusOrderByCreatedAtDesc(normalized, PageRequest.of(safePage, safeSize));
        List<QueueItem> items = result.getContent().stream().map(this::toQueueItem).toList();
        return PageResult.of(items, result.getTotalElements(), safePage, safeSize);
    }

    /** 标注结论：纳入黄金集 / 误报关闭 / 退回队列。 */
    @Transactional
    public void review(Long feedbackId, ReviewRequest request) {
        if (request == null) throw new IllegalArgumentException("标注内容不能为空");
        String status = request.getStatus() == null ? "" : request.getStatus().toUpperCase(Locale.ROOT);
        if (!VALID_REVIEW_STATUS.contains(status)) {
            throw new IllegalArgumentException("标注状态只能是 QUEUED / ANNOTATED / DISMISSED");
        }
        AnswerFeedback feedback = feedbackRepository.findById(feedbackId)
                .orElseThrow(() -> new IllegalArgumentException("反馈记录不存在"));
        feedback.setReviewStatus(status);
        feedback.setReviewNote(truncate(request.getNote(), MAX_COMMENT));
        feedbackRepository.save(feedback);
    }

    /** 反馈看板汇总（差评率按全部反馈计算，用于按档位 A/B 对照）。 */
    @Transactional(readOnly = true)
    public Stats stats() {
        long up = feedbackRepository.countByRating(AnswerFeedback.RATING_UP);
        long down = feedbackRepository.countByRating(AnswerFeedback.RATING_DOWN);
        long total = feedbackRepository.count();
        return new Stats(total, up, down,
                feedbackRepository.countByReviewStatus(AnswerFeedback.STATUS_QUEUED),
                feedbackRepository.countByReviewStatus(AnswerFeedback.STATUS_ANNOTATED),
                feedbackRepository.countByReviewStatus(AnswerFeedback.STATUS_DISMISSED),
                total == 0 ? 0.0 : (double) down / total);
    }

    /** 按书统计好评/差评，供后台图书页做质量对比。 */
    @Transactional(readOnly = true)
    public List<Object[]> countByRatingForBook(Long bookId) {
        return feedbackRepository.countByRatingForBook(bookId);
    }

    /** 重新生成删除旧回答时一并清理旧反馈，避免队列里留指向已删行的孤儿样本。 */
    @Transactional
    public void deleteByConversationId(Long conversationId) {
        if (conversationId == null) return;
        feedbackRepository.deleteByConversationId(conversationId);
    }

    /** 用快照 source 反推三档上下文模式，便于按档位分组统计差评率。 */
    private String resolveContextMode(Long conversationId) {
        try {
            List<AiRetrievalSnapshot> snapshots =
                    snapshotRepository.findByConversationIdOrderByChunkIndexAsc(conversationId);
            if (snapshots == null || snapshots.isEmpty()) return "unknown";
            String source = snapshots.get(0).getSource();
            if ("full_text".equals(source)) return "small";
            if ("chapter".equals(source)) return "medium";
            return "large";
        } catch (Exception e) {
            log.debug("[AnswerFeedback] 快照读取失败，模式标记 unknown: convId={} - {}", conversationId, e.getMessage());
            return "unknown";
        }
    }

    private QueueItem toQueueItem(AnswerFeedback f) {
        AiConversation answer = conversationRepository.findById(f.getConversationId()).orElse(null);
        String answerPreview = answer == null ? "" : truncate(answer.getContent(), ANSWER_PREVIEW);
        String question = "";
        if (answer != null && answer.getSessionId() != null) {
            question = conversationRepository
                    .findPreviousUserMessage(answer.getSessionId(), answer.getId())
                    .map(c -> truncate(c.getContent(), ANSWER_PREVIEW))
                    .orElse("");
        }
        String bookTitle = bookRepository.findById(f.getBookId()).map(Book::getTitle).orElse("");
        String createdAt = f.getCreatedAt() == null ? "" : f.getCreatedAt().format(TS);
        return new QueueItem(f.getId(), f.getBookId(), bookTitle, f.getConversationId(),
                f.getSessionId(), f.getRating(), f.getReason(), f.getComment(), f.getContextMode(),
                f.getReviewStatus(), f.getReviewNote(), createdAt, question, answerPreview);
    }

    private VO toVO(AnswerFeedback f) {
        return new VO(f.getId(), f.getConversationId(), f.getRating(), f.getReason(),
                f.getComment(), f.getContextMode(), f.getReviewStatus());
    }

    private static String normalizeRating(String rating) {
        String r = rating == null ? "" : rating.trim().toUpperCase(Locale.ROOT);
        if (!AnswerFeedback.RATING_UP.equals(r) && !AnswerFeedback.RATING_DOWN.equals(r)) {
            throw new IllegalArgumentException("反馈类型只能是 UP 或 DOWN");
        }
        return r;
    }

    private static String normalizeReason(String reason, String rating) {
        if (reason == null || reason.isBlank()) {
            return AnswerFeedback.RATING_DOWN.equals(rating) ? "OTHER" : null;
        }
        String r = reason.trim().toUpperCase(Locale.ROOT);
        return VALID_REASONS.contains(r) ? r : "OTHER";
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
