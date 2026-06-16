package com.kbook.service.recommend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.entity.Book;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 书籍维度得分表管理服务
 * <p>
 * 将 Book.relevanceScores JSON 展开到独立的 book_dimension_scores 表中，
 * 供 SQL 批量评分使用，避免 JSON 解析开销。
 * <p>
 * 表通过 @PostConstruct 自动创建，无需 Flyway 迁移。
 */
@Slf4j
@Service
public class BookDimensionScoreService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** 所有可能的维度 key → 列名映射 */
    public static final Map<String, String> DIMENSION_COLUMNS = new ConcurrentHashMap<>();

    static {
        // 年龄
        DIMENSION_COLUMNS.put("0-9", "score_age_0_9");
        DIMENSION_COLUMNS.put("10-19", "score_age_10_19");
        DIMENSION_COLUMNS.put("20-29", "score_age_20_29");
        DIMENSION_COLUMNS.put("30-39", "score_age_30_39");
        DIMENSION_COLUMNS.put("40-49", "score_age_40_49");
        DIMENSION_COLUMNS.put("50-59", "score_age_50_59");
        DIMENSION_COLUMNS.put("60+", "score_age_60_plus");
        // 性别
        DIMENSION_COLUMNS.put("male", "score_gender_male");
        DIMENSION_COLUMNS.put("female", "score_gender_female");
        // 婚姻
        DIMENSION_COLUMNS.put("married", "score_married");
        DIMENSION_COLUMNS.put("unmarried", "score_unmarried");
        // 子女年龄段
        DIMENSION_COLUMNS.put("children_0_2", "score_children_0_2");
        DIMENSION_COLUMNS.put("children_3_6", "score_children_3_6");
        DIMENSION_COLUMNS.put("children_7_12", "score_children_7_12");
        DIMENSION_COLUMNS.put("children_13_17", "score_children_13_17");
        DIMENSION_COLUMNS.put("children_18_plus", "score_children_18_plus");
        DIMENSION_COLUMNS.put("no_children", "score_no_children");
        // MBTI (16)
        DIMENSION_COLUMNS.put("INTJ", "score_mbti_intj");
        DIMENSION_COLUMNS.put("INTP", "score_mbti_intp");
        DIMENSION_COLUMNS.put("ENTJ", "score_mbti_entj");
        DIMENSION_COLUMNS.put("ENTP", "score_mbti_entp");
        DIMENSION_COLUMNS.put("INFJ", "score_mbti_infj");
        DIMENSION_COLUMNS.put("INFP", "score_mbti_infp");
        DIMENSION_COLUMNS.put("ENFJ", "score_mbti_enfj");
        DIMENSION_COLUMNS.put("ENFP", "score_mbti_enfp");
        DIMENSION_COLUMNS.put("ISTJ", "score_mbti_istj");
        DIMENSION_COLUMNS.put("ISFJ", "score_mbti_isfj");
        DIMENSION_COLUMNS.put("ESTJ", "score_mbti_estj");
        DIMENSION_COLUMNS.put("ESFJ", "score_mbti_esfj");
        DIMENSION_COLUMNS.put("ISTP", "score_mbti_istp");
        DIMENSION_COLUMNS.put("ISFP", "score_mbti_isfp");
        DIMENSION_COLUMNS.put("ESTP", "score_mbti_estp");
        DIMENSION_COLUMNS.put("ESFP", "score_mbti_esfp");
        // 职业
        DIMENSION_COLUMNS.put("student", "score_occ_student");
        DIMENSION_COLUMNS.put("tech", "score_occ_tech");
        DIMENSION_COLUMNS.put("finance", "score_occ_finance");
        DIMENSION_COLUMNS.put("education", "score_occ_education");
        DIMENSION_COLUMNS.put("medical", "score_occ_medical");
        DIMENSION_COLUMNS.put("arts", "score_occ_arts");
        DIMENSION_COLUMNS.put("management", "score_occ_management");
        DIMENSION_COLUMNS.put("freelance", "score_occ_freelance");
        DIMENSION_COLUMNS.put("retired", "score_occ_retired");
        DIMENSION_COLUMNS.put("other", "score_occ_other");
        // 学历
        DIMENSION_COLUMNS.put("high_school", "score_edu_high_school");
        DIMENSION_COLUMNS.put("college", "score_edu_college");
        DIMENSION_COLUMNS.put("bachelor", "score_edu_bachelor");
        DIMENSION_COLUMNS.put("master", "score_edu_master");
        DIMENSION_COLUMNS.put("doctorate", "score_edu_doctorate");
        DIMENSION_COLUMNS.put("other_edu", "score_edu_other");
        // 创业意向
        DIMENSION_COLUMNS.put("entrepreneur_or_want", "score_ent_want");
        DIMENSION_COLUMNS.put("notInterested", "score_ent_not_interested");
        // 收入
        DIMENSION_COLUMNS.put("under_50k", "score_inc_under_50k");
        DIMENSION_COLUMNS.put("50k_150k", "score_inc_50k_150k");
        DIMENSION_COLUMNS.put("150k_300k", "score_inc_150k_300k");
        DIMENSION_COLUMNS.put("300k_500k", "score_inc_300k_500k");
        DIMENSION_COLUMNS.put("500k_1m", "score_inc_500k_1m");
        DIMENSION_COLUMNS.put("over_1m", "score_inc_over_1m");
        DIMENSION_COLUMNS.put("prefer_not_to_say", "score_inc_pnts");
        // 心情
        DIMENSION_COLUMNS.put("happy", "score_mood_happy");
        DIMENSION_COLUMNS.put("calm", "score_mood_calm");
        DIMENSION_COLUMNS.put("anxious", "score_mood_anxious");
        DIMENSION_COLUMNS.put("sad", "score_mood_sad");
        DIMENSION_COLUMNS.put("frustrated", "score_mood_frustrated");
        DIMENSION_COLUMNS.put("tired", "score_mood_tired");
        // 阅读意图
        DIMENSION_COLUMNS.put("growth", "score_intent_growth");
        DIMENSION_COLUMNS.put("comfort", "score_intent_comfort");
        DIMENSION_COLUMNS.put("escape", "score_intent_escape");
        DIMENSION_COLUMNS.put("excite", "score_intent_excite");
        DIMENSION_COLUMNS.put("insight", "score_intent_insight");
    }

    public BookDimensionScoreService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 启动时自动建表 + 自动补列 */
    @PostConstruct
    public void initTable() {
        // 1. 建表（首次）
        StringBuilder sql = new StringBuilder("""
            CREATE TABLE IF NOT EXISTS book_dimension_scores (
                book_id BIGINT PRIMARY KEY
        """);
        for (String col : DIMENSION_COLUMNS.values()) {
            sql.append(",\n  ").append(col).append(" DECIMAL(5,4) DEFAULT NULL");
        }
        sql.append(",\n  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");
        sql.append(",\n  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
        sql.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute(sql.toString());

        // 2. 自动补列：DIMENSION_COLUMNS 里新增的 key 自动 ADD COLUMN
        // 新增维度时只需在 map 加一行 + 重启，无需手动执行 DDL
        for (String col : DIMENSION_COLUMNS.values()) {
            try {
                jdbcTemplate.execute("ALTER TABLE book_dimension_scores ADD COLUMN "
                        + col + " DECIMAL(5,4) DEFAULT NULL");
            } catch (Exception e) {
                // 列已存在时 MySQL 会抛异常，这是预期行为，忽略
            }
        }

        log.info("表 book_dimension_scores 已就绪 ({} 维度列)", DIMENSION_COLUMNS.size());
    }

    /**
     * 将单本书的 relevanceScores JSON 同步到维度得分表
     */
    @Transactional
    public void syncFromBook(Book book) {
        if (book.getId() == null) return;
        String json = book.getRelevanceScores();
        if (json == null || json.isBlank()) {
            jdbcTemplate.update("DELETE FROM book_dimension_scores WHERE book_id = ?", book.getId());
            return;
        }
        upsert(book.getId(), json);
    }

    /**
     * 全量回填：将所有有 relevanceScores 的书籍同步到维度得分表
     */
    @Transactional
    public void syncAll(Iterable<Book> books) {
        int count = 0;
        for (Book book : books) {
            if (book.getRelevanceScores() != null && !book.getRelevanceScores().isBlank()) {
                upsert(book.getId(), book.getRelevanceScores());
                count++;
                if (count % 1000 == 0) {
                    log.info("维度得分回填进度: {}", count);
                }
            }
        }
        log.info("维度得分回填完成，共 {} 本", count);
    }

    private void upsert(Long bookId, String json) {
        try {
            JsonNode scores = objectMapper.readTree(json);
            StringBuilder sql = new StringBuilder("REPLACE INTO book_dimension_scores (book_id");
            StringBuilder vals = new StringBuilder(" VALUES (?");
            // 收集非空维度
            Iterator<Map.Entry<String, JsonNode>> fields = scores.fields();
            java.util.ArrayList<Map.Entry<String, String>> params = new java.util.ArrayList<>();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                String col = DIMENSION_COLUMNS.get(key);
                if (col == null) {
                    log.trace("未知维度 key: {}，跳过", key);
                    continue;
                }
                double val = entry.getValue().asDouble();
                if (val <= 0 && val >= 0) continue; // 跳过 0 值
                sql.append(", ").append(col);
                vals.append(", ?");
                params.add(Map.entry(col, String.valueOf(val)));
            }
            sql.append(", created_at)").append(vals).append(", NOW())");
            // 执行
            String finalSql = sql.toString();
            Object[] finalParams = new Object[params.size() + 1];
            finalParams[0] = bookId;
            for (int i = 0; i < params.size(); i++) {
                finalParams[i + 1] = params.get(i).getValue();
            }
            jdbcTemplate.update(finalSql, finalParams);
        } catch (Exception e) {
            log.warn("解析 relevanceScores 失败，bookId={}: {}", bookId, e.getMessage());
        }
    }

    public void deleteByBookId(Long bookId) {
        jdbcTemplate.update("DELETE FROM book_dimension_scores WHERE book_id = ?", bookId);
    }
}
