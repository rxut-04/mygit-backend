package com.mygit.backend.repository;

import com.mygit.backend.model.RepositoryCommit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RepositoryCommitRepository extends JpaRepository<RepositoryCommit, Long> {

    List<RepositoryCommit> findByRepositoryIdOrderByAuthoredAtDesc(Long repositoryId);

    Optional<RepositoryCommit> findByRepositoryIdAndCommitSha(Long repositoryId, String commitSha);

    boolean existsByRepositoryIdAndCommitSha(Long repositoryId, String commitSha);
}
