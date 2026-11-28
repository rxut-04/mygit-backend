package com.mygit.backend.controller;

import com.mygit.backend.dto.CreateRepoReq;
import com.mygit.backend.dto.RepoResponse;
import com.mygit.backend.model.*;
import com.mygit.backend.repository.*;
import com.mygit.backend.security.CustomUser;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
public class RepositoryController {

    @Autowired
    private RepositoryRepository repos;

    @Autowired
    private UserRepository users;

    @Autowired
    private RepositoryStarRepository stars;

    @Autowired
    private RepositoryFileRepository repoFiles;

    @Autowired
    private GitRefRepository gitRefs;

    public record UpdateRepoReq(String name, String description) {}
    public record UpdateVisibilityReq(boolean isPrivate) {}
    public record TransferRepoReq(String newOwnerUsername) {}

    @Autowired
    private com.mygit.backend.service.RepositoryLookupService repoLookup;

    /**
     * Lists repositories belonging to the authenticated user.
     */
    @GetMapping("/api/repos")
    public ResponseEntity<List<RepoResponse>> getMyRepositories(@AuthenticationPrincipal CustomUser principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<Repository> userRepos = repos.findByOwnerId(principal.getId());
        List<RepoResponse> res = userRepos.stream().map(RepoResponse::from).toList();
        return ResponseEntity.ok(res);
    }

    /**
     * Searches public repositories across all users (no authentication required).
     */
    @GetMapping(value = "/api/repos/public", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> searchPublicRepositories(
            @RequestParam(name = "q", required = false, defaultValue = "") String q
    ) {
        List<Repository> found = repos.searchPublicRepositories(q.trim());
        List<Map<String, Object>> result = found.stream().limit(40).map(r -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("description", r.getDescription());
            m.put("isPrivate", r.isPrivate());
            m.put("ownerUsername", r.getOwner().getUsername());
            m.put("starsCount", stars.countByRepositoryId(r.getId()));
            m.put("forksCount", repos.countByParentRepositoryId(r.getId()));
            m.put("updatedAt", r.getUpdatedAt());
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    /**
     * Creates a new repository owned by the authenticated user.
     */
    @PostMapping(value = "/api/repos", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createRepository(
            @AuthenticationPrincipal CustomUser principal,
            @Valid @RequestBody CreateRepoReq req
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        if (repos.existsByOwnerIdAndName(principal.getId(), req.name())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Repository with name '" + req.name() + "' already exists in your workspace"));
        }

        User owner = users.findById(principal.getId()).orElseThrow();
        Repository repo = new Repository(req.name(), req.description(), req.isPrivate(), owner);
        Repository saved = repos.save(repo);

        return ResponseEntity.status(HttpStatus.CREATED).body(RepoResponse.from(saved));
    }

    /**
     * Retrieves a repository.
     */
    @GetMapping(value = {
            "/api/repos/{name}",
            "/api/repos/{owner}/{name}"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getRepository(
            @PathVariable String name,
            @PathVariable(required = false) String owner,
            @AuthenticationPrincipal CustomUser principal
    ) {
        Repository repo = repoLookup.resolve(name, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }
        return ResponseEntity.ok(RepoResponse.from(repo));
    }

    /**
     * Check star status and star count.
     */
    @GetMapping(value = {
            "/api/repos/{name}/star",
            "/api/repos/{owner}/{name}/star"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getStarStatus(
            @PathVariable String name,
            @PathVariable(required = false) String owner,
            @AuthenticationPrincipal CustomUser principal
    ) {
        Repository repo = repoLookup.resolve(name, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        long count = stars.countByRepositoryId(repo.getId());
        boolean isStarred = false;
        if (principal != null) {
            isStarred = stars.existsByRepositoryIdAndUserId(repo.getId(), principal.getId());
        }

        return ResponseEntity.ok(Map.of(
                "starred", isStarred,
                "starCount", count
        ));
    }

    /**
     * Toggle Star (Star/Unstar).
     */
    @Transactional
    @PostMapping(value = {
            "/api/repos/{name}/star",
            "/api/repos/{owner}/{name}/star"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> toggleStar(
            @PathVariable String name,
            @PathVariable(required = false) String owner,
            @AuthenticationPrincipal CustomUser principal
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required to star"));
        }

        Repository repo = repoLookup.resolve(name, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        Optional<RepositoryStar> existing = stars.findByRepositoryIdAndUserId(repo.getId(), principal.getId());
        boolean starred;
        if (existing.isPresent()) {
            stars.delete(existing.get());
            starred = false;
        } else {
            User user = users.findById(principal.getId()).orElse(null);
            stars.save(new RepositoryStar(repo, user));
            starred = true;
        }

        long count = stars.countByRepositoryId(repo.getId());
        return ResponseEntity.ok(Map.of(
                "starred", starred,
                "starCount", count
        ));
    }

    /**
     * Fork repository into authenticated user's namespace.
     */
    @Transactional
    @PostMapping(value = {
            "/api/repos/{name}/fork",
            "/api/repos/{owner}/{name}/fork"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> forkRepository(
            @PathVariable String name,
            @PathVariable(required = false) String owner,
            @AuthenticationPrincipal CustomUser principal
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required to fork"));
        }

        Repository sourceRepo = repoLookup.resolve(name, owner, principal);
        if (sourceRepo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Source repository not found"));
        }

        // Cannot fork your own repo into same account
        if (sourceRepo.getOwner().getId().equals(principal.getId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "You cannot fork your own repository"));
        }

        // Check if fork already exists in target user account
        if (repos.existsByOwnerIdAndName(principal.getId(), sourceRepo.getName())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "You already have a repository named '" + sourceRepo.getName() + "' in your account"
            ));
        }

        User targetUser = users.findById(principal.getId()).orElse(null);
        Repository forked = new Repository(
                sourceRepo.getName(),
                sourceRepo.getDescription(),
                sourceRepo.isPrivate(),
                targetUser
        );
        forked.setParentRepository(sourceRepo);
        Repository savedFork = repos.save(forked);

        // 1. Copy repository files
        List<RepositoryFile> sourceFiles = repoFiles.findByRepositoryId(sourceRepo.getId());
        for (RepositoryFile sf : sourceFiles) {
            RepositoryFile copy = new RepositoryFile(
                    savedFork,
                    sf.getBranchName(),
                    sf.getFilePath(),
                    sf.getFileName(),
                    sf.getFileExtension(),
                    sf.getFileSize(),
                    sf.getCommitSha(),
                    sf.getBlobSha(),
                    sf.getContentText(),
                    sf.getContentBytes(),
                    sf.isBinary(),
                    targetUser
            );
            repoFiles.save(copy);
        }

        // 2. Copy git refs
        List<GitRef> sourceRefs = gitRefs.findByRepositoryId(sourceRepo.getId());
        for (GitRef sr : sourceRefs) {
            GitRef copyRef = new GitRef(savedFork, sr.getRefName(), sr.getTargetSha());
            gitRefs.save(copyRef);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(RepoResponse.from(savedFork));
    }

    /**
     * Update repository settings (Rename or update description).
     */
    @Transactional
    @PatchMapping(value = {
            "/api/repos/{name}",
            "/api/repos/{owner}/{name}"
    }, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateRepository(
            @PathVariable String name,
            @PathVariable(required = false) String owner,
            @RequestBody UpdateRepoReq body,
            @AuthenticationPrincipal CustomUser principal
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }

        Repository repo = repoLookup.resolve(name, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        if (!repo.getOwner().getId().equals(principal.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only repository owner can modify settings"));
        }

        if (body.name() != null && !body.name().isBlank()) {
            String newName = body.name().trim();
            if (!newName.equals(repo.getName())) {
                if (repos.existsByOwnerIdAndName(principal.getId(), newName)) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Repository name already exists"));
                }
                repo.setName(newName);
            }
        }

        if (body.description() != null) {
            repo.setDescription(body.description().trim());
        }

        repo.setUpdatedAt(LocalDateTime.now());
        repos.save(repo);

        return ResponseEntity.ok(RepoResponse.from(repo));
    }

    /**
     * Update repository visibility (Public <-> Private).
     */
    @Transactional
    @PatchMapping(value = {
            "/api/repos/{name}/visibility",
            "/api/repos/{owner}/{name}/visibility"
    }, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateVisibility(
            @PathVariable String name,
            @PathVariable(required = false) String owner,
            @RequestBody UpdateVisibilityReq body,
            @AuthenticationPrincipal CustomUser principal
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }

        Repository repo = repoLookup.resolve(name, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        if (!repo.getOwner().getId().equals(principal.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only repository owner can change visibility"));
        }

        repo.setPrivate(body.isPrivate());
        repo.setUpdatedAt(LocalDateTime.now());
        repos.save(repo);

        return ResponseEntity.ok(Map.of(
                "isPrivate", repo.isPrivate(),
                "message", "Repository is now " + (repo.isPrivate() ? "private" : "public")
        ));
    }

    /**
     * Transfer repository ownership to another user.
     */
    @Transactional
    @PostMapping(value = {
            "/api/repos/{name}/transfer",
            "/api/repos/{owner}/{name}/transfer"
    }, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> transferRepository(
            @PathVariable String name,
            @PathVariable(required = false) String owner,
            @RequestBody TransferRepoReq body,
            @AuthenticationPrincipal CustomUser principal
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }

        Repository repo = repoLookup.resolve(name, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        if (!repo.getOwner().getId().equals(principal.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only repository owner can transfer ownership"));
        }

        if (body.newOwnerUsername() == null || body.newOwnerUsername().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "New owner username is required"));
        }

        User newOwner = users.findByUsername(body.newOwnerUsername().trim()).orElse(null);
        if (newOwner == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User '" + body.newOwnerUsername() + "' does not exist"));
        }

        if (newOwner.getId().equals(principal.getId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot transfer repository to yourself"));
        }

        if (repos.existsByOwnerIdAndName(newOwner.getId(), repo.getName())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Target user already has a repository named '" + repo.getName() + "'"));
        }

        repo.setOwner(newOwner);
        repo.setUpdatedAt(LocalDateTime.now());
        repos.save(repo);

        return ResponseEntity.ok(Map.of(
                "message", "Repository transferred successfully to @" + newOwner.getUsername(),
                "newOwner", newOwner.getUsername()
        ));
    }

    /**
     * Deletes a repository permanently. Only owner can delete.
     */
    @Transactional
    @DeleteMapping(value = {
            "/api/repos/{name}",
            "/api/repos/{owner}/{name}"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deleteRepository(
            @PathVariable String name,
            @PathVariable(required = false) String owner,
            @AuthenticationPrincipal CustomUser principal
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }

        Repository repo = repoLookup.resolve(name, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        if (!repo.getOwner().getId().equals(principal.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only repository owner can delete repository"));
        }

        repos.delete(repo);
        return ResponseEntity.ok(Map.of("message", "Repository deleted successfully"));
    }
}
