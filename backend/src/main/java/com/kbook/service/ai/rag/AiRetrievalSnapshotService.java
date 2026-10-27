package com.kbook.service.ai.rag;

import com.kbook.entity.AiRetrievalSnapshot;
import com.kbook.repository.AiRetrievalSnapshotRepository;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 检索依据快照写入：把一条 assistant 回答实际看到的 chunk 落库。
 * <p>
 * 只记录引用与预览，不复制正文；写入失败只记日志，不影响回答。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiRetrievalSnapshotService {

    /** 与 ai_retrieval_snapshots.text_preview 的 VARCHAR(200) 对齐 */
    static final int PREVIEW_MAX_CHARS = 200;

    private final AiRetrievalSnapshotRepository repository;

    /**
     * 不加 {@code @Transactional}：快照是诊断数据，且本方法在 SSE 回调里随主流程一起调用。
     * 若事务内写入失败被 catch 吞掉，事务已被标记 rollback-only，提交阶段仍会抛
     * UnexpectedRollbackException 把「回答已成功生成」变成「AI 响应异常」。
     */
    public void save(Long conversationId, Long bookId,
                     List<EmbeddingMatch<TextSegment>> matches, String promptVersion) {
        if (conversationId == null || bookId == null || matches == null || matches.isEmpty()) {
            return;
        }

        List<AiRetrievalSnapshot> rows = new ArrayList<>(matches.size());
        Set<Integer> seen = new HashSet<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            if (match == null) continue;
            // 合并片段在元数据里带跨度：12+13 合并后记为 chunk_index=12, chunk_end_index=13，
            // 只记组内最后一块会让召回统计漏掉被合并进去的前一块。
            int[] span = RagPipelineComponents.getChunkSpan(match);
            int chunkIndex = span[0];
            if (chunkIndex < 0 || !seen.add(chunkIndex)) continue;
            String text = match.embedded() != null ? match.embedded().text() : "";
            if (text == null || text.isBlank()) continue;

            Double rerankScore = match.embedded() != null && match.embedded().metadata() != null
                    ? match.embedded().metadata().getDouble("rerankScore") : null;

            rows.add(AiRetrievalSnapshot.builder()
                    .conversationId(conversationId)
                    .bookId(bookId)
                    .chunkIndex(chunkIndex)
                    .chunkEndIndex(span[1])
                    .score(match.score())
                    .rerankScore(rerankScore)
                    .source("context")
                    .textHash(sha256(text))
                    .textPreview(preview(text))
                    .promptVersion(promptVersion)
                    .build());
        }

        if (rows.isEmpty()) {
            return;
        }
        try {
            repository.saveAll(rows);
            log.debug("[Snapshot] 写入检索依据: conversationId={}, rows={}", conversationId, rows.size());
        } catch (Exception e) {
            // 快照是诊断数据，写入失败不能影响回答
            log.warn("[Snapshot] 写入失败（不影响回答）: conversationId={}, error={}",
                    conversationId, e.getMessage());
        }
    }

    /**
     * 预览严格不超过 {@link #PREVIEW_MAX_CHARS}：{@code CommonUtils.truncateText} 会额外追加 "..."，
     * 传 200 实际得到 203 字符，超出列宽导致整批插入失败。
     */
    static String preview(String text) {
        if (text == null) return null;
        if (text.length() <= PREVIEW_MAX_CHARS) return text;
        return text.substring(0, PREVIEW_MAX_CHARS - 3) + "...";
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return null;
        }
    }

    /** 删除某条回答的依据快照（重新生成旧回答时调用）。失败只记日志。 */
    public void deleteByConversationId(Long conversationId) {
        if (conversationId == null) {
            return;
        }
        try {
            repository.deleteByConversationId(conversationId);
        } catch (Exception e) {
            log.warn("[Snapshot] 删除失败（不影响主流程）: conversationId={}, error={}",
                    conversationId, e.getMessage());
        }
    }

    /** 查询某条回答的依据快照（按 chunkIndex 升序）。失败返回空列表。 */
    public List<AiRetrievalSnapshot> findByConversationId(Long conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        try {
            return repository.findByConversationIdOrderByChunkIndexAsc(conversationId);
        } catch (Exception e) {
            log.warn("[Snapshot] 查询失败: conversationId={}, error={}",
                    conversationId, e.getMessage());
            return List.of();
        }
    }
}
