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
 * 辩论门面 — 公开 API 原签名全量委托（Controller 零改动）。
 * M6 拆分：辩题/会话/发言/轮次由四个子服务承接。
 */
@Slf4j
@Service
public class DebateService {

    private final DebateTopicService topicService;
    private final DebateSessionService sessionService;
    private final DebateSpeechService speechService;
    private final DebateRoundService roundService;

    public DebateService(DebateTopicService topicService, DebateSessionService sessionService,
                         DebateSpeechService speechService, DebateRoundService roundService) {
        this.topicService = topicService;
        this.sessionService = sessionService;
        this.speechService = speechService;
        this.roundService = roundService;
    }

    // ===== 辩题 =====
    public List<DebateTopicVO> generateTopics(Long bookId, boolean forceRefresh) { return topicService.generateTopics(bookId, forceRefresh); }
    public DebateTopicVO optimizeTopic(Long bookId, String topic, String proArg, String conArg) { return topicService.optimizeTopic(bookId, topic, proArg, conArg); }

    // ===== 会话 =====
    public DebateSessionVO createSession(Long userId, Long bookId, String topic, String topicSource, String bookContext, String proRoleKeys, String conRoleKeys) { return sessionService.createSession(userId, bookId, topic, topicSource, bookContext, proRoleKeys, conRoleKeys); }
    public List<DebateSessionVO> getSessions(Long userId, Long bookId) { return sessionService.getSessions(userId, bookId); }
    public DebateSessionVO getSession(String sessionId) { return sessionService.getSession(sessionId); }
    public List<DebateMessageVO> getHistory(Long userId, String sessionId) { return sessionService.getHistory(userId, sessionId); }
    public void deleteSession(Long userId, String sessionId) { sessionService.deleteSession(userId, sessionId); }
    public List<DebateRoleVO> getRoles() { return sessionService.getRoles(); }
    public Page<DebateSessionFeedVO> getGlobalSessions(int page, int size, String sort, boolean mine) { return sessionService.getGlobalSessions(page, size, sort, mine); }

    // ===== 发言 =====
    public SseEmitter streamOpeningSpeech(Long userId, Long bookId, DebateSpeakRequest request) { return speechService.streamOpeningSpeech(userId, bookId, request); }
    public SseEmitter streamAttackSpeech(Long userId, Long bookId, DebateSpeakRequest request, String opponentSpeech) { return speechService.streamAttackSpeech(userId, bookId, request, opponentSpeech); }
    public SseEmitter streamCrossExamSpeech(Long userId, Long bookId, DebateSpeakRequest request, String defenderOpening, String questionContent) { return speechService.streamCrossExamSpeech(userId, bookId, request, defenderOpening, questionContent); }
    public SseEmitter streamRebuttalSpeech(Long userId, Long bookId, DebateSpeakRequest request, String opponentOpening, String crossExamContext) { return speechService.streamRebuttalSpeech(userId, bookId, request, opponentOpening, crossExamContext); }
    public SseEmitter streamFreeSpeech(Long userId, Long bookId, DebateSpeakRequest request, String lastSpeech) { return speechService.streamFreeSpeech(userId, bookId, request, lastSpeech); }
    public SseEmitter streamClosingSpeech(Long userId, Long bookId, DebateSpeakRequest request) { return speechService.streamClosingSpeech(userId, bookId, request); }
    public SseEmitter streamHostCommentary(Long userId, String sessionId, String type, String context) { return speechService.streamHostCommentary(userId, sessionId, type, context); }

    // ===== 轮次 =====
    public String getNextSpeakerFree(Long userId, String sessionId) { return roundService.getNextSpeakerFree(userId, sessionId); }
    public DebateSessionVO advanceRound(Long userId, String sessionId) { return roundService.advanceRound(userId, sessionId); }
}
