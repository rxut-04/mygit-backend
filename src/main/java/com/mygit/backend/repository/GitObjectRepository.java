package com.mygit.backend.repository;

import com.mygit.backend.model.GitObject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GitObjectRepository extends JpaRepository<GitObject, Long> {
    Optional<GitObject> findByRepositoryIdAndSha(Long repositoryId, String sha);
    boolean existsByRepositoryIdAndSha(Long repositoryId, String sha);
}
