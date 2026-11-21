package com.mygit.backend.dto;

import java.util.List;

public record PushPayload(
        String ref,
        String target_sha,
        boolean force,
        List<PushedObject> objects
) {
    public record PushedObject(
            String sha,
            String type,
            String content_hex
    ) {}
}
