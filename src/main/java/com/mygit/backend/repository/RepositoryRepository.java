package com.mygit.backend.repository;

import com.mygit.backend.model.Repository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@org.springframework.stereotype.Repository
public interface RepositoryRepository extends JpaRepository<Repository, Long> {

    List<Repository> findByOwnerId(Long ownerId);

    Optional<Repository> findByOwnerIdAndName(Long ownerId, String name);

    boolean existsByOwnerIdAndName(Long ownerId, String name);

    Optional<Repository> findByOwnerUsernameAndName(String username, String name);

    List<Repository> findByName(String name);

    long countByParentRepositoryId(Long parentRepositoryId);
}
