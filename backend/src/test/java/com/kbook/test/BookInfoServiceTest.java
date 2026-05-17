package com.kbook.test;

import com.kbook.controller.HomeController;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.BookService;
import net.minidev.json.JSONArray;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest
@ActiveProfiles("test")
public class BookInfoServiceTest {

    @Autowired
    private BookService bookService;

    @Autowired
    private BookRepository bookRepository;

    @Test
    public void getTags() {
        List<Book> all = bookRepository.findAll();
        Map<String, Long> tagCount = new HashMap<>();

        for (Book book : all) {
            if (book.getFormatTags() == null || book.getFormatTags().isBlank()) continue;
            // 移除 JSON 数组符号和引号: ["a","b"] -> a,b
            String tags = book.getFormatTags().replaceAll("[\\[\\]\"]", "");
            for (String tag : tags.split("[,，]")) {
                String t = tag.trim();
                if (!t.isEmpty()) {
                    tagCount.merge(t, 1L, Long::sum);
                }
            }
        }

        List<HomeController.TagStat> list = tagCount.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(e -> HomeController.TagStat.builder()
                        .name(e.getKey())
                        .count(e.getValue())
                        .build())
                .filter(tagStat -> tagStat.getCount() > 1)
                .toList();

        System.out.println(JSONArray.toJSONString(list));
    }
}
