package com.kbook.common.util;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Order;
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

/**
 * Fluent API 查询构建器
 * <p>
 * 使用示例：
 * <pre>
 * List<Book> books = queryBuilder.query(bookRepository)
 *     .where("sessionId", eq(sessionId))
 *     .and("title", like("%关键词%"))
 *     .orderByDesc("createdAt")
 *     .list();
 * </pre>
 */
public class QueryBuilder<T> {

    private final JpaSpecificationExecutor<T> repository;
    private final List<PredicateBuilder<T>> predicates = new ArrayList<>();
    private final List<OrderBuilder<T>> orderBuilders = new ArrayList<>();

    public QueryBuilder(JpaSpecificationExecutor<T> repository) {
        this.repository = repository;
    }

    public QueryBuilder<T> where(String field, Condition condition) {
        predicates.add(new PredicateBuilder<>(field, condition));
        return this;
    }

    /**
     * Lambda 方式的 WHERE 条件
     * <p>
     * 使用示例：.where(Book::getSessionId, eq(sessionId))
     */
    public <R> QueryBuilder<T> where(SFunction<T, R> fieldFn, Condition condition) {
        return where(LambdaUtils.resolve(fieldFn), condition);
    }

    public QueryBuilder<T> and(String field, Condition condition) {
        predicates.add(new PredicateBuilder<>(field, condition));
        return this;
    }

    /**
     * Lambda 方式的 AND 条件
     * <p>
     * 使用示例：.and(Book::getTitle, like("%关键词%"))
     */
    public <R> QueryBuilder<T> and(SFunction<T, R> fieldFn, Condition condition) {
        return and(LambdaUtils.resolve(fieldFn), condition);
    }

    public QueryBuilder<T> orderBy(String field) {
        orderBuilders.add(new OrderBuilder<>(field, true));
        return this;
    }

    /**
     * Lambda 方式的 ORDER BY（升序）
     */
    public <R> QueryBuilder<T> orderBy(SFunction<T, R> fieldFn) {
        return orderBy(LambdaUtils.resolve(fieldFn));
    }

    public QueryBuilder<T> orderByDesc(String field) {
        orderBuilders.add(new OrderBuilder<>(field, false));
        return this;
    }

    /**
     * Lambda 方式的 ORDER BY（降序）
     */
    public <R> QueryBuilder<T> orderByDesc(SFunction<T, R> fieldFn) {
        return orderByDesc(LambdaUtils.resolve(fieldFn));
    }

    public List<T> list() {
        return repository.findAll(buildSpec(), buildPageable(Integer.MAX_VALUE)).getContent();
    }

    public List<T> list(int limit) {
        return repository.findAll(buildSpec(), buildPageable(limit)).getContent();
    }

    public Page<T> page(int pageNum, int pageSize) {
        return repository.findAll(buildSpec(), PageRequest.of(pageNum - 1, pageSize));
    }

    public long count() {
        return repository.count(buildSpec());
    }

    public boolean exists() {
        return repository.exists(buildSpec());
    }

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
        if (orderBuilders.isEmpty()) {
            return PageRequest.of(0, Math.min(limit, 1000));
        }
        Sort sort = Sort.unsorted();
        for (OrderBuilder<?> ob : orderBuilders) {
            sort = ob.isAsc ? sort.and(Sort.by(Sort.Direction.ASC, ob.field))
                    : sort.and(Sort.by(Sort.Direction.DESC, ob.field));
        }
        return PageRequest.of(0, Math.min(limit, 1000), sort);
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

    private static class OrderBuilder<T> {
        final String field;
        final boolean isAsc;

        OrderBuilder(String field, boolean isAsc) {
            this.field = field;
            this.isAsc = isAsc;
        }
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
}
