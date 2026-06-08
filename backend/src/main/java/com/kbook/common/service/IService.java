package com.kbook.common.service;

import com.kbook.dto.ChartRequestDTO;
import com.kbook.dto.ConditionDTO;
import com.kbook.common.entity.IEntity;
import org.springframework.data.domain.Page;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 统一的 CRUD 服务接口
 * <p>
 * 参考 talking-mouse-server 的 IService，精简掉：
 * - 部门权限（IgnorePermission 系列）
 * - SFunction lambda 引用
 * - 缓存管理（initCacheData / deleteCacheData / reloadCache）
 * - 布隆过滤器 / 最大ID过滤器
 * - dealData / dealSaveResult / dealUpdateAround 等钩子
 * <p>
 * 保留核心能力：
 * - 单条/批量 查询、保存、更新、删除
 * - 动态条件查询（ConditionDTO）
 * - 分页查询
 * - 图表统计（ChartRequestDTO）
 *
 * @param <T>   实体类型，必须实现 IEntity 接口
 * @param <ID>  主键类型
 */
@SuppressWarnings("all")
public interface IService<T extends IEntity<ID>, ID> {

    String ID_NAME = "id";
    String CREATE_TIME_NAME = "createdAt";
    String UPDATE_TIME_NAME = "updatedAt";

    // ==================== 分页查询 ====================

    /**
     * 分页查询
     *
     * @param conditions 查询条件列表
     * @param ascList    升序排序字段列表
     * @param descList   降序排序字段列表
     * @param page       页码，从1开始
     * @param size       每页大小
     * @return 分页结果
     */
    Page<T> page(@Nonnull List<ConditionDTO> conditions, List<String> ascList, List<String> descList,
                 int page, int size);

    // ==================== 单条查询 ====================

    T findOneById(@Nonnull ID id);

    T findOne(@Nonnull List<ConditionDTO> conditions);

    // ==================== 列表查询 ====================

    List<T> findList();

    List<T> findList(@Nonnull List<ConditionDTO> conditions);

    List<T> findList(@Nonnull List<ConditionDTO> conditions, List<String> ascList, List<String> descList);

    List<T> findList(@Nonnull List<ConditionDTO> conditions, List<String> ascList, List<String> descList,
                     int page, int size);

    List<T> findListByIds(@Nonnull Collection<ID> ids);

    // ==================== 保存 ====================

    T saveOne(@Nonnull T t);

    List<T> saveList(@Nonnull List<T> ts);

    // ==================== 更新 ====================

    T updateOne(@Nonnull T t);

    T updateFieldInfoById(@Nonnull ID id, @Nonnull String fieldName, Object value);

    T updateFieldInfoById(@Nonnull ID id, @Nonnull Map<String, Object> fieldValueMap);

    List<T> updateList(@Nonnull List<T> ts);

    // ==================== 删除 ====================

    void deleteOneById(@Nonnull ID id);

    void deleteListByIds(@Nonnull Collection<ID> ids);

    // ==================== 计数 / 存在性 ====================

    long getCount(@Nonnull List<ConditionDTO> conditions);

    boolean exist(@Nonnull List<ConditionDTO> conditions);

    // ==================== 图表统计 ====================

    Map<String, Map<String, Double>> getChartOptions(@Nonnull ChartRequestDTO chartRequestDTO);

    Map<String, Map<String, Double>> getChartOptions(@Nonnull ChartRequestDTO chartRequestDTO, int maxResults);
}
