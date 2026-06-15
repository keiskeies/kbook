package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.debate.DebateMessageVO;
import com.kbook.dto.debate.DebateReportVO;
import com.kbook.dto.debate.DebateRoleVO;
import com.kbook.dto.debate.DebateSessionFeedVO;
import com.kbook.dto.debate.DebateSessionVO;
import com.kbook.dto.debate.DebateScoreVO;
import com.kbook.dto.debate.DebateSpeakRequest;
import com.kbook.dto.debate.DebateTopicVO;
import com.kbook.service.ai.DebateReportService;
import com.kbook.service.ai.DebateScoringService;
import com.kbook.service.ai.DebateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 奇葩说辩论控制器 — AI 辩论功能 REST + SSE 接口
 * <p>
 * 辩论流程（新国辩赛制 5 轮）：
 * 1. 角色列表：GET /api/debate/roles
 * 2. 话题生成：GET /api/debate/books/{bookId}/topics
 * 3. 创建会话：POST /api/debate/books/{bookId}/sessions
 * 4. 开篇立论：POST /api/debate/books/{bookId}/speak/opening (SSE)
 * 5. 交叉质询：POST /api/debate/books/{bookId}/speak/cross-exam (SSE)
 * 6. 驳论：POST /api/debate/books/{bookId}/speak/rebuttal (SSE)
 * 7. 自由辩论：POST /api/debate/books/{bookId}/speak/free (SSE)
 * 8. 总结陈词：POST /api/debate/books/{bookId}/speak/closing (SSE)
 * 9. 评分查询：GET /api/debate/sessions/{sessionId}/scores
 * 10. 报告生成：POST /api/debate/sessions/{sessionId}/report
 */
@Slf4j
@RestController
@RequestMapping("/api/debate")
@RequiredArgsConstructor
@Tag(name = "奇葩说")
public class DebateController extends BaseController {

    private final DebateService debateService;
    private final DebateScoringService scoringService;
    private final DebateReportService reportService;

    // ==================== 角色 ====================

    @Operation(summary = "获取辩论角色列表")
    @GetMapping("/roles")
    public Result<List<DebateRoleVO>> getRoles() {
        return Result.ok(debateService.getRoles());
    }

    // ==================== 辩题 ====================

    @Operation(summary = "获取LLM推荐辩题")
    @GetMapping("/books/{bookId}/topics")
    public Result<List<DebateTopicVO>> getTopics(@PathVariable Long bookId) {
        return Result.ok(debateService.generateTopics(bookId));
    }

    // ==================== 辩题优化 ====================

    @Operation(summary = "使用LLM优化用户自定义辩题")
    @PostMapping("/books/{bookId}/optimize-topic")
    public Result<DebateTopicVO> optimizeTopic(
            @PathVariable Long bookId,
            @RequestBody Map<String, String> body) {
        String userTopic = body.get("manualTopic");
        String userProArg = body.get("manualProArg");
        String userConArg = body.get("manualConArg");
        return Result.ok(debateService.optimizeTopic(bookId, userTopic, userProArg, userConArg));
    }

    // ==================== 会话 ====================

    @Operation(summary = "创建辩论会话")
    @PostMapping("/books/{bookId}/sessions")
    public Result<DebateSessionVO> createSession(
            @PathVariable Long bookId,
            @RequestBody Map<String, Object> body) {
        Long userId = extractUserId();
        String topic = (String) body.get("topic");
        String topicSource = (String) body.getOrDefault("topicSource", "USER");
        String bookContext = (String) body.get("bookContext");
        String proRoleKeys = (String) body.get("proRoleKeys");
        String conRoleKeys = (String) body.get("conRoleKeys");
        if (topic == null || topic.isBlank()) {
            return Result.fail("辩题不能为空");
        }
        return Result.ok(debateService.createSession(userId, bookId, topic, topicSource, bookContext, proRoleKeys, conRoleKeys));
    }

    @Operation(summary = "获取辩论会话列表")
    @GetMapping("/books/{bookId}/sessions")
    public Result<List<DebateSessionVO>> getSessions(@PathVariable Long bookId) {
        Long userId = extractUserId();
        return Result.ok(debateService.getSessions(userId, bookId));
    }

    @Operation(summary = "获取全局辩论列表（发现页）")
    @GetMapping("/sessions")
    public Result<Page<DebateSessionFeedVO>> getGlobalSessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "recent") String sort,
            @RequestParam(defaultValue = "false") boolean mine) {
        return Result.ok(debateService.getGlobalSessions(page, size, sort, mine));
    }

    @Operation(summary = "获取辩论历史消息")
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<DebateMessageVO>> getMessages(@PathVariable String sessionId) {
        Long userId = extractUserId();
        return Result.ok(debateService.getHistory(userId, sessionId));
    }

    @Operation(summary = "获取辩论会话详情")
    @GetMapping("/sessions/{sessionId}")
    public Result<DebateSessionVO> getSession(@PathVariable String sessionId) {
        return Result.ok(debateService.getSession(sessionId));
    }

    @Operation(summary = "删除辩论会话")
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        Long userId = extractUserId();
        debateService.deleteSession(userId, sessionId);
        return Result.ok(null);
    }

    // ==================== 发音（SSE） ====================

    @Operation(summary = "开篇立论发言（SSE）")
    @PostMapping(value = "/books/{bookId}/speak/opening", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter speakOpening(
            @PathVariable Long bookId,
            @Valid @RequestBody DebateSpeakRequest request) {
        Long userId = extractUserId();
        return debateService.streamOpeningSpeech(userId, bookId, request);
    }

    @Operation(summary = "奇袭攻辩发言（SSE）— 已废弃，使用交叉质询+驳论")
    @Deprecated
    @PostMapping(value = "/books/{bookId}/speak/attack", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter speakAttack(
            @PathVariable Long bookId,
            @RequestBody Map<String, Object> body) {
        Long userId = extractUserId();
        DebateSpeakRequest request = DebateSpeakRequest.builder()
                .roleKey((String) body.get("roleKey"))
                .sessionId((String) body.get("sessionId"))
                .roundType((String) body.getOrDefault("roundType", "ATTACK"))
                .roundNumber(body.get("roundNumber") instanceof Integer
                        ? (Integer) body.get("roundNumber") : 2)
                .build();
        String opponentSpeech = (String) body.get("opponentSpeech");
        return debateService.streamAttackSpeech(userId, bookId, request, opponentSpeech);
    }

    @Operation(summary = "交叉质询发言（SSE）— 二辩质询对方一辩")
    @PostMapping(value = "/books/{bookId}/speak/cross-exam", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter speakCrossExam(
            @PathVariable Long bookId,
            @RequestBody Map<String, Object> body) {
        Long userId = extractUserId();

        DebateSpeakRequest request = DebateSpeakRequest.builder()
                .roleKey((String) body.get("roleKey"))
                .sessionId((String) body.get("sessionId"))
                .roundType("CROSS_EXAM")
                .roundNumber(body.get("roundNumber") instanceof Integer
                        ? (Integer) body.get("roundNumber") : 2)
                .examRole((String) body.get("examRole"))
                .build();

        String defenderOpening = (String) body.get("defenderOpening");
        String questionContent = (String) body.get("questionContent");
        return debateService.streamCrossExamSpeech(userId, bookId, request, defenderOpening, questionContent);
    }

    @Operation(summary = "驳论发言（SSE）— 二辩集中反驳")
    @PostMapping(value = "/books/{bookId}/speak/rebuttal", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter speakRebuttal(
            @PathVariable Long bookId,
            @RequestBody Map<String, Object> body) {
        Long userId = extractUserId();

        DebateSpeakRequest request = DebateSpeakRequest.builder()
                .roleKey((String) body.get("roleKey"))
                .sessionId((String) body.get("sessionId"))
                .roundType("REBUTTAL")
                .roundNumber(body.get("roundNumber") instanceof Integer
                        ? (Integer) body.get("roundNumber") : 3)
                .build();

        String opponentOpening = (String) body.get("opponentOpening");
        String crossExamContext = (String) body.get("crossExamContext");
        return debateService.streamRebuttalSpeech(userId, bookId, request, opponentOpening, crossExamContext);
    }

    @Operation(summary = "自由辩论发言（SSE）")
    @PostMapping(value = "/books/{bookId}/speak/free", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter speakFree(
            @PathVariable Long bookId,
            @RequestBody Map<String, Object> body) {
        Long userId = extractUserId();
        DebateSpeakRequest request = DebateSpeakRequest.builder()
                .roleKey((String) body.get("roleKey"))
                .sessionId((String) body.get("sessionId"))
                .roundType((String) body.getOrDefault("roundType", "FREE"))
                .roundNumber(body.get("roundNumber") instanceof Integer
                        ? (Integer) body.get("roundNumber") : 4)
                .build();
        String lastSpeech = (String) body.get("lastSpeech");
        return debateService.streamFreeSpeech(userId, bookId, request, lastSpeech);
    }

    @Operation(summary = "总结陈词发言（SSE）")
    @PostMapping(value = "/books/{bookId}/speak/closing", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter speakClosing(
            @PathVariable Long bookId,
            @Valid @RequestBody DebateSpeakRequest request) {
        Long userId = extractUserId();
        return debateService.streamClosingSpeech(userId, bookId, request);
    }

    // ==================== 自由辩论发言人 ====================

    @Operation(summary = "自由辩论下一发言人")
    @PostMapping("/sessions/{sessionId}/next-speaker")
    public Result<String> getNextSpeaker(@PathVariable String sessionId) {
        Long userId = extractUserId();
        String nextSpeaker = debateService.getNextSpeakerFree(userId, sessionId);
        return Result.ok(nextSpeaker);
    }

    // ==================== 轮次 ====================

    @Operation(summary = "推进到下一轮")
    @PostMapping("/sessions/{sessionId}/advance-round")
    public Result<DebateSessionVO> advanceRound(@PathVariable String sessionId) {
        return Result.ok(debateService.advanceRound(sessionId));
    }

    // ==================== 评分 ====================

    @Operation(summary = "获取会话评分")
    @GetMapping("/sessions/{sessionId}/scores")
    public Result<List<DebateScoreVO>> getScores(@PathVariable String sessionId) {
        return Result.ok(scoringService.getScoresBySession(sessionId));
    }

    @Operation(summary = "获取某轮评分")
    @GetMapping("/sessions/{sessionId}/scores/round/{roundNumber}")
    public Result<List<DebateScoreVO>> getScoresByRound(
            @PathVariable String sessionId,
            @PathVariable int roundNumber) {
        return Result.ok(scoringService.getScoresByRound(sessionId, roundNumber));
    }

    // ==================== 报告 ====================

    @Operation(summary = "触发辩论报告生成")
    @PostMapping("/sessions/{sessionId}/report")
    public Result<DebateReportVO> triggerReport(@PathVariable String sessionId) {
        Long userId = extractUserId();
        return Result.ok(reportService.triggerReport(sessionId, userId));
    }

    @Operation(summary = "获取辩论报告")
    @GetMapping("/sessions/{sessionId}/report")
    public Result<DebateReportVO> getReport(@PathVariable String sessionId) {
        DebateReportVO report = reportService.getReport(sessionId);
        return Result.ok(report);
    }
}
