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
 * 评论点赞实体
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "comment_likes", uniqueConstraints = {
        @UniqueConstraint(name = "uk_comment_like", columnNames = {"comment_id", "user_id"})
})
public class CommentLike extends BaseEntity implements IMiddleEntity<Long, Long> {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 评论 ID */
    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    /** 点赞用户 ID */
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
