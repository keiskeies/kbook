package com.kbook.service;

import com.kbook.dto.ChartRequestDTO;
import com.kbook.dto.ConditionDTO;
import com.kbook.entity.Book;
import com.kbook.common.enums.chart.CalcType;
import com.kbook.common.enums.chart.ColumnType;
import com.kbook.common.enums.chart.TimeDeltaEnum;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 图表统计工具支持类
 * <p>
 * 基于 JPA Criteria API 的动态聚合统计，参考 talking-mouse-server 的 ChartEntityToolSupport 设计。
 * <p>
 * 核心能力：
 * 1. 按时间维度聚合（小时/天/周/月/季度/年）
 * 2. 按字段维度分组（如按 format 分组统计）
 * 3. 多种计算方式（COUNT/SUM/AVG）
 * 4. 支持额外条件过滤
 * 5. 支持分组字段（多系列图表）
 * <p>
 * 返回结构：{分组值: {索引值: 数值}}，无分组时 key 为 null
 */
@Component
@Slf4j
public class ChartEntityToolSupport {

    @PersistenceContext
    private EntityManager entityManager;

    private static final String FIELD_NAME = "fieldName";
    private static final String FIELD_GROUP = "fieldGroup";
    private static final String FIELD_NUMBER = "fieldNumber";

    /** 星期映射（MySQL WEEKDAY 返回 0=周一 ~ 6=周日） */
    private static final Map<Integer, String> WEEK_DAY_MAP = Map.of(
            0, "周一", 1, "周二", 2, "周三", 3, "周四",
            4, "周五", 5, "周六", 6, "周日"
    );

    // ==================== 公开 API ====================

    /**
     * 获取实体图表统计数据
     *
     * @param chartRequestDTO 图表请求参数
     * @return {分组值: {索引值: 数值}}
     */
    public Map<String, Map<String, Double>> getEntityChartOptions(ChartRequestDTO chartRequestDTO) {
        return getEntityChartOptions(chartRequestDTO, 0);
    }

    /**
     * 获取实体图表统计数据
     *
     * @param chartRequestDTO 图表请求参数
     * @param maxResults 最大返回条数，0 表示不限制
     * @return {分组值: {索引值: 数值}}
     */
    public Map<String, Map<String, Double>> getEntityChartOptions(ChartRequestDTO chartRequestDTO, int maxResults) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = builder.createTupleQuery();
        Root<Book> root = query.from(Book.class);

        // 默认值处理
        String timeField = StringUtils.hasText(chartRequestDTO.getTimeField())
                ? chartRequestDTO.getTimeField() : "createdAt";
        if (chartRequestDTO.getCalcType() == null) {
            chartRequestDTO.setCalcType(CalcType.COUNT);
        }
        if (chartRequestDTO.getFieldType() == null) {
            chartRequestDTO.setFieldType(ColumnType.TIME);
        }

        // 构建条件：时间范围 + 额外条件
        List<ConditionDTO> conditions = chartRequestDTO.getConditions();
        if (conditions == null) {
            conditions = new ArrayList<>();
        }
        // 添加时间范围条件
        if (chartRequestDTO.getStart() != null || chartRequestDTO.getEnd() != null) {
            conditions.add(ConditionDTO.between(timeField,
                    chartRequestDTO.getStart(), chartRequestDTO.getEnd()));
        }

        List<Predicate> predicates = buildPredicates(root, builder, conditions);
        query.where(predicates.toArray(new Predicate[0]));

        // 聚合表达式
        Expression<?> indexNumber = getIndexNumber(chartRequestDTO, builder, root);

        // 分组查询
        if (StringUtils.hasText(chartRequestDTO.getGroupField())) {
            return getGroupEntityChartOptions(chartRequestDTO, builder, query, root, indexNumber);
        } else {
            return getNoGroupEntityChartOptions(chartRequestDTO, builder, query, root, indexNumber, maxResults);
        }
    }

    /**
     * 格式化图表数据为可读字符串
     *
     * @param data 图表数据
     * @param title 标题
     * @return 格式化后的字符串
     */
    public String formatChartResult(Map<String, Map<String, Double>> data, String title) {
        if (data == null || data.isEmpty()) {
            return title + "：暂无数据。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(title).append("：\n");

        for (Map.Entry<String, Map<String, Double>> groupEntry : data.entrySet()) {
            if (groupEntry.getKey() != null) {
                sb.append("【").append(groupEntry.getKey()).append("】\n");
            }
            for (Map.Entry<String, Double> entry : groupEntry.getValue().entrySet()) {
                String key = entry.getKey();
                if (key == null || "null".equals(key)) {
                    key = "(未知)";
                }
                Double value = entry.getValue();
                if (value == value.longValue()) {
                    sb.append(String.format("  %s: %.0f\n", key, value));
                } else {
                    sb.append(String.format("  %s: %.2f\n", key, value));
                }
            }
        }
        return sb.toString();
    }

    // ==================== 时间快捷方法 ====================

    /** 获取本周一的 00:00 */
    public static LocalDateTime getWeekStart() {
        return LocalDateTime.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toLocalDate().atStartOfDay();
    }

    /** 获取本周日的 23:59:59 */
    public static LocalDateTime getWeekEnd() {
        return LocalDateTime.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                .toLocalDate().atTime(23, 59, 59);
    }

    /** 获取本月的 1 号 00:00 */
    public static LocalDateTime getMonthStart() {
        return LocalDateTime.now().withDayOfMonth(1).toLocalDate().atStartOfDay();
    }

    /** 获取本月的最后一天 23:59:59 */
    public static LocalDateTime getMonthEnd() {
        return LocalDateTime.now().with(TemporalAdjusters.lastDayOfMonth())
                .toLocalDate().atTime(23, 59, 59);
    }

    /** 获取今年的 1 月 1 日 00:00 */
    public static LocalDateTime getYearStart() {
        return LocalDateTime.now().withDayOfYear(1).toLocalDate().atStartOfDay();
    }

    /** 获取今年的 12 月 31 日 23:59:59 */
    public static LocalDateTime getYearEnd() {
        return LocalDateTime.now().with(TemporalAdjusters.lastDayOfYear())
                .toLocalDate().atTime(23, 59, 59);
    }

    /** 获取最近 N 天的起始时间 */
    public static LocalDateTime getRecentDaysStart(int days) {
        return LocalDateTime.now().minusDays(days).toLocalDate().atStartOfDay();
    }

    /** 获取最近 N 个月的起始时间 */
    public static LocalDateTime getRecentMonthsStart(int months) {
        return LocalDateTime.now().minusMonths(months).withDayOfMonth(1).toLocalDate().atStartOfDay();
    }

    // ==================== 分组查询 ====================

    private Map<String, Map<String, Double>> getGroupEntityChartOptions(
            ChartRequestDTO chartRequestDTO, CriteriaBuilder builder,
            CriteriaQuery<Tuple> query, Root<Book> root, Expression<?> indexNumber) {

        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        Expression<?> indexGroup = root.get(chartRequestDTO.getGroupField()).as(String.class);

        Expression<String> index = getIndexExpression(chartRequestDTO, builder, root);

        query.multiselect(index.alias(FIELD_NAME), indexNumber.alias(FIELD_NUMBER), indexGroup.alias(FIELD_GROUP));
        query.groupBy(index, indexGroup);

        List<Tuple> list = entityManager.createQuery(query).getResultList();

        // 星期几的特殊处理
        if (ColumnType.TIME.equals(chartRequestDTO.getFieldType())
                && TimeDeltaEnum.WEEK_DAY.equals(chartRequestDTO.getFieldDelta())) {
            list.forEach(e -> {
                String gv = String.valueOf(e.get(2));
                result.computeIfAbsent(gv, k -> new LinkedHashMap<>())
                        .put(WEEK_DAY_MAP.getOrDefault(Integer.parseInt(e.get(0, String.class)), e.get(0, String.class)),
                                toDouble(e.get(1)));
            });
        } else {
            list.forEach(e -> {
                String gv = String.valueOf(e.get(2));
                result.computeIfAbsent(gv, k -> new LinkedHashMap<>())
                        .put(String.valueOf(e.get(0)), toDouble(e.get(1)));
            });
        }
        return result;
    }

    private Map<String, Map<String, Double>> getNoGroupEntityChartOptions(
            ChartRequestDTO chartRequestDTO, CriteriaBuilder builder,
            CriteriaQuery<Tuple> query, Root<Book> root, Expression<?> indexNumber, int maxResults) {

        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        Map<String, Double> rt = new LinkedHashMap<>();

        Expression<String> index = getIndexExpression(chartRequestDTO, builder, root);

        query.multiselect(index.alias(FIELD_NAME), indexNumber.alias(FIELD_NUMBER));
        query.groupBy(index);

        // 非时间字段按计数降序（排行榜），时间字段按时间升序（趋势图）
        if (ColumnType.FIELD.equals(chartRequestDTO.getFieldType())) {
            query.orderBy(builder.desc(indexNumber));
        } else {
            query.orderBy(builder.asc(index));
        }

        var typedQuery = entityManager.createQuery(query);
        if (maxResults > 0) {
            typedQuery.setMaxResults(maxResults);
        }

        List<Tuple> list = typedQuery.getResultList();

        if (ColumnType.TIME.equals(chartRequestDTO.getFieldType())
                && TimeDeltaEnum.WEEK_DAY.equals(chartRequestDTO.getFieldDelta())) {
            list.forEach(e -> rt.put(
                    WEEK_DAY_MAP.getOrDefault(Integer.parseInt(e.get(0, String.class)), e.get(0, String.class)),
                    toDouble(e.get(1))
            ));
        } else {
            list.forEach(e -> rt.put(String.valueOf(e.get(0)), toDouble(e.get(1))));
        }
        result.put(null, rt);
        return result;
    }

    // ==================== 表达式构建 ====================

    /**
     * 获取索引表达式（时间维度或字段维度）
     */
    private Expression<String> getIndexExpression(ChartRequestDTO chartRequestDTO,
                                                   CriteriaBuilder builder, Root<Book> root) {
        if (ColumnType.FIELD.equals(chartRequestDTO.getFieldType())) {
            return root.get(chartRequestDTO.getField()).as(String.class);
        } else {
            return getTimeIndex(builder, root, chartRequestDTO.getField(), chartRequestDTO.getFieldDelta());
        }
    }

    /**
     * 获取聚合表达式
     */
    private static Expression<?> getIndexNumber(ChartRequestDTO chartRequestDTO,
                                                 CriteriaBuilder builder, Root<Book> root) {
        return switch (chartRequestDTO.getCalcType()) {
            case SUM -> builder.sum(root.get(chartRequestDTO.getSumField()));
            case AVG -> builder.avg(root.get(chartRequestDTO.getSumField()));
            case COUNT -> builder.count(root);
        };
    }

    /**
     * 根据时间粒度构建 JPA Criteria 时间索引表达式
     * <p>
     * 使用 MySQL 的 DATE_FORMAT / WEEKDAY / QUARTER 函数
     */
    private Expression<String> getTimeIndex(CriteriaBuilder builder, Root<Book> root,
                                             String field, TimeDeltaEnum delta) {
        if (delta == null) {
            delta = TimeDeltaEnum.ALL_DAYS;
        }
        // 使用 builder.function 调用 MySQL 日期函数
        return switch (delta) {
            case HOUR -> builder.function("DATE_FORMAT", String.class,
                    root.get(field), builder.literal("%H"));
            case ALL_HOURS -> builder.function("DATE_FORMAT", String.class,
                    root.get(field), builder.literal("%Y-%m-%d %H"));
            case WEEK_DAY -> builder.function("WEEKDAY", String.class, root.get(field));
            case MONTH_DAYS -> builder.function("DATE_FORMAT", String.class,
                    root.get(field), builder.literal("%d"));
            case ALL_DAYS -> builder.function("DATE_FORMAT", String.class,
                    root.get(field), builder.literal("%Y-%m-%d"));
            case MONTH -> builder.function("DATE_FORMAT", String.class,
                    root.get(field), builder.literal("%m"));
            case ALL_MONTHS -> builder.function("DATE_FORMAT", String.class,
                    root.get(field), builder.literal("%Y-%m"));
            case QUARTER -> builder.function("QUARTER", String.class, root.get(field));
            case ALL_QUARTERS -> builder.function("CONCAT", String.class,
                    builder.function("DATE_FORMAT", String.class, root.get(field), builder.literal("%Y")),
                    builder.literal("-Q"),
                    builder.function("QUARTER", String.class, root.get(field)));
            case YEAR -> builder.function("DATE_FORMAT", String.class,
                    root.get(field), builder.literal("%Y"));
        };
    }

    // ==================== 条件构建 ====================

    /**
     * 将 ConditionDTO 列表转换为 JPA Predicate
     * 复用 DynamicQueryService 的条件构建逻辑
     */
    private List<Predicate> buildPredicates(Root<Book> root, CriteriaBuilder builder,
                                             List<ConditionDTO> conditions) {
        List<Predicate> predicates = new ArrayList<>();
        if (CollectionUtils.isEmpty(conditions)) {
            return predicates;
        }

        for (ConditionDTO cond : conditions) {
            String field = cond.getColumn();
            if (field == null || field.isBlank()) continue;

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
        Supplier<Expression<Comparable>> exprGetter = () -> (Expression<Comparable>) (Expression<?>) root.get(field);

        return switch (cond.getOp()) {
            case EQ -> values.isEmpty() ? builder.isNull(root.get(field))
                    : builder.equal(root.get(field), convertToFieldType(field, cond.firstValue()));
            case NE -> values.isEmpty() ? builder.isNotNull(root.get(field))
                    : builder.notEqual(root.get(field), convertToFieldType(field, cond.firstValue()));
            case GT -> values.isEmpty() ? builder.isNull(root.get(field))
                    : builder.greaterThan(exprGetter.get(), (Comparable) convertToFieldType(field, cond.firstValue()));
            case GE -> values.isEmpty() ? builder.isNull(root.get(field))
                    : builder.greaterThanOrEqualTo(exprGetter.get(), (Comparable) convertToFieldType(field, cond.firstValue()));
            case LT -> values.isEmpty() ? builder.isNull(root.get(field))
                    : builder.lessThan(exprGetter.get(), (Comparable) convertToFieldType(field, cond.firstValue()));
            case LE -> values.isEmpty() ? builder.isNull(root.get(field))
                    : builder.lessThanOrEqualTo(exprGetter.get(), (Comparable) convertToFieldType(field, cond.firstValue()));
            case LIKE -> values.isEmpty() ? builder.isNull(root.get(field))
                    : builder.like(builder.upper(root.get(field).as(String.class)),
                    "%" + cond.firstValue().toString().toUpperCase() + "%");
            case LL -> values.isEmpty() ? builder.isNull(root.get(field))
                    : builder.like(builder.upper(root.get(field).as(String.class)),
                    cond.firstValue().toString().toUpperCase() + "%");
            case LR -> values.isEmpty() ? builder.isNull(root.get(field))
                    : builder.like(builder.upper(root.get(field).as(String.class)),
                    "%" + cond.firstValue().toString().toUpperCase());
            case IN -> {
                if (values.isEmpty()) yield builder.isNull(root.get(field));
                List<Object> inValues = values.stream()
                        .map(v -> convertToFieldType(field, v)).collect(Collectors.toList());
                yield root.get(field).in(inValues);
            }
            case NI -> {
                if (values.isEmpty()) yield builder.isNotNull(root.get(field));
                List<Object> niValues = values.stream()
                        .map(v -> convertToFieldType(field, v)).collect(Collectors.toList());
                yield builder.not(root.get(field).in(niValues));
            }
            case BT -> {
                if (values.size() < 2) yield builder.isNull(root.get(field));
                Object minVal = convertToFieldType(field, values.get(0));
                Object maxVal = convertToFieldType(field, values.get(1));
                if (minVal == null && maxVal == null) yield builder.isNull(root.get(field));
                if (minVal == null) yield builder.lessThanOrEqualTo(exprGetter.get(), (Comparable) maxVal);
                if (maxVal == null) yield builder.greaterThanOrEqualTo(exprGetter.get(), (Comparable) minVal);
                yield builder.between(exprGetter.get(), (Comparable) minVal, (Comparable) maxVal);
            }
            case IS_NULL -> builder.isNull(root.get(field));
            case NOT_NULL -> builder.isNotNull(root.get(field));
            case OR_NULL -> values.isEmpty() ? builder.isNull(root.get(field))
                    : builder.or(
                    builder.equal(root.get(field), convertToFieldType(field, cond.firstValue())),
                    builder.isNull(root.get(field)));
        };
    }

    // ==================== 类型转换 ====================

    private Object convertToFieldType(String field, Object value) {
        if (value == null) return null;
        try {
            java.lang.reflect.Field declaredField = Book.class.getDeclaredField(field);
            Class<?> type = declaredField.getType();

            String strValue = value.toString().trim();
            if (type == String.class) return strValue;
            if (type == Long.class || type == long.class) return Long.parseLong(strValue);
            if (type == Integer.class || type == int.class) return Integer.parseInt(strValue);
            if (type == Double.class || type == double.class) return Double.parseDouble(strValue);
            if (type == Boolean.class || type == boolean.class)
                return "true".equalsIgnoreCase(strValue) || "1".equals(strValue);
            if (type == LocalDateTime.class) {
                if (value instanceof LocalDateTime) return value;
                return LocalDateTime.parse(strValue, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
            return strValue;
        } catch (NoSuchFieldException | NumberFormatException e) {
            return value;
        }
    }

    private Double toDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
