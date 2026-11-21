package com.mygit.backend.dto;

import java.util.List;

public record PushPayload(
        String ref,
        String target_sha,
        boolean force,
        List<PushedObject> objects,
        List<PushedFile> files
) {
    public record PushedObject(
            String sha,
            String type,
            String content_hex
    ) {}

    public record PushedFile(
            String path,
            String blob_sha,
            Long size,
            String content_text,
            Boolean is_binary
    ) {}
}
