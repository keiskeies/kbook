package com.kbook.dto.note;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 向自己的笔记提问
 */
@Data
public class NoteAskRequest {

    @NotBlank(message = "问题不能为空")
    @Size(max = 500, message = "问题不能超过 500 字")
    private String question;
}
