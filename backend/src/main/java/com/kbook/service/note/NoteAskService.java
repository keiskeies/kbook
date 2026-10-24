package com.kbook.service.note;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.api.ErrorCode;
import com.kbook.common.exception.BusinessException;
import com.kbook.dto.note.NoteAskSourceVO;
import com.kbook.dto.note.NoteAskVO;
import com.kbook.dto.note.NoteSourceSnapshot;
import com.kbook.entity.AiScene;
import com.kbook.entity.Note;
import com.kbook.repository.NoteRepository;
import com.kbook.service.ai.core.ChatModelManager;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.kbook.common.util.QueryBuilder.eq;
import static com.kbook.common.util.QueryBuilder.isNull;

/**
 * 笔记问答 — 先用语义检索找出最相关的笔记，再让模型只基于这些笔记回答并标注引用。
 * <p>
 * 不包事务：LLM 调用可能耗时数十秒，避免长时间占用数据库连接。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteAskService {

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("M月d日");
    private static final int TOP_K = 8;
    private static final int MAX_CONTEXT_CHARS = 12000;
    private static final int MAX_BODY_CHARS = 1000;

    private static final String SYSTEM_PROMPT = """
            你是用户私人笔记的检索助手。用户会提出一个问题，并附上从他自己过往笔记里检索到的若干条内容。
            请只用这些笔记作答：
            1. 先用一两句话直接回答问题，再补充笔记里的依据。
            2. 引用笔记时使用 [1]、[2] 这样的编号，编号对应提供的笔记。
            3. 如果笔记不足以回答，就诚实说明「你的笔记里还没有写到这一点」，不要编造，也不要使用外部知识。
            4. 用中文，语气自然、具体，不要空泛，总篇幅 200-500 字。
            """;

    private final NoteRepository noteRepository;
    private final NoteEmbeddingService noteEmbeddingService;
    private final ChatModelManager chatModelManager;
    private final ObjectMapper objectMapper;

    public NoteAskVO ask(Long userId, String question) {
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "问题不能为空");
        }

        List<Note> candidates = retrieve(userId, q);
        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "还没有可用的笔记，先记几条再来问吧");
        }
        // 上下文的 [n] 编号与返回给前端的来源必须是同一批，否则引用会对不上
        List<Note> used = withinBudget(candidates);
        String userPrompt = "我的问题：" + q + "\n\n我检索到的笔记：\n\n" + buildContext(used);
        List<ChatMessage> messages = List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(userPrompt));
        String answer;
        try {
            answer = chatModelManager.callAiForScene(
                    AiScene.NOTE_INSIGHT, "笔记问答", "q=" + (q.length() > 60 ? q.substring(0, 60) : q), messages);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("笔记问答调用模型失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 模型暂时不可用，请检查模型配置后重试");
        }
        if (answer == null || answer.isBlank()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 暂时没有给出回答，请确认模型配置");
        }

        log.info("笔记问答完成: userId={}, candidates={}, used={}", userId, candidates.size(), used.size());
        return NoteAskVO.builder()
                .answer(answer.trim())
                .sources(used.stream().map(this::toSource).toList())
                .build();
    }

    /**
     * 语义检索优先；向量不可用时退回关键词搜索；再不行用最近笔记兜底
     */
    private List<Note> retrieve(Long userId, String question) {
        List<NoteEmbeddingService.NoteHit> hits = noteEmbeddingService.search(userId, question, TOP_K);
        if (!hits.isEmpty()) {
            List<Long> ids = hits.stream().map(NoteEmbeddingService.NoteHit::noteId).toList();
            Map<Long, Note> byId = noteRepository.findAllById(ids).stream()
                    .filter(n -> userId.equals(n.getUserId()) && n.getDeletedAt() == null)
                    .collect(Collectors.toMap(Note::getId, n -> n));
            List<Note> ordered = ids.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .toList();
            if (!ordered.isEmpty()) {
                return ordered;
            }
        }

        List<Note> keywordHits = noteRepository.search(userId, question, PageRequest.of(0, TOP_K)).getContent();
        if (!keywordHits.isEmpty()) {
            return keywordHits;
        }

        return noteRepository.query()
                .where(Note::getUserId, eq(userId))
                .and(Note::getDeletedAt, isNull())
                .orderByDesc(Note::getOccurredAt)
                .list(TOP_K);
    }

    /** 按字符预算裁剪进入上下文的笔记；[n] 编号与来源列表都以这批为准 */
    private List<Note> withinBudget(List<Note> notes) {
        List<Note> used = new ArrayList<>();
        int chars = 0;
        for (Note note : notes) {
            String body = noteBody(note);
            chars += (body == null ? 0 : Math.min(body.length(), MAX_BODY_CHARS)) + 80;
            if (chars > MAX_CONTEXT_CHARS && !used.isEmpty()) {
                break;
            }
            used.add(note);
        }
        return used;
    }

    private String buildContext(List<Note> notes) {
        StringBuilder sb = new StringBuilder(4096);
        int index = 0;
        for (Note note : notes) {
            index++;
            sb.append("[").append(index).append("] ")
                    .append(note.getOccurredAt().format(MD));
            if (note.getTitle() != null && !note.getTitle().isBlank()) {
                sb.append("｜").append(note.getTitle());
            }
            if (note.getBookTitle() != null) {
                sb.append("｜《").append(note.getBookTitle()).append("》");
            }
            sb.append("\n");
            String body = noteBody(note);
            if (body != null && !body.isBlank()) {
                sb.append(body.length() > MAX_BODY_CHARS ? body.substring(0, MAX_BODY_CHARS) : body).append("\n");
            }
            sb.append("---\n");
        }
        return sb.toString();
    }

    private NoteAskSourceVO toSource(Note note) {
        String body = noteBody(note);
        String snippet = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (snippet.length() > 120) {
            snippet = snippet.substring(0, 120) + "…";
        }
        return NoteAskSourceVO.builder()
                .id(note.getId())
                .title(note.getTitle())
                .bookTitle(note.getBookTitle())
                .occurredAt(note.getOccurredAt())
                .snippet(snippet)
                .build();
    }

    /** 笔记正文；纯收录没有批注时，用快照里的书评内容兜底 */
    private String noteBody(Note note) {
        if (note.getContentMd() != null && !note.getContentMd().isBlank()) {
            return note.getContentMd();
        }
        if (note.getSourceSnapshot() == null || note.getSourceSnapshot().isBlank()) {
            return null;
        }
        try {
            NoteSourceSnapshot snapshot = objectMapper.readValue(note.getSourceSnapshot(), NoteSourceSnapshot.class);
            if (note.getCaptureMode() == null) {
                return snapshot.getSourceContent();
            }
            return switch (note.getCaptureMode()) {
                case EXCERPT -> snapshot.getExcerpt();
                case REFERENCE -> snapshot.getSourceTitle();
                default -> snapshot.getSourceContent();
            };
        } catch (Exception e) {
            log.warn("问答解析笔记快照失败: noteId={}", note.getId());
            return null;
        }
    }
}
