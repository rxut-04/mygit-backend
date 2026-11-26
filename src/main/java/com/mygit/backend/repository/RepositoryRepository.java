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

    @org.springframework.data.jpa.repository.Query("""
        SELECT r FROM Repository r JOIN FETCH r.owner o
        WHERE r.isPrivate = false
          AND (:q IS NULL OR :q = '' OR LOWER(r.name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(r.description, '')) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(o.username) LIKE LOWER(CONCAT('%', :q, '%')))
        ORDER BY r.updatedAt DESC
    """)
    List<Repository> searchPublicRepositories(@org.springframework.data.repository.query.Param("q") String q);
}
