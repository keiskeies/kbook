package com.kbook.common.util;

import com.kbook.dto.stats.ConditionDTO;
import com.kbook.dto.stats.ChartRequestDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Component
public class RepositoryHelper {

    private final EntityManager entityManager;

    public RepositoryHelper(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    // ==================== 旧 API（保留兼容） ====================

    public <T> Page<T> page(List<ConditionDTO> conditions, List<String> ascFields,
                            List<String> descFields, Pageable pageable, 
                            JpaSpecificationExecutor<T> executor) {
        Specification<T> spec = buildSpecification(conditions);
        return executor.findAll(spec, pageable);
    }

    public <T> List<T> findList(List<ConditionDTO> conditions, List<String> ascFields, 
                                List<String> descFields, int page, int size,
                                JpaSpecificationExecutor<T> executor, Class<T> entityClass) {
        Specification<T> spec = buildSpecification(conditions);
        Pageable pageable = org.springframework.data.domain.PageRequest.of(page - 1, size);
        Page<T> result = executor.findAll(spec, pageable);
        return result.getContent();
    }

    public <T> List<T> findList(List<ConditionDTO> conditions,
                                JpaSpecificationExecutor<T> executor) {
        Specification<T> spec = buildSpecification(conditions);
        return executor.findAll(spec);
    }

    public <T> long getCount(List<ConditionDTO> conditions, JpaSpecificationExecutor<T> executor) {
        Specification<T> spec = buildSpecification(conditions);
        return executor.count(spec);
    }

    private <T> Specification<T> buildSpecification(List<ConditionDTO> conditions) {
        return (root, query, cb) -> {
            if (conditions == null || conditions.isEmpty()) {
                return cb.conjunction();
            }
            
            List<Predicate> predicates = new ArrayList<>();
            for (ConditionDTO condition : conditions) {
                Predicate predicate = buildPredicate(root, cb, condition);
                if (predicate != null) {
                    predicates.add(predicate);
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private <T> Predicate buildPredicate(Root<T> root, CriteriaBuilder cb, ConditionDTO condition) {
        if (condition == null || condition.getColumn() == null) {
            return null;
        }
        
        String field = condition.getColumn();
        String op = condition.getOp() != null ? condition.getOp().name() : "EQ";
        Object value = condition.firstValue();
        List<Object> values = condition.getValues();
        
        jakarta.persistence.criteria.Path<?> path = root.get(field);
        
        return switch (op) {
            case "EQ" -> cb.equal(path, value);
            case "NE" -> cb.notEqual(path, value);
            case "GT" -> cb.greaterThan(path.as(Comparable.class), (Comparable) value);
            case "LT" -> cb.lessThan(path.as(Comparable.class), (Comparable) value);
            case "GTE", "GE" -> cb.greaterThanOrEqualTo(path.as(Comparable.class), (Comparable) value);
            case "LTE", "LE" -> cb.lessThanOrEqualTo(path.as(Comparable.class), (Comparable) value);
            case "LIKE" -> cb.like(path.as(String.class), "%" + value + "%");
            case "IN" -> path.in(values);
            case "NI", "NOT_IN" -> cb.not(path.in(values));
            case "BT", "BETWEEN" -> {
                if (values.size() >= 2) {
                    yield cb.between(path.as(Comparable.class), 
                            (Comparable) values.get(0), (Comparable) values.get(1));
                }
                yield null;
            }
            case "IS_NULL" -> cb.isNull(path);
            case "NOT_NULL", "IS_NOT_NULL" -> cb.isNotNull(path);
            case "OR_NULL" -> cb.or(cb.isNull(path), cb.equal(path, value));
            default -> cb.equal(path, value);
        };
    }

    private Object convertToFieldType(String fieldName, Class<?> entityClass, Object value) {
        if (value == null) {
            return null;
        }
        
        try {
            Field field = entityClass.getDeclaredField(fieldName);
            Class<?> fieldType = field.getType();
            
            if (fieldType == Integer.class || fieldType == int.class) {
                return Integer.parseInt(value.toString());
            } else if (fieldType == Long.class || fieldType == long.class) {
                return Long.parseLong(value.toString());
            } else if (fieldType == Double.class || fieldType == double.class) {
                return Double.parseDouble(value.toString());
            } else if (fieldType == Boolean.class || fieldType == boolean.class) {
                return Boolean.parseBoolean(value.toString());
            } else if (fieldType == LocalDateTime.class) {
                return LocalDateTime.parse(value.toString());
            } else if (fieldType == LocalDate.class) {
                return LocalDate.parse(value.toString());
            }
        } catch (Exception e) {
            // ignore, return original value
        }
        
        return value;
    }

    public Map<String, Object> getChartOptions(ChartRequestDTO request, int limit) {
        Map<String, Object> result = new HashMap<>();
        result.put("field", request.getField());
        result.put("limit", limit);
        return result;
    }

    public String formatChartResult(Map<String, Map<String, Double>> data, String field) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Map<String, Double>> entry : data.entrySet()) {
            sb.append(entry.getKey()).append(": ");
            sb.append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }

    public Object convertToFieldType(String field, Object value) {
        return value;
    }

    public LocalDateTime getWeekStart() {
        return LocalDate.now().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                .atStartOfDay();
    }

    public LocalDateTime getWeekEnd() {
        return LocalDate.now().with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY))
                .atTime(LocalTime.MAX);
    }

    public LocalDateTime getMonthStart() {
        return LocalDate.now().with(TemporalAdjusters.firstDayOfMonth()).atStartOfDay();
    }

    public LocalDateTime getMonthEnd() {
        return LocalDate.now().with(TemporalAdjusters.lastDayOfMonth()).atTime(LocalTime.MAX);
    }

    public LocalDateTime getYearStart() {
        return LocalDate.now().with(TemporalAdjusters.firstDayOfYear()).atStartOfDay();
    }

    public LocalDateTime getYearEnd() {
        return LocalDate.now().with(TemporalAdjusters.lastDayOfYear()).atTime(LocalTime.MAX);
    }

    public LocalDateTime getRecentDaysStart(int days) {
        return LocalDateTime.now().minusDays(days);
    }

    public LocalDateTime getRecentMonthsStart(int months) {
        return LocalDateTime.now().minusMonths(months);
    }
}