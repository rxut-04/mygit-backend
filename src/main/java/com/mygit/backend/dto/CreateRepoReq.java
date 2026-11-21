package com.mygit.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateRepoReq(
        @NotBlank(message = "Repository name is required")
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "Repository name can only contain letters, numbers, hyphens, periods, and underscores")
        String name,

        String description,

        boolean isPrivate
) {
}
