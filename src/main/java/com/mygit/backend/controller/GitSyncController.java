package com.mygit.backend.controller;

import com.mygit.backend.dto.PushPayload;
import com.mygit.backend.model.GitObject;
import com.mygit.backend.model.GitRef;
import com.mygit.backend.model.Repository;
import com.mygit.backend.model.RepositoryCommit;
import com.mygit.backend.model.RepositoryFile;
import com.mygit.backend.model.User;
import com.mygit.backend.repository.GitObjectRepository;
import com.mygit.backend.repository.GitRefRepository;
import com.mygit.backend.repository.RepositoryCommitRepository;
import com.mygit.backend.repository.RepositoryFileRepository;
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

    @Autowired
    private RepositoryFileRepository repoFiles;

    @Autowired
    private DeploymentController deploymentController;

    @Autowired
    private RepositoryCommitRepository repoCommits;

    @Autowired
    private com.mygit.backend.service.DiffService diffService;
    @Autowired
    private com.mygit.backend.service.RepositoryLookupService repoLookup;

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
        Repository repo = repoLookup.resolve(repoName, owner, principal);
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

        Repository repo = repoLookup.resolve(repoName, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        if (!repo.getOwner().getId().equals(principal.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only the repository owner can push"));
        }

        // Guard against pushing node_modules/ or oversized files (> 25 MB)
        if (payload.files() != null) {
            for (PushPayload.PushedFile pf : payload.files()) {
                if (pf.path() != null) {
                    String norm = pf.path().replace("\\", "/").toLowerCase();
                    if (norm.equals("node_modules") || norm.startsWith("node_modules/") || norm.contains("/node_modules/")) {
                        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                                "error", "Push rejected: 'node_modules/' must not be pushed. Add 'node_modules/' to .gitignore and recommit."
                        ));
                    }
                }
            }
        }
        if (payload.objects() != null) {
            for (PushPayload.PushedObject obj : payload.objects()) {
                if (obj.content_hex() != null && obj.content_hex().length() > 50 * 1024 * 1024) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                            "error", "Push rejected: object '" + obj.sha() + "' exceeds 25 MB per-file limit."
                    ));
                }
            }
        }

        HexFormat hex = HexFormat.of();

        // 1. Ingest all objects
        if (payload.objects() != null) {
            for (PushPayload.PushedObject obj : payload.objects()) {
                if (gitObjects.existsByRepositoryIdAndSha(repo.getId(), obj.sha())) {
                    continue;
                }

                byte[] body = hex.parseHex(obj.content_hex());
                if ("commit".equals(obj.type())) {
                    try {
                        String commitText = new String(body, StandardCharsets.UTF_8);
                        saveCommitRecord(repo, obj.sha(), commitText);
                    } catch (Exception ignored) {}
                }
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

        // 2. Ingest file metadata and code into dedicated repository_files table
        if (payload.files() != null && !payload.files().isEmpty()) {
            String branch = (payload.ref() != null && payload.ref().startsWith("refs/heads/"))
                    ? payload.ref().substring("refs/heads/".length())
                    : "main";
            String commitSha = payload.target_sha() != null ? payload.target_sha() : "";

            User uploader = users.findById(principal.getId()).orElse(null);
            Set<String> activePaths = new HashSet<>();

            for (PushPayload.PushedFile pf : payload.files()) {
                String path = pf.path();
                if (path == null || path.isBlank()) continue;
                activePaths.add(path);
                String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "";

                RepositoryFile repoFile = repoFiles
                        .findByRepositoryIdAndBranchNameAndFilePath(repo.getId(), branch, path)
                        .orElse(new RepositoryFile(
                                repo,
                                branch,
                                path,
                                fileName,
                                ext,
                                pf.size() != null ? pf.size() : 0L,
                                commitSha,
                                pf.blob_sha() != null ? pf.blob_sha() : "",
                                pf.content_text(),
                                null,
                                Boolean.TRUE.equals(pf.is_binary()),
                                uploader
                        ));

                repoFile.setCommitSha(commitSha);
                if (pf.blob_sha() != null) repoFile.setBlobSha(pf.blob_sha());
                if (pf.size() != null) repoFile.setFileSize(pf.size());
                if (pf.content_text() != null) repoFile.setContentText(pf.content_text());
                repoFile.setBinary(Boolean.TRUE.equals(pf.is_binary()));

                repoFiles.save(repoFile);
            }

            if (!activePaths.isEmpty()) {
                repoFiles.deleteByRepositoryIdAndBranchNameAndFilePathNotIn(repo.getId(), branch, activePaths);
            }
        }

        // 3. Update the target reference
        if (payload.ref() != null && payload.target_sha() != null) {
            GitRef ref = gitRefs.findByRepositoryIdAndRefName(repo.getId(), payload.ref())
                    .orElse(new GitRef(repo, payload.ref(), payload.target_sha()));
            ref.setTargetSha(payload.target_sha());
            gitRefs.save(ref);

            if (!repoCommits.existsByRepositoryIdAndCommitSha(repo.getId(), payload.target_sha())) {
                gitObjects.findByRepositoryIdAndSha(repo.getId(), payload.target_sha()).ifPresent(gitObj -> {
                    try (java.util.zip.InflaterInputStream iis = new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(gitObj.getData()))) {
                        byte[] decompressed = iis.readAllBytes();
                        int nullIdx = -1;
                        for (int i = 0; i < decompressed.length; i++) {
                            if (decompressed[i] == 0) {
                                nullIdx = i;
                                break;
                            }
                        }
                        if (nullIdx != -1) {
                            String bodyStr = new String(decompressed, nullIdx + 1, decompressed.length - (nullIdx + 1), StandardCharsets.UTF_8);
                            saveCommitRecord(repo, payload.target_sha(), bodyStr);
                        }
                    } catch (Exception ignored) {}
                });
            }

            String branch = (payload.ref() != null && payload.ref().startsWith("refs/heads/"))
                    ? payload.ref().substring("refs/heads/".length())
                    : "main";
            if (payload.files() == null || payload.files().isEmpty()) {
                unpackCommitTreeToFiles(repo, branch, payload.target_sha(), principal);
            }
            try {
                deploymentController.autoDeployIfEligible(repo, branch);
            } catch (Exception ignored) {}
        }

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "ref", payload.ref() != null ? payload.ref() : "",
                "sha", payload.target_sha() != null ? payload.target_sha() : ""
        ));
    }

    /**
     * Endpoint to fetch the dynamic file tree and source code from repository_files table.
     */
    @Transactional(readOnly = true)
    @GetMapping(value = {
            "/api/repos/{repoName}/files",
            "/api/repos/{owner}/{repoName}/files"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getRepositoryFiles(
            @PathVariable String repoName,
            @PathVariable(required = false) String owner,
            @RequestParam(defaultValue = "main") String branch,
            @AuthenticationPrincipal CustomUser principal
    ) {
        Repository repo = repoLookup.resolve(repoName, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        List<RepositoryFile> list = repoFiles.findByRepositoryIdAndBranchName(repo.getId(), branch);
        List<Map<String, Object>> res = list.stream().map(f -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("path", f.getFilePath());
            map.put("name", f.getFileName());
            map.put("extension", f.getFileExtension());
            map.put("size", f.getFileSize());
            map.put("isBinary", f.isBinary());
            map.put("content", f.getContentText());
            map.put("commitSha", f.getCommitSha());
            return map;
        }).toList();

        return ResponseEntity.ok(res);
    }

    /**
     * Endpoint to list all branches for a repository.
     */
    @Transactional(readOnly = true)
    @GetMapping(value = {
            "/api/repos/{repoName}/branches",
            "/api/repos/{owner}/{repoName}/branches"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getRepositoryBranches(
            @PathVariable String repoName,
            @PathVariable(required = false) String owner,
            @AuthenticationPrincipal CustomUser principal
    ) {
        Repository repo = repoLookup.resolve(repoName, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        List<GitRef> refs = gitRefs.findByRepositoryId(repo.getId());
        Set<String> branches = new LinkedHashSet<>();
        for (GitRef ref : refs) {
            if (ref.getRefName().startsWith("refs/heads/")) {
                branches.add(ref.getRefName().substring("refs/heads/".length()));
            }
        }
        if (branches.isEmpty()) {
            branches.add("main");
        }

        List<Map<String, Object>> result = branches.stream().map(b -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", b);
            map.put("isDefault", "main".equalsIgnoreCase(b));
            return map;
        }).toList();

        return ResponseEntity.ok(result);
    }

    /**
     * Endpoint to download repository contents as a ZIP archive.
     */
    @Transactional(readOnly = true)
    @GetMapping(value = {
            "/api/repos/{repoName}/archive/zip",
            "/api/repos/{owner}/{repoName}/archive/zip"
    }, produces = "application/zip")
    public ResponseEntity<?> downloadRepositoryZip(
            @PathVariable String repoName,
            @PathVariable(required = false) String owner,
            @RequestParam(defaultValue = "main") String branch,
            @AuthenticationPrincipal CustomUser principal
    ) {
        Repository repo = repoLookup.resolve(repoName, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        List<RepositoryFile> files = repoFiles.findByRepositoryIdAndBranchName(repo.getId(), branch);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            for (RepositoryFile f : files) {
                String entryPath = repo.getName() + "-" + branch + "/" + f.getFilePath();
                java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(entryPath);
                zos.putNextEntry(entry);

                byte[] data;
                if (f.getContentText() != null) {
                    data = f.getContentText().getBytes(StandardCharsets.UTF_8);
                } else if (f.getContentBytes() != null) {
                    data = f.getContentBytes();
                } else {
                    GitObject gitObj = gitObjects.findByRepositoryIdAndSha(repo.getId(), f.getBlobSha()).orElse(null);
                    if (gitObj != null) {
                        byte[] decompressed = decompressGitObject(gitObj.getData());
                        int nullIdx = findNullByte(decompressed);
                        data = (nullIdx != -1) ? Arrays.copyOfRange(decompressed, nullIdx + 1, decompressed.length) : new byte[0];
                    } else {
                        data = new byte[0];
                    }
                }
                zos.write(data);
                zos.closeEntry();
            }
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        byte[] zipBytes = baos.toByteArray();
        String filename = repo.getName() + "-" + branch + ".zip";

        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(zipBytes.length)
                .body(zipBytes);
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
        Repository repo = repoLookup.resolve(repoName, owner, principal);
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

    /**
     * Endpoint to fetch the commit history from repository_commits table.
     */
    @Transactional(readOnly = true)
    @GetMapping(value = {
            "/api/repos/{repoName}/commits",
            "/api/repos/{owner}/{repoName}/commits"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getRepositoryCommits(
            @PathVariable String repoName,
            @PathVariable(required = false) String owner,
            @AuthenticationPrincipal CustomUser principal
    ) {
        Repository repo = repoLookup.resolve(repoName, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        List<RepositoryCommit> list = repoCommits.findByRepositoryIdOrderByAuthoredAtDesc(repo.getId());
        List<Map<String, Object>> res = list.stream().map(c -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", c.getId());
            map.put("commitSha", c.getCommitSha());
            map.put("parentSha", c.getParentSha());
            map.put("treeSha", c.getTreeSha());
            map.put("message", c.getMessage());
            map.put("authorName", c.getAuthorName());
            map.put("authorEmail", c.getAuthorEmail());
            map.put("committerName", c.getCommitterName());
            map.put("committerEmail", c.getCommitterEmail());
            map.put("authoredAt", c.getAuthoredAt());
            map.put("pushedAt", c.getPushedAt());
            return map;
        }).toList();

        return ResponseEntity.ok(res);
    }

    /**
     * Endpoint to fetch the diff for a specific commit compared to its parent.
     */
    @Transactional(readOnly = true)
    @GetMapping(value = {
            "/api/repos/{repoName}/commits/{commitSha}/diff",
            "/api/repos/{owner}/{repoName}/commits/{commitSha}/diff"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getCommitDiff(
            @PathVariable String repoName,
            @PathVariable(required = false) String owner,
            @PathVariable String commitSha,
            @AuthenticationPrincipal CustomUser principal
    ) {
        Repository repo = repoLookup.resolve(repoName, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        RepositoryCommit commit = repoCommits.findByRepositoryIdAndCommitSha(repo.getId(), commitSha).orElse(null);
        if (commit == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Commit not found"));
        }

        String parentSha = commit.getParentSha();

        Map<String, String> commitFiles = extractFilesForCommit(repo.getId(), commitSha);
        Map<String, String> parentFiles = (parentSha != null && !parentSha.isBlank())
                ? extractFilesForCommit(repo.getId(), parentSha)
                : Collections.emptyMap();

        Set<String> allPaths = new TreeSet<>();
        allPaths.addAll(commitFiles.keySet());
        allPaths.addAll(parentFiles.keySet());

        List<com.mygit.backend.service.DiffService.FileDiffResult> fileDiffs = new ArrayList<>();
        for (String path : allPaths) {
            String oldContent = parentFiles.get(path);
            String newContent = commitFiles.get(path);

            com.mygit.backend.service.DiffService.FileDiffResult fdiff = diffService.computeFileDiff(path, oldContent, newContent);
            if (!"UNCHANGED".equals(fdiff.getStatus())) {
                fileDiffs.add(fdiff);
            }
        }

        int totalAdditions = fileDiffs.stream().mapToInt(com.mygit.backend.service.DiffService.FileDiffResult::getAdditions).sum();
        int totalDeletions = fileDiffs.stream().mapToInt(com.mygit.backend.service.DiffService.FileDiffResult::getDeletions).sum();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("commitSha", commit.getCommitSha());
        response.put("parentSha", commit.getParentSha());
        response.put("treeSha", commit.getTreeSha());
        response.put("message", commit.getMessage());
        response.put("authorName", commit.getAuthorName());
        response.put("authorEmail", commit.getAuthorEmail());
        response.put("authoredAt", commit.getAuthoredAt());
        response.put("totalAdditions", totalAdditions);
        response.put("totalDeletions", totalDeletions);
        response.put("filesChangedCount", fileDiffs.size());
        response.put("files", fileDiffs);

        return ResponseEntity.ok(response);
    }

    private Map<String, String> extractFilesForCommit(Long repoId, String commitSha) {
        Map<String, String> result = new LinkedHashMap<>();
        gitObjects.findByRepositoryIdAndSha(repoId, commitSha).ifPresent(gitObj -> {
            try {
                byte[] decompressed = decompressGitObject(gitObj.getData());
                int nullIdx = findNullByte(decompressed);
                if (nullIdx == -1) return;
                String commitBody = new String(decompressed, nullIdx + 1, decompressed.length - (nullIdx + 1), StandardCharsets.UTF_8);
                String treeSha = null;
                for (String line : commitBody.split("\n")) {
                    if (line.startsWith("tree ")) {
                        treeSha = line.substring(5).trim();
                        break;
                    }
                }
                if (treeSha != null) {
                    walkTreeToContentMap(repoId, treeSha, "", result);
                }
            } catch (Exception ignored) {}
        });
        if (result.isEmpty()) {
            List<RepositoryFile> rfs = repoFiles.findByRepositoryId(repoId);
            for (RepositoryFile rf : rfs) {
                if (commitSha.equals(rf.getCommitSha()) && rf.getContentText() != null) {
                    result.put(rf.getFilePath(), rf.getContentText());
                }
            }
        }
        return result;
    }

    private void walkTreeToContentMap(Long repoId, String treeSha, String prefix, Map<String, String> result) {
        gitObjects.findByRepositoryIdAndSha(repoId, treeSha).ifPresent(gitObj -> {
            try {
                byte[] decompressed = decompressGitObject(gitObj.getData());
                int nullIdx = findNullByte(decompressed);
                if (nullIdx == -1) return;
                byte[] treeBytes = Arrays.copyOfRange(decompressed, nullIdx + 1, decompressed.length);

                HexFormat hex = HexFormat.of();
                int i = 0;
                while (i < treeBytes.length) {
                    int spaceIdx = -1;
                    for (int j = i; j < treeBytes.length; j++) {
                        if (treeBytes[j] == ' ') { spaceIdx = j; break; }
                    }
                    if (spaceIdx == -1) break;
                    String mode = new String(treeBytes, i, spaceIdx - i, StandardCharsets.ISO_8859_1);

                    int entryNullIdx = -1;
                    for (int j = spaceIdx + 1; j < treeBytes.length; j++) {
                        if (treeBytes[j] == 0) { entryNullIdx = j; break; }
                    }
                    if (entryNullIdx == -1) break;
                    String name = new String(treeBytes, spaceIdx + 1, entryNullIdx - (spaceIdx + 1), StandardCharsets.UTF_8);

                    if (entryNullIdx + 21 > treeBytes.length) break;
                    byte[] shaBytes = Arrays.copyOfRange(treeBytes, entryNullIdx + 1, entryNullIdx + 21);
                    String sha = hex.formatHex(shaBytes);

                    i = entryNullIdx + 21;

                    String fullPath = prefix.isEmpty() ? name : prefix + "/" + name;
                    if ("40000".equals(mode) || "040000".equals(mode)) {
                        walkTreeToContentMap(repoId, sha, fullPath, result);
                    } else {
                        gitObjects.findByRepositoryIdAndSha(repoId, sha).ifPresent(blobObj -> {
                            try {
                                byte[] blobDecomp = decompressGitObject(blobObj.getData());
                                int bNull = findNullByte(blobDecomp);
                                if (bNull != -1) {
                                    byte[] content = Arrays.copyOfRange(blobDecomp, bNull + 1, blobDecomp.length);
                                    result.put(fullPath, new String(content, StandardCharsets.UTF_8));
                                }
                            } catch (Exception ignored) {}
                        });
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void saveCommitRecord(Repository repo, String sha, String commitText) {
        if (repoCommits.existsByRepositoryIdAndCommitSha(repo.getId(), sha)) {
            return;
        }
        String treeSha = null;
        String parentSha = null;
        String authorName = "mygit user";
        String authorEmail = null;
        String committerName = null;
        String committerEmail = null;
        java.time.LocalDateTime authoredAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);

        String[] parts = commitText.split("\n\n", 2);
        String header = parts[0];
        String message = parts.length > 1 ? parts[1].trim() : "Commit " + sha.substring(0, Math.min(7, sha.length()));

        for (String line : header.split("\n")) {
            line = line.trim();
            if (line.startsWith("tree ")) {
                treeSha = line.substring(5).trim();
            } else if (line.startsWith("parent ")) {
                parentSha = line.substring(7).trim();
            } else if (line.startsWith("author ")) {
                String authorLine = line.substring(7).trim();
                int emailStart = authorLine.indexOf('<');
                int emailEnd = authorLine.indexOf('>');
                if (emailStart != -1 && emailEnd > emailStart) {
                    authorName = authorLine.substring(0, emailStart).trim();
                    authorEmail = authorLine.substring(emailStart + 1, emailEnd).trim();
                    String tail = authorLine.substring(emailEnd + 1).trim();
                    if (!tail.isEmpty()) {
                        String[] tsParts = tail.split("\\s+");
                        try {
                            long epochSec = Long.parseLong(tsParts[0]);
                            authoredAt = java.time.LocalDateTime.ofInstant(
                                    java.time.Instant.ofEpochSecond(epochSec),
                                    java.time.ZoneOffset.UTC
                            );
                        } catch (NumberFormatException ignored) {}
                    }
                } else {
                    authorName = authorLine;
                }
            } else if (line.startsWith("committer ")) {
                String committerLine = line.substring(10).trim();
                int emailStart = committerLine.indexOf('<');
                int emailEnd = committerLine.indexOf('>');
                if (emailStart != -1 && emailEnd > emailStart) {
                    committerName = committerLine.substring(0, emailStart).trim();
                    committerEmail = committerLine.substring(emailStart + 1, emailEnd).trim();
                } else {
                    committerName = committerLine;
                }
            }
        }

        RepositoryCommit commit = new RepositoryCommit(
                repo,
                sha,
                parentSha,
                treeSha,
                message.isEmpty() ? "Commit " + sha.substring(0, Math.min(7, sha.length())) : message,
                authorName,
                authorEmail,
                committerName,
                committerEmail,
                authoredAt
        );
        repoCommits.save(commit);
    }

    private void unpackCommitTreeToFiles(Repository repo, String branch, String commitSha, CustomUser principal) {
        if (commitSha == null || commitSha.isBlank()) return;
        gitObjects.findByRepositoryIdAndSha(repo.getId(), commitSha).ifPresent(gitObj -> {
            try {
                byte[] decompressed = decompressGitObject(gitObj.getData());
                int nullIdx = findNullByte(decompressed);
                if (nullIdx == -1) return;
                String commitBody = new String(decompressed, nullIdx + 1, decompressed.length - (nullIdx + 1), StandardCharsets.UTF_8);
                String treeSha = null;
                for (String line : commitBody.split("\n")) {
                    if (line.startsWith("tree ")) {
                        treeSha = line.substring(5).trim();
                        break;
                    }
                }
                if (treeSha != null) {
                    User uploader = principal != null ? users.findById(principal.getId()).orElse(null) : null;
                    Set<String> activePaths = new HashSet<>();
                    walkAndSaveTree(repo, branch, treeSha, "", commitSha, uploader, activePaths);
                    if (!activePaths.isEmpty()) {
                        repoFiles.deleteByRepositoryIdAndBranchNameAndFilePathNotIn(repo.getId(), branch, activePaths);
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void walkAndSaveTree(Repository repo, String branch, String treeSha, String prefix, String commitSha, User uploader, Set<String> activePaths) {
        gitObjects.findByRepositoryIdAndSha(repo.getId(), treeSha).ifPresent(gitObj -> {
            try {
                byte[] decompressed = decompressGitObject(gitObj.getData());
                int nullIdx = findNullByte(decompressed);
                if (nullIdx == -1) return;
                byte[] treeBytes = Arrays.copyOfRange(decompressed, nullIdx + 1, decompressed.length);

                HexFormat hex = HexFormat.of();
                int i = 0;
                while (i < treeBytes.length) {
                    int spaceIdx = -1;
                    for (int j = i; j < treeBytes.length; j++) {
                        if (treeBytes[j] == ' ') { spaceIdx = j; break; }
                    }
                    if (spaceIdx == -1) break;
                    String mode = new String(treeBytes, i, spaceIdx - i, StandardCharsets.ISO_8859_1);

                    int entryNullIdx = -1;
                    for (int j = spaceIdx + 1; j < treeBytes.length; j++) {
                        if (treeBytes[j] == 0) { entryNullIdx = j; break; }
                    }
                    if (entryNullIdx == -1) break;
                    String name = new String(treeBytes, spaceIdx + 1, entryNullIdx - (spaceIdx + 1), StandardCharsets.UTF_8);

                    if (entryNullIdx + 21 > treeBytes.length) break;
                    byte[] shaBytes = Arrays.copyOfRange(treeBytes, entryNullIdx + 1, entryNullIdx + 21);
                    String sha = hex.formatHex(shaBytes);

                    i = entryNullIdx + 21;

                    String fullPath = prefix.isEmpty() ? name : prefix + "/" + name;
                    if ("40000".equals(mode) || "040000".equals(mode)) {
                        walkAndSaveTree(repo, branch, sha, fullPath, commitSha, uploader, activePaths);
                    } else {
                        activePaths.add(fullPath);
                        saveBlobAsFile(repo, branch, fullPath, name, sha, commitSha, uploader);
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void saveBlobAsFile(Repository repo, String branch, String fullPath, String fileName, String blobSha, String commitSha, User uploader) {
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "";
        gitObjects.findByRepositoryIdAndSha(repo.getId(), blobSha).ifPresent(gitObj -> {
            try {
                byte[] decompressed = decompressGitObject(gitObj.getData());
                int nullIdx = findNullByte(decompressed);
                if (nullIdx == -1) return;
                byte[] blobBytes = Arrays.copyOfRange(decompressed, nullIdx + 1, decompressed.length);
                long size = blobBytes.length;
                boolean isBin = false;
                String contentText = null;
                for (byte b : blobBytes) {
                    if (b == 0) { isBin = true; break; }
                }
                if (!isBin) {
                    contentText = new String(blobBytes, StandardCharsets.UTF_8);
                }

                RepositoryFile repoFile = repoFiles
                        .findByRepositoryIdAndBranchNameAndFilePath(repo.getId(), branch, fullPath)
                        .orElse(new RepositoryFile(
                                repo, branch, fullPath, fileName, ext, size, commitSha, blobSha, contentText, null, isBin, uploader
                        ));
                repoFile.setCommitSha(commitSha);
                repoFile.setBlobSha(blobSha);
                repoFile.setFileSize(size);
                repoFile.setContentText(contentText);
                repoFile.setBinary(isBin);
                repoFiles.save(repoFile);
            } catch (Exception ignored) {}
        });
    }

    @Transactional
    @PostMapping(value = {
            "/api/repos/{repoName}/files",
            "/api/repos/{owner}/{repoName}/files"
    }, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> upsertFile(
            @PathVariable String repoName,
            @PathVariable(required = false) String owner,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal CustomUser principal
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        Repository repo = repoLookup.resolve(repoName, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }
        if (!repo.getOwner().getId().equals(principal.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only repository owner can edit files"));
        }

        String rawPath = body.getOrDefault("path", "").trim().replace("\\", "/");
        while (rawPath.startsWith("/")) rawPath = rawPath.substring(1);
        if (rawPath.isEmpty() || rawPath.contains("..")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid file path"));
        }
        String branch = body.getOrDefault("branch", "main").trim();
        if (branch.isEmpty()) branch = "main";
        String content = body.getOrDefault("content", "");
        String message = body.getOrDefault("message", "").trim();
        if (message.isEmpty()) message = "Update " + rawPath;

        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        String blobSha = sha1Hex("blob " + contentBytes.length + "\0", contentBytes);
        String commitSha = sha1Hex("web-commit:" + repo.getId() + ":" + System.currentTimeMillis() + ":" + rawPath + "\0", contentBytes);

        String fileName = rawPath.contains("/") ? rawPath.substring(rawPath.lastIndexOf('/') + 1) : rawPath;
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "";

        User uploader = users.findById(principal.getId()).orElse(repo.getOwner());
        RepositoryFile repoFile = repoFiles
                .findByRepositoryIdAndBranchNameAndFilePath(repo.getId(), branch, rawPath)
                .orElse(new RepositoryFile(
                        repo, branch, rawPath, fileName, ext, (long) contentBytes.length,
                        commitSha, blobSha, content, null, false, uploader
                ));
        repoFile.setCommitSha(commitSha);
        repoFile.setBlobSha(blobSha);
        repoFile.setFileSize((long) contentBytes.length);
        repoFile.setContentText(content);
        repoFile.setBinary(false);
        repoFiles.save(repoFile);

        RepositoryCommit commit = new RepositoryCommit(
                repo, commitSha, null, blobSha, message,
                uploader.getUsername(), uploader.getEmail(),
                uploader.getUsername(), uploader.getEmail(),
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
        );
        repoCommits.save(commit);

        try {
            deploymentController.autoDeployIfEligible(repo, branch);
        } catch (Exception ignored) {}

        return ResponseEntity.ok(Map.of(
                "path", rawPath,
                "branch", branch,
                "commitSha", commitSha,
                "blobSha", blobSha
        ));
    }

    private String sha1Hex(String header, byte[] body) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            md.update(header.getBytes(StandardCharsets.UTF_8));
            md.update(body);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    private byte[] decompressGitObject(byte[] data) throws IOException {
        try (java.util.zip.InflaterInputStream iis = new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(data))) {
            return iis.readAllBytes();
        }
    }

    private int findNullByte(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) return i;
        }
        return -1;
    }
}
