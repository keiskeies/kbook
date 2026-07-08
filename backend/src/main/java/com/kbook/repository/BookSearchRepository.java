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
     * 多字段全文搜索（标题/作者/简介/语义标签），带高亮
     * 用于前端搜索页 — 纯文本相关度排序
     */
    @Highlight(fields = {
            @HighlightField(name = "title"),
            @HighlightField(name = "author"),
            @HighlightField(name = "description")
    })
    @Query("{\"bool\": {\"should\": [" +
            "{\"match\": {\"title\": {\"query\": \"?0\", \"boost\": 3.0}}}," +
            "{\"match\": {\"author\": {\"query\": \"?0\", \"boost\": 2.0}}}," +
            "{\"match\": {\"conceptTags\": {\"query\": \"?0\", \"boost\": 2.5}}}," +
            "{\"match\": {\"readerNeedTags\": {\"query\": \"?0\", \"boost\": 2.0}}}," +
            "{\"match\": {\"targetReaderTags\": {\"query\": \"?0\", \"boost\": 1.5}}}," +
            "{\"match\": {\"description\": {\"query\": \"?0\", \"boost\": 1.0}}}" +
            "], \"minimum_should_match\": 1}}")
    Page<BookDocument> searchWithHighlight(String keyword, Pageable pageable);

    /**
     * 多字段全文搜索 + quality function_score（仅评分轻微加权）
     * 用于混合搜索召回 — 文本相关度主导，评分仅作轻微参考
     * 注：readCount 不作为排序依据，避免热门偏见埋没冷门好书和新书
     *     rating factor 较小，避免低分好书被埋（评分主观性强）
     */
    @Query("{\"function_score\": {\"query\": {\"bool\": {\"should\": [" +
            "{\"match\": {\"title\": {\"query\": \"?0\", \"boost\": 3.0}}}," +
            "{\"match\": {\"author\": {\"query\": \"?0\", \"boost\": 2.0}}}," +
            "{\"match\": {\"conceptTags\": {\"query\": \"?0\", \"boost\": 2.5}}}," +
            "{\"match\": {\"readerNeedTags\": {\"query\": \"?0\", \"boost\": 2.0}}}," +
            "{\"match\": {\"targetReaderTags\": {\"query\": \"?0\", \"boost\": 1.5}}}," +
            "{\"match\": {\"description\": {\"query\": \"?0\", \"boost\": 1.0}}}" +
            "], \"minimum_should_match\": 1}}, \"functions\": [" +
            "{\"weight\": 1}," +
            "{\"field_value_factor\": {\"field\": \"rating\", \"factor\": 0.05, \"modifier\": \"log1p\", \"missing\": 0}}" +
            "], \"score_mode\": \"sum\", \"boost_mode\": \"multiply\"}}")
    Page<BookDocument> searchForRecall(String keyword, Pageable pageable);

    /**
     * 搜索建议（前缀匹配标题）
     */
    @Query("{\"match_phrase_prefix\": {\"title\": \"?0\"}}")
    List<BookDocument> suggestByTitle(String keyword, Pageable pageable);
}
