package com.kbook.service;

import com.kbook.common.exception.BusinessException;
import com.kbook.config.properties.BookStorageProperties;
import com.kbook.entity.UploadedFile;
import com.kbook.repository.ChatMessageRepository;
import com.kbook.repository.UploadedFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件存储服务
 * <p>
 * 负责聊天文件的访问控制与读取。用户只能访问自己上传的文件，
 * 或自己参与的会话中他人上传的文件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final UploadedFileRepository uploadedFileRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final BookStorageProperties storageProps;

    /**
     * 提供聊天文件的访问（含权限校验）
     * @param requestingUserId 请求者用户ID
     * @param filename 文件名
     * @return 文件资源
     */
    @Transactional(readOnly = true)
    public Resource serveChatFile(Long requestingUserId, String filename) {
        UploadedFile file = uploadedFileRepository.findByFilename(filename)
                .orElseThrow(() -> new BusinessException("文件不存在"));

        boolean isOwner = file.getUploaderId().equals(requestingUserId);
        if (isOwner) {
            return loadFileResource(file.getFilePath());
        }

        boolean hasAccess = chatMessageRepository.existsByFileUrlAndParticipants(
                storageProps.getUpload().getChatUrlPrefix() + "/" + filename,
                requestingUserId);

        if (!hasAccess) {
            throw new BusinessException("无权访问此文件");
        }

        return loadFileResource(file.getFilePath());
    }

    /** 从磁盘加载文件为 Resource */
    private Resource loadFileResource(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new BusinessException("文件不存在");
        }
        return new FileSystemResource(path);
    }
}
