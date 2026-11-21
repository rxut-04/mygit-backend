package com.mygit.backend.controller;

import com.mygit.backend.dto.PushPayload;
import com.mygit.backend.model.GitObject;
import com.mygit.backend.model.GitRef;
import com.mygit.backend.model.Repository;
import com.mygit.backend.model.User;
import com.mygit.backend.repository.GitObjectRepository;
import com.mygit.backend.repository.GitRefRepository;
import com.mygit.backend.repository.RepositoryRepository;
import com.mygit.backend.repository.UserRepository;
import com.mygit.backend.security.CustomUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.DeflaterOutputStream;

@RestController
public class GitSyncController {

    @Autowired
    private RepositoryRepository repos;

    @Autowired
    private UserRepository users;

    @Autowired
    private GitRefRepository gitRefs;

    @Autowired
    private GitObjectRepository gitObjects;

    private String cleanRepoName(String name) {
        if (name == null) return "";
        if (name.endsWith(".mygit")) {
            return name.substring(0, name.length() - 6);
        }
        if (name.endsWith(".git")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    private Repository resolveRepository(String repoName, String ownerName, CustomUser principal) {
        String cleanName = cleanRepoName(repoName);
        if (ownerName != null && !ownerName.isBlank()) {
            User owner = users.findByUsername(ownerName).orElse(null);
            if (owner != null) {
                return repos.findByOwnerIdAndName(owner.getId(), cleanName).orElse(null);
            }
        }
        if (principal != null) {
            Repository userRepo = repos.findByOwnerIdAndName(principal.getId(), cleanName).orElse(null);
            if (userRepo != null) return userRepo;
        }
        // Fallback: search public repository with this name
        return repos.findByName(cleanName).stream()
                .filter(r -> !r.isPrivate() || (principal != null && r.getOwner().getId().equals(principal.getId())))
                .findFirst()
                .orElse(null);
    }

    /**
     * Protocol: GET /refs -> map of ref names to commit SHAs
     */
    @GetMapping(value = {
            "/api/repos/{repoName}/refs",
            "/api/repos/{owner}/{repoName}/refs"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getRefs(
            @PathVariable String repoName,
            @PathVariable(required = false) String owner,
            @AuthenticationPrincipal CustomUser principal
    ) {
        Repository repo = resolveRepository(repoName, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        List<GitRef> refs = gitRefs.findByRepositoryId(repo.getId());
        Map<String, String> refMap = new LinkedHashMap<>();
        for (GitRef r : refs) {
            refMap.put(r.getRefName(), r.getTargetSha());
        }

        if (refMap.containsKey("refs/heads/main") && !refMap.containsKey("HEAD")) {
            refMap.put("HEAD", refMap.get("refs/heads/main"));
        }

        return ResponseEntity.ok(refMap);
    }

    /**
     * Protocol: POST /push -> upload missing objects and advance ref
     */
    @Transactional
    @PostMapping(value = {
            "/api/repos/{repoName}/push",
            "/api/repos/{owner}/{repoName}/push"
    }, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> handlePush(
            @PathVariable String repoName,
            @PathVariable(required = false) String owner,
            @RequestBody PushPayload payload,
            @AuthenticationPrincipal CustomUser principal
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required to push"));
        }

        Repository repo = resolveRepository(repoName, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        if (!repo.getOwner().getId().equals(principal.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only the repository owner can push"));
        }

        HexFormat hex = HexFormat.of();

        // 1. Ingest all objects
        if (payload.objects() != null) {
            for (PushPayload.PushedObject obj : payload.objects()) {
                if (gitObjects.existsByRepositoryIdAndSha(repo.getId(), obj.sha())) {
                    continue;
                }

                byte[] body = hex.parseHex(obj.content_hex());
                String headerStr = String.format("%s %d\0", obj.type(), body.length);
                byte[] header = headerStr.getBytes(StandardCharsets.ISO_8859_1);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (DeflaterOutputStream dos = new DeflaterOutputStream(baos)) {
                    dos.write(header);
                    dos.write(body);
                } catch (IOException e) {
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(Map.of("error", "Failed to compress object: " + e.getMessage()));
                }

                GitObject gitObj = new GitObject(repo, obj.sha(), obj.type(), baos.toByteArray());
                gitObjects.save(gitObj);
            }
        }

        // 2. Update the target reference
        if (payload.ref() != null && payload.target_sha() != null) {
            GitRef ref = gitRefs.findByRepositoryIdAndRefName(repo.getId(), payload.ref())
                    .orElse(new GitRef(repo, payload.ref(), payload.target_sha()));
            ref.setTargetSha(payload.target_sha());
            gitRefs.save(ref);
        }

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "ref", payload.ref() != null ? payload.ref() : "",
                "sha", payload.target_sha() != null ? payload.target_sha() : ""
        ));
    }

    /**
     * Protocol: GET /objects/{sha} -> raw compressed git object
     */
    @Transactional(readOnly = true)
    @GetMapping(value = {
            "/api/repos/{repoName}/objects/{sha}",
            "/api/repos/{owner}/{repoName}/objects/{sha}"
    }, produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<?> getObject(
            @PathVariable String repoName,
            @PathVariable(required = false) String owner,
            @PathVariable String sha,
            @AuthenticationPrincipal CustomUser principal
    ) {
        Repository repo = resolveRepository(repoName, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        GitObject gitObj = gitObjects.findByRepositoryIdAndSha(repo.getId(), sha).orElse(null);
        if (gitObj == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(gitObj.getData());
    }
}
