package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.common.util.CommonUtils;
import com.kbook.config.properties.BookStorageProperties;
import com.kbook.dto.request.SendMessageRequest;
import com.kbook.dto.response.ChatMessageVO;
import com.kbook.dto.response.ConversationVO;
import com.kbook.entity.ChatMessage;
import com.kbook.service.ai.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Tag(name = "聊天")
public class ChatController {

    private final ChatService chatService;
    private final BookStorageProperties storageProps;

    @Operation(summary = "获取会话列表")
    @GetMapping("/conversations")
    public Result<List<ConversationVO>> getConversations(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(chatService.getConversations(userId));
    }

    @Operation(summary = "搜索会话")
    @GetMapping("/conversations/search")
    public Result<List<ConversationVO>> searchConversations(
            Authentication authentication,
            @RequestParam String keyword) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(chatService.searchConversations(userId, keyword));
    }

    @Operation(summary = "发起会话")
    @PostMapping("/conversations")
    public Result<ConversationVO> startConversation(
            Authentication authentication,
            @RequestParam Long recipientId) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(chatService.startConversation(userId, recipientId));
    }

    @Operation(summary = "获取会话详情")
    @GetMapping("/conversations/{conversationId}")
    public Result<ConversationVO> getConversation(
            Authentication authentication,
            @PathVariable Long conversationId) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(chatService.getConversation(userId, conversationId));
    }

    @Operation(summary = "标记会话已读")
    @PutMapping("/conversations/{conversationId}/read")
    public Result<Void> markAsRead(
            Authentication authentication,
            @PathVariable Long conversationId) {
        Long userId = (Long) authentication.getPrincipal();
        chatService.markAsRead(userId, conversationId);
        return Result.ok();
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/conversations/{conversationId}")
    public Result<Void> deleteConversation(
            Authentication authentication,
            @PathVariable Long conversationId) {
        Long userId = (Long) authentication.getPrincipal();
        chatService.deleteConversation(userId, conversationId);
        return Result.ok();
    }

    @Operation(summary = "获取聊天消息")
    @GetMapping("/conversations/{conversationId}/messages")
    public Result<List<ChatMessageVO>> getMessages(
            Authentication authentication,
            @PathVariable Long conversationId,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "20") int limit) {
        Long userId = (Long) authentication.getPrincipal();
        List<ChatMessageVO> messages = chatService.getMessages(userId, conversationId, beforeId, limit);
        return Result.ok(messages);
    }

    @Operation(summary = "发送消息")
    @PostMapping("/messages")
    public Result<ConversationVO> sendMessage(
            Authentication authentication,
            @Valid @RequestBody SendMessageRequest request) {
        Long userId = (Long) authentication.getPrincipal();
        ChatMessage.MessageType messageType = ChatMessage.MessageType.valueOf(request.getMessageType().toUpperCase());

        ConversationVO conversation = chatService.sendMessage(
                userId,
                request.getRecipientId(),
                request.getContent(),
                messageType,
                request.getFileName(),
                request.getFileSize(),
                request.getFileUrl(),
                request.getVoiceDuration()
        );
        return Result.ok(conversation);
    }

    @Operation(summary = "获取未读数")
    @GetMapping("/unread-count")
    public Result<Map<String, Long>> getUnreadCount(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        Long count = chatService.getUnreadCount(userId);
        return Result.ok(Map.of("count", count));
    }

    @Operation(summary = "上传聊天文件")
    @PostMapping("/files")
    public Result<Map<String, String>> uploadFile(
            Authentication authentication,
            @RequestParam("file") MultipartFile file,
            @RequestParam("conversationId") Long conversationId) throws IOException {
        Long userId = (Long) authentication.getPrincipal();
        String fileUrl = chatService.uploadChatFile(userId, conversationId, file);
        return Result.ok(Map.of("url", fileUrl));
    }

    @Operation(summary = "获取聊天文件")
    @GetMapping("/files/{conversationId}/{filename:.+}")
    public ResponseEntity<Resource> serveFile(
            @PathVariable Long conversationId,
            @PathVariable String filename) throws IOException {

        Path chatDir = Paths.get(storageProps.getUpload().getChatDir());
        Path convDir = chatDir.resolve(conversationId.toString());
        Path filePath = CommonUtils.safeResolvePath(convDir, filename);
        if (filePath == null) {
            return ResponseEntity.notFound().build();
        }

        boolean isImageThumb = isImageThumbRequest(filename);
        boolean isVideoThumb = isVideoThumbRequest(filename);

        if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
            if (isImageThumb || isVideoThumb) {
                return buildJpegResponse(filePath);
            }
            return CommonUtils.buildImageResponse(filePath, filename);
        }

        if (isImageThumb) {
            String originalFilename = getOriginalFilename(filename, "_thumbnail");
            Path originalPath = CommonUtils.safeResolvePath(convDir, originalFilename);
            if (originalPath == null || !Files.exists(originalPath) || !Files.isRegularFile(originalPath)) {
                return ResponseEntity.notFound().build();
            }

            log.info("生成图片缩略图: {}/{} -> {}", conversationId, originalFilename, filename);
            CommonUtils.generateThumbnail(originalPath, filePath, 100, 100);
            return buildJpegResponse(filePath);
        }

        return ResponseEntity.notFound().build();
    }

    private ResponseEntity<Resource> buildJpegResponse(Path filePath) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic())
                .body(new FileSystemResource(filePath));
    }

    private boolean isImageThumbRequest(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0) return false;
        return filename.substring(0, dotIndex).endsWith("_thumbnail");
    }

    private boolean isVideoThumbRequest(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0) return false;
        String nameWithoutExt = filename.substring(0, dotIndex);
        return nameWithoutExt.endsWith("_thumb") && !nameWithoutExt.endsWith("_thumbnail");
    }

    private String getOriginalFilename(String thumbFilename, String suffix) {
        int dotIndex = thumbFilename.lastIndexOf('.');
        if (dotIndex < 0) return thumbFilename;
        String nameWithoutExt = thumbFilename.substring(0, dotIndex);
        String ext = thumbFilename.substring(dotIndex);
        String originalName = nameWithoutExt.substring(0, nameWithoutExt.length() - suffix.length());
        return originalName + ext;
    }
}
