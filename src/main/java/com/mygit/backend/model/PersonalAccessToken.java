package com.mygit.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "personal_access_tokens")
public class PersonalAccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 120)
    private String tokenPreview;

    @Column(nullable = false, length = 255)
    private String scopes = "repo:read,repo:write";

    @Column(nullable = false, length = 40)
    private String expiresLabel = "30 days";

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime lastUsedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public PersonalAccessToken() {}

    public PersonalAccessToken(User user, String name, String tokenPreview, String scopes, String expiresLabel) {
        this.user = user;
        this.name = name;
        this.tokenPreview = tokenPreview;
        this.scopes = scopes;
        this.expiresLabel = expiresLabel;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTokenPreview() { return tokenPreview; }
    public void setTokenPreview(String tokenPreview) { this.tokenPreview = tokenPreview; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public String getExpiresLabel() { return expiresLabel; }
    public void setExpiresLabel(String expiresLabel) { this.expiresLabel = expiresLabel; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
