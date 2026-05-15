package com.kbook.repository;

import com.kbook.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 用户数据访问层
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Page<User> findByStatus(String status, Pageable pageable);

    /**
     * 按状态分页查询（支持多状态筛选）
     */
    Page<User> findByStatusIn(List<String> statuses, Pageable pageable);

    /**
     * 按昵称/邮箱模糊搜索
     */
    @Query("SELECT u FROM User u WHERE " +
            "(:keyword IS NULL OR u.nickname LIKE %:keyword% OR u.email LIKE %:keyword%) AND " +
            "(:status IS NULL OR u.status = :status)")
    Page<User> searchUsers(@Param("keyword") String keyword,
                           @Param("status") String status,
                           Pageable pageable);

    /**
     * 统计各状态用户数
     */
    @Query("SELECT u.status, COUNT(u) FROM User u GROUP BY u.status")
    List<Object[]> countGroupByStatus();

    /**
     * 查询所有管理员
     */
    List<User> findByRole(String role);
}
