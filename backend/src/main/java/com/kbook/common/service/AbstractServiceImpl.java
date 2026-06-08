package com.kbook.common.service;

import com.kbook.common.util.TransactionUtils;
import com.kbook.dto.stats.ChartRequestDTO;
import com.kbook.dto.stats.ConditionDTO;
import com.kbook.common.entity.IEntity;
import com.kbook.common.enums.chart.CalcType;
import com.kbook.common.enums.chart.ColumnType;
import com.kbook.common.enums.chart.TimeDeltaEnum;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 服务实现基类 — 统一的 CRUD + 动态查询 + 图表统计
 * <p>
 * 参考 talking-mouse-server 的 AbstractServiceImpl，精简掉：
 * - 部门权限（DepartmentTopAll / UnDepartment / RequestUtils.getDepartmentSql）
 * - 缓存过滤器（CacheEntityToolSupport / BloomFilter / MaxIdFilter）
 * - SFunction lambda 引用
 * - EntityFactory / SelfBeanUtils / DataTransformUtils
 * - addFieldNumberById（数值增量更新）
 * <p>
 * 保留钩子机制：
 * - dealData：保存/更新前处理
 * - dealSaveResult：保存成功后（事务提交后）处理 ES/Redis
 * - dealUpdateResult：更新成功后（事务提交后）处理 ES/Redis
 * - dealDeleteById：删除成功后（事务提交后）处理 ES/Redis
 * <p>
 * 融合 KBook 现有的 DynamicQueryService + ChartEntityToolSupport 逻辑为泛型版本。
 *
 * @param <T>   实体类型
 * @param <ID>  主键类型
 */
@Slf4j
public abstract class AbstractServiceImpl<T extends IEntity<ID>, ID>
        implements IService<T, ID> {

    /** JPA 实体管理器，用于构建 CriteriaQuery 动态查询 */
    @PersistenceContext
    protected EntityManager entityManager;
    /** JPA Repository 实例，提供基础的 CRUD 操作 */
    @Autowired
    protected JpaRepository<T, ID> jpaRepository;

    /** 当前服务所操作的实体 Class 类型（通过泛型反射获取） */
    protected final Class<T> tClass;
    /** 实体类的简单名称，用于日志输出 */
    protected final String tClassSimpleName;

    /**
     * 构造函数 — 通过反射获取泛型参数的 Class 类型
     * 子类无需手动指定 T 的 Class，框架自动推断
     */
    @SuppressWarnings("unchecked")
    public AbstractServiceImpl() {
        ParameterizedType parameterizedType = ((ParameterizedType) this.getClass().getGenericSuperclass());
        Type[] types = parameterizedType.getActualTypeArguments();
        this.tClass = (Class<T>) types[0];
        this.tClassSimpleName = this.tClass.getSimpleName();
    }

    // ==================== 钩子方法 ====================

    /**
     * 保存/更新前处理数据
     *
     * @param t 原始数据
     */
    protected void dealData(T t) {
    }

    /**
     * 保存成功后处理（事务提交后执行，用于处理 ES/Redis 等）
     *
     * @param saved 保存后的数据
     */
    protected void dealSaveResult(T saved) {
    }

    /**
     * 更新成功后处理（事务提交后执行，用于处理 ES/Redis 等）
     *
     * @param updated 更新后的数据
     */
    protected void dealUpdateResult(T updated) {
    }

    /**
     * 删除成功后处理（事务提交后执行，用于处理 ES/Redis 等）
     *
     * @param id 删除的 ID
     */
    protected void dealDeleteById(ID id) {
    }

    // ==================== 分页查询 ====================

    @Override
    @Transactional(readOnly = true)
    public Page<T> page(@Nonnull List<ConditionDTO> conditions, List<String> ascList, List<String> descList,
                        int page, int size) {
        Long count = getCount_(conditions);
        List<T> list = findList_(conditions, ascList, descList, page, size);
        return new PageImpl<>(list, PageRequest.of(page - 1, size), count);
    }

    // ==================== 单条查询 ====================

    @Override
    @Transactional(readOnly = true)
    public T findOneById(@Nonnull ID id) {
        return jpaRepository.findById(id).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public T findOne(@Nonnull List<ConditionDTO> conditions) {
        List<T> list = this.findList_(conditions, null, null, 1, 1);
        return CollectionUtils.isEmpty(list) ? null : list.get(0);
    }

    // ==================== 列表查询 ====================

    @Override
    @Transactional(readOnly = true)
    public List<T> findList() {
        return this.findList_(null, null, null, -1, -1);
    }

    @Override
    @Transactional(readOnly = true)
    public List<T> findList(@Nonnull List<ConditionDTO> conditions) {
        return this.findList_(conditions, null, null, -1, -1);
    }

    @Override
    @Transactional(readOnly = true)
    public List<T> findList(@Nonnull List<ConditionDTO> conditions, List<String> ascList, List<String> descList) {
        return this.findList_(conditions, ascList, descList, -1, -1);
    }

    @Override
    @Transactional(readOnly = true)
    public List<T> findList(@Nonnull List<ConditionDTO> conditions, List<String> ascList, List<String> descList,
                            int page, int size) {
        return this.findList_(conditions, ascList, descList, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public List<T> findListByIds(@Nonnull Collection<ID> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return new ArrayList<>();
        }
        return jpaRepository.findAllById(ids);
    }

    // ==================== 保存 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public T saveOne(@Nonnull T t) {
        log.info("{} saveOne() t: {}", tClassSimpleName, t);
        dealData(t);
        T saved = jpaRepository.saveAndFlush(t);
        TransactionUtils.afterCommit(() -> dealSaveResult(saved));
        return saved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<T> saveList(@Nonnull List<T> ts) {
        if (CollectionUtils.isEmpty(ts)) {
            return new ArrayList<>();
        }
        log.info("{} saveList() size: {}", tClassSimpleName, ts.size());
        for (T t : ts) {
            dealData(t);
        }
        List<T> savedList = jpaRepository.saveAllAndFlush(ts);
        TransactionUtils.afterCommit(() -> {
            for (T saved : savedList) {
                dealSaveResult(saved);
            }
        });
        return savedList;
    }

    // ==================== 更新 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public T updateOne(@Nonnull T t) {
        log.info("{} updateOne() t: {}", tClassSimpleName, t);
        dealData(t);
        T updated = jpaRepository.saveAndFlush(t);
        TransactionUtils.afterCommit(() -> dealUpdateResult(updated));
        return updated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public T updateFieldInfoById(@Nonnull ID id, @Nonnull String fieldName, Object value) {
        log.info("{} updateFieldInfoById() id: {}, field: {}, value: {}", tClassSimpleName, id, fieldName, value);
        T t = findOneById(id);
        if (t == null) {
            throw new RuntimeException("数据不存在: " + id);
        }
        try {
            Field field = tClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object newValue = convertToFieldType(fieldName, value);
            field.set(t, newValue);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("更新字段失败: " + fieldName, e);
        }
        dealData(t);
        T updated = jpaRepository.saveAndFlush(t);
        TransactionUtils.afterCommit(() -> dealUpdateResult(updated));
        return updated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public T updateFieldInfoById(@Nonnull ID id, @Nonnull Map<String, Object> fieldValueMap) {
        log.info("{} updateFieldInfoById() id: {}, fields: {}", tClassSimpleName, id, fieldValueMap.keySet());
        T t = findOneById(id);
        if (t == null) {
            throw new RuntimeException("数据不存在: " + id);
        }
        for (Map.Entry<String, Object> entry : fieldValueMap.entrySet()) {
            try {
                Field field = tClass.getDeclaredField(entry.getKey());
                field.setAccessible(true);
                Object newValue = convertToFieldType(entry.getKey(), entry.getValue());
                field.set(t, newValue);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException("更新字段失败: " + entry.getKey(), e);
            }
        }
        dealData(t);
        T updated = jpaRepository.saveAndFlush(t);
        TransactionUtils.afterCommit(() -> dealUpdateResult(updated));
        return updated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<T> updateList(@Nonnull List<T> ts) {
        if (CollectionUtils.isEmpty(ts)) {
            return new ArrayList<>();
        }
        log.info("{} updateList() size: {}", tClassSimpleName, ts.size());
        for (T t : ts) {
            dealData(t);
        }
        List<T> updatedList = jpaRepository.saveAllAndFlush(ts);
        TransactionUtils.afterCommit(() -> {
            for (T updated : updatedList) {
                dealUpdateResult(updated);
            }
        });
        return updatedList;
    }

    // ==================== 删除 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteOneById(@Nonnull ID id) {
        log.info("{} deleteOneById() id: {}", tClassSimpleName, id);
        jpaRepository.deleteById(id);
        TransactionUtils.afterCommit(() -> dealDeleteById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteListByIds(@Nonnull Collection<ID> ids) {
        log.info("{} deleteListByIds() ids: {}", tClassSimpleName, ids);
        jpaRepository.deleteAllByIdInBatch(new ArrayList<>(ids));
        TransactionUtils.afterCommit(() -> {
            for (ID id : ids) {
                dealDeleteById(id);
            }
        });
    }

    // ==================== 计数 / 存在性 ====================

    @Override
    public long getCount(@Nonnull List<ConditionDTO> conditions) {
        return getCount_(conditions);
    }

    @Override
    public boolean exist(@Nonnull List<ConditionDTO> conditions) {
        return getCount_(conditions) > 0;
    }

    // ==================== 图表统计 ====================

    @Override
    public Map<String, Map<String, Double>> getChartOptions(@Nonnull ChartRequestDTO chartRequestDTO) {
        return getChartOptions(chartRequestDTO, 0);
    }

    @Override
    public Map<String, Map<String, Double>> getChartOptions(@Nonnull ChartRequestDTO chartRequestDTO, int maxResults) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = builder.createTupleQuery();
        Root<T> root = query.from(tClass);

        // 默认值处理
        String timeField = StringUtils.hasText(chartRequestDTO.getTimeField())
                ? chartRequestDTO.getTimeField() : CREATE_TIME_NAME;
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
        if (chartRequestDTO.getStart() != null || chartRequestDTO.getEnd() != null) {
            conditions.add(ConditionDTO.between(timeField,
                    chartRequestDTO.getStart(), chartRequestDTO.getEnd()));
        }

        List<Predicate> predicates = buildPredicates(root, builder, conditions);
        query.where(predicates.toArray(new Predicate[0]));

        Expression<?> indexNumber = getIndexNumber(chartRequestDTO, builder, root);

        if (StringUtils.hasText(chartRequestDTO.getGroupField())) {
            return getGroupChartOptions(chartRequestDTO, builder, query, root, indexNumber);
        } else {
            return getNoGroupChartOptions(chartRequestDTO, builder, query, root, indexNumber, maxResults);
        }
    }

    // ==================== 内部查询方法 ====================

    /**
     * 核心列表查询方法
     */
    protected List<T> findList_(List<ConditionDTO> conditions, List<String> ascList, List<String> descList,
                                int page, int size) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = builder.createQuery(tClass);
        Root<T> root = query.from(tClass);

        List<Predicate> predicates = buildPredicates(root, builder, conditions);
        query.where(predicates.toArray(new Predicate[0]));
        query.orderBy(getOrders(root, builder, ascList, descList));

        if (page > 0 && size > 0) {
            int offset = (page - 1) * size;
            return entityManager.createQuery(query).setFirstResult(offset).setMaxResults(size).getResultList();
        }
        return entityManager.createQuery(query).getResultList();
    }

    /**
     * 核心计数方法
     */
    protected Long getCount_(List<ConditionDTO> conditions) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<T> root = query.from(tClass);

        List<Predicate> predicates = buildPredicates(root, builder, conditions);
        query.select(builder.count(root));
        query.where(predicates.toArray(new Predicate[0]));

        return entityManager.createQuery(query).getSingleResult();
    }

    // ==================== 条件构建 ====================

    /**
     * 将 ConditionDTO 列表转换为 JPA Predicate
     */
    protected List<Predicate> buildPredicates(Root<T> root, CriteriaBuilder builder,
                                              List<ConditionDTO> conditions) {
        List<Predicate> predicates = new ArrayList<>();
        if (CollectionUtils.isEmpty(conditions)) {
            return predicates;
        }
        for (ConditionDTO cond : conditions) {
            String field = cond.getColumn();
            if (field == null || field.isBlank()) continue;
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

    /**
     * 单个条件构建
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected Predicate buildPredicate(Root<T> root, CriteriaBuilder builder, ConditionDTO cond) {
        String field = cond.getColumn();
        List<Object> values = cond.getValues();
        java.util.function.Supplier<Expression<Comparable>> exprGetter =
                () -> (Expression<Comparable>) (Expression<?>) root.get(field);

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

    // ==================== 排序构建 ====================

    protected List<Order> getOrders(Root<T> root, CriteriaBuilder builder,
                                    List<String> ascList, List<String> descList) {
        List<Order> orders = new ArrayList<>();
        if (!CollectionUtils.isEmpty(descList)) {
            for (String field : descList) {
                if (isValidField(field)) {
                    orders.add(builder.desc(root.get(field)));
                }
            }
        }
        if (!CollectionUtils.isEmpty(ascList)) {
            for (String field : ascList) {
                if (isValidField(field)) {
                    orders.add(builder.asc(root.get(field)));
                }
            }
        }
        // 默认按创建时间倒序
        if (orders.isEmpty() && isValidField(CREATE_TIME_NAME)) {
            orders.add(builder.desc(root.get(CREATE_TIME_NAME)));
        }
        return orders;
    }

    // ==================== 图表统计内部方法 ====================

    private static final String FIELD_NAME = "fieldName";
    private static final String FIELD_NUMBER = "fieldNumber";
    private static final String FIELD_GROUP = "fieldGroup";

    /** 星期映射（MySQL WEEKDAY 返回 0=周一 ~ 6=周日） */
    private static final Map<Integer, String> WEEK_DAY_MAP = Map.of(
            0, "周一", 1, "周二", 2, "周三", 3, "周四",
            4, "周五", 5, "周六", 6, "周日"
    );

    private Map<String, Map<String, Double>> getGroupChartOptions(
            ChartRequestDTO chartRequestDTO, CriteriaBuilder builder,
            CriteriaQuery<Tuple> query, Root<T> root, Expression<?> indexNumber) {

        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        Expression<?> indexGroup = root.get(chartRequestDTO.getGroupField()).as(String.class);
        Expression<String> index = getIndexExpression(chartRequestDTO, builder, root);

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

    private Map<String, Map<String, Double>> getNoGroupChartOptions(
            ChartRequestDTO chartRequestDTO, CriteriaBuilder builder,
            CriteriaQuery<Tuple> query, Root<T> root, Expression<?> indexNumber, int maxResults) {

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

    /**
     * 获取索引表达式（时间维度或字段维度）
     */
    private Expression<String> getIndexExpression(ChartRequestDTO chartRequestDTO,
                                                   CriteriaBuilder builder, Root<T> root) {
        if (ColumnType.FIELD.equals(chartRequestDTO.getFieldType())) {
            return root.get(chartRequestDTO.getField()).as(String.class);
        } else {
            return getTimeIndex(builder, root, chartRequestDTO.getField(), chartRequestDTO.getFieldDelta());
        }
    }

    /**
     * 获取聚合表达式
     */
    private Expression<?> getIndexNumber(ChartRequestDTO chartRequestDTO,
                                          CriteriaBuilder builder, Root<T> root) {
        return switch (chartRequestDTO.getCalcType()) {
            case SUM -> builder.sum(root.get(chartRequestDTO.getSumField()));
            case AVG -> builder.avg(root.get(chartRequestDTO.getSumField()));
            case COUNT -> builder.count(root);
        };
    }

    /**
     * 根据时间粒度构建 JPA Criteria 时间索引表达式
     * 使用 MySQL 的 DATE_FORMAT / WEEKDAY / QUARTER 函数
     */
    private Expression<String> getTimeIndex(CriteriaBuilder builder, Root<T> root,
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

    /**
     * 格式化图表数据为可读字符串
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

    // ==================== 类型转换 ====================

    /**
     * 验证字段是否存在于实体类
     */
    protected boolean isValidField(String field) {
        if (field == null || field.isBlank()) return false;
        try {
            tClass.getDeclaredField(field);
            return true;
        } catch (NoSuchFieldException e) {
            // 尝试通过 getter 检查
            String getter = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            try {
                tClass.getMethod(getter);
                return true;
            } catch (NoSuchMethodException ex) {
                return false;
            }
        }
    }

    /**
     * 将值转换为字段类型
     */
    public Object convertToFieldType(String field, Object value) {
        if (value == null) return null;
        try {
            Field declaredField = tClass.getDeclaredField(field);
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
