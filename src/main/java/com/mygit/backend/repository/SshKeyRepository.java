package com.mygit.backend.repository;

import com.mygit.backend.model.SshKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SshKeyRepository extends JpaRepository<SshKey, Long> {
    List<SshKey> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<SshKey> findByIdAndUserId(Long id, Long userId);
}
