package com.kbook.service;

import com.kbook.common.exception.BusinessException;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.config.properties.BookStorageProperties;
import com.kbook.dto.response.ChatMessageVO;
import com.kbook.dto.response.ConversationVO;
import com.kbook.entity.ChatMessage;
import com.kbook.entity.Conversation;
import com.kbook.entity.UploadedFile;
import com.kbook.entity.User;
import com.kbook.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.*;
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

/**
 * 私信聊天服务
 * <p>
 * 提供用户之间的即时通讯功能，包括发送文本/图片/语音/文件消息、
 * 会话管理、消息历史查询、未读计数、文件上传（含图片压缩和视频转码）等。
 * 陌生人限制：未互关的用户30天内只能发送1条消息。
 */
@Slf4j
@Service
@LogModule("聊天")
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

    /** 陌生人消息限制：30天内最多发送条数 */
    private static final int MAX_STRANGER_MESSAGES = 1;

    /**
     * 发送消息（文本/图片/语音/文件）
     * @param senderId 发送者ID
     * @param recipientId 接收者ID
     * @param content 消息内容
     * @param messageType 消息类型
     * @param fileName 文件名（文件消息）
     * @param fileSize 文件大小（文件消息）
     * @param fileUrl 文件URL（文件消息）
     * @param voiceDuration 语音时长（语音消息）
     * @return 会话视图对象
     */
    @Transactional
    @LogAction("发送消息")
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

    /** 校验用户是否存在 */
    private void validateUserExists(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException("用户不存在");
        }
    }

    /** 陌生人消息限制检查：未互关用户30天内只能发1条消息 */
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

    /** 获取或创建两个用户之间的会话（user1Id < user2Id 保证唯一性） */
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

    /** 根据消息类型格式化最后一条消息的显示文本 */
    private String formatLastMessage(ChatMessage message) {
        return switch (message.getMessageType()) {
            case TEXT -> message.getContent();
            case IMAGE -> "[图片]";
            case VOICE -> "[语音]";
            case FILE -> "[文件]" + (message.getFileName() != null ? message.getFileName() : "");
        };
    }

    /**
     * 获取用户的所有会话列表
     * @param userId 用户ID
     * @return 会话视图对象列表
     */
    @LogAction("获取会话列表")
    public List<ConversationVO> getConversations(Long userId) {
        List<Conversation> conversations = conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        return conversations.stream()
                .map(c -> getConversationVO(c, userId))
                .collect(Collectors.toList());
    }

    /**
     * 搜索用户的会话（按昵称或最后消息内容过滤）
     * @param userId 用户ID
     * @param keyword 搜索关键词
     * @return 匹配的会话视图对象列表
     */
    @LogAction("搜索会话")
    public List<ConversationVO> searchConversations(Long userId, String keyword) {
        List<Conversation> conversations = conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        String lowerKeyword = keyword.toLowerCase();
        return conversations.stream()
                .map(c -> getConversationVO(c, userId))
                .filter(vo -> (vo.getOtherUserNickname() != null && vo.getOtherUserNickname().toLowerCase().contains(lowerKeyword))
                        || (vo.getLastMessage() != null && vo.getLastMessage().toLowerCase().contains(lowerKeyword)))
                .collect(Collectors.toList());
    }

    /** 将会话实体转换为视图对象，填充对方用户昵称和头像 */
    private ConversationVO getConversationVO(Conversation conversation, Long userId) {
        Long otherUserId = conversation.getUser1Id().equals(userId) ? conversation.getUser2Id() : conversation.getUser1Id();
        User otherUser = userRepository.findById(otherUserId).orElse(null);
        
        String nickname = otherUser != null ? otherUser.getNickname() : "未知用户";
        String avatar = otherUser != null && otherUser.getAvatar() != null ? otherUser.getAvatar() : "";

        return ConversationVO.fromEntity(conversation, userId, nickname, avatar);
    }

    /**
     * 获取会话的消息列表（支持分页加载）
     * @param userId 用户ID
     * @param conversationId 会话ID
     * @param beforeId 加载此ID之前的消息（用于向上翻页，null则加载最新）
     * @param limit 每页数量
     * @return 消息视图对象列表
     */
    @Transactional
    @LogAction("获取消息列表")
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

    /**
     * 标记会话中所有消息为已读
     * @param userId 用户ID
     * @param conversationId 会话ID
     */
    @Transactional
    @LogAction("标记已读")
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

    /**
     * 删除会话（仅对当前用户可见，对方不受影响）
     * @param userId 用户ID
     * @param conversationId 会话ID
     */
    @Transactional
    @LogAction("删除会话")
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

    /**
     * 获取用户的未读消息总数
     * @param userId 用户ID
     * @return 未读消息数
     */
    @LogAction("获取未读消息数")
    public Long getUnreadCount(Long userId) {
        return conversationRepository.sumUnreadCount(userId);
    }

    /**
     * 上传聊天文件（图片自动压缩为JPEG，视频可选转码）
     * @param userId 上传者ID
     * @param conversationId 会话ID
     * @param file 上传的文件
     * @return 文件访问URL
     */
    @LogAction("上传聊天文件")
    public String uploadChatFile(Long userId, Long conversationId, MultipartFile file) throws IOException {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new BusinessException("会话不存在"));

        if (!conversation.getUser1Id().equals(userId) && !conversation.getUser2Id().equals(userId)) {
            throw new BusinessException("无权访问此会话");
        }

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
            long originalSize = file.getSize();
            
            // 读取上传的图片
            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) {
                throw new BusinessException("无法解析图片文件");
            }
            
            // 如果文件大小超过 200KB，长宽压缩到原图的 1/2
            BufferedImage processedImage = image;
            if (originalSize > 200 * 1024L) {
                int newWidth = image.getWidth() / 2;
                int newHeight = image.getHeight() / 2;
                processedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = processedImage.createGraphics();
                g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.drawImage(image, 0, 0, newWidth, newHeight, null);
                g2d.dispose();
            } else {
                // JPEG 不支持透明通道，透明背景填充白色
                BufferedImage rgbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = rgbImage.createGraphics();
                g2d.setColor(Color.WHITE);
                g2d.fillRect(0, 0, rgbImage.getWidth(), rgbImage.getHeight());
                g2d.drawImage(image, 0, 0, null);
                g2d.dispose();
                processedImage = rgbImage;
            }

            // 根据原始文件大小决定压缩质量
            float compressionQuality = originalSize > 500 * 1024L ? 0.65f : 0.8f;

            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(compressionQuality);
            try (FileImageOutputStream output = new FileImageOutputStream(filePath.toFile())) {
                writer.setOutput(output);
                writer.write(null, new IIOImage(processedImage, null, null), params);
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

    /** 判断文件类型是否允许上传 */
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

    /** 判断是否为图片文件 */
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

    /** 判断是否为音频文件 */
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

    /** 判断是否为视频文件 */
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

    /**
     * 获取指定会话信息
     * @param userId 用户ID
     * @param conversationId 会话ID
     * @return 会话视图对象
     */
    @LogAction("获取会话信息")
    public ConversationVO getConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new BusinessException("会话不存在"));

        if (!conversation.getUser1Id().equals(userId) && !conversation.getUser2Id().equals(userId)) {
            throw new BusinessException("无权访问此会话");
        }

        return getConversationVO(conversation, userId);
    }

    /**
     * 发起或获取与指定用户的会话
     * @param userId 当前用户ID
     * @param recipientId 对方用户ID
     * @return 会话视图对象
     */
    @LogAction("创建会话")
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