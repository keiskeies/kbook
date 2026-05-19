package com.kbook.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class RegisterRequest {
    @Email @NotBlank
    private String email;
    @NotBlank
    private String code;
    @NotBlank @Size(min = 6, max = 20, message = "密码长度应为6-20位")
    private String password;
    private LocalDate birthday;
    private String gender;
    private Boolean married;
    private Boolean hasChildren;
    private String mbti;
}
