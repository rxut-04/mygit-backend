package com.mygit.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "git_refs", uniqueConstraints = {
    @UniqueConstraint(name = "uk_repo_ref", columnNames = {"repository_id", "ref_name"})
})
public class GitRef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @Column(name = "ref_name", nullable = false, length = 255)
    private String refName;

    @Column(name = "target_sha", nullable = false, length = 64)
    private String targetSha;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        this.updatedAt = LocalDateTime.now();
    }

    public GitRef() {
    }

    public GitRef(Repository repository, String refName, String targetSha) {
        this.repository = repository;
        this.refName = refName;
        this.targetSha = targetSha;
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

    public String getRefName() {
        return refName;
    }

    public void setRefName(String refName) {
        this.refName = refName;
    }

    public String getTargetSha() {
        return targetSha;
    }

    public void setTargetSha(String targetSha) {
        this.targetSha = targetSha;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
