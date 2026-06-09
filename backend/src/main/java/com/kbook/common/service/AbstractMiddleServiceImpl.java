package com.kbook.common.service;

import com.kbook.common.enums.ConditionEnum;
import com.kbook.common.util.TransactionUtils;
import com.kbook.dto.stats.ConditionDTO;
import com.kbook.common.entity.IMiddleEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 中间实体服务实现抽象类 — 提供基于两个ID的关联操作
 * <p>
 * 参考 talking-mouse-server 的 AbstractMiddleServiceImpl，精简掉：
 * - 部门权限检查（IgnorePermission 系列）
 * - SFunction lambda 引用
 * - 缓存初始化相关方法
 * <p>
 * 适配 KBook 使用 Long 类型主键
 *
 * @param <T>   中间实体类型
 * @param <ID1> 第一个实体的ID类型
 * @param <ID2> 第二个实体的ID类型
 */
@Slf4j
public abstract class AbstractMiddleServiceImpl<T extends IMiddleEntity<ID1, ID2>, ID1, ID2>
        extends AbstractServiceImpl<T, Long>
        implements IMiddleService<T, ID1, ID2> {

    /**
     * 自身服务实例，用于循环调用
     */
    @Autowired
    @Lazy
    protected AbstractMiddleServiceImpl<T, ID1, ID2> middleService;

    /**
     * ID1 的 Class类型
     */
    protected final Class<ID1> id1Class;

    /**
     * ID2 的 Class类型
     */
    protected final Class<ID2> id2Class;

    /**
     * ID1 对应的 JPA 持久化字段名（如 "userId"），用于 Criteria API 查询
     */
    protected final String id1FieldName;

    /**
     * ID2 对应的 JPA 持久化字段名（如 "bookId"），用于 Criteria API 查询
     */
    protected final String id2FieldName;

    /**
     * 构造函数，通过反射获取泛型参数的Class类型，并解析 id1/id2 对应的 JPA 字段名
     */
    @SuppressWarnings(value = {"unchecked"})
    public AbstractMiddleServiceImpl() {
        super();
        ParameterizedType parameterizedType = ((ParameterizedType) this.getClass().getGenericSuperclass());
        Type[] types = parameterizedType.getActualTypeArguments();
        this.id1Class = (Class<ID1>) types[1];
        this.id2Class = (Class<ID2>) types[2];
        this.id1FieldName = resolveFieldName("getId1");
        this.id2FieldName = resolveFieldName("getId2");
    }

    /**
     * 通过反射找到 getter 方法对应的实体 JPA 持久化字段名。
     * 原理：创建实体实例，给每个 Long 字段设唯一哨兵值，调用 getter 看返回值对应哪个字段。
     * 例如 Bookshelf: 设 userId=1, bookId=2 → getId1() 返回 1 → 映射到 "userId"
     */
    private String resolveFieldName(String getterName) {
        try {
            T instance = tClass.getDeclaredConstructor().newInstance();
            // 给所有 Long 字段（除 id 外）设置唯一哨兵值
            long counter = 1;
            Map<Long, String> valueToFieldName = new java.util.HashMap<>();
            for (Field f : tClass.getDeclaredFields()) {
                if ((f.getType() == Long.class || f.getType() == long.class) && !"id".equals(f.getName())) {
                    f.setAccessible(true);
                    f.set(instance, counter);
                    valueToFieldName.put(counter, f.getName());
                    counter++;
                }
            }
            // 调用 getter，根据返回值找到对应的字段名
            Method getter = tClass.getMethod(getterName);
            Object value = getter.invoke(instance);
            if (value instanceof Long && valueToFieldName.containsKey(value)) {
                return valueToFieldName.get(value);
            }
        } catch (Exception e) {
            log.warn("无法解析 {} 的 {} 对应 JPA 字段名", tClass.getSimpleName(), getterName, e);
        }
        // 回退到默认值
        return getterName.equals("getId1") ? "id1" : "id2";
    }

    /**
     * 根据ID1和ID2查找单个实体
     */
    @Override
    @Transactional(readOnly = true)
    public T findOneById(@Nonnull ID1 id1, @Nonnull ID2 id2) {
        return super.findOne(List.of(
                new ConditionDTO(id1FieldName, ConditionEnum.EQ, id1),
                new ConditionDTO(id2FieldName, ConditionEnum.EQ, id2)
        ));
    }

    /**
     * 根据ID1查找实体列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<T> findListById1(@Nonnull ID1 id1) {
        return super.findList(List.of(new ConditionDTO(id1FieldName, ConditionEnum.EQ, id1)));
    }

    /**
     * 根据ID1统计关联实体数量
     */
    @Override
    @Transactional(readOnly = true)
    public Long countById1(@Nonnull ID1 id1) {
        return super.getCount(List.of(new ConditionDTO(id1FieldName, ConditionEnum.EQ, id1)));
    }

    /**
     * 根据ID1集合查找实体列表
     */
    @Override
    public List<T> findListById1s(@Nonnull Collection<ID1> id1s) {
        if (CollectionUtils.isEmpty(id1s)) {
            return new ArrayList<>();
        }
        return middleService.findList(List.of(new ConditionDTO(id1FieldName, ConditionEnum.IN, id1s)));
    }

    /**
     * 根据ID2查找实体列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<T> findListById2(@Nonnull ID2 id2) {
        return super.findList(List.of(new ConditionDTO(id2FieldName, ConditionEnum.EQ, id2)));
    }

    /**
     * 根据ID2统计关联实体数量
     */
    @Override
    @Transactional(readOnly = true)
    public Long countById2(@Nonnull ID2 id2) {
        return super.getCount(List.of(new ConditionDTO(id2FieldName, ConditionEnum.EQ, id2)));
    }

    /**
     * 根据ID2集合查找实体列表
     */
    @Override
    public List<T> findListById2s(@Nonnull Collection<ID2> id2s) {
        if (CollectionUtils.isEmpty(id2s)) {
            return new ArrayList<>();
        }
        return middleService.findList(List.of(new ConditionDTO(id2FieldName, ConditionEnum.IN, id2s)));
    }

    /**
     * 根据ID1保存或更新实体列表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<T> saveOrUpdateById1(@Nonnull List<T> ts, @Nonnull ID1 id1) {
        ts.forEach(t -> t.setId1(id1));
        List<T> oldTs = middleService.findListById1(id1);
        return this.processDiffData(ts, oldTs);
    }

    /**
     * 根据ID1保存实体列表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<T> saveById1(@Nonnull List<T> ts, @Nonnull ID1 id1) {
        ts.forEach(t -> t.setId1(id1));
        return super.saveList(ts);
    }

    /**
     * 根据ID2保存或更新实体列表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<T> saveOrUpdateById2(@Nonnull List<T> ts, @Nonnull ID2 id2) {
        ts.forEach(t -> t.setId2(id2));
        List<T> oldTs = middleService.findListById2(id2);
        return this.processDiffData(ts, oldTs);
    }

    /**
     * 根据ID2保存实体列表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<T> saveById2(@Nonnull List<T> ts, @Nonnull ID2 id2) {
        ts.forEach(t -> t.setId2(id2));
        return super.saveList(ts);
    }

    /**
     * 根据ID1删除记录
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteById1(@Nonnull ID1 id1) {
        log.info("{} deleteById1() id1: {}", tClassSimpleName, id1);
        List<T> toDelete = middleService.findListById1(id1);
        if (!toDelete.isEmpty()) {
            List<Long> ids = toDelete.stream().map(T::getId).toList();
            super.deleteListByIds(ids);
        }
    }

    /**
     * 根据ID2删除记录
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteById2(@Nonnull ID2 id2) {
        log.info("{} deleteById2() id2: {}", tClassSimpleName, id2);
        List<T> toDelete = middleService.findListById2(id2);
        if (!toDelete.isEmpty()) {
            List<Long> ids = toDelete.stream().map(T::getId).toList();
            super.deleteListByIds(ids);
        }
    }

    /**
     * 检查指定ID组合的实体是否存在
     */
    @Override
    public Boolean exist(@Nonnull ID1 id1, @Nonnull ID2 id2) {
        return super.exist(List.of(
                new ConditionDTO(id1FieldName, ConditionEnum.EQ, id1),
                new ConditionDTO(id2FieldName, ConditionEnum.EQ, id2)
        ));
    }

    /**
     * 保存单个实体对象
     * 如果已存在相同ID1+ID2的实体则返回旧实体，否则保存新实体
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public T saveOne(@Nonnull T t) {
        log.info("{} saveOne() t: {}", tClassSimpleName, t);
        T old = middleService.findOneById(t.getId1(), t.getId2());
        if (old != null) {
            return old;
        }
        return super.saveOne(t);
    }

    /**
     * 批量保存实体列表（过滤已存在的）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<T> saveList(@Nonnull List<T> ts) {
        log.info("{} saveList() size: {}", tClassSimpleName, ts.size());
        List<T> newTs = new ArrayList<>();
        for (T t : ts) {
            if (!middleService.exist(t.getId1(), t.getId2())) {
                newTs.add(t);
            }
        }
        if (!newTs.isEmpty()) {
            return super.saveList(newTs);
        }
        return ts;
    }

    /**
     * 处理新旧数据差异，执行增删改操作
     */
    private List<T> processDiffData(List<T> newTs, List<T> oldTs) {
        List<T> result = new ArrayList<>();

        // 构建ID到实体的映射
        Map<Long, T> newMap = newTs.stream().collect(Collectors.toMap(T::getId, t -> t));
        Map<Long, T> oldMap = oldTs.stream().collect(Collectors.toMap(T::getId, t -> t));

        // 识别需要新增的数据
        List<T> toAdd = newTs.stream()
                .filter(t -> !oldMap.containsKey(t.getId()))
                .collect(Collectors.toList());

        // 识别需要更新的数据
        List<T> toUpdate = newTs.stream()
                .filter(t -> oldMap.containsKey(t.getId()))
                .collect(Collectors.toList());

        // 识别需要删除的数据
        List<T> toDelete = oldTs.stream()
                .filter(t -> !newMap.containsKey(t.getId()))
                .collect(Collectors.toList());

        log.info("{} saveOrUpdate() add: {}, update: {}, delete: {}",
                tClassSimpleName, toAdd.size(), toUpdate.size(), toDelete.size());

        if (!toAdd.isEmpty()) {
            jpaRepository.saveAllAndFlush(toAdd);
            TransactionUtils.afterCommit(() -> toAdd.forEach(this::dealSaveResult));
            result.addAll(toAdd);
        }

        if (!toUpdate.isEmpty()) {
            jpaRepository.saveAllAndFlush(toUpdate);
            TransactionUtils.afterCommit(() -> toUpdate.forEach(this::dealUpdateResult));
            result.addAll(toUpdate);
        }

        if (!toDelete.isEmpty()) {
            List<Long> deleteIds = toDelete.stream().map(T::getId).toList();
            jpaRepository.deleteAllByIdInBatch(deleteIds);
            TransactionUtils.afterCommit(() -> deleteIds.forEach(this::dealDeleteById));
        }

        return result;
    }
}