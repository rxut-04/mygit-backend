package com.mygit.backend.repository;

import com.mygit.backend.model.DiscussionComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DiscussionCommentRepository extends JpaRepository<DiscussionComment, Long> {
    @Query("SELECT c FROM DiscussionComment c JOIN FETCH c.author WHERE c.discussion.id = :discussionId ORDER BY c.createdAt ASC")
    List<DiscussionComment> findByDiscussionIdOrderByCreatedAtAsc(@Param("discussionId") Long discussionId);
}
