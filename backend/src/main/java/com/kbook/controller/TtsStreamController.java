package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.request.TtsStreamPrepareRequest;
import com.kbook.dto.response.TtsStreamTicket;
import com.kbook.service.tts.TtsClientGoneException;
import com.kbook.service.tts.TtsProgressiveService;
import com.kbook.service.tts.TtsStreamTicketStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 渐进式语音播放控制器
 * <p>
 * 面向「整篇朗读」场景，把合成结果暴露成单一连续音频流，
 * 使浏览器用一个常驻 audio 元素播完整个文本。相比逐段播放，
 * 锁屏控制、后台播放、系统媒体键、进度显示全部由平台原生能力承担。
 * <p>
 * 两步交互：
 * <ol>
 *   <li>{@code POST /api/tts/stream/prepare} — 提交文本，换回一个短 ID
 *       （避开 &lt;audio src&gt; 只能 GET、无法带请求头、URL 长度受限的问题）</li>
 *   <li>{@code GET /api/tts/stream/{ticketId}?token=...} — 作为音源地址，
 *       边合成边输出；若整篇已缓存则整段输出并支持 Range 回放</li>
 * </ol>
 */
@Slf4j
@RestController
@Tag(name = "语音合成")
public class TtsStreamController extends BaseController {

    private final TtsStreamTicketStore ticketStore;

    private final TtsProgressiveService progressiveService;

    public TtsStreamController(TtsStreamTicketStore ticketStore,
                               TtsProgressiveService progressiveService) {
        this.ticketStore = ticketStore;
        this.progressiveService = progressiveService;
    }

    @Operation(summary = "申请渐进式播放凭据")
    @PostMapping("/api/tts/stream/prepare")
    public Result<TtsStreamTicket> prepare(@Valid @RequestBody TtsStreamPrepareRequest request) {
        Long userId = extractUserId();
        TtsProgressiveService.AudioProfile profile =
                progressiveService.resolveProfile(request.getConfigId());

        String ticketId = ticketStore.create(request.getText(), request.getConfigId(), userId);
        log.info("TTS stream ticket created: ticketId={}, textLength={}, configId={}, contentType={}",
                ticketId, request.getText().length(), request.getConfigId(), profile.contentType());

        return Result.ok(new TtsStreamTicket(ticketId, profile.contentType()));
    }

    /**
     * 渐进式音频流
     * <p>
     * 直接写 {@link HttpServletResponse} 而不返回 ResponseEntity：
     * 响应体在合成过程中就已开始输出，状态码与响应头必须在写入前确定。
     *
     * @param ticketId 播放凭据
     * @param token    查询参数形式的 JWT（audio 元素无法携带 Authorization 头）
     * @param request  HTTP 请求，用于读取 Range
     * @param response HTTP 响应
     */
    @Operation(summary = "渐进式语音播放")
    @GetMapping("/api/tts/stream/{ticketId}")
    public void stream(@PathVariable String ticketId,
                       @RequestParam(required = false) String token,
                       @RequestParam(required = false, defaultValue = "progressive") String mode,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        Long userId = extractUserId();
        TtsStreamTicketStore.Ticket ticket = ticketStore.resolve(ticketId, userId);
        if (ticket == null) {
            // 凭据过期或不属于当前用户：前端会重新 prepare 后重试
            response.setStatus(HttpServletResponse.SC_GONE);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":410,\"message\":\"播放凭据已失效，请重新开始朗读\"}");
            return;
        }

        TtsProgressiveService.AudioProfile profile = progressiveService.resolveProfile(ticket.configId());

        // 整篇已缓存时按完整文件输出：可声明长度并支持 Range，
        // 这样锁屏恢复、拖动进度条、系统 seek 都能精确定位。
        byte[] complete = progressiveService.tryLoadComplete(ticket.text(), ticket.configId());
        if (complete == null && "complete".equals(mode)) {
            // 拖动进度条等需要精确定位的场景：把缺失分段补齐后一次性返回完整文件。
            // 渐进式流的可 seek 范围只到已缓冲位置，用户拖到未缓冲区间会被浏览器
            // 静默忽略；只有拿到带 Content-Length 的完整文件才能自由跳转。
            log.info("TTS stream completing all segments: ticketId={}", ticketId);
            complete = progressiveService.ensureComplete(ticket.text(), ticket.configId());
        }
        if (complete != null) {
            writeComplete(ticketId, complete, profile.contentType(), request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(profile.contentType());
        // 渐进式输出无法预知总长度，故不声明 Content-Length 与 Range 支持
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Accept-Ranges", "none");
        // 明确告知前置 nginx 不要缓冲，保证首帧立刻到达浏览器
        response.setHeader("X-Accel-Buffering", "no");

        OutputStream out = response.getOutputStream();
        try {
            long bytes = progressiveService.streamTo(ticket.text(), ticket.configId(), out);
            out.flush();
            log.info("TTS stream finished: ticketId={}, bytes={}", ticketId, bytes);
        } catch (TtsClientGoneException e) {
            // 用户切歌、关页面、锁屏切后台都可能提前断开，属正常现象
            log.info("TTS stream aborted by client: ticketId={}", ticketId);
        } catch (IOException e) {
            log.info("TTS stream write aborted: ticketId={}, reason={}", ticketId, e.getMessage());
        }
    }

    @Operation(summary = "释放渐进式播放凭据")
    @DeleteMapping("/api/tts/stream/{ticketId}")
    public Result<Void> release(@PathVariable String ticketId) {
        Long userId = extractUserId();
        if (ticketStore.resolve(ticketId, userId) != null) {
            ticketStore.remove(ticketId);
        }
        return Result.ok(null);
    }

    /**
     * 输出完整音频，支持单区间 Range 请求
     * <p>
     * 浏览器在 seek、锁屏恢复、或预加载后续分片时会带 {@code Range: bytes=...}。
     * 不支持 Range 会退化为整段重下，既浪费流量也无法精确定位播放位置。
     */
    private void writeComplete(String ticketId, byte[] data, String contentType,
                               HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType(contentType);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Accept-Ranges", "bytes");

        long total = data.length;
        String rangeHeader = request.getHeader("Range");
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentLengthLong(total);
            response.getOutputStream().write(data);
            response.getOutputStream().flush();
            return;
        }

        // 仅支持单区间；多区间（逗号分隔）按整段返回，行为安全且实现简单
        String spec = rangeHeader.substring("bytes=".length()).trim();
        if (spec.contains(",")) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentLengthLong(total);
            response.getOutputStream().write(data);
            response.getOutputStream().flush();
            return;
        }

        long start;
        long end;
        int dash = spec.indexOf('-');
        try {
            if (dash <= 0) {
                // 形如 bytes=-500：末尾 N 字节
                long suffixLen = Long.parseLong(spec.substring(1));
                if (suffixLen <= 0) {
                    start = 0;
                    end = total - 1;
                } else {
                    start = Math.max(0, total - suffixLen);
                    end = total - 1;
                }
            } else {
                start = Long.parseLong(spec.substring(0, dash).trim());
                String endPart = spec.substring(dash + 1).trim();
                end = endPart.isEmpty() ? total - 1 : Long.parseLong(endPart);
            }
        } catch (NumberFormatException e) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentLengthLong(total);
            response.getOutputStream().write(data);
            response.getOutputStream().flush();
            return;
        }

        if (start < 0) start = 0;
        if (end > total - 1) end = total - 1;
        if (start > end || start >= total) {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader("Content-Range", "bytes */" + total);
            return;
        }

        long len = end - start + 1;
        response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + total);
        response.setContentLengthLong(len);
        response.getOutputStream().write(data, (int) start, (int) len);
        response.getOutputStream().flush();
        log.debug("TTS stream range served: ticketId={}, {}-{}/{}", ticketId, start, end, total);
    }
}