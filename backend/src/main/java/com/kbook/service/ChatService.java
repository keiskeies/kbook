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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;

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
    private final VideoService videoService;

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
        List<Conversation> conversations = conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        String lowerKeyword = keyword.toLowerCase();
        return conversations.stream()
                .map(c -> getConversationVO(c, userId))
                .filter(vo -> (vo.getOtherUserNickname() != null && vo.getOtherUserNickname().toLowerCase().contains(lowerKeyword))
                        || (vo.getLastMessage() != null && vo.getLastMessage().toLowerCase().contains(lowerKeyword)))
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
    public List<ChatMessageVO> getMessages(Long userId, Long conversationId, Long beforeId, int limit) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new BusinessException("会话不存在"));

        if (!conversation.getUser1Id().equals(userId) && !conversation.getUser2Id().equals(userId)) {
            throw new BusinessException("无权访问此会话");
        }

        List<ChatMessage> messages;
        if (beforeId == null) {
            messages = chatMessageRepository.findTop20ByConversationIdOrderByCreatedAtDesc(conversationId);
        } else {
            messages = chatMessageRepository.findByConversationIdAndIdLessThanOrderByCreatedAtDesc(
                    conversationId, beforeId, PageRequest.of(0, limit));
        }

        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        return messages.stream().map(ChatMessageVO::fromEntity).toList();
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

    public String uploadChatFile(Long userId, Long conversationId, MultipartFile file) throws IOException {
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

        boolean isImage = isImageFile(contentType, ext);
        // 图片统一转换为 JPEG（节省磁盘空间和带宽），其他文件保持原格式
        String filename = UUID.randomUUID().toString().replace("-", "") + (isImage ? ".jpg" : ext);

        Path chatDir = Paths.get(storageProps.getUpload().getChatDir());
        Path dirPath = chatDir.resolve(conversationId.toString());
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
        }
        Path filePath = dirPath.resolve(filename);

        long savedSize;
        String savedContentType;

        if (isImage) {
            // 读取上传的图片 → 转 JPEG q=1.0 → 写入磁盘
            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) {
                throw new BusinessException("无法解析图片文件");
            }
            // JPEG 不支持透明通道，透明背景填充白色
            BufferedImage rgbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = rgbImage.createGraphics();
            g2d.setColor(java.awt.Color.WHITE);
            g2d.fillRect(0, 0, rgbImage.getWidth(), rgbImage.getHeight());
            g2d.drawImage(image, 0, 0, null);
            g2d.dispose();

            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(1.0f);
            try (FileImageOutputStream output = new FileImageOutputStream(filePath.toFile())) {
                writer.setOutput(output);
                writer.write(null, new IIOImage(rgbImage, null, null), params);
            } finally {
                writer.dispose();
            }
            savedSize = Files.size(filePath);
            savedContentType = "image/jpeg";
        } else {
            file.transferTo(filePath.toFile());
            savedSize = file.getSize();
            savedContentType = contentType;

            // 视频处理（可选，通过 kbook.video.thumbnail/transcode.enabled 控制）
            if (isVideoFile(contentType, ext)) {
                String baseName = filename.contains(".")
                        ? filename.substring(0, filename.lastIndexOf('.'))
                        : filename;

                // 缩略图提取
                Path thumbPath = dirPath.resolve(baseName + "_thumb.jpg");
                videoService.extractThumbnail(filePath, thumbPath);

                // 转码（成功则替换原文件）
                if (storageProps.getVideo().getTranscode().isEnabled()) {
                    String transcodeExt = ".mp4"; // 统一输出 mp4
                    Path transcodedPath = dirPath.resolve(baseName + "_transcoded" + transcodeExt);
                    if (videoService.transcode(filePath, transcodedPath)) {
                        // 转码成功 → 删除原始文件，用转码文件替换
                        Files.delete(filePath);
                        Files.move(transcodedPath, filePath);
                        savedSize = Files.size(filePath);
                        savedContentType = "video/mp4";
                        log.info("视频转码完成: {} -> {} ({} bytes)", filename, filePath.getFileName(), savedSize);
                    }
                }
            }
        }

        UploadedFile uploadedFile = UploadedFile.builder()
                .filename(filename)
                .originalFilename(originalFilename)
                .uploaderId(userId)
                .contentType(savedContentType)
                .fileSize(savedSize)
                .filePath(filePath.toString())
                .build();
        uploadedFileRepository.save(uploadedFile);

        return storageProps.getUpload().getChatUrlPrefix() + "/" + conversationId + "/" + filename;
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