package com.kbook.dto;

import lombok.Data;

import java.util.List;

@Data
public class UserBooksVO {
    private List<UserBookItem> readingBooks;
    private List<UserBookItem> completedBooks;
}
