package com.mygit.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "discussion_comments")
public class DiscussionComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "discussion_id", nullable = false)
    private Discussion discussion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public DiscussionComment() {}

    public DiscussionComment(Discussion discussion, User author, String body) {
        this.discussion = discussion;
        this.author = author;
        this.body = body;
    }

    public Long getId() { return id; }
    public Discussion getDiscussion() { return discussion; }
    public User getAuthor() { return author; }
    public String getBody() { return body; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
