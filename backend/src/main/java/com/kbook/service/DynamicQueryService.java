package com.kbook.service;

import com.kbook.common.util.CommonUtils;
import com.kbook.dto.ConditionDTO;
import com.kbook.entity.Book;
import com.kbook.common.enums.ConditionEnum;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 动态查询服务 — 基于 JPA Criteria API 的通用增删改查工具
 * <p>
 * 提供灵活的查询条件组装能力，支持：
 * - 动态条件查询（EQ, NE, GT, GE, LT, LE, LIKE, LL, LR, IN, BT, IS_NULL, NOT_NULL）
 * - 动态排序（多字段组合）
 * - 动态更新（按条件批量更新指定字段）
 * - 动态删除（按条件批量删除）
 * - 动态统计（COUNT, SUM, AVG 等）
 * <p>
 * 设计参考：RequestUtils + AbstractServiceImpl 的条件组装模式
 */
@Slf4j
@Service
public class DynamicQueryService {

    /**
     * Book 实体允许 AI 动态更新的字段（排除敏感字段和关联字段）
     */
    private static final Set<String> UPDATABLE_FIELDS = Set.of(
            "title", "author", "description", "formatTags", "conceptTags",
            "readerNeedTags", "targetReaderTags", "toc", "chapterSummary",
            "coverUrl", "rating", "contentEmbedded"
    );

    @PersistenceContext
    private EntityManager entityManager;

    // ==================== 解析器 ====================

    /**
     * 解析条件字符串为 ConditionDTO 列表
     * <p>
     * 格式: field|op|value,field|op|value
     * 示例: title|LL|三体,rating|GE|4.0,format|EQ|EPUB
     */
    public List<ConditionDTO> parseConditions(String conditionStr) {
        if (!StringUtils.hasText(conditionStr)) {
            return List.of();
        }

        List<ConditionDTO> conditions = new ArrayList<>();
        String[] parts = conditionStr.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;

            String[] segments = part.split("\\|");
            if (segments.length < 2) {
                // 没有操作符，默认为模糊匹配
                conditions.add(new ConditionDTO("title", ConditionEnum.LIKE, segments[0].trim()));
                continue;
            }

            String field = segments[0].trim();
            String op = segments[1].trim();
            String value = segments.length > 2 ? segments[2].trim() : "";

            ConditionEnum opEnum = ConditionEnum.fromString(op);

            // 特殊处理 BT（区间）和 IN（多个值）
            if (opEnum == ConditionEnum.BT && segments.length > 2) {
                // 格式: field|BT|min,max
                String[] range = segments[2].split("~");
                if (range.length == 2) {
                    conditions.add(new ConditionDTO(field, opEnum, range[0].trim(), range[1].trim()));
                } else {
                    conditions.add(new ConditionDTO(field, opEnum, segments[2].trim(), ""));
                }
            } else if (opEnum == ConditionEnum.IN && segments.length > 2) {
                // 格式: field|IN|value1,value2,value3
                String[] values = segments[2].split(",");
                List<Object> valueList = Arrays.stream(values)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
                conditions.add(new ConditionDTO(field, opEnum, valueList.toArray()));
            } else if (opEnum == ConditionEnum.IS_NULL || opEnum == ConditionEnum.NOT_NULL) {
                conditions.add(new ConditionDTO(field, opEnum));
            } else {
                conditions.add(new ConditionDTO(field, opEnum, value));
            }
        }
        return conditions;
    }

    /**
     * 解析排序字符串
     * <p>
     * 格式: field,direction 或 field1,direction1;field2,direction2
     * 示例: rating,desc 或 createdAt,desc;rating,desc
     */
    public List<Order> parseSort(Root<Book> root, CriteriaBuilder builder, String sortStr) {
        List<Order> orders = new ArrayList<>();
        if (!StringUtils.hasText(sortStr)) {
            // 默认按创建时间倒序
            orders.add(builder.desc(root.get("createdAt")));
            return orders;
        }

        String[] parts = sortStr.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;

            String[] segments = part.split(",");
            String field = segments[0].trim();
            boolean desc = segments.length > 1 && "desc".equalsIgnoreCase(segments[1].trim());

            // 验证字段是否存在
            if (!isValidField(field)) {
                log.warn("排序字段无效: {}", field);
                continue;
            }

            if (desc) {
                orders.add(builder.desc(root.get(field)));
            } else {
                orders.add(builder.asc(root.get(field)));
            }
        }
        return orders;
    }

    /**
     * 解析更新字段字符串
     * <p>
     * 格式: field1=value1,field2=value2
     * 示例: author=金庸,description=新简介,rating=4.5
     */
    public Map<String, Object> parseUpdates(String updateStr) {
        Map<String, Object> updates = new LinkedHashMap<>();
        if (!StringUtils.hasText(updateStr)) {
            return updates;
        }

        String[] parts = updateStr.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty() || !part.contains("=")) continue;

            int eqIndex = part.indexOf("=");
            String field = part.substring(0, eqIndex).trim();
            String value = part.substring(eqIndex + 1).trim();

            if (field.isEmpty() || !UPDATABLE_FIELDS.contains(field)) {
                log.warn("字段不允许更新或不存在: {}", field);
                continue;
            }

            // 类型转换
            updates.put(field, convertToFieldType(field, value));
        }
        return updates;
    }

    // ==================== 核心查询方法 ====================

    /**
     * 动态查询列表
     */
    public List<Book> queryBooks(String conditionStr, String sortStr, int page, int limit) {
        List<ConditionDTO> conditions = parseConditions(conditionStr);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Book> query = builder.createQuery(Book.class);
        Root<Book> root = query.from(Book.class);

        List<Predicate> predicates = buildPredicates(root, builder, conditions);
        query.where(predicates.toArray(new Predicate[0]));
        query.orderBy(parseSort(root, builder, sortStr));

        // 分页
        if (page > 0 && limit > 0) {
            return entityManager.createQuery(query)
                    .setFirstResult((page - 1) * limit)
                    .setMaxResults(limit)
                    .getResultList();
        }
        return entityManager.createQuery(query).getResultList();
    }

    /**
     * 动态查询总数
     */
    public long countBooks(String conditionStr) {
        List<ConditionDTO> conditions = parseConditions(conditionStr);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<Book> root = query.from(Book.class);

        List<Predicate> predicates = buildPredicates(root, builder, conditions);
        query.select(builder.count(root));
        query.where(predicates.toArray(new Predicate[0]));

        return entityManager.createQuery(query).getSingleResult();
    }

    /**
     * 动态更新（按条件批量更新指定字段）
     */
    @Transactional
    public int updateBooks(String conditionStr, String updateStr) {
        Map<String, Object> updates = parseUpdates(updateStr);
        if (updates.isEmpty()) {
            return 0;
        }

        List<ConditionDTO> conditions = parseConditions(conditionStr);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Book> selectQuery = builder.createQuery(Book.class);
        Root<Book> root = selectQuery.from(Book.class);

        List<Predicate> predicates = buildPredicates(root, builder, conditions);
        selectQuery.where(predicates.toArray(new Predicate[0]));

        List<Book> books = entityManager.createQuery(selectQuery).getResultList();
        if (books.isEmpty()) {
            return 0;
        }

        for (Book book : books) {
            for (Map.Entry<String, Object> entry : updates.entrySet()) {
                setFieldValue(book, entry.getKey(), entry.getValue());
            }
            entityManager.merge(book);
        }

        entityManager.flush();
        log.info("动态更新图书: 条件={}, 更新={}, 影响行数={}", conditionStr, updates, books.size());
        return books.size();
    }

    /**
     * 动态删除（按条件批量删除）
     */
    @Transactional
    public int deleteBooks(String conditionStr) {
        List<ConditionDTO> conditions = parseConditions(conditionStr);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Book> selectQuery = builder.createQuery(Book.class);
        Root<Book> root = selectQuery.from(Book.class);

        List<Predicate> predicates = buildPredicates(root, builder, conditions);
        selectQuery.where(predicates.toArray(new Predicate[0]));

        List<Book> books = entityManager.createQuery(selectQuery).getResultList();
        if (books.isEmpty()) {
            return 0;
        }

        for (Book book : books) {
            entityManager.remove(book);
        }

        entityManager.flush();
        log.info("动态删除图书: 条件={}, 删除行数={}", conditionStr, books.size());
        return books.size();
    }

    // ==================== 辅助方法 ====================

    private List<Predicate> buildPredicates(Root<Book> root, CriteriaBuilder builder, List<ConditionDTO> conditions) {
        List<Predicate> predicates = new ArrayList<>();

        for (ConditionDTO cond : conditions) {
            String field = cond.getColumn();
            if (!isValidField(field)) {
                log.warn("查询字段无效: {}", field);
                continue;
            }

            Predicate predicate = buildPredicate(root, builder, cond);
            if (predicate != null) {
                predicates.add(predicate);
            }
        }
        return predicates;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Predicate buildPredicate(Root<Book> root, CriteriaBuilder builder, ConditionDTO cond) {
        String field = cond.getColumn();
        List<Object> values = cond.getValues();

        // 统一获取 Comparable 类型的表达式
        java.util.function.Supplier<Expression<Comparable>> exprGetter = () -> (Expression<Comparable>) (Expression<?>) root.get(field);

        switch (cond.getOp()) {
            case EQ:
                return values.isEmpty() ? builder.isNull(root.get(field))
                        : builder.equal(root.get(field), convertToFieldType(field, cond.firstValue()));
            case NE:
                return values.isEmpty() ? builder.isNotNull(root.get(field))
                        : builder.notEqual(root.get(field), convertToFieldType(field, cond.firstValue()));
            case GT:
                return values.isEmpty() ? builder.isNull(root.get(field))
                        : builder.greaterThan(exprGetter.get(), (Comparable) toNumber(cond.firstValue()));
            case GE:
                return values.isEmpty() ? builder.isNull(root.get(field))
                        : builder.greaterThanOrEqualTo(exprGetter.get(), (Comparable) toNumber(cond.firstValue()));
            case LT:
                return values.isEmpty() ? builder.isNull(root.get(field))
                        : builder.lessThan(exprGetter.get(), (Comparable) toNumber(cond.firstValue()));
            case LE:
                return values.isEmpty() ? builder.isNull(root.get(field))
                        : builder.lessThanOrEqualTo(exprGetter.get(), (Comparable) toNumber(cond.firstValue()));
            case LIKE:
                return values.isEmpty() ? builder.isNull(root.get(field))
                        : builder.like(builder.upper(root.get(field).as(String.class)),
                                "%" + cond.firstValue().toString().toUpperCase() + "%");
            case LL:
                return values.isEmpty() ? builder.isNull(root.get(field))
                        : builder.like(builder.upper(root.get(field).as(String.class)),
                                cond.firstValue().toString().toUpperCase() + "%");
            case LR:
                return values.isEmpty() ? builder.isNull(root.get(field))
                        : builder.like(builder.upper(root.get(field).as(String.class)),
                                "%" + cond.firstValue().toString().toUpperCase());
            case IN:
                if (values.isEmpty()) return builder.isNull(root.get(field));
                List<Object> inValues = values.stream()
                        .map(v -> convertToFieldType(field, v))
                        .collect(Collectors.toList());
                return root.get(field).in(inValues);
            case NI:
                if (values.isEmpty()) return builder.isNotNull(root.get(field));
                List<Object> niValues = values.stream()
                        .map(v -> convertToFieldType(field, v))
                        .collect(Collectors.toList());
                return builder.not(root.get(field).in(niValues));
            case BT:
                if (values.size() < 2) return builder.isNull(root.get(field));
                Object minVal = convertToFieldType(field, values.get(0));
                Object maxVal = convertToFieldType(field, values.get(1));
                if (minVal == null && maxVal == null) return builder.isNull(root.get(field));
                if (minVal == null) return builder.lessThanOrEqualTo(exprGetter.get(), (Comparable) maxVal);
                if (maxVal == null) return builder.greaterThanOrEqualTo(exprGetter.get(), (Comparable) minVal);
                return builder.between(exprGetter.get(), (Comparable) minVal, (Comparable) maxVal);
            case IS_NULL:
                return builder.isNull(root.get(field));
            case NOT_NULL:
                return builder.isNotNull(root.get(field));
            case OR_NULL:
                if (values.isEmpty()) return builder.isNull(root.get(field));
                return builder.or(
                        builder.equal(root.get(field), convertToFieldType(field, cond.firstValue())),
                        builder.isNull(root.get(field))
                );
            default:
                return null;
        }
    }

    private boolean isValidField(String field) {
        if (field == null || field.isBlank()) return false;
        try {
            Book.class.getDeclaredField(field);
            return true;
        } catch (NoSuchFieldException e) {
            // 尝试通过 getter 检查
            String getter = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            try {
                Book.class.getMethod(getter);
                return true;
            } catch (NoSuchMethodException ex) {
                return false;
            }
        }
    }

    private Object convertToFieldType(String field, Object value) {
        if (value == null || !StringUtils.hasText(value.toString())) return null;
        String strValue = value.toString().trim();

        try {
            Field declaredField = Book.class.getDeclaredField(field);
            Class<?> type = declaredField.getType();

            if (type == String.class) return strValue;
            if (type == Long.class || type == long.class) return Long.parseLong(strValue);
            if (type == Integer.class || type == int.class) return Integer.parseInt(strValue);
            if (type == Double.class || type == double.class) return Double.parseDouble(strValue);
            if (type == Boolean.class || type == boolean.class) {
                return "true".equalsIgnoreCase(strValue) || "1".equals(strValue);
            }
            if (type == LocalDateTime.class) {
                return LocalDateTime.parse(strValue, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
            return strValue;
        } catch (NoSuchFieldException | NumberFormatException e) {
            log.warn("字段类型转换失败: field={}, value={}", field, value);
            return strValue;
        }
    }

    private void setFieldValue(Book book, String field, Object value) {
        try {
            Field declaredField = Book.class.getDeclaredField(field);
            declaredField.setAccessible(true);
            declaredField.set(book, value);
        } catch (Exception e) {
            log.error("设置字段值失败: field={}, value={}", field, value, e);
        }
    }

    private Number toNumber(Object value) {
        if (value == null) return null;
        String str = value.toString().trim();
        if (str.isEmpty()) return null;
        try {
            if (str.contains(".")) {
                return Double.parseDouble(str);
            } else {
                return Long.parseLong(str);
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 格式化输出 ====================

    /**
     * 格式化图书列表为可读字符串
     */
    public String formatBookList(List<Book> books, String title) {
        if (books == null || books.isEmpty()) {
            return "没有找到图书。";
        }
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append(title).append(":\n");
        }
        for (int i = 0; i < books.size(); i++) {
            Book b = books.get(i);
            sb.append(String.format("%d. [BOOK:id=%d]《%s》 作者:%s 格式:%s 评分:%.1f 阅读:%d\n",
                    i + 1, b.getId(), b.getTitle(),
                    b.getAuthor() != null ? b.getAuthor() : "未知",
                    b.getFormat() != null ? b.getFormat() : "-",
                    b.getRating() != null ? b.getRating() : 0.0,
                    b.getReadCount() != null ? b.getReadCount() : 0));
            if (b.getFormatTags() != null && !b.getFormatTags().isBlank()) {
                sb.append("   标签: ").append(b.getFormatTags().replaceAll("[\\[\\]\"]", "").replace(",", "、")).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 格式化图书详情为可读字符串
     */
    public String formatBookDetail(Book b) {
        if (b == null) return "图书不存在。";
        StringBuilder sb = new StringBuilder();
        sb.append("[BOOK:id=").append(b.getId()).append("]《").append(b.getTitle()).append("》\n");
        sb.append("作者: ").append(b.getAuthor() != null ? b.getAuthor() : "未知").append("\n");
        sb.append("格式: ").append(b.getFormat() != null ? b.getFormat() : "-").append("\n");
        sb.append("评分: ").append(b.getRating() != null ? String.format("%.1f", b.getRating()) : "0.0").append("\n");
        sb.append("阅读次数: ").append(b.getReadCount() != null ? b.getReadCount() : 0).append("\n");
        if (b.getFileSize() != null) {
            sb.append("文件大小: ").append(CommonUtils.formatFileSize(b.getFileSize())).append("\n");
        }
        if (b.getFormatTags() != null && !b.getFormatTags().isBlank()) {
            sb.append("标签: ").append(b.getFormatTags().replaceAll("[\\[\\]\"]", "").replace(",", "、")).append("\n");
        }
        if (b.getDescription() != null && !b.getDescription().isBlank()) {
            sb.append("简介: ").append(CommonUtils.truncateText(b.getDescription(), 200)).append("\n");
        }
        if (b.getChapterSummary() != null && !b.getChapterSummary().isBlank()) {
            sb.append("章节摘要: ").append(CommonUtils.truncateText(b.getChapterSummary(), 200)).append("\n");
        }
        return sb.toString();
    }
}
