package com.kbook.dto;

import lombok.Data;

@Data
public class UpdateCoefficientRequest {
    private String category;
    private String key;
    private Double value;
    private Boolean locked;
}
