package com.kbook.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kbook.config.ai.AiConfigProvider;
import com.kbook.common.api.Result;
import com.kbook.constants.AiPromptConstants;
import com.kbook.dto.roundtable.RoleVO;
import com.kbook.dto.roundtable.RoundTableSessionFeedVO;
import com.kbook.dto.roundtable.SpeakRequest;
import com.kbook.entity.RoundTableCoverage;
import com.kbook.entity.RoundTableMessage;
import com.kbook.entity.RoundTableReport;
import com.kbook.entity.RoundTableSession;
import com.kbook.service.ai.RoundTableCoverageService;
import com.kbook.service.ai.RoundTableReportService;
import com.kbook.service.ai.RoundTableService;
import com.kbook.service.ai.ChatModelManager;
import com.kbook.service.book.BookService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 圆桌派控制器 — 多角色 AI 讨论接口
 * <p>
 * 新架构：每个角色独立发言，前端控制谁先发言（抢麦机制）。
 * 后端提供单角色 SSE 端点，每次调用只生成一个角色的发言。
 * 会话和消息持久化到数据库，支持历史回放。
 */
@Slf4j
@RestController
@RequestMapping("/api/round-table")
@RequiredArgsConstructor
@Tag(name = "圆桌派")
public class RoundTableController extends BaseController {

    /** 圆桌派服务 */
    private final RoundTableService roundTableService;

    /** 圆桌派覆盖度服务 */
    private final RoundTableCoverageService coverageService;

    /** 圆桌派解读报告服务 */
    private final RoundTableReportService reportService;

    /** 书籍服务 */
    private final BookService bookService;

    /** AI 配置（读取角色信息） */
    private final AiConfigProvider aiConfigProvider;

    /** AI 模型调用（导出时生成钩子文案） */
    private final ChatModelManager chatModelManager;

    /**
     * 获取推荐角色列表（LLM 驱动）
     * <p>
     * 根据书籍信息通过 LLM 推荐适合的讨论角色，LLM 失败时回退到标签匹配
     *
     * @param bookId 书籍ID
     * @return 推荐角色列表（含 LLM 赋值的 domainRelevance）
     */
    @Operation(summary = "获取推荐角色")
    @GetMapping("/books/{bookId}/roles")
    public Result<List<RoleVO>> getRecommendedRoles(
            @PathVariable Long bookId,
            @RequestParam(required = false, defaultValue = "false") boolean refresh) {
        return Result.ok(roundTableService.getRecommendedRoles(bookId, refresh));
    }

    /**
     * 创建圆桌派会话
     *
     * @param bookId 书籍ID
     * @param body   请求体（roleKeys: 角色键名列表, roleConfigs: 角色配置 JSON）
     * @return 创建的会话
     */
    @Operation(summary = "创建圆桌派会话")
    @PostMapping("/books/{bookId}/sessions")
    public Result<RoundTableSession> createSession(
            @PathVariable Long bookId,
            @RequestBody Map<String, Object> body) {
        Long userId = extractUserId();
        @SuppressWarnings("unchecked")
        List<String> roleKeys = (List<String>) body.get("roleKeys");
        String roleConfigs = (String) body.get("roleConfigs");
        if (roleKeys == null || roleKeys.isEmpty()) {
            return Result.fail("角色列表不能为空");
        }
        return Result.ok(roundTableService.createSession(userId, bookId, roleKeys, roleConfigs));
    }

    /**
     * 获取用户对指定书籍的圆桌派会话列表
     *
     * @param bookId 书籍ID
     * @return 会话列表
     */
    @Operation(summary = "获取圆桌派会话列表")
    @GetMapping("/books/{bookId}/sessions")
    public Result<List<RoundTableSession>> getSessions(@PathVariable Long bookId) {
        Long userId = extractUserId();
        return Result.ok(roundTableService.getSessions(userId, bookId));
    }

    /**
     * 获取全局圆桌列表（发现页）
     */
    @Operation(summary = "获取全局圆桌列表（发现页）")
    @GetMapping("/sessions")
    public Result<Page<RoundTableSessionFeedVO>> getGlobalSessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "recent") String sort,
            @RequestParam(defaultValue = "false") boolean mine) {
        return Result.ok(roundTableService.getGlobalSessions(page, size, sort, mine));
    }

    /**
     * 获取圆桌派会话详情
     *
     * @param sessionId 会话ID
     * @return 会话详情
     */
    @Operation(summary = "获取圆桌派会话详情")
    @GetMapping("/sessions/{sessionId}")
    public Result<RoundTableSession> getSession(@PathVariable String sessionId) {
        return Result.ok(roundTableService.getSession(sessionId));
    }

    /**
     * 更新圆桌派会话状态
     *
     * @param sessionId 会话ID
     * @param body      请求体（status: ACTIVE / COMPLETED / ABANDONED）
     * @return 更新后的会话
     */
    @Operation(summary = "更新圆桌派会话状态")
    @PutMapping("/sessions/{sessionId}/status")
    public Result<RoundTableSession> updateSessionStatus(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        Long userId = extractUserId();
        String status = (String) body.get("status");
        if (status == null || status.isBlank()) {
            return Result.fail("状态不能为空");
        }
        roundTableService.updateSessionStatus(userId, sessionId, status);
        return Result.ok(roundTableService.getSession(sessionId));
    }

    /**
     * 获取圆桌派会话历史消息
     *
     * @param sessionId 会话ID
     * @return 消息列表
     */
    @Operation(summary = "获取圆桌派历史消息")
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<RoundTableMessage>> getMessages(@PathVariable String sessionId) {
        Long userId = extractUserId();
        return Result.ok(roundTableService.getHistory(userId, sessionId));
    }

    /**
     * 单角色发言（SSE）
     * <p>
     * 前端指定某个角色发言，后端从 DB 加载历史并调用 AI 生成该角色的发言内容并流式推送
     *
     * @param bookId  书籍ID
     * @param request 发言请求（包含角色键名、会话ID、话题方向）
     * @return SSE 事件流
     */
    @Operation(summary = "单角色发言")
    @PostMapping(value = "/books/{bookId}/speak", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter speak(
            @PathVariable Long bookId,
            @Valid @RequestBody SpeakRequest request
    ) {
        Long userId = extractUserId();
        return withSseLimit(userId, () -> roundTableService.streamCharacterSpeak(userId, bookId, request));
    }

    /**
     * 删除圆桌派会话及其消息
     *
     * @param sessionId 会话ID
     * @return 操作结果
     */
    @Operation(summary = "删除圆桌派会话")
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        Long userId = extractUserId();
        roundTableService.deleteSession(userId, sessionId);
        return Result.ok(null);
    }

    /**
     * LLM 判断下一轮发言人
     */
    @Operation(summary = "LLM判断下一发言人")
    @PostMapping("/sessions/{sessionId}/next-speaker")
    public Result<String> getNextSpeaker(
            @PathVariable String sessionId) {
        Long userId = extractUserId();
        String nextSpeaker = roundTableService.getNextSpeakerOnlyLLM(userId, sessionId);
        return Result.ok(nextSpeaker);
    }

    /**
     * 获取会话覆盖度报告
     */
    @Operation(summary = "获取覆盖度报告")
    @GetMapping("/sessions/{sessionId}/coverage")
    public Result<RoundTableCoverage> getCoverage(@PathVariable String sessionId) {
        return Result.ok(coverageService.getCoverage(sessionId));
    }

    /**
     * 手动触发覆盖度计算（管理用）
     */
    @Operation(summary = "刷新覆盖度")
    @PostMapping("/sessions/{sessionId}/coverage/refresh")
    public Result<RoundTableCoverage> refreshCoverage(@PathVariable String sessionId) {
        Long userId = extractUserId();
        roundTableService.verifySessionOwnership(userId, sessionId);
        return Result.ok(coverageService.updateCoverage(sessionId, false));
    }

    /**
     * 触发解读报告生成
     * <p>
     * 异步生成，约 2-3 分钟完成。完成后通过站内信通知用户。
     * 如果已有报告（非失败状态），直接返回已有报告。
     */
    @Operation(summary = "生成解读报告")
    @PostMapping("/sessions/{sessionId}/report")
    public Result<RoundTableReport> triggerReport(@PathVariable String sessionId) {
        Long userId = extractUserId();
        return Result.ok(reportService.triggerReport(sessionId, userId));
    }

    /**
     * 获取解读报告
     * <p>
     * 返回报告实体（含状态和内容），不存在时返回 null
     */
    @Operation(summary = "获取解读报告")
    @GetMapping("/sessions/{sessionId}/report")
    public Result<RoundTableReport> getReport(@PathVariable String sessionId) {
        return Result.ok(reportService.getReport(sessionId));
    }

    /**
     * 导出圆桌派讨论记录为 TXT 文件
     */
    @Operation(summary = "导出讨论记录")
    @GetMapping("/sessions/{sessionId}/export")
    public void exportSession(@PathVariable String sessionId, HttpServletResponse response) throws IOException {
        Long userId = extractUserId();
        roundTableService.verifySessionOwnership(userId, sessionId);

        RoundTableSession session = roundTableService.getSession(sessionId);
        if (session == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "会话不存在");
            return;
        }

        List<RoundTableMessage> messages = roundTableService.getHistory(userId, sessionId);
        RoundTableReport report = reportService.getReport(sessionId);

        com.kbook.entity.Book book = null;
        if (session.getBookId() != null) {
            book = bookService.getBookById(session.getBookId());
        }

        StringBuilder sb = new StringBuilder();
        // 标题
        sb.append("# ").append(session.getTitle());
        if (book != null) {
            sb.append("（《").append(book.getTitle()).append("》）");
        }
        sb.append("\n\n");

        // 书籍简介（截取前 300 字）
        if (book != null && book.getDescription() != null && !book.getDescription().isBlank()) {
            String desc = book.getDescription().trim();
            if (desc.length() > 300) desc = desc.substring(0, 300) + "……";
            sb.append("> ").append(desc).append("\n\n");
        }

        // 嘉宾介绍
        String[] roleKeys = session.getRoleKeys() != null ? session.getRoleKeys().split(",") : new String[0];
        if (roleKeys.length > 0) {
            sb.append("**本期嘉宾**\n\n");
            for (String key : roleKeys) {
                key = key.trim();
                if (key.isBlank()) continue;
                com.kbook.config.ai.AiConfig.RoundTableRole role = aiConfigProvider.getRoundTableRole(key);
                if (role == null) continue;
                sb.append("- **").append(role.getName()).append("**");
                if (role.getTitle() != null && !role.getTitle().isBlank()) {
                    sb.append("（").append(role.getTitle()).append("）");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // 短视频钩子（基于报告内容，放在最前面吸引注意力）
        if (report != null && "COMPLETED".equals(report.getStatus()) && report.getContent() != null) {
            String hook = generateExportHook(report.getContent());
            if (hook != null && !hook.isBlank()) {
                sb.append(hook).append("\n\n---\n\n");
            }
        }

        // 每隔约 5000 字用 6 个换行分段，不截断单条发言
        int PARAGRAPH_LIMIT = 5000;
        int currentParagraphLen = 0;
        for (RoundTableMessage msg : messages) {
            String line = "**" + msg.getRoleName() + "说**：" + msg.getContent() + "\n\n";
            if (currentParagraphLen > 0 && currentParagraphLen + line.length() > PARAGRAPH_LIMIT) {
                sb.append("\n\n---\n\n");
                currentParagraphLen = 0;
            }
            sb.append(line);
            currentParagraphLen += line.length();
        }

        if (report != null && "COMPLETED".equals(report.getStatus()) && report.getContent() != null) {
            sb.append("---\n\n## 总结报告\n\n").append(report.getContent());
        }

        String filename = "圆桌派讨论_" + session.getTitle() + ".md";
        // 清理文件名中的非法字符
        filename = filename.replaceAll("[\\\\/:*?\"<>|]", "_");

        response.setContentType("text/markdown;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8));
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        try (PrintWriter writer = response.getWriter()) {
            writer.write(sb.toString());
        }
    }

    /**
     * 基于报告内容生成短视频风格的钩子文案
     */
    private String generateExportHook(String reportContent) {
        try {
            String hook = chatModelManager.callAi("导出钩子", "report→hook",
                    List.of(
                            SystemMessage.from(AiPromptConstants.ROUND_TABLE_EXPORT_HOOK_PROMPT),
                            UserMessage.from(reportContent)));
            if (hook != null && hook.length() > 150) {
                int lastPeriod = hook.lastIndexOf('。', 150);
                if (lastPeriod > 50) {
                    hook = hook.substring(0, lastPeriod + 1);
                } else {
                    hook = hook.substring(0, 150) + "……";
                }
            }
            return hook;
        } catch (Exception e) {
            log.warn("生成导出钩子失败: {}", e.getMessage());
            return null;
        }
    }
}
