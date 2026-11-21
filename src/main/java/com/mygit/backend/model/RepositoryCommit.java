package com.mygit.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "repository_commits", uniqueConstraints = {
    @UniqueConstraint(name = "uk_repo_commit_sha", columnNames = {"repository_id", "commit_sha"})
})
public class RepositoryCommit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @Column(name = "parent_sha", length = 64)
    private String parentSha;

    @Column(name = "tree_sha", length = 64)
    private String treeSha;

    @Column(name = "message", nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "author_name", length = 100)
    private String authorName;

    @Column(name = "author_email", length = 255)
    private String authorEmail;

    @Column(name = "committer_name", length = 100)
    private String committerName;

    @Column(name = "committer_email", length = 255)
    private String committerEmail;

    @Column(name = "authored_at")
    private LocalDateTime authoredAt;

    @Column(name = "pushed_at", nullable = false, updatable = false)
    private LocalDateTime pushedAt;

    public RepositoryCommit() {}

    public RepositoryCommit(
            Repository repository,
            String commitSha,
            String parentSha,
            String treeSha,
            String message,
            String authorName,
            String authorEmail,
            String committerName,
            String committerEmail,
            LocalDateTime authoredAt
    ) {
        this.repository = repository;
        this.commitSha = commitSha;
        this.parentSha = parentSha;
        this.treeSha = treeSha;
        this.message = message;
        this.authorName = authorName;
        this.authorEmail = authorEmail;
        this.committerName = committerName;
        this.committerEmail = committerEmail;
        this.authoredAt = authoredAt;
    }

    @PrePersist
    protected void onCreate() {
        if (this.pushedAt == null) {
            this.pushedAt = LocalDateTime.now();
        }
        if (this.authoredAt == null) {
            this.authoredAt = LocalDateTime.now();
        }
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public Repository getRepository() {
        return repository;
    }

    public void setRepository(Repository repository) {
        this.repository = repository;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public String getParentSha() {
        return parentSha;
    }

    public void setParentSha(String parentSha) {
        this.parentSha = parentSha;
    }

    public String getTreeSha() {
        return treeSha;
    }

    public void setTreeSha(String treeSha) {
        this.treeSha = treeSha;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public String getAuthorEmail() {
        return authorEmail;
    }

    public void setAuthorEmail(String authorEmail) {
        this.authorEmail = authorEmail;
    }

    public String getCommitterName() {
        return committerName;
    }

    public void setCommitterName(String committerName) {
        this.committerName = committerName;
    }

    public String getCommitterEmail() {
        return committerEmail;
    }

    public void setCommitterEmail(String committerEmail) {
        this.committerEmail = committerEmail;
    }

    public LocalDateTime getAuthoredAt() {
        return authoredAt;
    }

    public void setAuthoredAt(LocalDateTime authoredAt) {
        this.authoredAt = authoredAt;
    }

    public LocalDateTime getPushedAt() {
        return pushedAt;
    }

    public void setPushedAt(LocalDateTime pushedAt) {
        this.pushedAt = pushedAt;
    }
}
