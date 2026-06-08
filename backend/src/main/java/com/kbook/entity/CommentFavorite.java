package com.kbook.entity;

import com.kbook.common.entity.IMiddleEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

/**
 * 评论收藏实体
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "comment_favorites", uniqueConstraints = {
        @UniqueConstraint(name = "uk_comment_favorite", columnNames = {"comment_id", "user_id"})
})
public class CommentFavorite extends BaseEntity implements IMiddleEntity<Long, Long> {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 评论 ID */
    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    /** 收藏用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public Long getId1() {
        return commentId;
    }

    @Override
    public void setId1(Long commentId) {
        this.commentId = commentId;
    }

    @Override
    public Long getId2() {
        return userId;
    }

    @Override
    public void setId2(Long userId) {
        this.userId = userId;
    }
}
