package com.mygit.backend.controller;

import com.mygit.backend.model.Issue;
import com.mygit.backend.model.IssueComment;
import com.mygit.backend.model.Repository;
import com.mygit.backend.model.User;
import com.mygit.backend.repository.IssueCommentRepository;
import com.mygit.backend.repository.IssueRepository;
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

import java.time.LocalDateTime;
import java.util.*;

@RestController
public class IssueController {

    @Autowired
    private RepositoryRepository repos;

    @Autowired
    private UserRepository users;

    @Autowired
    private IssueRepository issues;

    @Autowired
    private IssueCommentRepository issueComments;

    private Repository resolveRepository(String repoName, String ownerName, CustomUser principal) {
        String cleanName = repoName.trim();
        if (cleanName.endsWith(".git")) cleanName = cleanName.substring(0, cleanName.length() - 4);

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
        return repos.findByName(cleanName).stream()
                .filter(r -> !r.isPrivate() || (principal != null && r.getOwner().getId().equals(principal.getId())))
                .findFirst()
                .orElse(null);
    }

    public record CreateIssueRequest(String title, String description, String labels) {}
    public record UpdateIssueStatusRequest(String status) {}
    public record AddCommentRequest(String commentText) {}

    /**
     * List issues for a repository (supports filtering by OPEN, CLOSED, or ALL).
     */
    @Transactional(readOnly = true)
    @GetMapping(value = {
            "/api/repos/{repoName}/issues",
            "/api/repos/{owner}/{repoName}/issues"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listIssues(
            @PathVariable String repoName,
            @PathVariable(required = false) String owner,
            @RequestParam(required = false, defaultValue = "OPEN") String status,
            @AuthenticationPrincipal CustomUser principal
    ) {
        Repository repo = resolveRepository(repoName, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        List<Issue> list;
        if ("ALL".equalsIgnoreCase(status)) {
            list = issues.findByRepositoryIdOrderByCreatedAtDesc(repo.getId());
        } else {
            list = issues.findByRepositoryIdAndStatusOrderByCreatedAtDesc(repo.getId(), status.toUpperCase());
        }

        List<Map<String, Object>> result = list.stream().map(this::mapIssueSummary).toList();
        return ResponseEntity.ok(result);
    }

    /**
     * Create a new issue.
     */
    @Transactional
    @PostMapping(value = {
            "/api/repos/{repoName}/issues",
            "/api/repos/{owner}/{repoName}/issues"
    }, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createIssue(
            @PathVariable String repoName,
            @PathVariable(required = false) String owner,
            @RequestBody CreateIssueRequest body,
            @AuthenticationPrincipal CustomUser principal
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }

        Repository repo = resolveRepository(repoName, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        if (body.title() == null || body.title().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Title is required"));
        }

        User author = users.findById(principal.getId()).orElse(null);
        Integer nextNum = issues.getNextIssueNumber(repo.getId());

        Issue issue = new Issue(
                repo,
                nextNum,
                body.title().trim(),
                body.description(),
                body.labels(),
                author,
                principal.getUsername()
        );
        issues.save(issue);

        return ResponseEntity.status(HttpStatus.CREATED).body(mapIssueSummary(issue));
    }

    /**
     * Get issue detail with discussion comments.
     */
    @Transactional(readOnly = true)
    @GetMapping(value = {
            "/api/repos/{repoName}/issues/{number}",
            "/api/repos/{owner}/{repoName}/issues/{number}"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getIssue(
            @PathVariable String repoName,
            @PathVariable(required = false) String owner,
            @PathVariable Integer number,
            @AuthenticationPrincipal CustomUser principal
    ) {
        Repository repo = resolveRepository(repoName, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        Issue issue = issues.findByRepositoryIdAndIssueNumber(repo.getId(), number).orElse(null);
        if (issue == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Issue not found"));
        }

        List<IssueComment> comments = issueComments.findByIssueIdOrderByCreatedAtAsc(issue.getId());
        List<Map<String, Object>> commentsList = comments.stream().map(c -> Map.<String, Object>of(
                "id", c.getId(),
                "authorUsername", c.getAuthorUsername(),
                "commentText", c.getCommentText(),
                "createdAt", c.getCreatedAt()
        )).toList();

        Map<String, Object> data = mapIssueSummary(issue);
        data.put("comments", commentsList);

        return ResponseEntity.ok(data);
    }

    /**
     * Update issue status (OPEN or CLOSED).
     */
    @Transactional
    @PatchMapping(value = {
            "/api/repos/{repoName}/issues/{number}/status",
            "/api/repos/{owner}/{repoName}/issues/{number}/status"
    }, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateStatus(
            @PathVariable String repoName,
            @PathVariable(required = false) String owner,
            @PathVariable Integer number,
            @RequestBody UpdateIssueStatusRequest body,
            @AuthenticationPrincipal CustomUser principal
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }

        Repository repo = resolveRepository(repoName, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        Issue issue = issues.findByRepositoryIdAndIssueNumber(repo.getId(), number).orElse(null);
        if (issue == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Issue not found"));
        }

        String newStatus = (body.status() != null && "CLOSED".equalsIgnoreCase(body.status())) ? "CLOSED" : "OPEN";
        issue.setStatus(newStatus);
        if ("CLOSED".equals(newStatus)) {
            issue.setClosedAt(LocalDateTime.now());
            issue.setClosedByUsername(principal.getUsername());
        } else {
            issue.setClosedAt(null);
            issue.setClosedByUsername(null);
        }
        issue.setUpdatedAt(LocalDateTime.now());
        issues.save(issue);

        return ResponseEntity.ok(mapIssueSummary(issue));
    }

    /**
     * Add a discussion comment to an issue.
     */
    @Transactional
    @PostMapping(value = {
            "/api/repos/{repoName}/issues/{number}/comments",
            "/api/repos/{owner}/{repoName}/issues/{number}/comments"
    }, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> addComment(
            @PathVariable String repoName,
            @PathVariable(required = false) String owner,
            @PathVariable Integer number,
            @RequestBody AddCommentRequest body,
            @AuthenticationPrincipal CustomUser principal
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }

        Repository repo = resolveRepository(repoName, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        Issue issue = issues.findByRepositoryIdAndIssueNumber(repo.getId(), number).orElse(null);
        if (issue == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Issue not found"));
        }

        if (body.commentText() == null || body.commentText().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Comment text cannot be empty"));
        }

        User author = users.findById(principal.getId()).orElse(null);
        IssueComment comment = new IssueComment(issue, author, principal.getUsername(), body.commentText().trim());
        issueComments.save(comment);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", comment.getId(),
                "authorUsername", comment.getAuthorUsername(),
                "commentText", comment.getCommentText(),
                "createdAt", comment.getCreatedAt()
        ));
    }

    private Map<String, Object> mapIssueSummary(Issue i) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", i.getId());
        map.put("number", i.getIssueNumber());
        map.put("title", i.getTitle());
        map.put("description", i.getDescription());
        map.put("status", i.getStatus());
        map.put("labels", (i.getLabels() != null && !i.getLabels().isBlank())
                ? Arrays.stream(i.getLabels().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
                : Collections.emptyList());
        map.put("authorUsername", i.getAuthorUsername());
        map.put("createdAt", i.getCreatedAt());
        map.put("updatedAt", i.getUpdatedAt());
        map.put("closedAt", i.getClosedAt());
        map.put("closedByUsername", i.getClosedByUsername());
        return map;
    }
}
