package com.kbook.repository;

import com.kbook.entity.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 评论数据访问层
 */
public interface CommentRepository extends JpaRepository<Comment, Long> {

    /** 查询书籍的顶级评论（parentId is null），按点赞数+时间排序 */
    @Query("SELECT c FROM Comment c WHERE c.bookId = :bookId AND c.chapterId IS NULL AND c.parentId IS NULL ORDER BY c.likeCount DESC, c.createdAt DESC")
    Page<Comment> findBookTopComments(@Param("bookId") Long bookId, Pageable pageable);

    /** 查询章节的顶级评论 */
    @Query("SELECT c FROM Comment c WHERE c.bookId = :bookId AND c.chapterId = :chapterId AND c.parentId IS NULL ORDER BY c.likeCount DESC, c.createdAt DESC")
    Page<Comment> findChapterTopComments(@Param("bookId") Long bookId, @Param("chapterId") String chapterId, Pageable pageable);

    /** 查询评论的回复列表 */
    List<Comment> findByParentIdOrderByCreatedAtAsc(Long parentId);

    /** 查询用户的评论列表 */
    Page<Comment> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** 统计书籍的评论数 */
    long countByBookIdAndChapterIdIsNull(Long bookId);

    /** 统计章节的评论数 */
    long countByBookIdAndChapterId(Long bookId, String chapterId);

    /** 高分书评（点赞数多的顶级评论） */
    @Query("SELECT c FROM Comment c WHERE c.chapterId IS NULL AND c.parentId IS NULL AND c.likeCount >= :minLikes ORDER BY c.likeCount DESC, c.createdAt DESC")
    Page<Comment> findTopRatedComments(@Param("minLikes") int minLikes, Pageable pageable);

    /** 查询用户对某本书的评论 */
    List<Comment> findByUserIdAndBookIdAndChapterIdIsNull(Long userId, Long bookId);
}
