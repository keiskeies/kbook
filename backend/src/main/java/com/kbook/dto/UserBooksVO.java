package com.kbook.dto;

import lombok.Data;

import java.util.List;

/**
 * 用户图书视图对象
 * 用于展示用户的图书列表，按阅读状态分组
 */
@Data
public class UserBooksVO {
    /** 正在阅读的图书列表 */
    private List<UserBookItem> readingBooks;
    /** 已读完的图书列表 */
    private List<UserBookItem> completedBooks;
}
