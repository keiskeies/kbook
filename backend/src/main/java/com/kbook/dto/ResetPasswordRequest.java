package com.kbook.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResetPasswordRequest {
    @Email @NotBlank
    private String email;
    @NotBlank
    private String code;
    @NotBlank @Size(min = 6, max = 20, message = "新密码长度应为6-20位")
    private String newPassword;
}
