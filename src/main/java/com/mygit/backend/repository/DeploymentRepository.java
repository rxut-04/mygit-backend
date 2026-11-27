package com.mygit.backend.repository;

import com.mygit.backend.model.Deployment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeploymentRepository extends JpaRepository<Deployment, Long> {
    List<Deployment> findByRepositoryIdOrderByCreatedAtDesc(Long repositoryId);
    Optional<Deployment> findFirstByRepositoryIdAndStatusOrderByCreatedAtDesc(Long repositoryId, String status);
}
