package com.kbook.service.roundtable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.exception.BusinessException;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SpeechSanitizer;
import com.kbook.common.util.SseHelper;
import com.kbook.config.ai.AiConfig;
import com.kbook.config.ai.AiConfigProvider;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.config.annotation.RedisLock;
import com.kbook.constants.AiPromptConstants;
import com.kbook.dto.roundtable.NextSpeakerResult;
import com.kbook.dto.roundtable.RoleVO;
import com.kbook.dto.roundtable.RoundTableSessionFeedVO;
import com.kbook.dto.roundtable.SpeakRequest;
import com.kbook.entity.AiScene;
import com.kbook.entity.AiProviderConfig;
import com.kbook.entity.Book;
import com.kbook.entity.RoundTableCoverage;
import com.kbook.entity.RoundTableMessage;
import com.kbook.entity.RoundTableSession;
import com.kbook.repository.BookRepository;
import com.kbook.repository.RoundTableCoverageRepository;
import com.kbook.repository.RoundTableMessageRepository;
import com.kbook.repository.RoundTableSessionRepository;
import com.kbook.service.ai.core.ChatHistoryCompressor;
import com.kbook.service.ai.core.ExternalKnowledgeGenerator;
import com.kbook.service.ai.streaming.StreamingSseHandler;
import com.kbook.service.book.BookParserService;
import com.kbook.service.book.BookService;
import com.kbook.service.embedding.EmbeddingService;
import com.kbook.service.progress.ReadingProgressService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import static com.kbook.common.util.QueryBuilder.eq;
import static com.kbook.common.util.QueryBuilder.in;
import com.kbook.service.ai.rag.RagPipelineComponents;
import com.kbook.service.ai.core.ChatModelManager;
import com.kbook.service.ai.core.AiProviderConfigService;
import com.kbook.service.ai.core.AiSceneConfigService;
import com.kbook.service.ai.core.AiUsageContext;

/**
 * 圆桌派 RAG 检索服务 — 角色视角子查询生成 / 向量检索管线 / 内容向量就绪等待。
 */
@Slf4j
@Service
@LogModule("圆桌派")
public class RoundTableRagService {

    private final EmbeddingService embeddingService;
    private final BookParserService bookParserService;
    private final BookService bookService;
    private final RoundTableRoleService roleService;

    public RoundTableRagService(
            EmbeddingService embeddingService,
            BookParserService bookParserService,
            BookService bookService,
            RoundTableRoleService roleService) {
        this.embeddingService = embeddingService;
        this.bookParserService = bookParserService;
        this.bookService = bookService;
        this.roleService = roleService;
    }

    /**
     * 角色视角 RAG 检索 — 每个角色根据自己的专业角度搜索书中相关内容
     * <p>
     * 不同角色关注书中不同方面：
     * - 哲学家关注"意义、本质、伦理"相关内容
     * - 心理学家关注"心理、情感、动机"相关内容
     * - 社会学家关注"社会、权力、阶级"相关内容
     * - 等等
     * <p>
     * 同时结合当前讨论的最新话题，让检索更精准
     */
    String retrieveRagContextForRole(Book book, AiConfig.RoundTableRole role, List<RoundTableMessage> history) {
        if (!embeddingService.isAvailable()) {
            log.debug("Embedding 不可用，跳过角色视角 RAG 检索");
            return "";
        }

        try {
            if (!waitForContentEmbedding(book.getId())) {
                log.debug("内容向量不可用，跳过角色视角 RAG 检索: bookId={}", book.getId());
                return "";
            }

            AiConfig.RagStrategy strategy = role.getRagStrategy();
            int topN = (strategy != null && strategy.getTopN() > 0) ? strategy.getTopN() : 10;
            int neighborPrev = (strategy != null) ? strategy.getNeighborPrev() : 0;
            int neighborNext = (strategy != null) ? strategy.getNeighborNext() : 0;
            int subQueryCount = (strategy != null && strategy.getSubQueryCount() > 0) ? strategy.getSubQueryCount() : 1;
            int maxChars = (strategy != null && strategy.getMaxChars() > 0) ? strategy.getMaxChars() : 8000;
            List<String> focusKeywords = (strategy != null) ? strategy.getFocusKeywords() : null;
            String perspectiveHint = (strategy != null) ? strategy.getPerspectiveHint() : null;

            // 生成子查询
            List<String> subQueries = buildRoleSubQueries(book, role, history, subQueryCount, perspectiveHint);
            if (subQueries.isEmpty()) {
                log.debug("角色视角 RAG 查询生成失败: bookId={}, role={}", book.getId(), role.getKey());
                return "";
            }
            log.debug("角色视角 RAG 查询: role={}, subQueries={}", role.getKey(), subQueries);

            // 1. 多子查询向量检索
            List<EmbeddingMatch<TextSegment>> allMatches = new ArrayList<>();
            for (String subQuery : subQueries) {
                try {
                    List<EmbeddingMatch<TextSegment>> matches =
                            embeddingService.searchContent(subQuery, topN, book);
                    allMatches.addAll(matches);
                } catch (Exception e) {
                    log.debug("子查询检索失败: role={}, subQuery={} - {}", role.getKey(), subQuery, e.getMessage());
                }
            }
            if (allMatches.isEmpty()) return "";

            // 2. score ≥ 0.1 过滤（保留原有风控）
            allMatches = allMatches.stream()
                    .filter(m -> m.score() >= 0.1)
                    .toList();
            if (allMatches.isEmpty()) return "";

            // 3. 按 chunkIndex 去重（保留 score 更高的）
            List<EmbeddingMatch<TextSegment>> deduped =
                    RagPipelineComponents.dedupByChunkIndex(allMatches);

            // 4. focusKeywords 加权（在 topN 截断前应用，让视角偏好影响排序）
            deduped = RagPipelineComponents.applyFocusKeywordsBoost(deduped, focusKeywords);

            // 5. 按 score 降序 + topN 截断（全局 topN，而非每子查询）
            deduped.sort((a, b) -> Double.compare(b.score(), a.score()));
            List<EmbeddingMatch<TextSegment>> topNMatches = deduped.stream()
                    .limit(topN)
                    .collect(Collectors.toCollection(ArrayList::new));

            // 6. 自适应邻域扩展
            List<EmbeddingMatch<TextSegment>> expanded = RagPipelineComponents.adaptiveNeighborExpand(
                    topNMatches, book.getId(), neighborPrev, neighborNext,
                    (bookId, idx) -> embeddingService.searchContentByChunkIndex(bookId, idx));

            // 7. 合并相邻 + 按 bestScore 重排
            List<EmbeddingMatch<TextSegment>> merged = RagPipelineComponents.mergeAdjacentChunks(expanded);
            merged.sort((a, b) -> Double.compare(b.score(), a.score()));

            // 8. maxChars 截断填充
            String ragContext = RagPipelineComponents.truncateToChars(merged, maxChars);

            log.info("圆桌派RAG role={} | 子查询={}, 原始{}条 → 去重{}条 → topN={} → 邻域扩展={} → 合并{}条 → 最终{}字",
                    role.getKey(), subQueries.size(), allMatches.size(),
                    deduped.size(), topNMatches.size(), expanded.size(),
                    merged.size(), ragContext.length());

            return ragContext;
        } catch (Exception e) {
            log.warn("角色视角 RAG 检索异常: bookId={}, role={} - {}", book.getId(), role.getKey(), e.getMessage());
            return "";
        }
    }

    /**
     * 生成角色专属的检索子查询列表
     * <p>
     * - subQueryCount = 1 时走单查询生成（向后兼容）
     * - subQueryCount > 1 时走多子查询生成（视角覆盖更广）
     * - LLM 失败时回退到关键词拼接
     */
    private List<String> buildRoleSubQueries(Book book, AiConfig.RoundTableRole role,
                                              List<RoundTableMessage> history,
                                              int subQueryCount, String perspectiveHint) {
        if ("HOST".equals(role.getKey())) {
            // 主持人走原有逻辑（关键词拼接，不需要 LLM）
            return List.of(buildHostSearchQuery(book, history));
        }

        // 构建最近两轮发言摘要
        String recentDiscussion = "";
        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - 2);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < history.size(); i++) {
                RoundTableMessage msg = history.get(i);
                String name = msg.getRoleName() != null ? msg.getRoleName() : msg.getRoleKey();
                String content = msg.getCompressedContent();
                sb.append(name).append("：").append(content).append("\n");
            }
            recentDiscussion = sb.toString();
        }

        String roleKeywords = getRoleSearchKeywords(role);

        // subQueryCount == 1 时走原单查询逻辑（向后兼容）
        if (subQueryCount <= 1) {
            String query = roleService.callAiForRoleSearchQuery(
                    role.getKey(), book.getTitle(),
                    role.getName(), role.getTitle(), roleKeywords,
                    recentDiscussion.isBlank() ? "（讨论尚未开始）" : recentDiscussion);
            if (query != null && !query.isBlank()) {
                query = query.trim()
                        .replaceAll("^(查询|检索|搜索|关键词)[：:]", "")
                        .trim();
                if (!query.isBlank()) return List.of(query);
            }
            // LLM 失败回退
            return List.of(book.getTitle() + " " + roleKeywords);
        }

        // subQueryCount > 1 时走多子查询生成
        try {
            List<String> queries = roleService.callAiForRoleSearchQueries(
                    role.getKey(), book.getTitle(),
                    role.getName(), role.getTitle(), roleKeywords,
                    recentDiscussion, subQueryCount, perspectiveHint);
            if (!queries.isEmpty()) return queries;
        } catch (Exception e) {
            log.warn("多子查询生成失败，回退到单查询: role={} - {}", role.getKey(), e.getMessage());
        }

        // 多子查询失败回退到单查询
        String query = roleService.callAiForRoleSearchQuery(
                role.getKey(), book.getTitle(),
                role.getName(), role.getTitle(), roleKeywords,
                recentDiscussion.isBlank() ? "（讨论尚未开始）" : recentDiscussion);
        if (query != null && !query.isBlank()) {
            return List.of(query.trim()
                    .replaceAll("^(查询|检索|搜索|关键词)[：:]", "")
                    .trim());
        }

        // 最终回退：书名 + 角色关键词
        return List.of(book.getTitle() + " " + roleKeywords);
    }

    /**
     * HOST 专用检索查询生成——简化版，直接用书名 + 核心概念 + 读者关注构建查询。
     */
    private String buildHostSearchQuery(Book book, List<RoundTableMessage> history) {
        StringBuilder sb = new StringBuilder(book.getTitle());

        String concepts = book.getConceptTags();
        if (concepts != null && !concepts.isBlank()) {
            String cleaned = concepts.replaceAll("[\\[\\]\"]", "").trim();
            if (!cleaned.isEmpty()) {
                List<String> tags = Arrays.stream(cleaned.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .limit(5)
                        .toList();
                if (!tags.isEmpty()) {
                    sb.append(" ").append(String.join(" ", tags));
                }
            }
        }

        String readerNeeds = book.getReaderNeedTags();
        if (readerNeeds != null && !readerNeeds.isBlank()) {
            String cleaned = readerNeeds.replaceAll("[\\[\\]\"]", "").trim();
            if (!cleaned.isEmpty()) {
                List<String> tags = Arrays.stream(cleaned.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .limit(3)
                        .toList();
                if (!tags.isEmpty()) {
                    sb.append(" ").append(String.join(" ", tags));
                }
            }
        }

        sb.append(" 核心观点 主要内容");
        return sb.toString();
    }

    /**
     * 获取角色的专业检索关键词
     * 不同角色在搜索书中内容时，关注的关键词不同
     */
    private String getRoleSearchKeywords(AiConfig.RoundTableRole role) {
        if (role.getSearchKeywords() != null && !role.getSearchKeywords().isEmpty()) {
            return String.join(" ", role.getSearchKeywords());
        }
        return role.getName();
    }

    /**
     * 等待图书内容向量就绪
     */
    boolean waitForContentEmbedding(Long bookId) {
        Book book = bookService.getBookById(bookId);
        if (book != null && Boolean.TRUE.equals(book.getContentEmbedded())) {
            return true;
        }

        int maxRetries = 30;
        for (int i = 0; i < maxRetries; i++) {
            Boolean result = bookParserService.ensureContentEmbedded(bookId);
            if (result != null) {
                return result;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
