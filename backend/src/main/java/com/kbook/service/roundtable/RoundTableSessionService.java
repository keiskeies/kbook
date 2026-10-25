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
 * 圆桌派会话服务 — 会话 CRUD / 归属校验 / 全局 Feed 与热度分。
 */
@Slf4j
@Service
@LogModule("圆桌派")
public class RoundTableSessionService {

    private final BookService bookService;
    private final AiConfigProvider aiConfigProvider;
    private final RoundTableSessionRepository sessionRepository;
    private final RoundTableMessageRepository messageRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ReadingProgressService readingProgressService;
    private final BookRepository bookRepository;
    private final RoundTableCoverageRepository roundTableCoverageRepository;
    private final ObjectMapper objectMapper;
    private final RoundTableRoleService roleService;

    public RoundTableSessionService(
            BookService bookService,
            AiConfigProvider aiConfigProvider,
            RoundTableSessionRepository sessionRepository,
            RoundTableMessageRepository messageRepository,
            StringRedisTemplate stringRedisTemplate,
            ReadingProgressService readingProgressService,
            @Lazy BookRepository bookRepository,
            @Lazy RoundTableCoverageRepository roundTableCoverageRepository,
            ObjectMapper objectMapper,
            RoundTableRoleService roleService) {
        this.bookService = bookService;
        this.aiConfigProvider = aiConfigProvider;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.stringRedisTemplate = stringRedisTemplate;
        this.readingProgressService = readingProgressService;
        this.bookRepository = bookRepository;
        this.roundTableCoverageRepository = roundTableCoverageRepository;
        this.objectMapper = objectMapper;
        this.roleService = roleService;
    }

    // ==================== 会话管理 ====================

    /**
     * 创建圆桌派会话
     *
     * @param userId      用户ID
     * @param bookId      书籍ID
     * @param roleKeys    参与角色键名列表
     * @param roleConfigs 角色配置 JSON（包含 LLM 赋值的 domainRelevance）
     * @return 创建的会话实体
     */
    @LogAction("创建圆桌派会话")
    public RoundTableSession createSession(Long userId, Long bookId, List<String> roleKeys, String roleConfigs) {
        // 确保 HOST 在角色列表中
        if (!roleKeys.contains("HOST")) {
            roleKeys = new ArrayList<>(roleKeys);
            roleKeys.add(0, "HOST");
        }

        // 确保至少 4 个角色（HOST + 3）
        if (roleKeys.size() < 4) {
            List<String> defaults = List.of("PHILOSOPHER", "PSYCHOLOGIST", "SOCIOLOGIST", "EDUCATOR", "COMEDIAN");
            for (String def : defaults) {
                if (roleKeys.size() >= 4) break;
                if (!roleKeys.contains(def)) {
                    roleKeys = new ArrayList<>(roleKeys);
                    roleKeys.add(def);
                }
            }
        }

        Book book = bookService.getBookById(bookId);
        String title = book != null ? "《" + book.getTitle() + "》圆桌派讨论" : "圆桌派讨论";

        // 规范化 roleConfigs：按 roleKeys 补齐富字段（前端传入 → LLM 名册 → 配置默认值），
        // 保证 domainRelevance/languageStyle 能持久化，并被发言人选择与角色发言复用
        String normalizedRoleConfigs = normalizeRoleConfigs(bookId, roleKeys, roleConfigs);

        String sessionId = "rt-" + bookId + "-" + UUID.randomUUID().toString().substring(0, 8);

        RoundTableSession session = RoundTableSession.builder()
                .userId(userId)
                .bookId(bookId)
                .sessionId(sessionId)
                .title(title)
                .roleKeys(String.join(",", roleKeys))
                .roleConfigs(normalizedRoleConfigs)
                .status("ACTIVE")
                .visibility("PUBLIC")
                .build();

        RoundTableSession saved = sessionRepository.save(session);

        // 递增 ZSet 分数：用户选择了哪些角色，对应角色的使用次数 +1
        String scoreKey = RoundTableRoleService.ROLE_SCORES_KEY_PREFIX + bookId;
        for (String roleKey : roleKeys) {
            if (roleKey != null && !roleKey.isBlank()) {
                stringRedisTemplate.opsForZSet().incrementScore(scoreKey, roleKey.trim(), 1);
            }
        }

        // 将该图书加入阅读历史（标记为讨论行为）
        readingProgressService.reportProgress(userId, bookId, 0.0, "chat");

        return saved;
    }

    /**
     * 规范化会话的角色配置 JSON。
     * <p>
     * 逐 roleKey 组装 {key,name,title,domainRelevance,languageStyle}，取值优先级：
     * ① 前端传入的 roleConfigs（选中角色携带 LLM 赋值）
     * ② 该书的角色名册缓存（后端自动补的角色从上次 LLM 推荐复用）
     * ③ AiConfig 配置默认值（name/title）与富字段默认值（0/空）
     *
     * @param bookId      书籍ID
     * @param roleKeys    最终参与角色 key 列表（含 HOST）
     * @param roleConfigs 前端传入的角色配置 JSON（可能只含部分 key，可为 null）
     * @return 规范化后的 roleConfigs JSON
     */
    private String normalizeRoleConfigs(Long bookId, List<String> roleKeys, String roleConfigs) {
        Map<String, JsonNode> incoming = parseRoleConfigsJson(roleConfigs);
        Map<String, JsonNode> roster = roleService.readRoleRoster(bookId);

        List<Map<String, Object>> list = new ArrayList<>();
        for (String rawKey : roleKeys) {
            if (rawKey == null || rawKey.isBlank()) continue;
            String key = rawKey.trim();

            JsonNode src = incoming.containsKey(key) ? incoming.get(key) : roster.get(key);
            AiConfig.RoundTableRole role = aiConfigProvider.getRoundTableRole(key);

            String name = src != null && src.hasNonNull("name") && !src.get("name").asText().isBlank()
                    ? src.get("name").asText()
                    : defaultRoleName(key, role);
            String title = src != null && src.hasNonNull("title") && !src.get("title").asText().isBlank()
                    ? src.get("title").asText()
                    : (role != null ? role.getTitle() : "");
            int domainRelevance = src != null && src.has("domainRelevance")
                    ? src.get("domainRelevance").asInt() : 0;
            String languageStyle = src != null && src.hasNonNull("languageStyle")
                    ? src.get("languageStyle").asText() : "";

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", key);
            entry.put("name", name);
            entry.put("title", title);
            entry.put("domainRelevance", domainRelevance);
            entry.put("languageStyle", languageStyle);
            list.add(entry);
        }

        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("规范化 roleConfigs 失败，沿用原始值: {}", e.getMessage());
            return roleConfigs;
        }
    }

    /**
     * 角色名兜底：优先 AiConfig 角色配置名，HOST 用主持人配置名，最后回退 key 本身。
     */
    private String defaultRoleName(String key, AiConfig.RoundTableRole role) {
        if (role != null && role.getName() != null && !role.getName().isBlank()) {
            return role.getName();
        }
        if ("HOST".equals(key)) {
            AiConfig.RoundTableHost host = aiConfigProvider.getRoundTableHost();
            if (host != null && host.getName() != null && !host.getName().isBlank()) {
                return host.getName();
            }
        }
        return key;
    }

    /**
     * 解析 roleConfigs JSON 数组为 key → 配置节点 映射，非法输入返回空表。
     */
    private Map<String, JsonNode> parseRoleConfigsJson(String json) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return map;
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    if (node.hasNonNull("key") && !node.get("key").asText().isBlank()) {
                        map.put(node.get("key").asText().trim(), node);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析 roleConfigs 失败: {}", e.getMessage());
        }
        return map;
    }

    /**
     * 获取用户对指定书籍的圆桌派会话列表
     *
     * @param userId 用户ID
     * @param bookId 书籍ID
     * @return 会话列表
     */
    @LogAction("获取圆桌派会话列表")
    public List<RoundTableSession> getSessions(Long userId, Long bookId) {
        return sessionRepository.query()
                .where(RoundTableSession::getUserId, eq(userId))
                .and(RoundTableSession::getBookId, eq(bookId))
                .orderByDesc(RoundTableSession::getUpdatedAt)
                .list();
    }

    /**
     * 获取圆桌派会话
     *
     * @param sessionId 会话ID
     * @return 会话实体
     */
    @LogAction("获取圆桌派会话")
    public RoundTableSession getSession(String sessionId) {
        return sessionRepository.query()
                .where(RoundTableSession::getSessionId, eq(sessionId))
                .list(1)
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException("会话不存在"));
    }

    /**
     * 验证会话归属当前用户，不属于则抛出 BusinessException
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     */
    public void verifySessionOwnership(Long userId, String sessionId) {
        RoundTableSession session = getSession(sessionId);
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该会话");
        }
    }

    /**
     * 获取圆桌派会话历史消息
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return 消息列表
     */
    @LogAction("获取圆桌派历史")
    public List<RoundTableMessage> getHistory(Long userId, String sessionId) {
        return messageRepository.query()
                .where(RoundTableMessage::getSessionId, eq(sessionId))
                .list();
    }

    /**
     * 更新圆桌派会话状态
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param status    新状态（ACTIVE / COMPLETED / ABANDONED）
     */
    @LogAction("更新圆桌派会话状态")
    public void updateSessionStatus(Long userId, String sessionId, String status) {
        RoundTableSession session = getSession(sessionId);
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该会话");
        }
        session.setStatus(status);
        sessionRepository.save(session);
    }

    /**
     * 删除圆桌派会话及其消息
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     */
    @LogAction("删除圆桌派会话")
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(Long userId, String sessionId) {
        // 批量删除该会话的所有消息（避免全量加载后逐条删除）
        messageRepository.delete()
                .where(RoundTableMessage::getUserId, eq(userId))
                .and(RoundTableMessage::getSessionId, eq(sessionId))
                .execute();

        // 删除会话
        sessionRepository.query()
                .where(RoundTableSession::getSessionId, eq(sessionId))
                .list(1)
                .stream().findFirst()
                .filter(s -> s.getUserId().equals(userId))
                .ifPresent(sessionRepository::delete);
    }

    /**
     * 获取全局圆桌派会话列表（发现页）
     */
    public Page<RoundTableSessionFeedVO> getGlobalSessions(int page, int size, String sort, boolean mine) {
        // 安全措施：sort 参数白名单校验，仅接受 "hot" 或 "recent"（默认）
        String safeSort = "hot".equals(sort) ? "hot" : "recent";
        var pageable = PageRequest.of(page, size,
                Sort.by("hot".equals(safeSort)
                        ? Sort.Order.desc("updatedAt")
                        : Sort.Order.desc("createdAt")));

        // 获取当前用户ID（未登录则只能看到公开会话）
        Long currentUserId = getCurrentUserId();

        // 查询公开会话或当前用户的会话；如果 mine=true 则只查当前用户
        var sessions = mine
                ? sessionRepository.findByUserId(currentUserId, pageable)
                : sessionRepository.findPublicOrOwnSessions(pageable);

        // Get book info
        var bookIds = sessions.getContent().stream().map(RoundTableSession::getBookId).distinct().toList();
        var books = bookRepository.findListByIds(bookIds);
        var bookMap = books.stream().collect(Collectors.toMap(Book::getId, b -> b));

        // Get coverage scores
        var sessionIds = sessions.getContent().stream().map(RoundTableSession::getSessionId).toList();
        var coverageMap = new java.util.HashMap<String, Double>();
        if (!sessionIds.isEmpty()) {
            var coverages = roundTableCoverageRepository.query()
                    .where(RoundTableCoverage::getSessionId, in(sessionIds))
                    .list();
            coverageMap.putAll(coverages.stream()
                    .collect(Collectors.toMap(
                            RoundTableCoverage::getSessionId,
                            c -> c.getOverallScore() != null ? c.getOverallScore() : 0.0,
                            (a, b) -> a)));
        }

        // 计算热度分数
        var now = LocalDateTime.now();

        return sessions.map(session -> {
            var book = bookMap.get(session.getBookId());
            var coverageScore = coverageMap.getOrDefault(session.getSessionId(), 0.0);

            // 计算热度分数
            double hotScore = calculateHotScore(coverageScore, session.getStatus(), session.getCreatedAt(), now);

            return RoundTableSessionFeedVO.builder()
                    .id(session.getId())
                    .sessionId(session.getSessionId())
                    .bookId(session.getBookId())
                    .bookTitle(book != null ? book.getTitle() : "未知书籍")
                    .bookCoverUrl(book != null ? book.getCoverUrl() : null)
                    .title(session.getTitle())
                    .roleKeys(session.getRoleKeys())
                    .status(session.getStatus())
                    .visibility(session.getVisibility())
                    .isOwner(currentUserId != null && currentUserId.equals(session.getUserId()))
                    .coverageScore(coverageScore)
                    .hotScore(hotScore)
                    .createdAt(session.getCreatedAt())
                    .updatedAt(session.getUpdatedAt())
                    .build();
        });
    }

    /**
     * 计算热度分数
     *
     * @param score     评分（辩论用avgScore，圆桌用coverageScore）
     * @param status    状态
     * @param createdAt 创建时间
     * @param now       当前时间
     * @return 热度分数
     */
    private double calculateHotScore(double score, String status, LocalDateTime createdAt, LocalDateTime now) {
        // 基础分：评分 * 100（归一化到0-100分）
        double baseScore = score * 100;

        // 完成加分：已完成+50分，进行中+10分
        int completionBonus = "COMPLETED".equals(status) ? 50 : ("ACTIVE".equals(status) ? 10 : 0);

        // 时间衰减：每天减2分，防止旧内容霸榜
        long daysSinceCreated = java.time.Duration.between(createdAt, now).toDays();
        double timeDecay = daysSinceCreated * 2.0;

        return baseScore + completionBonus - timeDecay;
    }

    /**
     * 从 Spring Security 上下文获取当前用户ID
     * 未登录返回 null（只能看到公开会话）
     */
    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Long userId) {
            return userId;
        }
        return null;
    }
}
