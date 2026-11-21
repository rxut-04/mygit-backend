package com.mygit.backend.repository;

import com.mygit.backend.model.GitRef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GitRefRepository extends JpaRepository<GitRef, Long> {
    List<GitRef> findByRepositoryId(Long repositoryId);
    Optional<GitRef> findByRepositoryIdAndRefName(Long repositoryId, String refName);
}
