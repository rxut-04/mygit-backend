package com.mygit.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "notification_states", uniqueConstraints = {
    @UniqueConstraint(name = "uk_user_notif_key", columnNames = {"user_id", "notification_key"})
})
public class NotificationState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "notification_key", nullable = false, length = 120)
    private String notificationKey;

    @Column(nullable = false)
    private boolean isRead = false;

    @Column(nullable = false)
    private boolean isMuted = false;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        this.updatedAt = LocalDateTime.now();
    }

    public NotificationState() {}

    public NotificationState(User user, String notificationKey, boolean isRead, boolean isMuted) {
        this.user = user;
        this.notificationKey = notificationKey;
        this.isRead = isRead;
        this.isMuted = isMuted;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getNotificationKey() { return notificationKey; }
    public void setNotificationKey(String notificationKey) { this.notificationKey = notificationKey; }
    public boolean isRead() { return isRead; }
    public void setRead(boolean read) { isRead = read; }
    public boolean isMuted() { return isMuted; }
    public void setMuted(boolean muted) { isMuted = muted; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
