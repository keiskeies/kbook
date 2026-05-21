package com.kbook.service;

import com.kbook.common.exception.BusinessException;
import com.kbook.config.properties.BookStorageProperties;
import com.kbook.dto.response.ChatMessageVO;
import com.kbook.dto.response.ConversationVO;
import com.kbook.entity.ChatMessage;
import com.kbook.entity.Conversation;
import com.kbook.entity.UploadedFile;
import com.kbook.entity.User;
import com.kbook.repository.ChatMessageRepository;
import com.kbook.repository.ConversationRepository;
import com.kbook.repository.UploadedFileRepository;
import com.kbook.repository.UserFollowRepository;
import com.kbook.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final UserFollowRepository userFollowRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final BookStorageProperties storageProps;

    private static final int MAX_STRANGER_MESSAGES = 1;

    @Transactional
    public ConversationVO sendMessage(Long senderId, Long recipientId, String content, 
                                      ChatMessage.MessageType messageType, String fileName,
                                      Long fileSize, String fileUrl, Integer voiceDuration) {
        validateUserExists(senderId);
        validateUserExists(recipientId);

        if (senderId.equals(recipientId)) {
            throw new BusinessException("不能给自己发消息");
        }

        checkStrangerLimit(senderId, recipientId);

        Conversation conversation = getOrCreateConversation(senderId, recipientId);

        ChatMessage message = ChatMessage.builder()
                .conversationId(conversation.getId())
                .senderId(senderId)
                .recipientId(recipientId)
                .messageType(messageType)
                .content(content)
                .fileName(fileName)
                .fileSize(fileSize)
                .fileUrl(fileUrl)
                .voiceDuration(voiceDuration)
                .read(false)
                .build();

        message = chatMessageRepository.save(message);

        String lastMessage = formatLastMessage(message);
        conversation.setLastMessage(lastMessage);

        if (senderId.equals(conversation.getUser1Id())) {
            conversation.setUnreadCountUser2(conversation.getUnreadCountUser2() + 1);
        } else {
            conversation.setUnreadCountUser1(conversation.getUnreadCountUser1() + 1);
        }

        conversation = conversationRepository.save(conversation);

        ChatMessageVO messageVO = ChatMessageVO.fromEntity(message);
        messagingTemplate.convertAndSendToUser(recipientId.toString(), "/queue/messages", messageVO);

        return getConversationVO(conversation, senderId);
    }

    private void validateUserExists(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException("用户不存在");
        }
    }

    private void checkStrangerLimit(Long senderId, Long recipientId) {
        boolean isFollowing = userFollowRepository.existsByFollowerIdAndFollowingId(senderId, recipientId);
        if (isFollowing) {
            return;
        }

        LocalDateTime since = LocalDateTime.now().minusDays(30);
        Long count = chatMessageRepository.countRecentMessages(senderId, recipientId, since);
        if (count >= MAX_STRANGER_MESSAGES) {
            throw new BusinessException("对方未关注你，你只能发送一条消息");
        }
    }

    private Conversation getOrCreateConversation(Long userId1, Long userId2) {
        Long user1Id = Math.min(userId1, userId2);
        Long user2Id = Math.max(userId1, userId2);

        return conversationRepository.findByUser1IdAndUser2Id(user1Id, user2Id)
                .orElseGet(() -> {
                    Conversation conversation = Conversation.builder()
                            .user1Id(user1Id)
                            .user2Id(user2Id)
                            .unreadCountUser1(0)
                            .unreadCountUser2(0)
                            .user1Deleted(false)
                            .user2Deleted(false)
                            .build();
                    return conversationRepository.save(conversation);
                });
    }

    private String formatLastMessage(ChatMessage message) {
        return switch (message.getMessageType()) {
            case TEXT -> message.getContent();
            case IMAGE -> "[图片]";
            case VOICE -> "[语音]";
            case FILE -> "[文件]" + (message.getFileName() != null ? message.getFileName() : "");
        };
    }

    public List<ConversationVO> getConversations(Long userId) {
        List<Conversation> conversations = conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        return conversations.stream()
                .map(c -> getConversationVO(c, userId))
                .collect(Collectors.toList());
    }

    public List<ConversationVO> searchConversations(Long userId, String keyword) {
        List<Conversation> conversations = conversationRepository.findByUserIdAndKeyword(userId, keyword);
        return conversations.stream()
                .map(c -> getConversationVO(c, userId))
                .collect(Collectors.toList());
    }

    private ConversationVO getConversationVO(Conversation conversation, Long userId) {
        Long otherUserId = conversation.getUser1Id().equals(userId) ? conversation.getUser2Id() : conversation.getUser1Id();
        User otherUser = userRepository.findById(otherUserId).orElse(null);
        
        String nickname = otherUser != null ? otherUser.getNickname() : "未知用户";
        String avatar = otherUser != null && otherUser.getAvatar() != null ? otherUser.getAvatar() : "";

        return ConversationVO.fromEntity(conversation, userId, nickname, avatar);
    }

    @Transactional
    public Page<ChatMessageVO> getMessages(Long userId, Long conversationId, int page, int size) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new BusinessException("会话不存在"));

        if (!conversation.getUser1Id().equals(userId) && !conversation.getUser2Id().equals(userId)) {
            throw new BusinessException("无权访问此会话");
        }

        Pageable pageable = PageRequest.of(page - 1, size);
        Page<ChatMessage> messagePage = chatMessageRepository.findByConversationIdOrderByCreatedAtDesc(conversationId, pageable);

        chatMessageRepository.markAllAsRead(conversationId, userId);
        
        if (conversation.getUser1Id().equals(userId)) {
            conversationRepository.clearUnreadCountUser1(conversationId);
        } else {
            conversationRepository.clearUnreadCountUser2(conversationId);
        }

        return messagePage.map(ChatMessageVO::fromEntity);
    }

    @Transactional
    public void markAsRead(Long userId, Long conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new BusinessException("会话不存在"));

        if (!conversation.getUser1Id().equals(userId) && !conversation.getUser2Id().equals(userId)) {
            throw new BusinessException("无权访问此会话");
        }

        chatMessageRepository.markAllAsRead(conversationId, userId);

        if (conversation.getUser1Id().equals(userId)) {
            conversationRepository.clearUnreadCountUser1(conversationId);
        } else {
            conversationRepository.clearUnreadCountUser2(conversationId);
        }
    }

    @Transactional
    public void deleteConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new BusinessException("会话不存在"));

        if (conversation.getUser1Id().equals(userId)) {
            conversationRepository.deleteByUser1(conversationId);
        } else if (conversation.getUser2Id().equals(userId)) {
            conversationRepository.deleteByUser2(conversationId);
        } else {
            throw new BusinessException("无权删除此会话");
        }
    }

    public Long getUnreadCount(Long userId) {
        return conversationRepository.sumUnreadCount(userId);
    }

    public String uploadChatFile(Long userId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择文件");
        }

        String contentType = file.getContentType();
        String originalFilename = file.getOriginalFilename();
        String ext = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            ext = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        }

        if (!isAllowedFileType(contentType, ext)) {
            throw new BusinessException("不支持的文件类型，仅支持图片、视频、PDF、Word、Excel、PPT、TXT、Markdown、音频");
        }

        long maxSize;
        if (isImageFile(contentType, ext)) {
            maxSize = 10 * 1024 * 1024L;
        } else if (isAudioFile(contentType, ext)) {
            maxSize = 20 * 1024 * 1024L;
        } else if (isVideoFile(contentType, ext)) {
            maxSize = 100 * 1024 * 1024L;
        } else {
            maxSize = 50 * 1024 * 1024L;
        }
        if (file.getSize() > maxSize) {
            String sizeHint = maxSize / (1024 * 1024) + "MB";
            throw new BusinessException("文件大小不能超过" + sizeHint);
        }

        String filename = UUID.randomUUID().toString().replace("-", "") + ext;

        Path dirPath = Paths.get(storageProps.getUpload().getChatDir());
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
        }
        Path filePath = dirPath.resolve(filename);
        file.transferTo(filePath.toFile());

        UploadedFile uploadedFile = UploadedFile.builder()
                .filename(filename)
                .originalFilename(originalFilename)
                .uploaderId(userId)
                .contentType(contentType)
                .fileSize(file.getSize())
                .filePath(filePath.toString())
                .build();
        uploadedFileRepository.save(uploadedFile);

        return storageProps.getUpload().getChatUrlPrefix() + "/" + filename;
    }

    private boolean isAllowedFileType(String contentType, String ext) {
        if (contentType != null) {
            if (contentType.startsWith("image/") || contentType.startsWith("audio/") || contentType.startsWith("video/")) {
                return true;
            }
            if (contentType.equals("application/pdf")) {
                return true;
            }
        }
        String[] allowedDocTypes = {
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "text/markdown"
        };
        if (contentType != null) {
            for (String type : allowedDocTypes) {
                if (contentType.equals(type)) {
                    return true;
                }
            }
        }
        String[] allowedExts = {
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".md",
            ".webm", ".mp3", ".wav", ".ogg", ".m4a", ".aac", ".wma",
            ".mp4", ".mov", ".avi", ".mkv", ".wmv", ".flv"
        };
        for (String allowed : allowedExts) {
            if (ext.equals(allowed)) {
                return true;
            }
        }
        return false;
    }

    private boolean isImageFile(String contentType, String ext) {
        if (contentType != null && contentType.startsWith("image/")) {
            return true;
        }
        String[] imageExts = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"};
        for (String e : imageExts) {
            if (ext.equals(e)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAudioFile(String contentType, String ext) {
        if (contentType != null && contentType.startsWith("audio/")) {
            return true;
        }
        String[] audioExts = {".webm", ".mp3", ".wav", ".ogg", ".m4a", ".aac", ".wma"};
        for (String e : audioExts) {
            if (ext.equals(e)) {
                return true;
            }
        }
        return false;
    }

    private boolean isVideoFile(String contentType, String ext) {
        if (contentType != null && contentType.startsWith("video/")) {
            return true;
        }
        String[] videoExts = {".mp4", ".mov", ".avi", ".mkv", ".wmv", ".flv", ".webm"};
        for (String e : videoExts) {
            if (ext.equals(e)) {
                return true;
            }
        }
        return false;
    }

    public ConversationVO getConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new BusinessException("会话不存在"));

        if (!conversation.getUser1Id().equals(userId) && !conversation.getUser2Id().equals(userId)) {
            throw new BusinessException("无权访问此会话");
        }

        return getConversationVO(conversation, userId);
    }

    public ConversationVO startConversation(Long userId, Long recipientId) {
        validateUserExists(userId);
        validateUserExists(recipientId);

        if (userId.equals(recipientId)) {
            throw new BusinessException("不能和自己创建会话");
        }

        Conversation conversation = getOrCreateConversation(userId, recipientId);
        return getConversationVO(conversation, userId);
    }
}