package com.mygit.backend.repository;

import com.mygit.backend.model.PersonalAccessToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PersonalAccessTokenRepository extends JpaRepository<PersonalAccessToken, Long> {
    List<PersonalAccessToken> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<PersonalAccessToken> findByIdAndUserId(Long id, Long userId);

    @org.springframework.data.jpa.repository.Query("SELECT t FROM PersonalAccessToken t JOIN FETCH t.user WHERE t.tokenHash = :tokenHash")
    Optional<PersonalAccessToken> findByTokenHashWithUser(@org.springframework.data.repository.query.Param("tokenHash") String tokenHash);
}
