package com.kbook.dto.request;

import lombok.Data;

@Data
public class TtsSynthesizeRequest {
    private String text;
    private Long configId;
}
