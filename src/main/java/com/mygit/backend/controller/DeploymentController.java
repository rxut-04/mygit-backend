package com.mygit.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.InflaterInputStream;

@RestController
public class DeploymentController {

    @Autowired private RepositoryRepository repos;
    @Autowired private RepositoryFileRepository files;
    @Autowired private RepositoryCommitRepository commits;
    @Autowired private DeploymentRepository deployments;
    @Autowired private DeploymentLogRepository deploymentLogs;
    @Autowired private GitObjectRepository gitObjects;

    private final ObjectMapper mapper = new ObjectMapper();

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
        m.put("entryFile", d.getEntryFile());
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

    /**
     * Auto-deploys on push if the repository already has a deployment or a static/prebuilt entry point on main.
     */
    @Transactional
    public void autoDeployIfEligible(Repository repo, String branch) {
        if (repo == null) return;
        String targetBranch = (branch != null && !branch.isBlank()) ? branch : "main";
        boolean hasExisting = !deployments.findByRepositoryIdOrderByCreatedAtDesc(repo.getId()).isEmpty();
        if (!hasExisting && !"main".equalsIgnoreCase(targetBranch)) return;

        List<RepositoryFile> repoFiles = files.findByRepositoryIdAndBranchName(repo.getId(), targetBranch);
        boolean looksWeb = repoFiles.stream().anyMatch(f -> {
            String p = f.getFilePath().toLowerCase();
            return p.endsWith("index.html") || p.endsWith("package.json");
        });
        if (hasExisting || looksWeb) {
            executeDeployment(repo, targetBranch, true);
        }
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

        String branch = (body != null && body.get("branch") != null && !body.get("branch").isBlank())
                ? body.get("branch")
                : "main";
        Map<String, Object> result = executeDeployment(repo, branch, false);
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> executeDeployment(Repository repo, String branch, boolean isAuto) {
        long startMs = System.currentTimeMillis();
        List<RepositoryCommit> repoCommits = commits.findByRepositoryIdOrderByAuthoredAtDesc(repo.getId());
        String latestSha = repoCommits.isEmpty() ? null : repoCommits.get(0).getCommitSha();

        Deployment dep = deployments.save(new Deployment(repo, branch, latestSha));
        List<DeploymentLog> logs = new ArrayList<>();
        int seq = 1;

        logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "INFO",
                (isAuto ? "[Auto-Deploy on push] " : "")
                        + "Initializing mygit Pages deployment for "
                        + repo.getOwner().getUsername() + "/" + repo.getName() + " (branch: " + branch + ")")));

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
            return Map.of(
                    "deployment", toDeploymentMap(dep),
                    "logs", logs.stream().map(this::toLogMap).toList()
            );
        }

        Map<String, RepositoryFile> byLowerPath = new LinkedHashMap<>();
        List<RepositoryFile> htmlFiles = new ArrayList<>();
        List<RepositoryFile> pkgFiles = new ArrayList<>();
        for (RepositoryFile f : repoFiles) {
            String lp = f.getFilePath().toLowerCase();
            byLowerPath.put(lp, f);
            if (lp.endsWith(".html") || lp.endsWith(".htm")) htmlFiles.add(f);
            if (lp.equals("package.json") || lp.endsWith("/package.json")) pkgFiles.add(f);
        }

        String detectedFramework = "unknown";
        String outputDir = null;
        String entryFile = "index.html";
        String errorSummary = null;

        // 1. Check standard & nested prebuilt output directories first (dist/, build/, out/)
        RepositoryFile prebuilt = findFirstMatching(byLowerPath, List.of(
                "dist/index.html", "build/index.html", "out/index.html"
        ));
        if (prebuilt == null) {
            for (RepositoryFile hf : htmlFiles) {
                String lp = hf.getFilePath().toLowerCase();
                if (lp.endsWith("/dist/index.html") || lp.endsWith("/build/index.html") || lp.endsWith("/out/index.html")) {
                    prebuilt = hf;
                    break;
                }
            }
        }

        if (prebuilt != null) {
            detectedFramework = prebuilt.getFilePath().toLowerCase().contains("out/") ? "nextjs-export" : "prebuilt-spa";
            outputDir = parentDir(prebuilt.getFilePath());
            entryFile = fileNameOnly(prebuilt.getFilePath());
            logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "INFO",
                    "Detected pre-built bundle at '" + prebuilt.getFilePath() + "'.")));
        } else if (byLowerPath.containsKey("index.html") && pkgFiles.isEmpty()) {
            detectedFramework = "static";
            outputDir = "";
            entryFile = "index.html";
            logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "INFO", "Detected static HTML entry point at index.html.")));
        } else if (byLowerPath.containsKey("docs/index.html") && pkgFiles.isEmpty()) {
            detectedFramework = "static";
            outputDir = "docs";
            entryFile = "index.html";
            logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "INFO", "Detected static HTML entry point at docs/index.html.")));
        } else if (byLowerPath.containsKey("public/index.html") && pkgFiles.isEmpty()) {
            detectedFramework = "static";
            outputDir = "public";
            entryFile = "index.html";
            logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "INFO", "Detected static HTML entry point at public/index.html.")));
        } else {
            // Ambiguous / nested / custom HTML / unbuilt JS project -> invoke OpenRouter AI Project Detector
            logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "INFO",
                    "Running AI Project Detector (OpenRouter) across " + repoFiles.size() + " files...")));

            AiDetectionResult ai = detectProjectWithAi(repo, repoFiles, pkgFiles);
            if (ai != null) {
                detectedFramework = ai.framework;
                logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, ai.deployable ? "INFO" : "WARN",
                        "AI Detector (" + ai.model + "): " + ai.reason)));
                if (ai.deployable && ai.entryFile != null && !ai.entryFile.isBlank()) {
                    String candidateFull = (ai.outputDir != null && !ai.outputDir.isBlank())
                            ? ai.outputDir.replaceAll("/+$", "") + "/" + ai.entryFile
                            : ai.entryFile;
                    RepositoryFile resolved = byLowerPath.get(candidateFull.toLowerCase());
                    if (resolved != null) {
                        outputDir = parentDir(resolved.getFilePath());
                        entryFile = fileNameOnly(resolved.getFilePath());
                        logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "INFO",
                                "Resolved entry file '" + resolved.getFilePath() + "' (outputDir: '" + outputDir + "').")));
                    } else {
                        errorSummary = "AI detector suggested '" + candidateFull + "', which does not exist in branch '" + branch + "'.";
                        logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "ERROR", errorSummary)));
                    }
                } else {
                    errorSummary = ai.reason;
                    logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "ERROR", errorSummary)));
                }
            } else {
                // Deterministic fallback if OpenRouter key is missing or unreachable
                if (!pkgFiles.isEmpty()) {
                    RepositoryFile pkgFile = pkgFiles.get(0);
                    String pkg = pkgFile.getContentText() != null ? pkgFile.getContentText().toLowerCase() : "";
                    if (pkg.contains("\"vite\"")) detectedFramework = "vite";
                    else if (pkg.contains("\"next\"")) detectedFramework = "nextjs";
                    else if (pkg.contains("\"react-scripts\"") || pkg.contains("\"react\"")) detectedFramework = "react";
                    else detectedFramework = "node";

                    errorSummary = "Detected unbuilt " + detectedFramework.toUpperCase()
                            + " project (" + pkgFile.getFilePath() + ") without a compiled dist/, build/, or out/ folder. "
                            + "Run 'npm run build' locally and push the generated dist/ folder so mygit Pages can serve it.";
                    logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "ERROR", errorSummary)));
                } else if (htmlFiles.size() == 1) {
                    RepositoryFile onlyHtml = htmlFiles.get(0);
                    detectedFramework = "static";
                    outputDir = parentDir(onlyHtml.getFilePath());
                    entryFile = fileNameOnly(onlyHtml.getFilePath());
                    logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "INFO",
                            "Auto-selected single HTML entry point '" + onlyHtml.getFilePath() + "'.")));
                } else {
                    errorSummary = "No web entry point (index.html) found in root, public/, docs/, dist/, build/, or out/. Add an index.html file to deploy.";
                    logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "ERROR", errorSummary)));
                }
            }
        }

        dep.setFramework(detectedFramework);
        if (errorSummary == null) {
            String livePath = "/pages/" + repo.getOwner().getUsername() + "/" + repo.getName() + "/";
            logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "INFO",
                    "Configuring HTML <base> asset rewriting, MIME headers, and SPA fallback...")));
            logs.add(deploymentLogs.save(new DeploymentLog(dep, seq++, "SUCCESS",
                    "Deployment LIVE at " + livePath)));
            dep.setStatus("LIVE");
            dep.setOutputDir(outputDir != null ? outputDir : "");
            dep.setEntryFile(entryFile);
            dep.setLiveUrl(livePath);
        } else {
            dep.setStatus("FAILED");
            dep.setErrorSummary(errorSummary);
        }

        dep.setDurationMs(Math.max(25L, System.currentTimeMillis() - startMs));
        dep.setFinishedAt(LocalDateTime.now());
        deployments.save(dep);

        return Map.of(
                "deployment", toDeploymentMap(dep),
                "logs", logs.stream().map(this::toLogMap).toList()
        );
    }

    private static class AiDetectionResult {
        boolean deployable;
        String framework;
        String outputDir;
        String entryFile;
        String reason;
        String model;
    }

    private AiDetectionResult detectProjectWithAi(Repository repo, List<RepositoryFile> repoFiles, List<RepositoryFile> pkgFiles) {
        String apiKey = System.getenv("OPENROUTER_API_KEY");
        if (apiKey == null || apiKey.isBlank()) return null;
        String model = System.getenv("OPENROUTER_MODEL");
        if (model == null || model.isBlank()) model = "nvidia/nemotron-3-ultra-550b-a55b:free";

        StringBuilder sb = new StringBuilder();
        sb.append("Repository: ").append(repo.getOwner().getUsername()).append("/").append(repo.getName()).append("\nFiles:\n");
        for (int i = 0; i < Math.min(repoFiles.size(), 80); i++) {
            sb.append("- ").append(repoFiles.get(i).getFilePath()).append("\n");
        }
        for (int i = 0; i < Math.min(pkgFiles.size(), 2); i++) {
            RepositoryFile p = pkgFiles.get(i);
            String txt = p.getContentText() != null ? p.getContentText() : "";
            sb.append("\n").append(p.getFilePath()).append(":\n")
              .append(txt, 0, Math.min(txt.length(), 1500)).append("\n");
        }

        String prompt = "You are a static-site deployment detector for mygit Pages (which serves pre-built HTML/CSS/JS bundles directly without running npm build on the server).\n"
                + "Analyze the file list and package.json below. Return ONLY valid JSON (no markdown fences) with keys:\n"
                + "{\"deployable\": boolean, \"framework\": string, \"outputDir\": string, \"entryFile\": string, \"reason\": string}\n"
                + "Rules:\n"
                + "- deployable=true ONLY if there is a ready-to-serve HTML entry point (e.g. static HTML files, docs/index.html, or prebuilt dist/build/out folder).\n"
                + "- deployable=false if it is an unbuilt React/Vite/Next/Angular/Vue source project missing dist/build/out, OR a non-web backend/CLI repo with no HTML.\n"
                + "- outputDir should be the folder containing entryFile (\"\" for repo root).\n\n"
                + sb;

        try {
            Map<String, Object> payload = Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", prompt))
            );
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://openrouter.ai/api/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(25))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("HTTP-Referer", "https://the-only-git-platform-you-need.netlify.app")
                    .header("X-Title", "mygit")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) return null;

            JsonNode root = mapper.readTree(resp.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("").trim();
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end <= start) return null;
            JsonNode json = mapper.readTree(content.substring(start, end + 1));

            AiDetectionResult res = new AiDetectionResult();
            res.deployable = json.path("deployable").asBoolean(false);
            res.framework = json.path("framework").asText("unknown");
            res.outputDir = json.path("outputDir").asText("");
            res.entryFile = json.path("entryFile").asText("index.html");
            res.reason = json.path("reason").asText("Analyzed by AI Project Detector.");
            res.model = model;
            return res;
        } catch (Exception e) {
            return null;
        }
    }

    private RepositoryFile findFirstMatching(Map<String, RepositoryFile> map, List<String> candidates) {
        for (String c : candidates) {
            if (map.containsKey(c.toLowerCase())) return map.get(c.toLowerCase());
        }
        return null;
    }

    private String parentDir(String path) {
        int idx = path.lastIndexOf('/');
        return idx > 0 ? path.substring(0, idx) : "";
    }

    private String fileNameOnly(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
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
            String reason = r == null
                    ? "Repository not found."
                    : "Repository is private — make it public in repository settings to view mygit Pages.";
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body(("<!doctype html><html><body style=\"font-family:system-ui,sans-serif;background:#0d1117;color:#e6edf3;padding:48px;text-align:center\">"
                            + "<h2>404 — mygit Pages unavailable</h2><p style=\"color:#8b949e\">" + reason + "</p></body></html>")
                            .getBytes(StandardCharsets.UTF_8));
        }

        Deployment liveDep = deployments.findFirstByRepositoryIdAndStatusOrderByCreatedAtDesc(r.getId(), "LIVE").orElse(null);
        String branch = liveDep != null ? liveDep.getBranch() : "main";
        String outputDir = liveDep != null && liveDep.getOutputDir() != null ? liveDep.getOutputDir() : "";
        String entryFile = liveDep != null ? liveDep.getEntryFile() : "index.html";

        List<RepositoryFile> repoFiles = files.findByRepositoryIdAndBranchName(r.getId(), branch);
        if (repoFiles.isEmpty()) {
            repoFiles = files.findByRepositoryId(r.getId());
        }

        String prefix = "/pages/" + owner + "/" + repo;
        String reqUri = request.getRequestURI();
        String relPath = reqUri.length() > prefix.length() ? reqUri.substring(prefix.length()) : "";
        if (relPath.startsWith("/")) relPath = relPath.substring(1);
        if (relPath.isEmpty()) relPath = entryFile;

        String targetPath = outputDir.isEmpty() ? relPath : outputDir + "/" + relPath;
        RepositoryFile matched = findFileIgnoreCase(repoFiles, targetPath);

        // SPA fallback if no file extension
        if (matched == null && !relPath.contains(".")) {
            String fallbackPath = outputDir.isEmpty() ? entryFile : outputDir + "/" + entryFile;
            matched = findFileIgnoreCase(repoFiles, fallbackPath);
        }

        // Custom 404.html fallback if present
        if (matched == null) {
            String custom404 = outputDir.isEmpty() ? "404.html" : outputDir + "/404.html";
            RepositoryFile f404 = findFileIgnoreCase(repoFiles, custom404);
            if (f404 != null) {
                String html = f404.getContentText() != null ? rewriteHtmlPaths(f404.getContentText(), owner, repo) : "";
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.TEXT_HTML)
                        .body(html.getBytes(StandardCharsets.UTF_8));
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body("<h1>404 - File not found in mygit Pages</h1>".getBytes(StandardCharsets.UTF_8));
        }

        String mime = detectMimeType(matched.getFilePath());
        byte[] raw = resolveRawFileBytes(r, matched);
        byte[] content;
        if (mime.startsWith("text/html")) {
            String html = new String(raw, StandardCharsets.UTF_8);
            content = rewriteHtmlPaths(html, owner, repo).getBytes(StandardCharsets.UTF_8);
        } else if (mime.startsWith("text/css")) {
            String css = new String(raw, StandardCharsets.UTF_8);
            content = rewriteCssPaths(css, owner, repo).getBytes(StandardCharsets.UTF_8);
        } else {
            content = raw;
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, mime)
                .body(content);
    }

    /**
     * Resolves full untruncated bytes for a repository file, inflating the underlying git blob object
     * when binary bytes are not cached on RepositoryFile or when contentText was truncated during CLI push.
     */
    private byte[] resolveRawFileBytes(Repository r, RepositoryFile matched) {
        if (matched.getContentBytes() != null && matched.getContentBytes().length > 0) {
            return matched.getContentBytes();
        }
        String text = matched.getContentText();
        boolean needsBlob = matched.isBinary()
                || text == null
                || (matched.getFileSize() != null && matched.getFileSize() > text.getBytes(StandardCharsets.UTF_8).length);
        if (needsBlob && matched.getBlobSha() != null && !matched.getBlobSha().isBlank()) {
            GitObject obj = gitObjects.findByRepositoryIdAndSha(r.getId(), matched.getBlobSha()).orElse(null);
            if (obj != null && obj.getData() != null) {
                try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(obj.getData()))) {
                    byte[] decompressed = iis.readAllBytes();
                    int nullIdx = -1;
                    for (int i = 0; i < decompressed.length; i++) {
                        if (decompressed[i] == 0) {
                            nullIdx = i;
                            break;
                        }
                    }
                    if (nullIdx >= 0 && nullIdx + 1 <= decompressed.length) {
                        return Arrays.copyOfRange(decompressed, nullIdx + 1, decompressed.length);
                    }
                } catch (Exception ignored) {}
            }
        }
        return text != null ? text.getBytes(StandardCharsets.UTF_8) : new byte[0];
    }

    /**
     * Rewrites root-relative src="/..." and href="/..." paths, injects <base>, and normalizes
     * client-side SPA router paths so Vite/React SPAs without a custom basename render at /pages/{owner}/{repo}/.
     */
    private String rewriteHtmlPaths(String html, String owner, String repo) {
        String prefix = "/pages/" + owner + "/" + repo;
        String base = prefix + "/";
        String out = html
                .replaceAll("(src|href)=([\"'])/(?!/)", "$1=$2" + base)
                .replace(base + "pages/", "/pages/");
        if (!out.toLowerCase().contains("<base ")) {
            String spaFix = "<script>(function(){var p=\"" + prefix + "\",l=window.location.pathname;if(l===p||l.indexOf(p+\"/\")===0){var s=l.slice(p.length)||\"/\";window.history.replaceState(window.history.state,\"\",s+window.location.search+window.location.hash);}})();</script>";
            String baseTag = "<base href=\"" + base + "\">" + spaFix;
            String updated = out.replaceFirst("(?i)<head(\\s[^>]*)?>", "$0" + baseTag);
            out = updated.equals(out) ? baseTag + out : updated;
        }
        return out;
    }

    /**
     * Rewrites root-relative url('/...') and url("/...") in CSS files under /pages/{owner}/{repo}/.
     */
    private String rewriteCssPaths(String css, String owner, String repo) {
        String base = "/pages/" + owner + "/" + repo + "/";
        return css
                .replaceAll("url\\(([\"']?)/(?!/)", "url($1" + base)
                .replace(base + "pages/", "/pages/");
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
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".otf")) return "font/otf";
        if (p.endsWith(".eot")) return "application/vnd.ms-fontobject";
        if (p.endsWith(".mp4")) return "video/mp4";
        if (p.endsWith(".webm")) return "video/webm";
        if (p.endsWith(".wasm")) return "application/wasm";
        if (p.endsWith(".xml")) return "application/xml; charset=utf-8";
        if (p.endsWith(".webmanifest")) return "application/manifest+json; charset=utf-8";
        if (p.endsWith(".txt") || p.endsWith(".md")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }
}
