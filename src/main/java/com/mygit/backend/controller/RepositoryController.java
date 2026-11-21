package com.mygit.backend.controller;

import com.mygit.backend.dto.CreateRepoReq;
import com.mygit.backend.dto.RepoResponse;
import com.mygit.backend.model.Repository;
import com.mygit.backend.model.User;
import com.mygit.backend.repository.RepositoryRepository;
import com.mygit.backend.repository.UserRepository;
import com.mygit.backend.security.CustomUser;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/repos")
public class RepositoryController {

    @Autowired
    private RepositoryRepository repos;

    @Autowired
    private UserRepository users;

    /**
     * Lists only repositories belonging to the authenticated user (strict user isolation).
     */
    @GetMapping
    public ResponseEntity<List<RepoResponse>> getMyRepositories(@AuthenticationPrincipal CustomUser principal) {
        List<Repository> userRepos = repos.findByOwnerId(principal.getId());
        List<RepoResponse> res = userRepos.stream().map(RepoResponse::from).toList();
        return ResponseEntity.ok(res);
    }

    /**
     * Creates a new repository strictly owned by the authenticated user.
     */
    @PostMapping
    public ResponseEntity<?> createRepository(
            @AuthenticationPrincipal CustomUser principal,
            @Valid @RequestBody CreateRepoReq req
    ) {
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
     * Retrieves a repository. Enforces privacy boundary between users.
     */
    @GetMapping("/{name}")
    public ResponseEntity<?> getRepository(
            @AuthenticationPrincipal CustomUser principal,
            @PathVariable String name
    ) {
        return repos.findByOwnerIdAndName(principal.getId(), name)
                .map(r -> ResponseEntity.ok(RepoResponse.from(r)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(null));
    }

    /**
     * Deletes a repository. User can only delete their own repository.
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<?> deleteRepository(
            @AuthenticationPrincipal CustomUser principal,
            @PathVariable String name
    ) {
        Repository repo = repos.findByOwnerIdAndName(principal.getId(), name).orElse(null);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }
        repos.delete(repo);
        return ResponseEntity.ok(Map.of("message", "Repository deleted successfully"));
    }
}
