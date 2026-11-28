package com.mygit.backend.repository;

import com.mygit.backend.model.RepoAccessRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RepoAccessRequestRepository extends JpaRepository<RepoAccessRequest, Long> {

    Optional<RepoAccessRequest> findByRepositoryIdAndRequesterIdAndStatus(Long repositoryId, Long requesterId, String status);

    @Query("SELECT r FROM RepoAccessRequest r JOIN FETCH r.repository repo JOIN FETCH repo.owner JOIN FETCH r.requester WHERE repo.owner.id = :ownerId ORDER BY r.createdAt DESC")
    List<RepoAccessRequest> findByOwnerIdOrderByCreatedAtDesc(@Param("ownerId") Long ownerId);
}
