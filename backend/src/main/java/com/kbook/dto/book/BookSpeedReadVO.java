package com.kbook.dto.book;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class BookSpeedReadVO {
    private Long bookId;
    private List<String> corePoints;
    private List<String> suitableFor;
    private List<String> notSuitableFor;
    private List<String> takeaways;
    private String difficulty;
    private String rawContent;
}
