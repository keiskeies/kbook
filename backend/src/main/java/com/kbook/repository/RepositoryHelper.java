package com.kbook.repository;

import com.kbook.dto.stats.ChartRequestDTO;
import com.kbook.dto.stats.ConditionDTO;
import com.kbook.entity.BaseEntity;
import com.kbook.common.enums.chart.CalcType;
import com.kbook.common.enums.chart.ColumnType;
import com.kbook.common.enums.chart.TimeDeltaEnum;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Repository 工具类
 * <p>
 * 提供动态条件查询、图表统计等通用功能，替代旧的 AbstractServiceImpl 中的相关逻辑。
 * 这些方法是静态的，可以在任何地方调用。
 *
 * @author KBook Team
 */
public class RepositoryHelper {

    private static final String ID_NAME = "id";
    private static final String CREATE_TIME_NAME = "createdAt";
    private static final String UPDATE_TIME_NAME = "updatedAt";

    private static final String FIELD_NAME = "fieldName";
    private static final String FIELD_NUMBER = "fieldNumber";
    private static final String FIELD_GROUP = "fieldGroup";

    private static final Map<Integer, String> WEEK_DAY_MAP = Map.of(
            0, "周一", 1, "周二", 2, "周三", 3, "周四",
            4, "周五", 5, "周六", 6, "周日"
    );

    // ==================== 分页查询 ====================

    /**
     * 分页查询（支持动态条件和排序）
     *
     * @param entityManager JPA 实体管理器
     * @param entityClass   实体类
     * @param conditions    查询条件列表
     * @param ascList       升序排序字段列表
     * @param descList      降序排序字段列表
     * @param page          页码（从1开始）
     * @param size          每页大小
     * @return 分页结果
     */
    public static <T extends BaseEntity> Page<T> page(EntityManager entityManager, Class<T> entityClass,
                                                       List<ConditionDTO> conditions, List<String> ascList,
                                                       List<String> descList, int page, int size) {
        Long count = getCount(entityManager, entityClass, conditions);
        List<T> list = findList(entityManager, entityClass, conditions, ascList, descList, page, size);
        return new PageImpl<>(list, PageRequest.of(page - 1, size), count);
    }

    /**
     * 根据条件查询单个实体
     */
    public static <T extends BaseEntity> T findOne(EntityManager entityManager, Class<T> entityClass,
                                                    List<ConditionDTO> conditions) {
        List<T> list = findList(entityManager, entityClass, conditions, null, null, 1, 1);
        return CollectionUtils.isEmpty(list) ? null : list.get(0);
    }

    /**
     * 根据条件查询列表（支持排序和分页）
     */
    public static <T extends BaseEntity> List<T> findList(EntityManager entityManager, Class<T> entityClass,
                                                          List<ConditionDTO> conditions, List<String> ascList,
                                                          List<String> descList, int page, int size) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = builder.createQuery(entityClass);
        Root<T> root = query.from(entityClass);

        List<Predicate> predicates = buildPredicates(root, builder, conditions, entityClass);
        query.where(predicates.toArray(new Predicate[0]));
        query.orderBy(getOrders(root, builder, ascList, descList, entityClass));

        if (page > 0 && size > 0) {
            int offset = (page - 1) * size;
            return entityManager.createQuery(query).setFirstResult(offset).setMaxResults(size).getResultList();
        }
        return entityManager.createQuery(query).getResultList();
    }

    /**
     * 获取记录总数
     */
    public static <T extends BaseEntity> Long getCount(EntityManager entityManager, Class<T> entityClass,
                                                       List<ConditionDTO> conditions) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<T> root = query.from(entityClass);

        List<Predicate> predicates = buildPredicates(root, builder, conditions, entityClass);
        query.select(builder.count(root));
        query.where(predicates.toArray(new Predicate[0]));

        return entityManager.createQuery(query).getSingleResult();
    }

    // ==================== 条件构建 ====================

    /**
     * 将 ConditionDTO 列表转换为 JPA Predicate
     */
    public static <T extends BaseEntity> List<Predicate> buildPredicates(Root<T> root, CriteriaBuilder builder,
                                                                          List<ConditionDTO> conditions, Class<T> entityClass) {
        List<Predicate> predicates = new ArrayList<>();
        if (CollectionUtils.isEmpty(conditions)) {
            return predicates;
        }
        for (ConditionDTO cond : conditions) {
            String field = cond.getColumn();
            if (field == null || field.isBlank()) continue;
            if (!isValidField(field, entityClass)) {
                continue;
            }
            Predicate predicate = buildPredicate(root, builder, cond, entityClass);
            if (predicate != null) {
                predicates.add(predicate);
            }
        }
        return predicates;
    }

    /**
     * 构建单个条件
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T extends BaseEntity> Predicate buildPredicate(Root<T> root, CriteriaBuilder builder,
                                                                  ConditionDTO cond, Class<T> entityClass) {
        String field = cond.getColumn();
        List<Object> values = cond.getValues();
        java.util.function.Supplier<Expression<Comparable>> exprGetter =
                () -> (Expression<Comparable>) (Expression<?>) root.get(field);

        return switch (cond.getOp()) {
            case EQ -> values.isEmpty() ? builder.isNull(root.get(field))
                    : builder.equal(root.get(field), convertToFieldType(field, values.get(0), entityClass));
            case NE -> values.isEmpty() ? builder.isNotNull(root.get(field))
                    : builder.notEqual(root.get(field), convertToFieldType(field, values.get(0), entityClass));
            case GT -> values.isEmpty() ? builder.isNull(root.get(field))
                    : builder.greaterThan(exprGetter.get(), (Comparable) convertToFieldType(field, values.get(0), entityClass));
            case GE -> values.isEmpty() ? builder.isNull(root.get(field))
                    : builder.greaterThanOrEqualTo(exprGetter.get(), (Comparable) convertToFieldType(field, values.get(0), entityClass));
            case LT -> values.isEmpty() ? builder.isNull(root.get(field))
                    : builder.lessThan(exprGetter.get(), (Comparable) convertToFieldType(field, values.get(0), entityClass));
            case LE -> values.isEmpty() ? builder.isNull(root.get(field))
                    : builder.lessThanOrEqualTo(exprGetter.get(), (Comparable) convertToFieldType(field, values.get(0), entityClass));
            case LIKE -> values.isEmpty() ? builder.isNull(root.get(field))
                    : builder.like(builder.upper(root.get(field).as(String.class)),
                    "%" + values.get(0).toString().toUpperCase() + "%");
            case LL -> values.isEmpty() ? builder.isNull(root.get(field))
                    : builder.like(builder.upper(root.get(field).as(String.class)),
                    values.get(0).toString().toUpperCase() + "%");
            case LR -> values.isEmpty() ? builder.isNull(root.get(field))
                    : builder.like(builder.upper(root.get(field).as(String.class)),
                    "%" + values.get(0).toString().toUpperCase());
            case IN -> {
                if (values.isEmpty()) yield builder.isNull(root.get(field));
                List<Object> inValues = values.stream()
                        .map(v -> convertToFieldType(field, v, entityClass)).collect(Collectors.toList());
                yield root.get(field).in(inValues);
            }
            case NI -> {
                if (values.isEmpty()) yield builder.isNotNull(root.get(field));
                List<Object> niValues = values.stream()
                        .map(v -> convertToFieldType(field, v, entityClass)).collect(Collectors.toList());
                yield builder.not(root.get(field).in(niValues));
            }
            case BT -> {
                if (values.size() < 2) yield builder.isNull(root.get(field));
                Object minVal = convertToFieldType(field, values.get(0), entityClass);
                Object maxVal = convertToFieldType(field, values.get(1), entityClass);
                if (minVal == null && maxVal == null) yield builder.isNull(root.get(field));
                if (minVal == null) yield builder.lessThanOrEqualTo(exprGetter.get(), (Comparable) maxVal);
                if (maxVal == null) yield builder.greaterThanOrEqualTo(exprGetter.get(), (Comparable) minVal);
                yield builder.between(exprGetter.get(), (Comparable) minVal, (Comparable) maxVal);
            }
            case IS_NULL -> builder.isNull(root.get(field));
            case NOT_NULL -> builder.isNotNull(root.get(field));
            case OR_NULL -> values.isEmpty() ? builder.isNull(root.get(field))
                    : builder.or(
                    builder.equal(root.get(field), convertToFieldType(field, values.get(0), entityClass)),
                    builder.isNull(root.get(field)));
        };
    }

    /**
     * 构建排序
     */
    public static <T extends BaseEntity> List<Order> getOrders(Root<T> root, CriteriaBuilder builder,
                                                               List<String> ascList, List<String> descList, Class<T> entityClass) {
        List<Order> orders = new ArrayList<>();
        if (!CollectionUtils.isEmpty(descList)) {
            for (String field : descList) {
                if (isValidField(field, entityClass)) {
                    orders.add(builder.desc(root.get(field)));
                }
            }
        }
        if (!CollectionUtils.isEmpty(ascList)) {
            for (String field : ascList) {
                if (isValidField(field, entityClass)) {
                    orders.add(builder.asc(root.get(field)));
                }
            }
        }
        if (orders.isEmpty() && isValidField(CREATE_TIME_NAME, entityClass)) {
            orders.add(builder.desc(root.get(CREATE_TIME_NAME)));
        }
        return orders;
    }

    // ==================== 图表统计 ====================

    /**
     * 获取图表统计数据
     */
    public static <T extends BaseEntity> Map<String, Map<String, Double>> getChartOptions(
            EntityManager entityManager, Class<T> entityClass, ChartRequestDTO chartRequestDTO) {
        return getChartOptions(entityManager, entityClass, chartRequestDTO, 0);
    }

    /**
     * 获取图表统计数据（带结果数量限制）
     */
    public static <T extends BaseEntity> Map<String, Map<String, Double>> getChartOptions(
            EntityManager entityManager, Class<T> entityClass, ChartRequestDTO chartRequestDTO, int maxResults) {

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = builder.createTupleQuery();
        Root<T> root = query.from(entityClass);

        String timeField = StringUtils.hasText(chartRequestDTO.getTimeField())
                ? chartRequestDTO.getTimeField() : CREATE_TIME_NAME;
        if (chartRequestDTO.getCalcType() == null) {
            chartRequestDTO.setCalcType(CalcType.COUNT);
        }
        if (chartRequestDTO.getFieldType() == null) {
            chartRequestDTO.setFieldType(ColumnType.TIME);
        }

        List<ConditionDTO> conditions = chartRequestDTO.getConditions();
        if (conditions == null) {
            conditions = new ArrayList<>();
        }
        if (chartRequestDTO.getStart() != null || chartRequestDTO.getEnd() != null) {
            conditions.add(ConditionDTO.between(timeField,
                    chartRequestDTO.getStart(), chartRequestDTO.getEnd()));
        }

        List<Predicate> predicates = buildPredicates(root, builder, conditions, entityClass);
        query.where(predicates.toArray(new Predicate[0]));

        Expression<?> indexNumber = getIndexNumber(chartRequestDTO, builder, root);

        if (StringUtils.hasText(chartRequestDTO.getGroupField())) {
            return getGroupChartOptions(entityManager, query, root, chartRequestDTO, indexNumber);
        } else {
            return getNoGroupChartOptions(entityManager, query, root, chartRequestDTO, indexNumber, maxResults);
        }
    }

    private static Expression<?> getIndexNumber(ChartRequestDTO chartRequestDTO,
                                                CriteriaBuilder builder, Root<?> root) {
        return switch (chartRequestDTO.getCalcType()) {
            case SUM -> builder.sum(root.get(chartRequestDTO.getSumField()));
            case AVG -> builder.avg(root.get(chartRequestDTO.getSumField()));
            case COUNT -> builder.count(root);
        };
    }

    private static Map<String, Map<String, Double>> getGroupChartOptions(
            EntityManager entityManager, CriteriaQuery<Tuple> query, Root<?> root,
            ChartRequestDTO chartRequestDTO, Expression<?> indexNumber) {

        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        Expression<?> indexGroup = root.get(chartRequestDTO.getGroupField()).as(String.class);
        Expression<String> index = getIndexExpression(entityManager, chartRequestDTO, query, root);

        query.multiselect(index.alias(FIELD_NAME), indexNumber.alias(FIELD_NUMBER), indexGroup.alias(FIELD_GROUP));
        query.groupBy(index, indexGroup);

        List<Tuple> list = entityManager.createQuery(query).getResultList();

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

    private static Map<String, Map<String, Double>> getNoGroupChartOptions(
            EntityManager entityManager, CriteriaQuery<Tuple> query, Root<?> root,
            ChartRequestDTO chartRequestDTO, Expression<?> indexNumber, int maxResults) {

        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        Map<String, Double> rt = new LinkedHashMap<>();

        Expression<String> index = getIndexExpression(entityManager, chartRequestDTO, query, root);

        query.multiselect(index.alias(FIELD_NAME), indexNumber.alias(FIELD_NUMBER));
        query.groupBy(index);

        if (ColumnType.FIELD.equals(chartRequestDTO.getFieldType())) {
            query.orderBy(((CriteriaBuilder) query.getRestriction().getExpressions().get(0)).desc(indexNumber));
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

    private static Expression<String> getIndexExpression(EntityManager entityManager,
                                                         ChartRequestDTO chartRequestDTO,
                                                         CriteriaQuery<?> query, Root<?> root) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();

        if (ColumnType.FIELD.equals(chartRequestDTO.getFieldType())) {
            return root.get(chartRequestDTO.getField()).as(String.class);
        } else {
            return getTimeIndex(builder, root, chartRequestDTO.getField(), chartRequestDTO.getFieldDelta());
        }
    }

    private static Expression<String> getTimeIndex(CriteriaBuilder builder, Root<?> root,
                                                   String field, TimeDeltaEnum delta) {
        if (delta == null) {
            delta = TimeDeltaEnum.ALL_DAYS;
        }
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

    // ==================== 辅助方法 ====================

    /**
     * 验证字段是否存在于实体类
     */
    public static <T extends BaseEntity> boolean isValidField(String field, Class<T> entityClass) {
        if (field == null || field.isBlank()) return false;
        try {
            entityClass.getDeclaredField(field);
            return true;
        } catch (NoSuchFieldException e) {
            String getter = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            try {
                entityClass.getMethod(getter);
                return true;
            } catch (NoSuchMethodException ex) {
                return false;
            }
        }
    }

    /**
     * 将值转换为字段类型
     */
    public static <T extends BaseEntity> Object convertToFieldType(String field, Object value, Class<T> entityClass) {
        if (value == null) return null;
        try {
            Field declaredField = entityClass.getDeclaredField(field);
            Class<?> type = declaredField.getType();
            String strValue = value.toString().trim();
            if (!StringUtils.hasText(strValue)) return null;

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

    private static Double toDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * 格式化图表数据为可读字符串
     */
    public static String formatChartResult(Map<String, Map<String, Double>> data, String title) {
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

    public static LocalDateTime getWeekStart() {
        return LocalDateTime.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toLocalDate().atStartOfDay();
    }

    public static LocalDateTime getWeekEnd() {
        return LocalDateTime.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                .toLocalDate().atTime(23, 59, 59);
    }

    public static LocalDateTime getMonthStart() {
        return LocalDateTime.now().withDayOfMonth(1).toLocalDate().atStartOfDay();
    }

    public static LocalDateTime getMonthEnd() {
        return LocalDateTime.now().with(TemporalAdjusters.lastDayOfMonth())
                .toLocalDate().atTime(23, 59, 59);
    }

    public static LocalDateTime getYearStart() {
        return LocalDateTime.now().withDayOfYear(1).toLocalDate().atStartOfDay();
    }

    public static LocalDateTime getYearEnd() {
        return LocalDateTime.now().with(TemporalAdjusters.lastDayOfYear())
                .toLocalDate().atTime(23, 59, 59);
    }

    public static LocalDateTime getRecentDaysStart(int days) {
        return LocalDateTime.now().minusDays(days).toLocalDate().atStartOfDay();
    }

    public static LocalDateTime getRecentMonthsStart(int months) {
        return LocalDateTime.now().minusMonths(months).withDayOfMonth(1).toLocalDate().atStartOfDay();
    }
}