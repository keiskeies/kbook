package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.request.SendMessageRequest;
import com.kbook.dto.response.ChatMessageVO;
import com.kbook.dto.response.ConversationVO;
import com.kbook.entity.ChatMessage;
import com.kbook.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @GetMapping("/conversations")
    public Result<List<ConversationVO>> getConversations(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(chatService.getConversations(userId));
    }

    @GetMapping("/conversations/search")
    public Result<List<ConversationVO>> searchConversations(
            Authentication authentication,
            @RequestParam String keyword) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(chatService.searchConversations(userId, keyword));
    }

    @GetMapping("/conversations/{conversationId}")
    public Result<ConversationVO> getConversation(
            Authentication authentication,
            @PathVariable Long conversationId) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(chatService.getConversation(userId, conversationId));
    }

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

    @PostMapping("/messages")
    public Result<ConversationVO> sendMessage(
            Authentication authentication,
            @RequestBody SendMessageRequest request) {
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

    @PostMapping("/conversations/{recipientId}")
    public Result<ConversationVO> startConversation(
            Authentication authentication,
            @PathVariable Long recipientId) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(chatService.startConversation(userId, recipientId));
    }

    @PostMapping("/conversations/{conversationId}/mark-read")
    public Result<Void> markAsRead(
            Authentication authentication,
            @PathVariable Long conversationId) {
        Long userId = (Long) authentication.getPrincipal();
        chatService.markAsRead(userId, conversationId);
        return Result.ok();
    }

    @DeleteMapping("/conversations/{conversationId}")
    public Result<Void> deleteConversation(
            Authentication authentication,
            @PathVariable Long conversationId) {
        Long userId = (Long) authentication.getPrincipal();
        chatService.deleteConversation(userId, conversationId);
        return Result.ok();
    }

    @GetMapping("/unread-count")
    public Result<Map<String, Long>> getUnreadCount(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        Long count = chatService.getUnreadCount(userId);
        return Result.ok(Map.of("count", count));
    }

    @PostMapping("/upload")
    public Result<Map<String, String>> uploadFile(
            Authentication authentication,
            @RequestParam("file") MultipartFile file,
            @RequestParam("conversationId") Long conversationId) throws IOException {
        Long userId = (Long) authentication.getPrincipal();
        String fileUrl = chatService.uploadChatFile(userId, conversationId, file);
        return Result.ok(Map.of("url", fileUrl));
    }
}