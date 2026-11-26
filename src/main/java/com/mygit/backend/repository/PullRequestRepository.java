package com.mygit.backend.repository;

import com.mygit.backend.model.PullRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PullRequestRepository extends JpaRepository<PullRequest, Long> {

    List<PullRequest> findByRepositoryIdOrderByCreatedAtDesc(Long repositoryId);

    List<PullRequest> findByRepositoryIdAndStatusOrderByCreatedAtDesc(Long repositoryId, String status);

    Optional<PullRequest> findByRepositoryIdAndPrNumber(Long repositoryId, Integer prNumber);

    @Query("SELECT COALESCE(MAX(pr.prNumber), 0) + 1 FROM PullRequest pr WHERE pr.repository.id = :repositoryId")
    Integer getNextPrNumber(Long repositoryId);

    @Query("SELECT pr FROM PullRequest pr JOIN FETCH pr.repository r JOIN FETCH pr.author WHERE r.owner.id = :userId OR pr.author.id = :userId ORDER BY pr.createdAt DESC")
    List<PullRequest> findAllRelatedToUser(@org.springframework.data.repository.query.Param("userId") Long userId);
}
