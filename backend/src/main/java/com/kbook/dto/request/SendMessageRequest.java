package com.kbook.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendMessageRequest {

    private Long recipientId;

    private String content;

    private String messageType;

    private String fileName;

    private Long fileSize;

    private String fileUrl;

    private Integer voiceDuration;
}