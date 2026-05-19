package com.kbook.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class UpdateTraitsRequest {
    private LocalDate birthday;
    private String gender;
    private Boolean married;
    private Boolean hasChildren;
    private String mbti;
    private String occupation;
    private String education;
    private String entrepreneurship;
    private String annualIncome;
}
