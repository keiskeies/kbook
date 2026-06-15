package com.kbook.common.util;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Fluent API 查询构建器 — 统一 WHERE 条件构建
 * <p>
 * 通过 {@code BaseRepository} 的便捷方法创建：
 * <pre>
 * bookRepository.query().where(Book::getStatus, eq("ACTIVE")).list();
 * bookRepository.delete().where(Book::getStatus, eq("DELETED")).execute();
 * bookRepository.query().where(Book::getStatus, eq("DELETED")).value();
 * bookRepository.query().where(Book::getEmail, eq("test@example.com")).exists();
 * bookRepository.update().where(Book::getStatus, eq("PENDING")).execute(e -> e.setStatus("ACTIVE"));
 * </pre>
 */
@SuppressWarnings({"unchecked", "rawtypes", "unused"})
public class QueryBuilder<T> {

    private final JpaSpecificationExecutor<T> specRepository;
    private final org.springframework.data.jpa.repository.JpaRepository<T, ?> crudRepository;
    private final List<PredicateBuilder> predicates = new ArrayList<>();
    private final List<String> orderFields = new ArrayList<>();
    private final List<Boolean> orderDescFlags = new ArrayList<>();

    /**
     * 构造函数
     *
     * @param specRepository JPA Specification 执行器，用于构建动态查询
     * @param crudRepository JPA CRUD 仓库，用于执行批量删除和保存操作
     */
    public QueryBuilder(JpaSpecificationExecutor<T> specRepository,
                        org.springframework.data.jpa.repository.JpaRepository<T, ?> crudRepository) {
        this.specRepository = specRepository;
        this.crudRepository = crudRepository;
    }

    // ==================== WHERE 条件 ====================

    /**
     * 添加 WHERE 条件（字符串字段名）
     *
     * @param field     字段名称
     * @param condition 条件表达式
     * @return 当前 QueryBuilder 实例，支持链式调用
     */
    public QueryBuilder<T> where(String field, Condition condition) {
        predicates.add(new PredicateBuilder(field, condition));
        return this;
    }

    /**
     * 添加 WHERE 条件（Lambda 表达式）
     *
     * @param fieldFn   Lambda 表达式，指向实体类的字段
     * @param condition 条件表达式
     * @param <R>       字段类型
     * @return 当前 QueryBuilder 实例，支持链式调用
     */
    public <R> QueryBuilder<T> where(SFunction<T, R> fieldFn, Condition condition) {
        return where(LambdaUtils.resolve(fieldFn), condition);
    }

    /**
     * 添加 AND 条件（字符串字段名）
     * <p>
     * 与 where() 功能相同，提供语义化别名
     *
     * @param field     字段名称
     * @param condition 条件表达式
     * @return 当前 QueryBuilder 实例，支持链式调用
     */
    public QueryBuilder<T> and(String field, Condition condition) {
        predicates.add(new PredicateBuilder(field, condition));
        return this;
    }

    /**
     * 添加 AND 条件（Lambda 表达式）
     * <p>
     * 与 where() 功能相同，提供语义化别名
     *
     * @param fieldFn   Lambda 表达式，指向实体类的字段
     * @param condition 条件表达式
     * @param <R>       字段类型
     * @return 当前 QueryBuilder 实例，支持链式调用
     */
    public <R> QueryBuilder<T> and(SFunction<T, R> fieldFn, Condition condition) {
        return and(LambdaUtils.resolve(fieldFn), condition);
    }

    // ==================== 排序 ====================

    /**
     * 添加升序排序（字符串字段名）
     *
     * @param field 排序字段名称
     * @return 当前 QueryBuilder 实例，支持链式调用
     */
    public QueryBuilder<T> orderBy(String field) {
        orderFields.add(field);
        orderDescFlags.add(false);
        return this;
    }

    /**
     * 添加升序排序（Lambda 表达式）
     *
     * @param fieldFn Lambda 表达式，指向实体类的字段
     * @param <R>     字段类型
     * @return 当前 QueryBuilder 实例，支持链式调用
     */
    public <R> QueryBuilder<T> orderBy(SFunction<T, R> fieldFn) {
        return orderBy(LambdaUtils.resolve(fieldFn));
    }

    /**
     * 添加降序排序（字符串字段名）
     *
     * @param field 排序字段名称
     * @return 当前 QueryBuilder 实例，支持链式调用
     */
    public QueryBuilder<T> orderByDesc(String field) {
        orderFields.add(field);
        orderDescFlags.add(true);
        return this;
    }

    /**
     * 添加降序排序（Lambda 表达式）
     *
     * @param fieldFn Lambda 表达式，指向实体类的字段
     * @param <R>     字段类型
     * @return 当前 QueryBuilder 实例，支持链式调用
     */
    public <R> QueryBuilder<T> orderByDesc(SFunction<T, R> fieldFn) {
        return orderByDesc(LambdaUtils.resolve(fieldFn));
    }

    // ==================== 查询操作 ====================

    /**
     * 查询所有符合条件的记录（无限制）
     *
     * @return 符合条件的实体列表
     */
    public List<T> list() {
        return specRepository.findAll(buildSpec(), buildPageable(Integer.MAX_VALUE)).getContent();
    }

    /**
     * 查询指定数量的记录
     *
     * @param limit 最大返回记录数
     * @return 符合条件的实体列表
     */
    public List<T> list(int limit) {
        return specRepository.findAll(buildSpec(), buildPageable(limit)).getContent();
    }

    /**
     * 分页查询
     *
     * @param pageNum  页码（从 0 开始）
     * @param pageSize 每页大小
     * @return 分页结果对象
     */
    public Page<T> page(int pageNum, int pageSize) {
        return specRepository.findAll(buildSpec(), buildPageable(pageNum, pageSize));
    }

    /**
     * 构建分页和排序配置
     *
     * @param pageNum  页码（从 0 开始）
     * @param pageSize 每页大小
     * @return Pageable 分页对象
     */
    private Pageable buildPageable(int pageNum, int pageSize) {
        if (orderFields.isEmpty()) {
            return PageRequest.of(pageNum, pageSize);
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (int i = 0; i < orderFields.size(); i++) {
            Sort.Order order = orderDescFlags.get(i)
                    ? Sort.Order.desc(orderFields.get(i))
                    : Sort.Order.asc(orderFields.get(i));
            orders.add(order);
        }
        return PageRequest.of(pageNum, pageSize, Sort.by(orders));
    }

    // ==================== 统计操作 ====================

    /**
     * 统计符合条件的记录数量
     *
     * @return 记录总数
     */
    public long value() {
        return specRepository.count(buildSpec());
    }

    // ==================== 判断操作 ====================

    /**
     * 判断是否存在符合条件的记录
     * <p>
     * 与 exists() 功能相同，提供语义化别名
     *
     * @return 存在返回 true，否则返回 false
     */
    public boolean valueAsBoolean() {
        return specRepository.exists(buildSpec());
    }

    /**
     * 判断是否存在符合条件的记录
     *
     * @return 存在返回 true，否则返回 false
     */
    public boolean exists() {
        return specRepository.exists(buildSpec());
    }

    // ==================== 删除操作 ====================

    /**
     * 批量删除符合查询条件的所有记录
     */
    public void execute() {
        List<T> records = specRepository.findAll(buildSpec(), PageRequest.of(0, Integer.MAX_VALUE)).getContent();
        if (!records.isEmpty()) {
            crudRepository.deleteAllInBatch(records);
        }
    }

    // ==================== 更新操作 ====================

    /**
     * 批量更新符合查询条件的所有记录
     *
     * @param setter 字段设置函数
     * @return 更新的记录数
     */
    public long execute(BiConsumer<T, Void> setter) {
        List<T> records = specRepository.findAll(buildSpec(), PageRequest.of(0, Integer.MAX_VALUE)).getContent();
        for (T record : records) {
            setter.accept(record, null);
        }
        if (!records.isEmpty()) {
            crudRepository.saveAll(records);
        }
        return records.size();
    }

    // ==================== 内部方法 ====================


    /**
     * 构建 JPA Specification 查询规范
     * <p>
     * 将所有添加的条件通过 AND 连接，生成最终的查询规范
     *
     * @return JPA Specification 对象
     */
    private org.springframework.data.jpa.domain.Specification<T> buildSpec() {
        return (root, query, cb) -> {
            if (predicates.isEmpty()) return cb.conjunction();
            Predicate[] array = predicates.stream()
                    .map(p -> p.build(root, cb))
                    .toArray(Predicate[]::new);
            return cb.and(array);
        };
    }

    /**
     * 构建分页配置（单参数版本）
     * <p>
     * 默认从第 0 页开始，限制最大数量为 1000
     *
     * @param limit 最大返回记录数
     * @return Pageable 分页对象
     */
    private Pageable buildPageable(int limit) {
        return buildPageable(0, Math.min(limit, 1000));
    }

    // ==================== 条件工厂方法 ====================

    /**
     * 等于条件
     *
     * @param value 比较值
     * @return 条件表达式
     */
    public static Condition eq(Object value) {
        return (root, cb, field) -> cb.equal(root.get(field), value);
    }

    /**
     * 不等于条件
     *
     * @param value 比较值
     * @return 条件表达式
     */
    public static Condition ne(Object value) {
        return (root, cb, field) -> cb.notEqual(root.get(field), value);
    }

    /**
     * 大于条件
     *
     * @param value 比较值
     * @return 条件表达式
     */
    public static Condition gt(Comparable value) {
        return (root, cb, field) -> cb.greaterThan(root.get(field), value);
    }

    /**
     * 大于等于条件
     *
     * @param value 比较值
     * @return 条件表达式
     */
    public static Condition ge(Comparable value) {
        return (root, cb, field) -> cb.greaterThanOrEqualTo(root.get(field), value);
    }

    /**
     * 小于条件
     *
     * @param value 比较值
     * @return 条件表达式
     */
    public static Condition lt(Comparable value) {
        return (root, cb, field) -> cb.lessThan(root.get(field), value);
    }

    /**
     * 小于等于条件
     *
     * @param value 比较值
     * @return 条件表达式
     */
    public static Condition le(Comparable value) {
        return (root, cb, field) -> cb.lessThanOrEqualTo(root.get(field), value);
    }

    /**
     * 模糊匹配条件（LIKE）
     *
     * @param value 匹配模式（可使用 % 通配符）
     * @return 条件表达式
     */
    public static Condition like(String value) {
        return (root, cb, field) -> cb.like(root.get(field), value);
    }

    /**
     * IN 条件（集合包含）
     *
     * @param values 值集合
     * @return 条件表达式
     */
    public static Condition in(Collection<?> values) {
        return (root, cb, field) -> root.get(field).in(values);
    }

    /**
     * IS NULL 条件
     *
     * @return 条件表达式
     */
    public static Condition isNull() {
        return (root, cb, field) -> cb.isNull(root.get(field));
    }

    /**
     * IS NOT NULL 条件
     *
     * @return 条件表达式
     */
    public static Condition isNotNull() {
        return (root, cb, field) -> cb.isNotNull(root.get(field));
    }

    /**
     * BETWEEN 条件（范围查询）
     *
     * @param from 起始值（包含）
     * @param to   结束值（包含）
     * @return 条件表达式
     */
    public static Condition between(Comparable from, Comparable to) {
        return (root, cb, field) -> cb.between(root.get(field), from, to);
    }

    /**
     * 条件函数式接口
     * <p>
     * 用于定义 JPA Criteria API 的查询条件
     */
    @FunctionalInterface
    public interface Condition {
        /**
         * 应用条件到查询
         *
         * @param root  查询根对象
         * @param cb    CriteriaBuilder 构建器
         * @param field 字段名称
         * @return Predicate 谓词对象
         */
        Predicate apply(Root<?> root, CriteriaBuilder cb, String field);
    }

    // ==================== 内部类 ====================

    /**
     * 谓词构建器内部记录类
     * <p>
     * 封装字段名和条件表达式，用于构建最终的 Predicate
     *
     * @param field     字段名称
     * @param condition 条件表达式
     */
    private record PredicateBuilder(String field, Condition condition) {

        /**
         * 构建 Predicate 对象
         *
         * @param root 查询根对象
         * @param cb   CriteriaBuilder 构建器
         * @return Predicate 谓词对象
         */
        Predicate build(Root<?> root, CriteriaBuilder cb) {
            return condition.apply(root, cb, field);
        }
    }
}
