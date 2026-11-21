package com.mygit.backend.dto;

import com.mygit.backend.model.Repository;
import java.time.LocalDateTime;

public record RepoResponse(
        Long id,
        String name,
        String description,
        boolean isPrivate,
        String ownerUsername,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static RepoResponse from(Repository r) {
        return new RepoResponse(
                r.getId(),
                r.getName(),
                r.getDescription(),
                r.isPrivate(),
                r.getOwner().getUsername(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
