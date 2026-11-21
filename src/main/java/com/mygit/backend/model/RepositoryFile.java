package com.mygit.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "repository_files", uniqueConstraints = {
    @UniqueConstraint(name = "uk_repo_branch_file", columnNames = {"repository_id", "branch_name", "file_path"})
})
public class RepositoryFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @Column(name = "branch_name", nullable = false, length = 100)
    private String branchName = "main";

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_extension", length = 50)
    private String fileExtension;

    @Column(name = "file_size")
    private Long fileSize = 0L;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @Column(name = "blob_sha", nullable = false, length = 64)
    private String blobSha;

    @Column(name = "content_text", columnDefinition = "text")
    private String contentText;

    @Column(name = "content_bytes", columnDefinition = "bytea")
    private byte[] contentBytes;

    @Column(name = "is_binary")
    private boolean isBinary = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_user_id")
    private User uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public RepositoryFile() {}

    public RepositoryFile(Repository repository, String branchName, String filePath, String fileName,
                          String fileExtension, Long fileSize, String commitSha, String blobSha,
                          String contentText, byte[] contentBytes, boolean isBinary, User uploadedBy) {
        this.repository = repository;
        this.branchName = branchName != null ? branchName : "main";
        this.filePath = filePath;
        this.fileName = fileName;
        this.fileExtension = fileExtension;
        this.fileSize = fileSize != null ? fileSize : 0L;
        this.commitSha = commitSha;
        this.blobSha = blobSha;
        this.contentText = contentText;
        this.contentBytes = contentBytes;
        this.isBinary = isBinary;
        this.uploadedBy = uploadedBy;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Repository getRepository() { return repository; }
    public void setRepository(Repository repository) { this.repository = repository; }

    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFileExtension() { return fileExtension; }
    public void setFileExtension(String fileExtension) { this.fileExtension = fileExtension; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }

    public String getBlobSha() { return blobSha; }
    public void setBlobSha(String blobSha) { this.blobSha = blobSha; }

    public String getContentText() { return contentText; }
    public void setContentText(String contentText) { this.contentText = contentText; }

    public byte[] getContentBytes() { return contentBytes; }
    public void setContentBytes(byte[] contentBytes) { this.contentBytes = contentBytes; }

    public boolean isBinary() { return isBinary; }
    public void setBinary(boolean binary) { isBinary = binary; }

    public User getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(User uploadedBy) { this.uploadedBy = uploadedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
