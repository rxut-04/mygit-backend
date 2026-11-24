package com.mygit.backend.dto;

import com.mygit.backend.model.Repository;
import java.time.LocalDateTime;

public record RepoResponse(
        Long id,
        String name,
        String description,
        boolean isPrivate,
        String ownerUsername,
        String forkedFrom,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static RepoResponse from(Repository r) {
        String forked = null;
        if (r.getParentRepository() != null && r.getParentRepository().getOwner() != null) {
            forked = r.getParentRepository().getOwner().getUsername() + "/" + r.getParentRepository().getName();
        }
        return new RepoResponse(
                r.getId(),
                r.getName(),
                r.getDescription(),
                r.isPrivate(),
                r.getOwner().getUsername(),
                forked,
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
