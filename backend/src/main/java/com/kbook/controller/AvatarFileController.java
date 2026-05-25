package com.kbook.controller;

import com.kbook.common.util.CommonUtils;
import com.kbook.config.properties.BookStorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/uploads/avatars")
@RequiredArgsConstructor
public class AvatarFileController {

    private final BookStorageProperties storageProps;

    @GetMapping("/{filename:.+}")
    public ResponseEntity<Resource> getAvatar(@PathVariable String filename) {
        Path avatarDir = Paths.get(storageProps.getUpload().getAvatarDir());
        Path imagePath = CommonUtils.safeResolvePath(avatarDir, filename);

        if (imagePath == null || !Files.exists(imagePath)) {
            return ResponseEntity.notFound().build();
        }

        return CommonUtils.buildImageResponse(imagePath, filename);
    }
}
