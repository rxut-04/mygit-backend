package com.mygit.backend.repository;

import com.mygit.backend.model.NotificationState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationStateRepository extends JpaRepository<NotificationState, Long> {
    List<NotificationState> findByUserId(Long userId);
    Optional<NotificationState> findByUserIdAndNotificationKey(Long userId, String notificationKey);
    void deleteByUserId(Long userId);
}
