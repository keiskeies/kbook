package com.kbook.repository;

import com.kbook.document.BookDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.annotations.Highlight;
import org.springframework.data.elasticsearch.annotations.HighlightField;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

/**
 * 图书 ES Repository — 全文检索
 */
public interface BookSearchRepository extends ElasticsearchRepository<BookDocument, Long> {

    /**
     * 多字段全文搜索（标题/作者/简介），带高亮
     */
    @Highlight(fields = {
            @HighlightField(name = "title"),
            @HighlightField(name = "author"),
            @HighlightField(name = "description")
    })
    @Query("{\"bool\": {\"should\": [" +
            "{\"match\": {\"title\": {\"query\": \"?0\", \"boost\": 3.0}}}," +
            "{\"match\": {\"author\": {\"query\": \"?0\", \"boost\": 2.0}}}," +
            "{\"match\": {\"description\": {\"query\": \"?0\", \"boost\": 1.0}}}" +
            "], \"minimum_should_match\": 1}}")
    Page<BookDocument> searchWithHighlight(String keyword, Pageable pageable);

    /**
     * 按格式筛选
     */
    Page<BookDocument> findByFormat(String format, Pageable pageable);

    /**
     * 多字段搜索 + 格式筛选，带高亮
     */
    @Highlight(fields = {
            @HighlightField(name = "title"),
            @HighlightField(name = "author"),
            @HighlightField(name = "description")
    })
    @Query("{\"bool\": {\"should\": [" +
            "{\"match\": {\"title\": {\"query\": \"?0\", \"boost\": 3.0}}}," +
            "{\"match\": {\"author\": {\"query\": \"?0\", \"boost\": 2.0}}}," +
            "{\"match\": {\"description\": {\"query\": \"?0\", \"boost\": 1.0}}}" +
            "], \"minimum_should_match\": 1, \"filter\": [{\"term\": {\"format\": \"?1\"}}]}}")
    Page<BookDocument> searchWithFormat(String keyword, String format, Pageable pageable);

    /**
     * 搜索建议（前缀匹配标题）
     */
    @Query("{\"match_phrase_prefix\": {\"title\": \"?0\"}}")
    List<BookDocument> suggestByTitle(String keyword, Pageable pageable);
}
