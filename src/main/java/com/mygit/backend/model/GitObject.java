package com.mygit.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "git_objects", uniqueConstraints = {
    @UniqueConstraint(name = "uk_repo_sha", columnNames = {"repository_id", "sha"})
})
public class GitObject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @Column(nullable = false, length = 64)
    private String sha;

    @Column(name = "object_type", length = 32)
    private String objectType;

    @Column(name = "data", columnDefinition = "bytea", nullable = false)
    private byte[] data;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public GitObject() {
    }

    public GitObject(Repository repository, String sha, String objectType, byte[] data) {
        this.repository = repository;
        this.sha = sha;
        this.objectType = objectType;
        this.data = data;
    }

    public Long getId() {
        return id;
    }

    public Repository getRepository() {
        return repository;
    }

    public void setRepository(Repository repository) {
        this.repository = repository;
    }

    public String getSha() {
        return sha;
    }

    public void setSha(String sha) {
        this.sha = sha;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
