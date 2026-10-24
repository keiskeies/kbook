package com.kbook.service.note;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.api.ErrorCode;
import com.kbook.common.exception.BusinessException;
import com.kbook.dto.note.NoteInsightGenerateRequest;
import com.kbook.dto.note.NoteInsightVO;
import com.kbook.dto.note.NoteSourceSnapshot;
import com.kbook.entity.AiScene;
import com.kbook.entity.Note;
import com.kbook.entity.NoteInsight;
import com.kbook.repository.NoteInsightRepository;
import com.kbook.repository.NoteRepository;
import com.kbook.service.ai.core.ChatModelManager;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.kbook.common.util.QueryBuilder.eq;
import static com.kbook.common.util.QueryBuilder.ge;
import static com.kbook.common.util.QueryBuilder.isNull;
import static com.kbook.common.util.QueryBuilder.le;

/**
 * 笔记成长总结 — 把一段时间内的私人笔记交给大模型，生成阶段性回顾并持久化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteInsightService {

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("M月d日");
    private static final int MAX_NOTES = 100;
    private static final int MAX_PROMPT_CHARS = 12000;

    private static final String SYSTEM_PROMPT = """
            你是用户私人的读书成长记录者。用户会给你一段时间内自己写的读书笔记（想法、摘录、收录的书评）。
            请基于这些笔记，写一份温暖、具体、不空洞的阶段性成长回顾。
            要求：
            1. 用第二人称「你」，像一位了解他的老朋友，不要用「用户」。
            2. 先概括这段时间的关注主题与兴趣走向，再点出反复出现的思考、认知上的推进或反复纠结的点。
            3. 引用笔记里的具体细节（书名、某句话、某个想法），不要泛泛而谈。
            4. 用 Markdown：开头一段总览，2-4 个小标题分述，最后一段给一句温和的提醒或期许。
            5. 篇幅 400-800 字。绝不编造笔记里不存在的内容。
            """;

    private final NoteInsightRepository insightRepository;
    private final NoteRepository noteRepository;
    private final ChatModelManager chatModelManager;
    private final ObjectMapper objectMapper;

    /**
     * 生成成长总结。
     * <p>
     * 刻意不加事务：LLM 调用可能耗时数十秒，若包在事务里会长时间占用数据库连接。
     * 这里只做「读笔记 → 调模型 → 单条插入」，无需事务保证。
     */
    public NoteInsightVO generate(Long userId, NoteInsightGenerateRequest req) {
        LocalDateTime to = req.getTo() != null ? req.getTo() : LocalDateTime.now();
        LocalDateTime from = req.getFrom() != null ? req.getFrom() : to.minusDays(30);
        if (!from.isBefore(to)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "时间范围不正确");
        }

        List<Note> notes = noteRepository.query()
                .where(Note::getUserId, eq(userId))
                .and(Note::getDeletedAt, isNull())
                .and(Note::getOccurredAt, ge(from))
                .and(Note::getOccurredAt, le(to))
                .orderBy(Note::getOccurredAt)
                .list(MAX_NOTES);
        if (notes.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "这段时间还没有笔记，先记点什么再来吧");
        }

        String userPrompt = buildPrompt(notes, from, to);
        List<ChatMessage> messages = List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(userPrompt));
        String output = chatModelManager.callAiForScene(
                AiScene.NOTE_INSIGHT, "笔记成长总结", "notes=" + notes.size(), messages);
        if (output == null || output.isBlank()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 总结生成失败，请确认模型已配置");
        }

        NoteInsight insight = NoteInsight.builder()
                .userId(userId)
                .periodStart(from)
                .periodEnd(to)
                .title("成长总结 · " + from.format(MD) + " - " + to.format(MD))
                .contentMd(output.trim())
                .noteCount(notes.size())
                .build();
        insightRepository.save(insight);
        log.info("笔记成长总结已生成: userId={}, insightId={}, notes={}", userId, insight.getId(), notes.size());
        return toVO(insight);
    }

    public List<NoteInsightVO> list(Long userId) {
        return insightRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .limit(30)
                .map(this::toVO)
                .toList();
    }

    public NoteInsightVO get(Long userId, Long insightId) {
        NoteInsight insight = insightRepository.findById(insightId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "总结不存在"));
        if (!insight.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能查看自己的总结");
        }
        return toVO(insight);
    }

    // ==================== 内部方法 ====================

    private String buildPrompt(List<Note> notes, LocalDateTime from, LocalDateTime to) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("时间范围：").append(from.format(MD)).append(" 至 ").append(to.format(MD))
                .append("，共有 ").append(notes.size()).append(" 条笔记：\n\n");
        for (Note note : notes) {
            if (sb.length() > MAX_PROMPT_CHARS) {
                sb.append("\n（后续笔记已省略）\n");
                break;
            }
            sb.append("[").append(note.getOccurredAt().format(MD)).append("]");
            if (note.getBookTitle() != null) {
                sb.append("《").append(note.getBookTitle()).append("》");
            }
            sb.append("｜").append(note.getNoteType());
            if (note.getTitle() != null) {
                sb.append("｜").append(note.getTitle());
            }
            sb.append("\n");
            String body = noteBody(note);
            if (body != null && !body.isBlank()) {
                sb.append(body.length() > 1000 ? body.substring(0, 1000) : body).append("\n");
            }
            sb.append("---\n");
        }
        return sb.toString();
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
            log.warn("总结时解析笔记快照失败: noteId={}", note.getId());
            return null;
        }
    }

    private NoteInsightVO toVO(NoteInsight insight) {
        return NoteInsightVO.builder()
                .id(insight.getId())
                .periodStart(insight.getPeriodStart())
                .periodEnd(insight.getPeriodEnd())
                .title(insight.getTitle())
                .contentMd(insight.getContentMd())
                .noteCount(insight.getNoteCount())
                .createdAt(insight.getCreatedAt())
                .build();
    }
}
