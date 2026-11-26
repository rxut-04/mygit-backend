package com.mygit.backend.repository;

import com.mygit.backend.model.RepositoryStar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RepositoryStarRepository extends JpaRepository<RepositoryStar, Long> {

    long countByRepositoryId(Long repositoryId);

    boolean existsByRepositoryIdAndUserId(Long repositoryId, Long userId);

    Optional<RepositoryStar> findByRepositoryIdAndUserId(Long repositoryId, Long userId);

    void deleteByRepositoryIdAndUserId(Long repositoryId, Long userId);

    @org.springframework.data.jpa.repository.Query("SELECT s FROM RepositoryStar s JOIN FETCH s.repository r JOIN FETCH s.user WHERE s.user.id = :userId ORDER BY s.createdAt DESC")
    List<RepositoryStar> findByUserIdWithRepo(@org.springframework.data.repository.query.Param("userId") Long userId);

    @org.springframework.data.jpa.repository.Query("SELECT s FROM RepositoryStar s JOIN FETCH s.repository r JOIN FETCH s.user WHERE r.owner.id = :ownerId ORDER BY s.createdAt DESC")
    List<RepositoryStar> findStarsOnOwnerRepos(@org.springframework.data.repository.query.Param("ownerId") Long ownerId);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(s) FROM RepositoryStar s WHERE s.repository.owner.id = :ownerId")
    long countStarsEarnedByOwnerId(@org.springframework.data.repository.query.Param("ownerId") Long ownerId);
}
