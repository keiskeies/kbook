package com.kbook.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TtsSynthesizeRequest {

    @NotBlank(message = "文本不能为空")
    @Size(max = 50_000, message = "单次合成文本不能超过 50000 字符")
    private String text;

    @Positive(message = "configId 必须为正数")
    private Long configId;
}
