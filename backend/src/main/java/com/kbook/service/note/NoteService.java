package com.kbook.service.note;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.api.ErrorCode;
import com.kbook.common.api.PageResult;
import com.kbook.common.exception.BusinessException;
import com.kbook.common.util.QueryBuilder;
import com.kbook.dto.note.NoteBookVO;
import com.kbook.dto.note.NoteCreateRequest;
import com.kbook.dto.note.NoteDailyCountVO;
import com.kbook.dto.note.NoteNeighborVO;
import com.kbook.dto.note.NoteNeighborsVO;
import com.kbook.dto.note.NoteRevisionVO;
import com.kbook.dto.note.NoteSourceSnapshot;
import com.kbook.dto.note.NoteStatsVO;
import com.kbook.dto.note.NoteTagVO;
import com.kbook.dto.note.NoteUpdateRequest;
import com.kbook.dto.note.NoteVO;
import com.kbook.entity.Note;
import com.kbook.entity.NoteRevision;
import com.kbook.common.enums.NoteSourceType;
import com.kbook.common.enums.NoteType;
import com.kbook.common.enums.SourceStatus;
import com.kbook.repository.NoteRepository;
import com.kbook.repository.NoteRevisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.kbook.common.util.QueryBuilder.between;
import static com.kbook.common.util.QueryBuilder.eq;
import static com.kbook.common.util.QueryBuilder.gt;
import static com.kbook.common.util.QueryBuilder.in;
import static com.kbook.common.util.QueryBuilder.isNotNull;
import static com.kbook.common.util.QueryBuilder.isNull;
import static com.kbook.common.util.QueryBuilder.le;
import static com.kbook.common.util.QueryBuilder.like;
import static com.kbook.common.util.QueryBuilder.lt;

/**
 * 笔记服务 — 私人资产的 CRUD 与时间轴查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteService {

    private final NoteRepository noteRepository;
    private final NoteRevisionRepository noteRevisionRepository;
    private final NoteEmbeddingService noteEmbeddingService;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public NoteVO createThought(Long userId, NoteCreateRequest req) {
        Note note = Note.builder()
                .userId(userId)
                .title(trimToNull(req.getTitle()))
                .contentMd(req.getContentMd())
                .noteType(NoteType.THOUGHT)
                .sourceType(NoteSourceType.MANUAL)
                .sourceStatus(SourceStatus.ACTIVE)
                .tags(normalizeTags(req.getTags()))
                .occurredAt(req.getOccurredAt() != null ? req.getOccurredAt() : LocalDateTime.now())
                .build();
        noteRepository.save(note);
        log.info("新建笔记: userId={}, noteId={}", userId, note.getId());
        noteEmbeddingService.index(note);
        return toVO(note);
    }

    @Transactional(rollbackFor = Exception.class)
    public NoteVO updateNote(Long userId, Long noteId, NoteUpdateRequest req) {
        Note note = loadOwnedNote(userId, noteId);

        String newTitle = trimToNull(req.getTitle());
        String newContent = req.getContentMd();
        LocalDateTime newOccurredAt = req.getOccurredAt() != null ? req.getOccurredAt() : note.getOccurredAt();

        boolean changed = !Objects.equals(note.getTitle(), newTitle)
                || !Objects.equals(note.getContentMd(), newContent)
                || !Objects.equals(note.getOccurredAt(), newOccurredAt);
        if (changed) {
            noteRevisionRepository.save(NoteRevision.builder()
                    .noteId(note.getId())
                    .revisionNo(note.getVersion())
                    .title(note.getTitle())
                    .contentMd(note.getContentMd())
                    .occurredAt(note.getOccurredAt())
                    .build());
            note.setTitle(newTitle);
            note.setContentMd(newContent);
            note.setOccurredAt(newOccurredAt);
            note.setVersion(note.getVersion() + 1);
        }
        // 标签属于元数据：null 表示本次不修改，空数组表示清空
        boolean tagsChanged = req.getTags() != null
                && !Objects.equals(note.getTags(), normalizeTags(req.getTags()));
        if (tagsChanged) {
            note.setTags(normalizeTags(req.getTags()));
        }
        if (changed || tagsChanged) {
            noteRepository.save(note);
        }
        if (changed) {
            noteEmbeddingService.index(note);
        }
        return toVO(note);
    }

    @Transactional(rollbackFor = Exception.class)
    public NoteVO setPinned(Long userId, Long noteId, boolean pinned) {
        Note note = loadOwnedNote(userId, noteId);
        note.setPinnedAt(pinned ? LocalDateTime.now() : null);
        noteRepository.save(note);
        return toVO(note);
    }

    @Transactional(rollbackFor = Exception.class)
    public NoteVO setFavorited(Long userId, Long noteId, boolean favorited) {
        Note note = loadOwnedNote(userId, noteId);
        note.setFavoritedAt(favorited ? LocalDateTime.now() : null);
        noteRepository.save(note);
        return toVO(note);
    }

    @Transactional(rollbackFor = Exception.class)
    public NoteVO updateTags(Long userId, Long noteId, List<String> tags) {
        Note note = loadOwnedNote(userId, noteId);
        note.setTags(normalizeTags(tags));
        noteRepository.save(note);
        return toVO(note);
    }

    /**
     * 标签云 — 按使用次数降序
     */
    public List<NoteTagVO> listTags(Long userId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String stored : noteRepository.findTagsByUserId(userId)) {
            for (String tag : parseTags(stored)) {
                counts.merge(tag, 1L, Long::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(50)
                .map(e -> new NoteTagVO(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * 当前笔记的前后邻居（按发生时间，不含置顶影响）
     */
    public NoteNeighborsVO getNeighbors(Long userId, Long noteId) {
        Note current = loadOwnedNote(userId, noteId);
        Note prev = noteRepository.query()
                .where(Note::getUserId, eq(userId))
                .and(Note::getDeletedAt, isNull())
                .and(Note::getOccurredAt, lt(current.getOccurredAt()))
                .orderByDesc(Note::getOccurredAt)
                .orderByDesc(Note::getId)
                .list(1)
                .stream().findFirst().orElse(null);
        Note next = noteRepository.query()
                .where(Note::getUserId, eq(userId))
                .and(Note::getDeletedAt, isNull())
                .and(Note::getOccurredAt, gt(current.getOccurredAt()))
                .orderBy(Note::getOccurredAt)
                .orderBy(Note::getId)
                .list(1)
                .stream().findFirst().orElse(null);
        return NoteNeighborsVO.builder()
                .prev(prev == null ? null : NoteNeighborVO.builder()
                        .id(prev.getId()).title(prev.getTitle()).occurredAt(prev.getOccurredAt()).build())
                .next(next == null ? null : NoteNeighborVO.builder()
                        .id(next.getId()).title(next.getTitle()).occurredAt(next.getOccurredAt()).build())
                .build();
    }

    /**
     * 历史版本 — 从新到旧，供「回看某个时间点的自己」
     */
    public List<NoteRevisionVO> getRevisions(Long userId, Long noteId) {
        loadOwnedNote(userId, noteId);
        return noteRevisionRepository.findByNoteIdOrderByRevisionNoDesc(noteId).stream()
                .map(r -> NoteRevisionVO.builder()
                        .id(r.getId())
                        .revisionNo(r.getRevisionNo())
                        .title(r.getTitle())
                        .contentMd(r.getContentMd())
                        .occurredAt(r.getOccurredAt())
                        .editedAt(r.getCreatedAt())
                        .build())
                .toList();
    }

    /**
     * 关键词搜索本人笔记（标题或正文）
     */
    public PageResult<NoteVO> search(Long userId, String keyword, int page, int size) {
        Page<Note> result = noteRepository.search(
                userId, keyword == null ? "" : keyword.trim(),
                PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "occurredAt")));
        return PageResult.of(result.getContent().stream().map(this::toVO).toList(),
                result.getTotalElements(), page, size);
    }

    /**
     * 语义检索本人笔记 — 向量库不可用时返回空列表，前端可回退到关键词搜索
     */
    public List<NoteVO> semanticSearch(Long userId, String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<NoteEmbeddingService.NoteHit> hits = noteEmbeddingService.search(userId, query.trim(), limit);
        if (hits.isEmpty()) {
            return List.of();
        }
        List<Long> ids = hits.stream().map(NoteEmbeddingService.NoteHit::noteId).toList();
        Map<Long, Note> byId = noteRepository.query()
                .where(Note::getUserId, eq(userId))
                .and(Note::getDeletedAt, isNull())
                .and(Note::getId, in(ids))
                .list()
                .stream()
                .collect(Collectors.toMap(Note::getId, n -> n));
        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(this::toVO)
                .toList();
    }

    /**
     * 软删 — 笔记是私人资产，允许撤销（Phase 3 提供回收入口）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteNote(Long userId, Long noteId) {
        Note note = loadOwnedNote(userId, noteId);
        note.setDeletedAt(LocalDateTime.now());
        noteRepository.save(note);
        noteEmbeddingService.remove(noteId);
        log.info("删除笔记: userId={}, noteId={}", userId, noteId);
    }

    public NoteVO getNote(Long userId, Long noteId) {
        return toVO(loadOwnedNote(userId, noteId));
    }

    /**
     * 时间轴 — 一律按 occurred_at 倒序
     */
    public PageResult<NoteVO> getTimeline(Long userId, String noteType, Long bookId,
                                          LocalDateTime from, LocalDateTime to,
                                          String tag, Boolean favorited, String sort,
                                          int page, int size) {
        QueryBuilder<Note> query = noteRepository.query()
                .where(Note::getUserId, eq(userId))
                .and(Note::getDeletedAt, isNull());
        if (Boolean.TRUE.equals(favorited)) {
            query.and(Note::getFavoritedAt, isNotNull());
        }
        if (tag != null && !tag.isBlank()) {
            query.and(Note::getTags, like("%," + sanitizeTag(tag) + ",%"));
        }
        if (noteType != null && !noteType.isBlank()) {
            NoteType parsed;
            try {
                parsed = NoteType.valueOf(noteType.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "未知的笔记类型: " + noteType);
            }
            query.and(Note::getNoteType, eq(parsed));
        }
        if (bookId != null) {
            query.and(Note::getBookId, eq(bookId));
        }
        if (from != null) {
            query.and(Note::getOccurredAt, QueryBuilder.ge(from));
        }
        if (to != null) {
            query.and(Note::getOccurredAt, QueryBuilder.le(to));
        }
        // 置顶永远优先（MySQL DESC 下 NULL 排在最后），其次按所选排序
        query.orderByDesc(Note::getPinnedAt);
        if ("updated".equalsIgnoreCase(sort)) {
            query.orderByDesc(Note::getUpdatedAt);
        } else {
            query.orderByDesc(Note::getOccurredAt);
        }
        query.orderByDesc(Note::getId);
        Page<Note> result = query.page(page - 1, size);
        return PageResult.of(result.getContent().stream().map(this::toVO).toList(),
                result.getTotalElements(), page, size);
    }

    public long countNotes(Long userId) {
        return noteRepository.countByUserIdAndDeletedAtIsNull(userId);
    }

    /**
     * 成长概览 — 总数、本周、涉及书目、连续记录天数、各类型计数与最近 7 天节奏
     */
    public NoteStatsVO getStats(Long userId) {
        LocalDateTime weekStart = LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();
        Map<LocalDate, Long> dailyCounts = noteRepository.findOccurredAtSince(userId, LocalDateTime.now().minusDays(400))
                .stream()
                .collect(Collectors.groupingBy(LocalDateTime::toLocalDate, Collectors.counting()));
        Set<LocalDate> days = dailyCounts.keySet();
        LocalDate today = LocalDate.now();
        List<NoteDailyCountVO> recentDays = IntStream.rangeClosed(0, 6)
                .mapToObj(i -> today.minusDays(6 - i))
                .map(date -> new NoteDailyCountVO(date, dailyCounts.getOrDefault(date, 0L)))
                .toList();
        List<NoteDailyCountVO> heatmap = IntStream.rangeClosed(0, 363)
                .mapToObj(i -> today.minusDays(363 - i))
                .map(date -> new NoteDailyCountVO(date, dailyCounts.getOrDefault(date, 0L)))
                .toList();
        return NoteStatsVO.builder()
                .total(noteRepository.countByUserIdAndDeletedAtIsNull(userId))
                .weekCount(noteRepository.countByUserIdAndDeletedAtIsNullAndOccurredAtGreaterThanEqual(userId, weekStart))
                .bookCount(noteRepository.countDistinctBooks(userId))
                .streakDays(computeStreak(days))
                // 与「全年足迹」同一口径，避免连续天数面板与热力图的累计天数对不上
                .activeDays(heatmap.stream().filter(d -> d.getCount() > 0).count())
                .thoughtCount(noteRepository.countByUserIdAndDeletedAtIsNullAndNoteType(userId, NoteType.THOUGHT))
                .captureCount(noteRepository.countByUserIdAndDeletedAtIsNullAndNoteType(userId, NoteType.REVIEW_CAPTURE))
                .excerptCount(noteRepository.countByUserIdAndDeletedAtIsNullAndNoteType(userId, NoteType.EXCERPT))
                .favoriteCount(noteRepository.countByUserIdAndDeletedAtIsNullAndFavoritedAtIsNotNull(userId))
                .recentDays(recentDays)
                .heatmap(heatmap)
                .build();
    }

    /**
     * 按书聚合 — 最近有记录的书排前面，供「按书回顾」使用
     */
    public List<NoteBookVO> listBooks(Long userId) {
        return noteRepository.aggregateByBook(userId);
    }

    /**
     * 时光机 — 优先「去年今天」「一个月前的今天」，否则随机翻一条 30 天前的笔记
     */
    public NoteVO getThrowback(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        NoteVO hit = firstInWindow(userId, now.minusYears(1).minusDays(2), now.minusYears(1).plusDays(2));
        if (hit != null) {
            return hit;
        }
        hit = firstInWindow(userId, now.minusMonths(1).minusDays(3), now.minusMonths(1).plusDays(3));
        if (hit != null) {
            return hit;
        }

        LocalDateTime cutoff = now.minusDays(30);
        long count = noteRepository.query()
                .where(Note::getUserId, eq(userId))
                .and(Note::getDeletedAt, isNull())
                .and(Note::getOccurredAt, le(cutoff))
                .value();
        if (count == 0) {
            return null;
        }
        int offset = ThreadLocalRandom.current().nextInt((int) Math.min(count, 500));
        Page<Note> page = noteRepository.query()
                .where(Note::getUserId, eq(userId))
                .and(Note::getDeletedAt, isNull())
                .and(Note::getOccurredAt, le(cutoff))
                .orderByDesc(Note::getOccurredAt)
                .page(offset, 1);
        return page.isEmpty() ? null : toVO(page.getContent().get(0));
    }

    private NoteVO firstInWindow(Long userId, LocalDateTime from, LocalDateTime to) {
        Page<Note> page = noteRepository.query()
                .where(Note::getUserId, eq(userId))
                .and(Note::getDeletedAt, isNull())
                .and(Note::getOccurredAt, between(from, to))
                .orderByDesc(Note::getOccurredAt)
                .page(0, 1);
        return page.isEmpty() ? null : toVO(page.getContent().get(0));
    }

    /** 今天或昨天有记录即视为连续中，向前逐日统计 */
    private long computeStreak(Set<LocalDate> days) {
        if (days.isEmpty()) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        LocalDate cursor = days.contains(today) ? today
                : days.contains(today.minusDays(1)) ? today.minusDays(1) : null;
        if (cursor == null) {
            return 0;
        }
        long count = 0;
        while (days.contains(cursor)) {
            count++;
            cursor = cursor.minusDays(1);
        }
        return count;
    }

    // ==================== 内部方法 ====================

    Note loadOwnedNote(Long userId, Long noteId) {
        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTE_NOT_FOUND));
        if (!note.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能操作自己的笔记");
        }
        if (note.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOTE_NOT_FOUND);
        }
        return note;
    }

    NoteVO toVO(Note note) {
        return NoteVO.builder()
                .id(note.getId())
                .title(note.getTitle())
                .contentMd(note.getContentMd())
                .noteType(note.getNoteType())
                .sourceType(note.getSourceType())
                .sourceId(note.getSourceId())
                .sourceStatus(note.getSourceStatus())
                .snapshot(parseSnapshot(note.getSourceSnapshot()))
                .captureMode(note.getCaptureMode())
                .bookId(note.getBookId())
                .bookTitle(note.getBookTitle())
                .bookCover(note.getBookCover())
                .tags(parseTags(note.getTags()))
                .pinned(note.getPinnedAt() != null)
                .favorited(note.getFavoritedAt() != null)
                .occurredAt(note.getOccurredAt())
                .version(note.getVersion())
                .createdAt(note.getCreatedAt())
                .updatedAt(note.getUpdatedAt())
                .build();
    }

    String writeSnapshot(NoteSourceSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "快照序列化失败");
        }
    }

    private NoteSourceSnapshot parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, NoteSourceSnapshot.class);
        } catch (JsonProcessingException e) {
            log.warn("笔记快照解析失败，按无快照处理: {}", e.getMessage());
            return null;
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 标签统一存成 ",a,b," 形式，便于 LIKE 精确匹配；去重、去空、限 8 个 */
    private String normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        List<String> cleaned = new ArrayList<>();
        for (String raw : tags) {
            String tag = sanitizeTag(raw);
            if (!tag.isEmpty() && !cleaned.contains(tag) && cleaned.size() < 8) {
                cleaned.add(tag);
            }
        }
        return cleaned.isEmpty() ? null : "," + String.join(",", cleaned) + ",";
    }

    /** 去掉逗号（分隔符）以及 LIKE 通配符，避免标签破坏查询语义 */
    private String sanitizeTag(String raw) {
        if (raw == null) {
            return "";
        }
        String tag = raw.replace(",", " ").replace("%", "").replace("_", "").trim();
        return tag.length() > 20 ? tag.substring(0, 20) : tag;
    }

    private List<String> parseTags(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String part : stored.split(",")) {
            String tag = part.trim();
            if (!tag.isEmpty()) {
                tags.add(tag);
            }
        }
        return tags;
    }
}
