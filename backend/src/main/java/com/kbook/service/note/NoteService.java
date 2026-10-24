package com.kbook.service.note;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.api.ErrorCode;
import com.kbook.common.api.PageResult;
import com.kbook.common.exception.BusinessException;
import com.kbook.common.util.QueryBuilder;
import com.kbook.dto.note.NoteCreateRequest;
import com.kbook.dto.note.NoteRevisionVO;
import com.kbook.dto.note.NoteSourceSnapshot;
import com.kbook.dto.note.NoteUpdateRequest;
import com.kbook.dto.note.NoteVO;
import com.kbook.entity.Note;
import com.kbook.entity.NoteRevision;
import com.kbook.entity.NoteSourceType;
import com.kbook.entity.NoteType;
import com.kbook.entity.SourceStatus;
import com.kbook.repository.NoteRepository;
import com.kbook.repository.NoteRevisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.kbook.common.util.QueryBuilder.eq;
import static com.kbook.common.util.QueryBuilder.in;
import static com.kbook.common.util.QueryBuilder.isNull;

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
            noteRepository.save(note);
            noteEmbeddingService.index(note);
        }
        return toVO(note);
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
                                          LocalDateTime from, LocalDateTime to, int page, int size) {
        QueryBuilder<Note> query = noteRepository.query()
                .where(Note::getUserId, eq(userId))
                .and(Note::getDeletedAt, isNull());
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
        Page<Note> result = query.orderByDesc(Note::getOccurredAt).page(page - 1, size);
        return PageResult.of(result.getContent().stream().map(this::toVO).toList(),
                result.getTotalElements(), page, size);
    }

    public long countNotes(Long userId) {
        return noteRepository.countByUserIdAndDeletedAtIsNull(userId);
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
}
