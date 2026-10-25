package com.kbook.service.roundtable;

import com.kbook.common.api.Result;
import com.kbook.config.annotation.LogModule;
import com.kbook.dto.roundtable.NextSpeakerResult;
import com.kbook.dto.roundtable.RoleVO;
import com.kbook.dto.roundtable.RoundTableSessionFeedVO;
import com.kbook.dto.roundtable.SpeakRequest;
import com.kbook.entity.RoundTableMessage;
import com.kbook.entity.RoundTableSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 圆桌派门面 — 对 Controller 暴露原有全部公开签名，内部按职责委托：
 * <ul>
 *   <li>{@link RoundTableRoleService} 角色推荐/名册/检索查询生成</li>
 *   <li>{@link RoundTableSessionService} 会话 CRUD / 全局 Feed</li>
 *   <li>{@link RoundTableSpeechService} 单角色 SSE 发言 / 历史压缩</li>
 *   <li>{@link RoundTableSpeakerService} 下一发言人选择（LLM + 机制兜底）</li>
 *   <li>{@link RoundTableRagService} 角色视角 RAG 检索</li>
 * </ul>
 */
@Slf4j
@Service
@LogModule("圆桌派")
@RequiredArgsConstructor
public class RoundTableService {

    private final RoundTableRoleService roleService;
    private final RoundTableSessionService sessionService;
    private final RoundTableSpeechService speechService;
    private final RoundTableSpeakerService speakerService;

    // ==================== 角色推荐 ====================

    public List<RoleVO> getRecommendedRoles(Long bookId, boolean refresh) {
        return roleService.getRecommendedRoles(bookId, refresh);
    }

    public String callAiForRoleSelection(long bookId, String bookTitle, String bookInfo, List<String> excludeKeys) {
        return roleService.callAiForRoleSelection(bookId, bookTitle, bookInfo, excludeKeys);
    }

    public String callAiForRoleSelectionFallback(long bookId, String bookTitle, String bookInfo, String roleList) {
        return roleService.callAiForRoleSelectionFallback(bookId, bookTitle, bookInfo, roleList);
    }

    public String callAiForRoleSearchQuery(String roleKey, String bookTitle, String roleName,
                                           String roleTitle, String roleKeywords, String recentDiscussion) {
        return roleService.callAiForRoleSearchQuery(roleKey, bookTitle, roleName, roleTitle, roleKeywords, recentDiscussion);
    }

    public List<String> callAiForRoleSearchQueries(String roleKey, String bookTitle, String roleName,
                                                   String roleTitle, String roleKeywords,
                                                   String recentDiscussion, int subQueryCount,
                                                   String perspectiveHint) {
        return roleService.callAiForRoleSearchQueries(roleKey, bookTitle, roleName, roleTitle, roleKeywords,
                recentDiscussion, subQueryCount, perspectiveHint);
    }

    // ==================== 会话管理 ====================

    public RoundTableSession createSession(Long userId, Long bookId, List<String> roleKeys, String roleConfigs) {
        return sessionService.createSession(userId, bookId, roleKeys, roleConfigs);
    }

    public List<RoundTableSession> getSessions(Long userId, Long bookId) {
        return sessionService.getSessions(userId, bookId);
    }

    public RoundTableSession getSession(String sessionId) {
        return sessionService.getSession(sessionId);
    }

    public void verifySessionOwnership(Long userId, String sessionId) {
        sessionService.verifySessionOwnership(userId, sessionId);
    }

    public List<RoundTableMessage> getHistory(Long userId, String sessionId) {
        return sessionService.getHistory(userId, sessionId);
    }

    public void updateSessionStatus(Long userId, String sessionId, String status) {
        sessionService.updateSessionStatus(userId, sessionId, status);
    }

    public void deleteSession(Long userId, String sessionId) {
        sessionService.deleteSession(userId, sessionId);
    }

    public Page<RoundTableSessionFeedVO> getGlobalSessions(int page, int size, String sort, boolean mine) {
        return sessionService.getGlobalSessions(page, size, sort, mine);
    }

    // ==================== 发言与选人 ====================

    public SseEmitter streamCharacterSpeak(Long userId, Long bookId, SpeakRequest request) {
        return speechService.streamCharacterSpeak(userId, bookId, request);
    }

    public NextSpeakerResult getNextSpeakerOnlyLLM(Long userId, String sessionId) {
        return speakerService.getNextSpeakerOnlyLLM(userId, sessionId);
    }
}
