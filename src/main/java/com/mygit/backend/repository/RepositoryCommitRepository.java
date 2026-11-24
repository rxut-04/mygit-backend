package com.mygit.backend.repository;

import com.mygit.backend.model.RepositoryCommit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RepositoryCommitRepository extends JpaRepository<RepositoryCommit, Long> {

    List<RepositoryCommit> findByRepositoryIdOrderByAuthoredAtDesc(Long repositoryId);

    Optional<RepositoryCommit> findByRepositoryIdAndCommitSha(Long repositoryId, String commitSha);

    boolean existsByRepositoryIdAndCommitSha(Long repositoryId, String commitSha);

    @Query(value = """
        SELECT TO_CHAR(rc.authored_at, 'YYYY-MM-DD') AS commit_date, COUNT(*) AS count
        FROM repository_commits rc
        JOIN repositories r ON rc.repository_id = r.id
        WHERE r.owner_id = :userId
          AND rc.authored_at >= NOW() - INTERVAL '365 days'
        GROUP BY TO_CHAR(rc.authored_at, 'YYYY-MM-DD')
        ORDER BY commit_date ASC
    """, nativeQuery = true)
    List<Object[]> getContributionsByUserId(@Param("userId") Long userId);

    @Query(value = """
        SELECT TO_CHAR(rc.authored_at, 'YYYY-MM-DD') AS commit_date, COUNT(*) AS count
        FROM repository_commits rc
        WHERE rc.authored_at >= NOW() - INTERVAL '365 days'
        GROUP BY TO_CHAR(rc.authored_at, 'YYYY-MM-DD')
        ORDER BY commit_date ASC
    """, nativeQuery = true)
    List<Object[]> getAllContributions();
}
