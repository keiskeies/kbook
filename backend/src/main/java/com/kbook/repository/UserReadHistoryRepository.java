package com.kbook.repository;

import com.kbook.entity.UserReadHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 用户阅读历史数据访问层
 */
public interface UserReadHistoryRepository extends BaseRepository<UserReadHistory, Long> {

    /**
     * 查询用户的所有阅读历史记录
     */
    List<UserReadHistory> findByUserId(Long userId);

    /**
     * 按用户和行为类型查询阅读历史记录
     */
    List<UserReadHistory> findByUserIdAndAction(Long userId, String action);

    /**
     * 查询用户对指定图书的特定行为记录
     */
    Optional<UserReadHistory> findByUserIdAndBookIdAndAction(Long userId, Long bookId, String action);

    /**
     * 获取用户已读完的图书ID列表
     */
    @Query("SELECT h.bookId FROM UserReadHistory h WHERE h.userId = :userId AND h.action = 'COMPLETE'")
    List<Long> findCompletedBookIdsByUserId(@Param("userId") Long userId);

    /**
     * 获取用户所有有交互的图书ID
     */
    @Query("SELECT DISTINCT h.bookId FROM UserReadHistory h WHERE h.userId = :userId")
    List<Long> findAllInteractedBookIdsByUserId(@Param("userId") Long userId);

    /**
     * 协同过滤：找到也读过指定图书的其他用户
     */
    @Query("SELECT DISTINCT h.userId FROM UserReadHistory h WHERE h.bookId IN :bookIds AND h.userId != :currentUserId")
    List<Long> findSimilarUsers(@Param("bookIds") List<Long> bookIds, @Param("currentUserId") Long currentUserId);

    /**
     * 获取指定用户们读过的所有图书（按权重降序），用于协同推荐
     */
    @Query("SELECT h.bookId, SUM(h.weight) as totalWeight FROM UserReadHistory h " +
           "WHERE h.userId IN :userIds AND h.bookId NOT IN :excludeBookIds " +
           "GROUP BY h.bookId ORDER BY totalWeight DESC")
    List<Object[]> findBookIdsByUserIdsExcluding(@Param("userIds") List<Long> userIds,
                                                   @Param("excludeBookIds") List<Long> excludeBookIds);

    /**
     * 删除用户对某本书的指定行为记录
     */
    void deleteByUserIdAndBookIdAndAction(Long userId, Long bookId, String action);
}
