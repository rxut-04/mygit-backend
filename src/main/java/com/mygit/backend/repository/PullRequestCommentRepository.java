package com.mygit.backend.repository;

import com.mygit.backend.model.PullRequestComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PullRequestCommentRepository extends JpaRepository<PullRequestComment, Long> {

    List<PullRequestComment> findByPullRequestIdOrderByCreatedAtAsc(Long pullRequestId);
}
