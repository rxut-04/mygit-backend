package com.mygit.backend.repository;

import com.mygit.backend.model.RepositoryStar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RepositoryStarRepository extends JpaRepository<RepositoryStar, Long> {

    long countByRepositoryId(Long repositoryId);

    boolean existsByRepositoryIdAndUserId(Long repositoryId, Long userId);

    Optional<RepositoryStar> findByRepositoryIdAndUserId(Long repositoryId, Long userId);

    void deleteByRepositoryIdAndUserId(Long repositoryId, Long userId);
}
