package com.kbook.entity;

import com.kbook.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

/**
 * 用户关注关系实体
 * follower 关注 following
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_follows", uniqueConstraints = {
        @UniqueConstraint(name = "uk_follow", columnNames = {"follower_id", "following_id"})
}, indexes = {
        @Index(name = "idx_follow_follower", columnList = "follower_id"),
        @Index(name = "idx_follow_following", columnList = "following_id")
})
public class UserFollow extends BaseEntity {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关注者 ID */
    @Column(name = "follower_id", nullable = false)
    private Long followerId;

    /** 被关注者 ID */
    @Column(name = "following_id", nullable = false)
    private Long followingId;

    @Override
    public Long getId() {
        return id;
    }
}
