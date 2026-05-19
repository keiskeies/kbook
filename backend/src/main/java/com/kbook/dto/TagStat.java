package com.kbook.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TagStat {
    private String name;
    private Long count;
}
