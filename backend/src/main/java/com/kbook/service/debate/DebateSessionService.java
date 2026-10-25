package com.kbook.service.debate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.exception.BusinessException;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.config.ai.AiConfig;
import com.kbook.config.ai.AiConfigProvider;
import com.kbook.dto.debate.*;
import com.kbook.entity.AiScene;
import com.kbook.entity.Book;
import com.kbook.entity.debate.DebateMessage;
import com.kbook.entity.debate.DebateScore;
import com.kbook.entity.debate.DebateSession;
import com.kbook.common.enums.DebateRole;
import com.kbook.repository.BookRepository;
import com.kbook.repository.debate.DebateMessageRepository;
import com.kbook.repository.debate.DebateScoreRepository;
import com.kbook.repository.debate.DebateSessionRepository;
import com.kbook.service.ai.core.ExternalKnowledgeGenerator;
import com.kbook.service.ai.streaming.StreamingSseHandler;
import com.kbook.service.book.BookService;
import com.kbook.service.progress.ReadingProgressService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.*;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.time.LocalDateTime;
import static com.kbook.common.util.QueryBuilder.eq;
import static com.kbook.common.util.QueryBuilder.ne;
import com.kbook.service.ai.core.ChatModelManager;
import com.kbook.service.ai.core.AiUsageContext;
import com.kbook.constants.ai.DebatePrompts;

/**
 * 辩论会话服务 — 会话 CRUD / 全局 Feed / 立场与位置键工具。
 */
@Slf4j
@Service
public class DebateSessionService {

    private static final String DEBATE_SESSION_KEY_PREFIX = "kbook:debate:session:";

    private final DebateSessionRepository sessionRepository;
    private final DebateMessageRepository messageRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ReadingProgressService readingProgressService;
    private final @Lazy BookRepository bookRepository;
    private final @Lazy DebateScoreRepository debateScoreRepository;

    public DebateSessionService(
            DebateSessionRepository sessionRepository,
            DebateMessageRepository messageRepository,
            StringRedisTemplate stringRedisTemplate,
            ReadingProgressService readingProgressService,
            @Lazy BookRepository bookRepository,
            @Lazy DebateScoreRepository debateScoreRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.stringRedisTemplate = stringRedisTemplate;
        this.readingProgressService = readingProgressService;
        this.bookRepository = bookRepository;
        this.debateScoreRepository = debateScoreRepository;
    }

    public DebateSessionVO createSession(Long userId, Long bookId, String topic,
                                          String topicSource, String bookContext,
                                          String proRoleKeys, String conRoleKeys) {
        String sessionId = "db-" + UUID.randomUUID().toString().replace("-", "");

        if (proRoleKeys == null || proRoleKeys.isBlank()) {
            proRoleKeys = "LOGICAL,SHARP,HUMOROUS,EMPATHETIC";
        }
        if (conRoleKeys == null || conRoleKeys.isBlank()) {
            conRoleKeys = "SENSITIVE,DOMINEERING,ERUDITE,PRACTICAL";
        }

        DebateSession session = DebateSession.builder()
                .userId(userId)
                .bookId(bookId)
                .sessionId(sessionId)
                .topic(topic)
                .topicSource(topicSource != null ? topicSource : "LLM")
                .bookContext(bookContext)
                .proRoleKeys(proRoleKeys)
                .conRoleKeys(conRoleKeys)
                .currentRound(1)
                .currentPhase("OPENING")
                .status("ACTIVE")
                .visibility("PUBLIC")
                .build();

        sessionRepository.save(session);

        stringRedisTemplate.opsForValue().set(
                DEBATE_SESSION_KEY_PREFIX + sessionId, topic);

        // 将该图书加入阅读历史（标记为讨论行为）
        readingProgressService.reportProgress(userId, bookId, 0.0, "chat");

        log.info("辩论会话已创建: sessionId={}, bookId={}, topic={}", sessionId, bookId, topic);
        return DebateSessionVO.from(session);
    }

    public List<DebateSessionVO> getSessions(Long userId, Long bookId) {
        List<DebateSession> sessions = sessionRepository.query()
                .where(DebateSession::getUserId, eq(userId))
                .and(DebateSession::getBookId, eq(bookId))
                .and(DebateSession::getStatus, ne("ABANDONED"))
                .orderByDesc(DebateSession::getCreatedAt)
                .list();
        return sessions.stream()
                .map(DebateSessionVO::from)
                .collect(Collectors.toList());
    }

    public DebateSessionVO getSession(String sessionId) {
        DebateSession session = getSessionBySessionId(sessionId);
        if (session == null) {
            throw new BusinessException("会话不存在");
        }
        return DebateSessionVO.from(session);
    }

    public List<DebateMessageVO> getHistory(Long userId, String sessionId) {
        List<DebateMessage> messages = messageRepository.query()
                .where(DebateMessage::getSessionId, eq(sessionId))
                .orderBy(DebateMessage::getId)
                .list();
        return messages.stream()
                .map(DebateMessageVO::from)
                .collect(Collectors.toList());
    }

    public void deleteSession(Long userId, String sessionId) {
        DebateSession session = sessionRepository.query()
                .where(DebateSession::getSessionId, eq(sessionId))
                .and(DebateSession::getUserId, eq(userId))
                .list(1).stream().findFirst().orElse(null);
        if (session == null) {
            throw new BusinessException("辩论会话不存在");
        }
        session.setStatus("ABANDONED");
        sessionRepository.save(session);
        stringRedisTemplate.delete(DEBATE_SESSION_KEY_PREFIX + sessionId);
    }

    public List<DebateRoleVO> getRoles() {
        return Stream.of(DebateRole.values())
                .map(DebateRoleVO::from)
                .collect(Collectors.toList());
    }

    /**
     * 获取全局辩论会话列表（发现页）
     * 只返回公开会话或当前用户创建的会话
     */
    public Page<DebateSessionFeedVO> getGlobalSessions(int page, int size, String sort, boolean mine) {
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
        var bookIds = sessions.getContent().stream().map(DebateSession::getBookId).distinct().toList();
        var books = bookRepository.findListByIds(bookIds);
        var bookMap = books.stream().collect(Collectors.toMap(Book::getId, b -> b));

        // Get average scores per session
        var sessionIds = sessions.getContent().stream().map(DebateSession::getSessionId).toList();
        var scoreMap = new java.util.HashMap<String, Double>();
        if (!sessionIds.isEmpty()) {
            var scores = debateScoreRepository.findAllBySessionIdIn(sessionIds);
            scores.stream()
                    .collect(Collectors.groupingBy(
                            DebateScore::getSessionId,
                            Collectors.averagingDouble(s -> s.getAverageScore() != null ? s.getAverageScore() : 0)))
                    .forEach(scoreMap::put);
        }

        // 计算热度分数
        var now = LocalDateTime.now();

        return sessions.map(session -> {
            var book = bookMap.get(session.getBookId());
            var avgScore = scoreMap.getOrDefault(session.getSessionId(), 0.0);
            
            // 计算热度分数
            double hotScore = calculateHotScore(avgScore, session.getStatus(), session.getCreatedAt(), now);
            
            return DebateSessionFeedVO.builder()
                    .id(session.getId())
                    .sessionId(session.getSessionId())
                    .bookId(session.getBookId())
                    .bookTitle(book != null ? book.getTitle() : "未知书籍")
                    .bookCoverUrl(book != null ? book.getCoverUrl() : null)
                    .topic(session.getTopic())
                    .proRoleKeys(session.getProRoleKeys())
                    .conRoleKeys(session.getConRoleKeys())
                    .currentRound(session.getCurrentRound())
                    .currentPhase(session.getCurrentPhase())
                    .status(session.getStatus())
                    .visibility(session.getVisibility())
                    .isOwner(currentUserId != null && currentUserId.equals(session.getUserId()))
                    .avgScore(avgScore)
                    .hotScore(hotScore)
                    .createdAt(session.getCreatedAt())
                    .updatedAt(session.getUpdatedAt())
                    .build();
        });
    }

    /**
     * 计算热度分数
     * @param score 评分（辩论用avgScore，圆桌用coverageScore）
     * @param status 状态
     * @param createdAt 创建时间
     * @param now 当前时间
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

    /** 从位置键获取立场 */
    String getSideFromPositionKey(String positionKey) {
        if (positionKey == null || "HOST".equals(positionKey)) return "NEUTRAL";
        return positionKey.startsWith("PRO") ? "PRO" : "CON";
    }

    /** 从立场获取中文名 */
    String getSideName(String side) {
        return switch (side) {
            case "PRO" -> "正方";
            case "CON" -> "反方";
            default -> "";
        };
    }

    /** 从位置键获取完整立场显示名 */
    String getSideFull(String positionKey) {
        if (positionKey == null || "HOST".equals(positionKey)) return "主持人（中立）";
        return positionKey.startsWith("PRO") ? "正方" : "反方";
    }

    /** 从位置键获取位置标签（如 正方一辩、反方三辩） */
    String getPositionLabel(String positionKey) {
        if (positionKey == null || "HOST".equals(positionKey)) return "主持人";
        String sideName = positionKey.startsWith("PRO") ? "正方" : "反方";
        char numChar = positionKey.charAt(positionKey.length() - 1);
        int index = numChar - '1';
        String[] slotLabels = {"一辩", "二辩", "三辩", "四辩"};
        if (index >= 0 && index < 4) {
            return sideName + slotLabels[index];
        }
        return positionKey;
    }

    /** 从 Session 和位置键获取性格键 */
    String getPersonalityKey(DebateSession session, String positionKey) {
        if (positionKey == null || "HOST".equals(positionKey)) return "HOST";
        String[] keys;
        int index;
        if (positionKey.startsWith("PRO")) {
            keys = splitKeys(session.getProRoleKeys());
            index = Integer.parseInt(positionKey.substring(positionKey.length() - 1)) - 1;
        } else {
            keys = splitKeys(session.getConRoleKeys());
            index = Integer.parseInt(positionKey.substring(positionKey.length() - 1)) - 1;
        }
        if (index >= 0 && index < keys.length && !keys[index].isBlank()) {
            return keys[index].trim();
        }
        return "LOGICAL"; // fallback
    }

    String[] splitKeys(String keys) {
        if (keys == null || keys.isBlank()) return new String[0];
        return keys.split(",", -1);
    }

    DebateSession getSessionBySessionId(String sessionId) {
        return sessionRepository.query()
                .where(DebateSession::getSessionId, eq(sessionId))
                .list(1).stream().findFirst().orElse(null);
    }
}
