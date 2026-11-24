package com.mygit.backend.controller;

import com.mygit.backend.model.*;
import com.mygit.backend.repository.*;
import com.mygit.backend.security.CustomUser;
import com.mygit.backend.service.DiffService;
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
public class PullRequestController {

    @Autowired
    private RepositoryRepository repos;

    @Autowired
    private UserRepository users;

    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private PullRequestCommentRepository prComments;

    @Autowired
    private RepositoryFileRepository repoFiles;

    @Autowired
    private GitRefRepository gitRefs;

    @Autowired
    private DiffService diffService;

    private Repository resolveRepository(String repoName, String ownerName, CustomUser principal) {
        String cleanName = repoName;
        if (cleanName.endsWith(".mygit")) cleanName = cleanName.substring(0, cleanName.length() - 6);
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

    public record CreatePrRequest(String title, String description, String sourceBranch, String targetBranch) {}
    public record AddCommentRequest(String commentText) {}

    /**
     * List all pull requests for a repository.
     */
    @Transactional(readOnly = true)
    @GetMapping(value = {
            "/api/repos/{repoName}/pulls",
            "/api/repos/{owner}/{repoName}/pulls"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listPullRequests(
            @PathVariable String repoName,
            @PathVariable(required = false) String owner,
            @RequestParam(defaultValue = "ALL") String status,
            @AuthenticationPrincipal CustomUser principal
    ) {
        Repository repo = resolveRepository(repoName, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        List<PullRequest> list;
        if ("ALL".equalsIgnoreCase(status)) {
            list = pullRequests.findByRepositoryIdOrderByCreatedAtDesc(repo.getId());
        } else {
            list = pullRequests.findByRepositoryIdAndStatusOrderByCreatedAtDesc(repo.getId(), status.toUpperCase());
        }

        List<Map<String, Object>> result = list.stream().map(this::mapPrSummary).toList();
        return ResponseEntity.ok(result);
    }

    /**
     * Create a new pull request.
     */
    @Transactional
    @PostMapping(value = {
            "/api/repos/{repoName}/pulls",
            "/api/repos/{owner}/{repoName}/pulls"
    }, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createPullRequest(
            @PathVariable String repoName,
            @PathVariable(required = false) String owner,
            @RequestBody CreatePrRequest body,
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
        if (body.sourceBranch() == null || body.sourceBranch().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Source branch is required"));
        }

        String targetBranch = (body.targetBranch() != null && !body.targetBranch().isBlank()) ? body.targetBranch() : "main";
        if (body.sourceBranch().equals(targetBranch)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Source and target branch cannot be the same"));
        }

        User author = users.findById(principal.getId()).orElse(null);
        Integer nextPrNum = pullRequests.getNextPrNumber(repo.getId());

        PullRequest pr = new PullRequest(
                repo,
                nextPrNum,
                body.title().trim(),
                body.description(),
                body.sourceBranch().trim(),
                targetBranch.trim(),
                author,
                principal.getUsername()
        );

        pullRequests.save(pr);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapPrSummary(pr));
    }

    /**
     * Get single pull request with diff preview and comments.
     */
    @Transactional(readOnly = true)
    @GetMapping(value = {
            "/api/repos/{repoName}/pulls/{number}",
            "/api/repos/{owner}/{repoName}/pulls/{number}"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getPullRequest(
            @PathVariable String repoName,
            @PathVariable(required = false) String owner,
            @PathVariable Integer number,
            @AuthenticationPrincipal CustomUser principal
    ) {
        Repository repo = resolveRepository(repoName, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        PullRequest pr = pullRequests.findByRepositoryIdAndPrNumber(repo.getId(), number).orElse(null);
        if (pr == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Pull request not found"));
        }

        Map<String, Object> data = mapPrSummary(pr);

        // Fetch comments
        List<PullRequestComment> comments = prComments.findByPullRequestIdOrderByCreatedAtAsc(pr.getId());
        List<Map<String, Object>> commentsList = comments.stream().map(c -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", c.getId());
            cm.put("authorUsername", c.getAuthorUsername());
            cm.put("commentText", c.getCommentText());
            cm.put("createdAt", c.getCreatedAt());
            return cm;
        }).toList();
        data.put("comments", commentsList);

        // Compute diff between sourceBranch and targetBranch
        List<DiffService.FileDiffResult> diffs = computeBranchDiff(repo.getId(), pr.getSourceBranch(), pr.getTargetBranch());
        int totalAdditions = diffs.stream().mapToInt(DiffService.FileDiffResult::getAdditions).sum();
        int totalDeletions = diffs.stream().mapToInt(DiffService.FileDiffResult::getDeletions).sum();

        data.put("totalAdditions", totalAdditions);
        data.put("totalDeletions", totalDeletions);
        data.put("filesChangedCount", diffs.size());
        data.put("files", diffs);

        return ResponseEntity.ok(data);
    }

    /**
     * Merge a pull request.
     */
    @Transactional
    @PostMapping(value = {
            "/api/repos/{repoName}/pulls/{number}/merge",
            "/api/repos/{owner}/{repoName}/pulls/{number}/merge"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> mergePullRequest(
            @PathVariable String repoName,
            @PathVariable(required = false) String owner,
            @PathVariable Integer number,
            @AuthenticationPrincipal CustomUser principal
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required to merge"));
        }

        Repository repo = resolveRepository(repoName, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        // Only owner can merge
        if (!repo.getOwner().getId().equals(principal.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only repository owner can merge pull requests"));
        }

        PullRequest pr = pullRequests.findByRepositoryIdAndPrNumber(repo.getId(), number).orElse(null);
        if (pr == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Pull request not found"));
        }

        if (!"OPEN".equalsIgnoreCase(pr.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Pull request is already " + pr.getStatus()));
        }

        // Merge logic: Copy/Upsert files from sourceBranch into targetBranch
        List<RepositoryFile> sourceFiles = repoFiles.findByRepositoryIdAndBranchName(repo.getId(), pr.getSourceBranch());
        User merger = users.findById(principal.getId()).orElse(null);

        Set<String> activePaths = new HashSet<>();
        for (RepositoryFile sf : sourceFiles) {
            activePaths.add(sf.getFilePath());
            RepositoryFile targetFile = repoFiles
                    .findByRepositoryIdAndBranchNameAndFilePath(repo.getId(), pr.getTargetBranch(), sf.getFilePath())
                    .orElse(new RepositoryFile(
                            repo,
                            pr.getTargetBranch(),
                            sf.getFilePath(),
                            sf.getFileName(),
                            sf.getFileExtension(),
                            sf.getFileSize(),
                            sf.getCommitSha(),
                            sf.getBlobSha(),
                            sf.getContentText(),
                            sf.getContentBytes(),
                            sf.isBinary(),
                            merger
                    ));

            targetFile.setCommitSha(sf.getCommitSha());
            targetFile.setBlobSha(sf.getBlobSha());
            targetFile.setFileSize(sf.getFileSize());
            targetFile.setContentText(sf.getContentText());
            targetFile.setContentBytes(sf.getContentBytes());
            targetFile.setBinary(sf.isBinary());
            repoFiles.save(targetFile);
        }

        // Prune any files that do not exist in sourceBranch
        if (!activePaths.isEmpty()) {
            repoFiles.deleteByRepositoryIdAndBranchNameAndFilePathNotIn(repo.getId(), pr.getTargetBranch(), activePaths);
        }

        // Update git ref for target branch
        GitRef sourceRef = gitRefs.findByRepositoryIdAndRefName(repo.getId(), "refs/heads/" + pr.getSourceBranch()).orElse(null);
        if (sourceRef != null) {
            GitRef targetRef = gitRefs.findByRepositoryIdAndRefName(repo.getId(), "refs/heads/" + pr.getTargetBranch())
                    .orElse(new GitRef(repo, "refs/heads/" + pr.getTargetBranch(), sourceRef.getTargetSha()));
            targetRef.setTargetSha(sourceRef.getTargetSha());
            gitRefs.save(targetRef);
        }

        // Mark PR as MERGED
        pr.setStatus("MERGED");
        pr.setMergedAt(LocalDateTime.now());
        pr.setMergedByUsername(principal.getUsername());
        pullRequests.save(pr);

        return ResponseEntity.ok(Map.of(
                "status", "MERGED",
                "prNumber", pr.getPrNumber(),
                "mergedAt", pr.getMergedAt(),
                "mergedBy", pr.getMergedByUsername()
        ));
    }

    /**
     * Add a comment to a pull request.
     */
    @Transactional
    @PostMapping(value = {
            "/api/repos/{repoName}/pulls/{number}/comments",
            "/api/repos/{owner}/{repoName}/pulls/{number}/comments"
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

        PullRequest pr = pullRequests.findByRepositoryIdAndPrNumber(repo.getId(), number).orElse(null);
        if (pr == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Pull request not found"));
        }

        if (body.commentText() == null || body.commentText().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Comment text cannot be empty"));
        }

        User author = users.findById(principal.getId()).orElse(null);
        PullRequestComment comment = new PullRequestComment(pr, author, principal.getUsername(), body.commentText().trim());
        prComments.save(comment);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", comment.getId(),
                "authorUsername", comment.getAuthorUsername(),
                "commentText", comment.getCommentText(),
                "createdAt", comment.getCreatedAt()
        ));
    }

    private List<DiffService.FileDiffResult> computeBranchDiff(Long repoId, String sourceBranch, String targetBranch) {
        List<RepositoryFile> sourceFiles = repoFiles.findByRepositoryIdAndBranchName(repoId, sourceBranch);
        List<RepositoryFile> targetFiles = repoFiles.findByRepositoryIdAndBranchName(repoId, targetBranch);

        Map<String, RepositoryFile> targetMap = new HashMap<>();
        for (RepositoryFile f : targetFiles) targetMap.put(f.getFilePath(), f);

        Map<String, RepositoryFile> sourceMap = new HashMap<>();
        for (RepositoryFile f : sourceFiles) sourceMap.put(f.getFilePath(), f);

        Set<String> allPaths = new TreeSet<>();
        allPaths.addAll(targetMap.keySet());
        allPaths.addAll(sourceMap.keySet());

        List<DiffService.FileDiffResult> results = new ArrayList<>();
        for (String path : allPaths) {
            RepositoryFile tf = targetMap.get(path);
            RepositoryFile sf = sourceMap.get(path);

            String oldContent = (tf != null) ? tf.getContentText() : null;
            String newContent = (sf != null) ? sf.getContentText() : null;

            DiffService.FileDiffResult fdiff = diffService.computeFileDiff(path, oldContent, newContent);
            if (!"UNCHANGED".equals(fdiff.getStatus())) {
                results.add(fdiff);
            }
        }
        return results;
    }

    private Map<String, Object> mapPrSummary(PullRequest pr) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", pr.getId());
        map.put("number", pr.getPrNumber());
        map.put("title", pr.getTitle());
        map.put("description", pr.getDescription());
        map.put("sourceBranch", pr.getSourceBranch());
        map.put("targetBranch", pr.getTargetBranch());
        map.put("status", pr.getStatus());
        map.put("authorUsername", pr.getAuthorUsername());
        map.put("createdAt", pr.getCreatedAt());
        map.put("updatedAt", pr.getUpdatedAt());
        map.put("mergedAt", pr.getMergedAt());
        map.put("mergedByUsername", pr.getMergedByUsername());
        return map;
    }
}
