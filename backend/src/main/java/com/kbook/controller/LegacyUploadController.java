package com.kbook.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/uploads")
public class LegacyUploadController {

    @GetMapping("/chat/{conversationId}/{filename:.+}")
    public ResponseEntity<Void> redirectChatFile(
            @PathVariable Long conversationId,
            @PathVariable String filename) {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .location(URI.create("/api/chat/files/" + conversationId + "/" + filename))
                .build();
    }
}
