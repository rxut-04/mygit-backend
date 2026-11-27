package com.mygit.backend.controller;

import com.mygit.backend.model.*;
import com.mygit.backend.repository.*;
import com.mygit.backend.security.CustomUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@RestController
public class DeploymentController {

    @Autowired private RepositoryRepository repos;
    @Autowired private RepositoryFileRepository files;
    @Autowired private RepositoryCommitRepository commits;
    @Autowired private DeploymentRepository deployments;
    @Autowired private DeploymentLogRepository deploymentLogs;

    private Repository resolveRepository(String name, String owner, CustomUser principal) {
        String cleanName = name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
        if (owner != null && !owner.isBlank()) {
            Repository r = repos.findByOwnerUsernameAndName(owner, cleanName).orElse(null);
            if (r != null && (!r.isPrivate() || (principal != null && r.getOwner().getId().equals(principal.getId())))) {
                return r;
            }
            return null;
        }
        if (principal != null) {
            Repository owned = repos.findByOwnerIdAndName(principal.getId(), cleanName).orElse(null);
            if (owned != null) return owned;
        }
        return repos.findByName(cleanName).stream()
                .filter(r -> !r.isPrivate() || (principal != null && r.getOwner().getId().equals(principal.getId())))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> toDeploymentMap(Deployment d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("repo", d.getRepository().getName());
        m.put("owner", d.getRepository().getOwner().getUsername());
        m.put("branch", d.getBranch());
        m.put("commitSha", d.getCommitSha());
        m.put("framework", d.getFramework());
        m.put("status", d.getStatus());
        m.put("outputDir", d.getOutputDir());
        m.put("liveUrl", d.getLiveUrl());
        m.put("errorSummary", d.getErrorSummary());
        m.put("durationMs", d.getDurationMs());
        m.put("createdAt", d.getCreatedAt());
        m.put("finishedAt", d.getFinishedAt());
        return m;
    }

    private Map<String, Object> toLogMap(DeploymentLog l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("seq", l.getSeq());
        m.put("level", l.getLevel());
        m.put("message", l.getMessage());
        m.put("createdAt", l.getCreatedAt());
        return m;
    }

    @Transactional
    @PostMapping(value = {
            "/api/repos/{name}/deployments",
            "/api/repos/{owner}/{name}/deployments"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> triggerDeployment(
            @PathVariable String name,
            @PathVariable(required = false) String owner,
            @AuthenticationPrincipal CustomUser principal,
            @RequestBody(required = false) Map<String, String> body
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        Repository repo = resolveRepository(name, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }
        if (!repo.getOwner().getId().equals(principal.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only repository owner can deploy"));
        }

        long startMs = System.currentTimeMillis();
        String branch = (body != null && body.get("branch") != null && !body.get("branch").isBlank())
                ? body.get("branch")
                : "main";

        List<RepositoryCommit> repoCommits = commits.findByRepositoryIdOrderByAuthoredAtDesc(repo.getId());
        String latestSha = repoCommits.isEmpty() ? null : repoCommits.get(0).getCommitSha();

        Deployment dep = deployments.save(new Deployment(repo, branch, latestSha));
        List<DeploymentLog> logs = new ArrayList<>();
        int seq = 1;

        logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "INFO",
                "Initializing mygit Pages deployment for " + repo.getOwner().getUsername() + "/" + repo.getName() + " (branch: " + branch + ")")));

        List<RepositoryFile> repoFiles = files.findByRepositoryIdAndBranchName(repo.getId(), branch);
        if (repoFiles.isEmpty()) {
            repoFiles = files.findByRepositoryId(repo.getId());
        }

        logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "INFO",
                "Scanning repository tree... found " + repoFiles.size() + " tracked file(s).")));

        if (repoFiles.isEmpty()) {
            String err = "Repository has 0 pushed files on branch '" + branch + "'. Push files with 'mygit push origin " + branch + "' before deploying.";
            logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "ERROR", err)));
            dep.setStatus("FAILED");
            dep.setFramework("empty");
            dep.setErrorSummary(err);
            dep.setDurationMs(Math.max(10L, System.currentTimeMillis() - startMs));
            dep.setFinishedAt(LocalDateTime.now());
            deployments.save(dep);
            return ResponseEntity.ok(Map.of(
                    "deployment", toDeploymentMap(dep),
                    "logs", logs.stream().map(this::toLogMap).toList()
            ));
        }

        Set<String> paths = new HashSet<>();
        RepositoryFile packageJson = null;
        for (RepositoryFile f : repoFiles) {
            paths.add(f.getFilePath().toLowerCase());
            if ("package.json".equalsIgnoreCase(f.getFilePath())) {
                packageJson = f;
            }
        }

        String detectedFramework = "unknown";
        String outputDir = null;
        String errorSummary = null;

        if (paths.contains("dist/index.html")) {
            detectedFramework = "prebuilt-spa";
            outputDir = "dist";
            logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "INFO", "Detected pre-built bundle at dist/index.html.")));
        } else if (paths.contains("build/index.html")) {
            detectedFramework = "prebuilt-spa";
            outputDir = "build";
            logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "INFO", "Detected pre-built bundle at build/index.html.")));
        } else if (paths.contains("out/index.html")) {
            detectedFramework = "nextjs-export";
            outputDir = "out";
            logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "INFO", "Detected static export at out/index.html.")));
        } else if (paths.contains("index.html") && packageJson == null) {
            detectedFramework = "static";
            outputDir = "";
            logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "INFO", "Detected static HTML entry point at index.html.")));
        } else if (paths.contains("public/index.html") && packageJson == null) {
            detectedFramework = "static";
            outputDir = "public";
            logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "INFO", "Detected static HTML entry point at public/index.html.")));
        } else if (packageJson != null) {
            String pkg = packageJson.getContentText() != null ? packageJson.getContentText().toLowerCase() : "";
            if (pkg.contains("\"vite\"")) detectedFramework = "vite";
            else if (pkg.contains("\"next\"")) detectedFramework = "nextjs";
            else if (pkg.contains("\"react-scripts\"") || pkg.contains("\"react\"")) detectedFramework = "react";
            else detectedFramework = "node";

            logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "INFO",
                    "Detected package.json (" + detectedFramework + " project).")));
            logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "WARN",
                    "Checking for compiled output directory (dist/, build/, or out/)...")));

            if (paths.contains("index.html") && pkg.contains("\"vite\"")) {
                logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "WARN",
                        "Root index.html references unbundled TypeScript/JSX modules (src/main.tsx) which browsers cannot execute directly without 'npm run build'.")));
            }

            errorSummary = "Detected unbuilt " + detectedFramework.toUpperCase() + " project without a compiled dist/, build/, or out/ folder. "
                    + "Run 'npm run build' locally and push the generated dist/ folder (or connect an external Node build worker) so mygit Pages can serve the compiled bundle.";
            logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "ERROR", errorSummary)));
        } else {
            errorSummary = "No web entry point (index.html) found in root, public/, dist/, build/, or out/. Add an index.html file to deploy this repository as a live site.";
            logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "ERROR", errorSummary)));
        }

        dep.setFramework(detectedFramework);
        if (errorSummary == null) {
            String livePath = "/pages/" + repo.getOwner().getUsername() + "/" + repo.getName() + "/";
            logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "INFO",
                    "Configuring SPA route fallback and MIME content headers...")));
            logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "SUCCESS",
                    "Deployment LIVE at " + livePath)));
            dep.setStatus("LIVE");
            dep.setOutputDir(outputDir);
            dep.setLiveUrl(livePath);
        } else {
            dep.setStatus("FAILED");
            dep.setErrorSummary(errorSummary);
        }

        dep.setDurationMs(Math.max(25L, System.currentTimeMillis() - startMs));
        dep.setFinishedAt(LocalDateTime.now());
        deployments.save(dep);

        return ResponseEntity.ok(Map.of(
                "deployment", toDeploymentMap(dep),
                "logs", logs.stream().map(this::toLogMap).toList()
        ));
    }

    @Transactional(readOnly = true)
    @GetMapping(value = {
            "/api/repos/{name}/deployments",
            "/api/repos/{owner}/{name}/deployments"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listDeployments(
            @PathVariable String name,
            @PathVariable(required = false) String owner,
            @AuthenticationPrincipal CustomUser principal
    ) {
        Repository repo = resolveRepository(name, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }
        List<Map<String, Object>> list = deployments.findByRepositoryIdOrderByCreatedAtDesc(repo.getId())
                .stream().map(this::toDeploymentMap).toList();
        return ResponseEntity.ok(list);
    }

    @Transactional(readOnly = true)
    @GetMapping(value = "/api/deployments/{id}/logs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getDeploymentLogs(@PathVariable Long id) {
        Deployment dep = deployments.findById(id).orElse(null);
        if (dep == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Deployment not found"));
        }
        List<Map<String, Object>> logs = deploymentLogs.findByDeploymentIdOrderBySeqAsc(id)
                .stream().map(this::toLogMap).toList();
        return ResponseEntity.ok(Map.of(
                "deployment", toDeploymentMap(dep),
                "logs", logs
        ));
    }

    @Transactional(readOnly = true)
    @GetMapping(value = "/api/deployments/{id}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamDeploymentLogs(@PathVariable Long id) {
        SseEmitter emitter = new SseEmitter(15000L);
        try {
            Deployment dep = deployments.findById(id).orElse(null);
            if (dep == null) {
                emitter.complete();
                return emitter;
            }
            List<DeploymentLog> logs = deploymentLogs.findByDeploymentIdOrderBySeqAsc(id);
            for (DeploymentLog l : logs) {
                emitter.send(SseEmitter.event().name("log").data(toLogMap(l)));
            }
            emitter.send(SseEmitter.event().name("status").data(toDeploymentMap(dep)));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @Transactional(readOnly = true)
    @GetMapping(value = {
            "/pages/{owner}/{repo}",
            "/pages/{owner}/{repo}/**"
    })
    public ResponseEntity<byte[]> servePages(
            @PathVariable String owner,
            @PathVariable String repo,
            HttpServletRequest request
    ) {
        Repository r = repos.findByOwnerUsernameAndName(owner, repo).orElse(null);
        if (r == null || r.isPrivate()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body("<h1>404 - Site not found or repository is private</h1>".getBytes(StandardCharsets.UTF_8));
        }

        Deployment liveDep = deployments.findFirstByRepositoryIdAndStatusOrderByCreatedAtDesc(r.getId(), "LIVE").orElse(null);
        String branch = liveDep != null ? liveDep.getBranch() : "main";
        String outputDir = liveDep != null && liveDep.getOutputDir() != null ? liveDep.getOutputDir() : "";

        List<RepositoryFile> repoFiles = files.findByRepositoryIdAndBranchName(r.getId(), branch);
        if (repoFiles.isEmpty()) {
            repoFiles = files.findByRepositoryId(r.getId());
        }

        String prefix = "/pages/" + owner + "/" + repo;
        String reqUri = request.getRequestURI();
        String relPath = reqUri.length() > prefix.length() ? reqUri.substring(prefix.length()) : "";
        if (relPath.startsWith("/")) relPath = relPath.substring(1);
        if (relPath.isEmpty()) relPath = "index.html";

        String targetPath = outputDir.isEmpty() ? relPath : outputDir + "/" + relPath;
        RepositoryFile matched = findFileIgnoreCase(repoFiles, targetPath);

        // SPA fallback if no file extension
        if (matched == null && !relPath.contains(".")) {
            String fallbackPath = outputDir.isEmpty() ? "index.html" : outputDir + "/index.html";
            matched = findFileIgnoreCase(repoFiles, fallbackPath);
        }

        if (matched == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body("<h1>404 - File not found in mygit Pages</h1>".getBytes(StandardCharsets.UTF_8));
        }

        byte[] content = matched.getContentBytes() != null
                ? matched.getContentBytes()
                : (matched.getContentText() != null ? matched.getContentText().getBytes(StandardCharsets.UTF_8) : new byte[0]);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, detectMimeType(matched.getFilePath()))
                .body(content);
    }

    private RepositoryFile findFileIgnoreCase(List<RepositoryFile> list, String path) {
        for (RepositoryFile f : list) {
            if (f.getFilePath().equalsIgnoreCase(path)) {
                return f;
            }
        }
        return null;
    }

    private String detectMimeType(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".txt") || p.endsWith(".md")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }
}
