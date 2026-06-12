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
public class QueryBuilder<T> {

    private final JpaSpecificationExecutor<T> specRepository;
    private final org.springframework.data.jpa.repository.JpaRepository<T, ?> crudRepository;
    private final List<PredicateBuilder<T>> predicates = new ArrayList<>();
    private final List<String> orderFields = new ArrayList<>();
    private final List<Boolean> orderDescFlags = new ArrayList<>();

    public QueryBuilder(JpaSpecificationExecutor<T> specRepository,
                        org.springframework.data.jpa.repository.JpaRepository<T, ?> crudRepository) {
        this.specRepository = specRepository;
        this.crudRepository = crudRepository;
    }

    // ==================== WHERE 条件 ====================

    public QueryBuilder<T> where(String field, Condition condition) {
        predicates.add(new PredicateBuilder<>(field, condition));
        return this;
    }

    public <R> QueryBuilder<T> where(SFunction<T, R> fieldFn, Condition condition) {
        return where(LambdaUtils.resolve(fieldFn), condition);
    }

    public QueryBuilder<T> and(String field, Condition condition) {
        predicates.add(new PredicateBuilder<>(field, condition));
        return this;
    }

    public <R> QueryBuilder<T> and(SFunction<T, R> fieldFn, Condition condition) {
        return and(LambdaUtils.resolve(fieldFn), condition);
    }

    // ==================== 排序 ====================

    public QueryBuilder<T> orderBy(String field) {
        orderFields.add(field);
        orderDescFlags.add(false);
        return this;
    }

    public <R> QueryBuilder<T> orderBy(SFunction<T, R> fieldFn) {
        return orderBy(LambdaUtils.resolve(fieldFn));
    }

    public QueryBuilder<T> orderByDesc(String field) {
        orderFields.add(field);
        orderDescFlags.add(true);
        return this;
    }

    public <R> QueryBuilder<T> orderByDesc(SFunction<T, R> fieldFn) {
        return orderByDesc(LambdaUtils.resolve(fieldFn));
    }

    // ==================== 查询操作 ====================

    public List<T> list() {
        return specRepository.findAll(buildSpec(), buildPageable(Integer.MAX_VALUE)).getContent();
    }

    public List<T> list(int limit) {
        return specRepository.findAll(buildSpec(), buildPageable(limit)).getContent();
    }

    public Page<T> page(int pageNum, int pageSize) {
        return specRepository.findAll(buildSpec(), buildPageable(pageNum, pageSize));
    }

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

    public long value() {
        return specRepository.count(buildSpec());
    }

    // ==================== 判断操作 ====================

    public boolean valueAsBoolean() {
        return specRepository.exists(buildSpec());
    }

    public boolean exists() {
        return specRepository.exists(buildSpec());
    }

    // ==================== 删除操作 ====================

    /**
     * 批量删除符合查询条件的所有记录
     *
     * @return 删除的记录数
     */
    public long execute() {
        List<T> records = specRepository.findAll(buildSpec(), PageRequest.of(0, Integer.MAX_VALUE)).getContent();
        if (!records.isEmpty()) {
            crudRepository.deleteAllInBatch(records);
        }
        return records.size();
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

    @SuppressWarnings("unchecked")
    private org.springframework.data.jpa.domain.Specification<T> buildSpec() {
        return (root, query, cb) -> {
            if (predicates.isEmpty()) return cb.conjunction();
            Predicate[] array = predicates.stream()
                    .map(p -> p.build((Root<?>) root, cb))
                    .toArray(Predicate[]::new);
            return cb.and(array);
        };
    }

    private Pageable buildPageable(int limit) {
        return buildPageable(0, Math.min(limit, 1000));
    }

    // ==================== 条件工厂方法 ====================

    public static Condition eq(Object value) {
        return (root, cb, field) -> cb.equal(root.get(field), value);
    }

    public static Condition ne(Object value) {
        return (root, cb, field) -> cb.notEqual(root.get(field), value);
    }

    public static Condition gt(Comparable value) {
        return (root, cb, field) -> cb.greaterThan(root.get(field), value);
    }

    public static Condition ge(Comparable value) {
        return (root, cb, field) -> cb.greaterThanOrEqualTo(root.get(field), value);
    }

    public static Condition lt(Comparable value) {
        return (root, cb, field) -> cb.lessThan(root.get(field), value);
    }

    public static Condition le(Comparable value) {
        return (root, cb, field) -> cb.lessThanOrEqualTo(root.get(field), value);
    }

    public static Condition like(String value) {
        return (root, cb, field) -> cb.like(root.get(field), value);
    }

    public static Condition in(Collection<?> values) {
        return (root, cb, field) -> root.get(field).in(values);
    }

    public static Condition isNull() {
        return (root, cb, field) -> cb.isNull(root.get(field));
    }

    public static Condition isNotNull() {
        return (root, cb, field) -> cb.isNotNull(root.get(field));
    }

    public static Condition between(Comparable from, Comparable to) {
        return (root, cb, field) -> cb.between(root.get(field), from, to);
    }

    @FunctionalInterface
    public interface Condition {
        Predicate apply(Root<?> root, CriteriaBuilder cb, String field);
    }

    // ==================== 内部类 ====================

    private static class PredicateBuilder<T> {
        final String field;
        final Condition condition;

        PredicateBuilder(String field, Condition condition) {
            this.field = field;
            this.condition = condition;
        }

        Predicate build(Root<?> root, CriteriaBuilder cb) {
            return condition.apply(root, cb, field);
        }
    }
}
