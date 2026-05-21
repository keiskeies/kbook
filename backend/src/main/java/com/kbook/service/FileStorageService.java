package com.kbook.service;

import com.kbook.common.exception.BusinessException;
import com.kbook.config.properties.BookStorageProperties;
import com.kbook.entity.UploadedFile;
import com.kbook.repository.ChatMessageRepository;
import com.kbook.repository.ConversationRepository;
import com.kbook.repository.UploadedFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final UploadedFileRepository uploadedFileRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ConversationRepository conversationRepository;
    private final BookStorageProperties storageProps;

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

    private Resource loadFileResource(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new BusinessException("文件不存在");
        }
        return new FileSystemResource(path);
    }
}
