package com.kbook.service.note;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kbook.dto.note.NoteTagVO;
import com.kbook.dto.note.NoteVO;
import com.kbook.entity.AiScene;
import com.kbook.entity.Note;
import com.kbook.repository.BookRepository;
import com.kbook.service.ai.core.ChatModelManager;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 笔记自动标签 — 由模型在「用户历史标签 + 图书标签」词库中做选择题。
 * <p>
 * 不做自由生成，也不强求结果：词库为空、模型不可用、输出不合规时都保持无标签。
 * 不包事务：LLM 调用可能耗时，写回由 {@link NoteService#addTagsIfEmpty} 的独立事务完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteAutoTagService {

    private static final String SYSTEM_PROMPT = """
            你是用户私人笔记的自动标签整理器。用户会给出一条笔记和一份候选标签词库。
            请从候选词库中选择 1-3 个最能概括这条笔记主题的标签。

            规则：
            1. 只能使用候选词库中已有的标签，逐字照抄，不得改写、合并或创造新标签。
            2. 如果候选词库里没有真正合适的标签，返回空数组 []，不要勉强选择。
            3. 只输出 JSON 字符串数组，例如 ["认知偏差","决策"]；不要输出解释、Markdown 或代码块。
            """;

    private static final int MAX_USER_TAGS = 50;
    private static final int MAX_BOOK_TAGS = 80;
    private static final int MAX_CANDIDATES = 120;
    private static final int MAX_SELECTED = 3;
    private static final int MAX_CONTENT_CHARS = 1200;
    private static final int MAX_TAG_CHARS = 20;
    private static final int MAX_BOOK_ROWS = 3000;

    private final NoteService noteService;
    private final BookRepository bookRepository;
    private final ChatModelManager chatModelManager;
    private final ObjectMapper objectMapper;

    /** 图书标签词库与用户无关，按热门图书取一次后复用，避免每条笔记都解析全库 */
    private final Cache<String, List<String>> bookTagCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(1)
            .build();

    /**
     * 为仍无标签的笔记补标签；任何失败都原样返回当前笔记。
     */
    public NoteVO autoTag(Long userId, Long noteId) {
        Note note = noteService.loadOwnedNote(userId, noteId);
        if (hasTags(note) || isBlank(note.getContentMd())) {
            return noteService.getNote(userId, noteId);
        }

        List<String> vocabulary = buildVocabulary(userId);
        if (vocabulary.isEmpty()) {
            log.info("笔记自动标签跳过：候选词库为空, userId={}", userId);
            return noteService.getNote(userId, noteId);
        }

        String raw = callModel(note, vocabulary);
        List<String> selected = NoteTagResponseParser.parse(raw, vocabulary, MAX_SELECTED);
        if (selected.isEmpty()) {
            log.info("笔记自动标签未选中合适标签: noteId={}", noteId);
            return noteService.getNote(userId, noteId);
        }

        NoteVO updated = noteService.addTagsIfEmpty(userId, noteId, selected);
        log.info("笔记自动标签完成: noteId={}, tags={}", noteId, selected);
        return updated;
    }

    private String callModel(Note note, List<String> vocabulary) {
        String userPrompt = "候选词库：" + String.join("、", vocabulary)
                + "\n\n笔记标题：" + (isBlank(note.getTitle()) ? "（无）" : note.getTitle())
                + "\n笔记内容：\n" + truncate(note.getContentMd().strip(), MAX_CONTENT_CHARS);
        List<ChatMessage> messages = List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(userPrompt));
        try {
            return chatModelManager.callAiForScene(
                    AiScene.NOTE_AUTO_TAG, "笔记自动标签", "noteId=" + note.getId(), messages);
        } catch (Exception e) {
            log.warn("笔记自动标签调用模型失败: noteId={}, {}", note.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 用户自己的标签优先，其次图书标签按出现频次降序；总量封顶，避免提示词膨胀。
     */
    private List<String> buildVocabulary(Long userId) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (NoteTagVO tag : noteService.listTags(userId)) {
            String cleaned = clean(tag.getTag());
            if (!cleaned.isEmpty()) {
                candidates.add(cleaned);
            }
            if (candidates.size() >= MAX_USER_TAGS) {
                break;
            }
        }

        for (String tag : bookVocabulary()) {
            candidates.add(tag);
            if (candidates.size() >= MAX_CANDIDATES) {
                break;
            }
        }

        return candidates.stream().limit(MAX_CANDIDATES).collect(Collectors.toList());
    }

    private List<String> bookVocabulary() {
        return bookTagCache.get("book-tags", key -> loadBookVocabulary());
    }

    private List<String> loadBookVocabulary() {
        Map<String, Integer> counts = new HashMap<>();
        try {
            for (Object[] row : bookRepository.findNoteTagColumns(PageRequest.of(0, MAX_BOOK_ROWS))) {
                Set<String> tagsInBook = new HashSet<>();
                for (Object column : row) {
                    collectJsonTags(column, tagsInBook);
                }
                for (String tag : tagsInBook) {
                    counts.merge(tag, 1, Integer::sum);
                }
            }
        } catch (Exception e) {
            log.warn("读取图书标签词库失败，仅使用用户历史标签: {}", e.getMessage());
        }

        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .limit(MAX_BOOK_TAGS)
                .collect(Collectors.toList());
    }

    private void collectJsonTags(Object column, Set<String> target) {
        if (column == null) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(column.toString());
            if (!root.isArray()) {
                return;
            }
            for (JsonNode node : root) {
                if (node.isTextual()) {
                    String tag = clean(node.asText());
                    if (!tag.isEmpty()) {
                        target.add(tag);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("图书标签列解析失败，跳过: {}", e.getMessage());
        }
    }

    /** 清洗规则与 NoteService 保持一致，避免候选与最终落库值不一致 */
    private String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String tag = raw.replace(",", " ").replace("%", "").replace("_", "").trim();
        return tag.length() > MAX_TAG_CHARS ? tag.substring(0, MAX_TAG_CHARS) : tag;
    }

    private static boolean hasTags(Note note) {
        return note.getTags() != null && !note.getTags().isBlank();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }
}
