package com.kbook.dto;

import lombok.Data;

import java.util.List;

@Data
public class UpdateTagsRequest {
    private List<String> tags;
}
