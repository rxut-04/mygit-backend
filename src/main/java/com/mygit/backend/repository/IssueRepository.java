package com.mygit.backend.repository;

import com.mygit.backend.model.Issue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IssueRepository extends JpaRepository<Issue, Long> {

    List<Issue> findByRepositoryIdOrderByCreatedAtDesc(Long repositoryId);

    List<Issue> findByRepositoryIdAndStatusOrderByCreatedAtDesc(Long repositoryId, String status);

    Optional<Issue> findByRepositoryIdAndIssueNumber(Long repositoryId, Integer issueNumber);

    @Query("SELECT COALESCE(MAX(i.issueNumber), 0) + 1 FROM Issue i WHERE i.repository.id = :repoId")
    Integer getNextIssueNumber(@Param("repoId") Long repoId);

    long countByRepositoryIdAndStatus(Long repositoryId, String status);
}
