package com.mygit.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "deployment_logs")
public class DeploymentLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "deployment_id", nullable = false)
    private Deployment deployment;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false, length = 20)
    private String level = "INFO";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public DeploymentLog() {}

    public DeploymentLog(Deployment deployment, int seq, String level, String message) {
        this.deployment = deployment;
        this.seq = seq;
        this.level = level;
        this.message = message;
    }

    public Long getId() { return id; }
    public Deployment getDeployment() { return deployment; }
    public int getSeq() { return seq; }
    public String getLevel() { return level; }
    public String getMessage() { return message; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
