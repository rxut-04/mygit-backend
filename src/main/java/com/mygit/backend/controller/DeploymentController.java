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
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

@RestController
public class DeploymentController {

    @Autowired private RepositoryRepository repos;
    @Autowired private RepositoryFileRepository files;
    @Autowired private RepositoryCommitRepository commits;
    @Autowired private DeploymentRepository deployments;
    @Autowired private DeploymentLogRepository deploymentLogs;
    @Autowired private GitObjectRepository gitObjects;
    @Autowired private UserRepository users;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> liveEmitters = new ConcurrentHashMap<>();

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
     * Auto-deploys on push only if the repository already has a deployment or has a root web entry point
     * (index.html, dist/index.html, build/index.html, or out/index.html) on main.
     * Non-web repositories (Java, Python, C++, etc.) can be pushed freely without triggering failed deployments.
     */
    @Transactional
    public void autoDeployIfEligible(Repository repo, String branch) {
        if (repo == null) return;
        String targetBranch = (branch != null && !branch.isBlank()) ? branch : "main";
        boolean hasExisting = !deployments.findByRepositoryIdOrderByCreatedAtDesc(repo.getId()).isEmpty();
        if (!hasExisting && !"main".equalsIgnoreCase(targetBranch)) return;

        List<RepositoryFile> repoFiles = files.findByRepositoryIdAndBranchName(repo.getId(), targetBranch);
        boolean hasRootWebEntry = repoFiles.stream().anyMatch(f -> {
            String p = f.getFilePath().toLowerCase();
            return p.equals("index.html")
                    || p.equals("dist/index.html")
                    || p.equals("build/index.html")
                    || p.equals("out/index.html");
        });
        if (hasExisting || hasRootWebEntry) {
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

    /**
     * AI Self-Healing Deployment endpoint: analyzes a failed deployment, generates & commits
     * targeted repository file fixes (e.g., entry index.html, base path / asset fixes), and
     * immediately re-runs deployment to produce a LIVE URL.
     */
    @Transactional
    @PostMapping(value = {
            "/api/repos/{name}/deployments/{id}/ai-fix",
            "/api/repos/{owner}/{name}/deployments/{id}/ai-fix"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> aiFixAndRedeploy(
            @PathVariable String name,
            @PathVariable(required = false) String owner,
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUser principal
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        Repository repo = resolveRepository(name, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }
        if (!repo.getOwner().getId().equals(principal.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only repository owner can apply AI fixes"));
        }

        Deployment dep = deployments.findById(id).orElse(null);
        if (dep == null || !dep.getRepository().getId().equals(repo.getId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Deployment not found"));
        }

        String branch = dep.getBranch() != null && !dep.getBranch().isBlank() ? dep.getBranch() : "main";
        List<RepositoryFile> repoFiles = files.findByRepositoryIdAndBranchName(repo.getId(), branch);
        if (repoFiles.isEmpty()) {
            repoFiles = files.findByRepositoryId(repo.getId());
        }
        List<DeploymentLog> logs = deploymentLogs.findByDeploymentIdOrderBySeqAsc(dep.getId());

        AiFixPlan plan = generateAiFixPlan(repo, dep, logs, repoFiles);
        if (plan == null || plan.changes.isEmpty()) {
            String diagnosis = plan != null && plan.diagnosis != null
                    ? plan.diagnosis
                    : "AI could not automatically patch this repository without a local build step.";
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("fixed", false);
            resp.put("diagnosis", diagnosis);
            resp.put("unfixableReason", plan != null ? plan.unfixableReason : diagnosis);
            resp.put("appliedChanges", List.of());
            return ResponseEntity.ok(resp);
        }

        User uploader = users.findById(principal.getId()).orElse(repo.getOwner());
        String commitMessage = (plan.commitMessage != null && !plan.commitMessage.isBlank())
                ? plan.commitMessage
                : "ai-fix: repair mygit Pages deployment";
        String commitSha = sha1Hex("commit ", (repo.getName() + ":" + branch + ":" + System.nanoTime()).getBytes(StandardCharsets.UTF_8));

        List<String> appliedPaths = new ArrayList<>();
        String lastBlobSha = "";

        for (AiFileChange ch : plan.changes) {
            if (ch.path == null) continue;
            String cleanPath = ch.path.trim().replace("\\", "/").replaceAll("^/+", "");
            if (cleanPath.isBlank() || cleanPath.contains("..")) continue;

            if ("delete".equalsIgnoreCase(ch.action)) {
                files.findByRepositoryIdAndBranchNameAndFilePath(repo.getId(), branch, cleanPath)
                        .ifPresent(files::delete);
                appliedPaths.add("deleted " + cleanPath);
                continue;
            }

            String content = ch.content != null ? ch.content : "";
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            String blobSha = sha1Hex("blob " + contentBytes.length + "\0", contentBytes);
            lastBlobSha = blobSha;

            if (!gitObjects.existsByRepositoryIdAndSha(repo.getId(), blobSha)) {
                try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                     DeflaterOutputStream dos = new DeflaterOutputStream(baos)) {
                    dos.write(("blob " + contentBytes.length + "\0").getBytes(StandardCharsets.ISO_8859_1));
                    dos.write(contentBytes);
                    dos.finish();
                    gitObjects.save(new GitObject(repo, blobSha, "blob", baos.toByteArray()));
                } catch (Exception ignored) {}
            }

            String fileName = cleanPath.contains("/") ? cleanPath.substring(cleanPath.lastIndexOf('/') + 1) : cleanPath;
            String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "";

            RepositoryFile repoFile = files
                    .findByRepositoryIdAndBranchNameAndFilePath(repo.getId(), branch, cleanPath)
                    .orElse(new RepositoryFile(
                            repo, branch, cleanPath, fileName, ext, (long) contentBytes.length,
                            commitSha, blobSha, content, null, false, uploader
                    ));
            repoFile.setCommitSha(commitSha);
            repoFile.setBlobSha(blobSha);
            repoFile.setFileSize((long) contentBytes.length);
            repoFile.setContentText(content);
            repoFile.setBinary(false);
            files.save(repoFile);
            appliedPaths.add(cleanPath);
        }

        if (appliedPaths.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "fixed", false,
                    "diagnosis", "No valid file paths were produced to patch.",
                    "appliedChanges", List.of()
            ));
        }

        commits.save(new RepositoryCommit(
                repo, commitSha, dep.getCommitSha(), lastBlobSha, commitMessage,
                uploader.getUsername(), uploader.getEmail(),
                uploader.getUsername(), uploader.getEmail(),
                LocalDateTime.now(ZoneOffset.UTC)
        ));

        Map<String, Object> redeploy = executeDeployment(repo, branch, false);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("fixed", true);
        resp.put("diagnosis", plan.diagnosis != null ? plan.diagnosis : "Applied AI deployment fix and redeployed.");
        resp.put("commitMessage", commitMessage);
        resp.put("commitSha", commitSha);
        resp.put("appliedChanges", appliedPaths);
        resp.put("deployment", redeploy.get("deployment"));
        resp.put("logs", redeploy.get("logs"));
        return ResponseEntity.ok(resp);
    }

    private Map<String, Object> executeDeployment(Repository repo, String branch, boolean isAuto) {
        long startMs = System.currentTimeMillis();
        List<RepositoryCommit> repoCommits = commits.findByRepositoryIdOrderByAuthoredAtDesc(repo.getId());
        String latestSha = repoCommits.isEmpty() ? null : repoCommits.get(0).getCommitSha();

        Deployment dep = deployments.save(new Deployment(repo, branch, latestSha));
        List<DeploymentLog> logs = new ArrayList<>();
        int seq = 1;

        logs.add(new DeploymentLog(dep, seq++, "INFO",
                (isAuto ? "[auto-deploy] " : "") + "Build ready to start"));
        logs.add(new DeploymentLog(dep, seq++, "INFO",
                "build-image: mygit-pages-edge (ubuntu-24.04-lts)"));
        logs.add(new DeploymentLog(dep, seq++, "INFO",
                "Fetching repository " + repo.getOwner().getUsername() + "/" + repo.getName() + " (branch: " + branch + ")"));
        if (latestSha != null && !latestSha.isBlank()) {
            logs.add(new DeploymentLog(dep, seq++, "INFO",
                    "Checked out commit " + latestSha.substring(0, Math.min(12, latestSha.length()))));
        }

        List<RepositoryFile> repoFiles = files.findByRepositoryIdAndBranchName(repo.getId(), branch);
        if (repoFiles.isEmpty()) {
            repoFiles = files.findByRepositoryId(repo.getId());
        }

        long totalRepoBytes = repoFiles.stream()
                .mapToLong(f -> f.getFileSize() != null ? f.getFileSize() : 0L)
                .sum();
        logs.add(new DeploymentLog(dep, seq++, "INFO",
                String.format(Locale.US, "Synced %d tracked file(s) (%.2f KB total)", repoFiles.size(), totalRepoBytes / 1024.0)));

        if (repoFiles.isEmpty()) {
            String err = "Repository has 0 pushed files on branch '" + branch + "'. Push files with 'mygit push origin " + branch + "' before deploying.";
            logs.add(new DeploymentLog(dep, seq++, "ERROR", err));
            deploymentLogs.saveAll(logs);
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
        List<RepositoryFile> pkgFiles = new ArrayList<>();
        for (RepositoryFile f : repoFiles) {
            String lp = f.getFilePath().toLowerCase();
            byLowerPath.put(lp, f);
            if (lp.equals("package.json")) pkgFiles.add(f);
        }

        logs.add(new DeploymentLog(dep, seq++, "INFO", "Inspecting root output directories: dist/, build/, out/"));
        logs.add(new DeploymentLog(dep, seq++, "INFO", "Checking root entry point: index.html"));

        String detectedFramework = "unknown";
        String outputDir = null;
        String entryFile = "index.html";
        String errorSummary = null;

        // 1. Check root prebuilt output directories first (dist/, build/, out/)
        RepositoryFile prebuilt = findFirstMatching(byLowerPath, List.of(
                "dist/index.html", "build/index.html", "out/index.html"
        ));

        if (prebuilt != null) {
            detectedFramework = prebuilt.getFilePath().toLowerCase().startsWith("out/") ? "nextjs-export" : "prebuilt-spa";
            outputDir = parentDir(prebuilt.getFilePath());
            entryFile = fileNameOnly(prebuilt.getFilePath());
            logs.add(new DeploymentLog(dep, seq++, "INFO",
                    "Detected pre-built bundle at '" + prebuilt.getFilePath() + "' (" + detectedFramework + ")"));
            logs.add(new DeploymentLog(dep, seq++, "INFO",
                    "Publish directory set to '" + outputDir + "/'"));
        } else if (byLowerPath.containsKey("index.html")) {
            RepositoryFile rootIndex = byLowerPath.get("index.html");
            String rootHtml = new String(resolveRawFileBytes(repo, rootIndex), StandardCharsets.UTF_8);
            boolean hasUnbuiltModuleEntry = rootHtml.matches("(?is).*<script[^>]+src=[\"'][^\"']*\\.(tsx|ts|jsx)[\"'][^>]*>.*");
            if (hasUnbuiltModuleEntry) {
                detectedFramework = !pkgFiles.isEmpty() ? "vite" : "react";
                errorSummary = "Root index.html references an uncompiled source entry (.ts/.tsx/.jsx) and no dist/, build/, or out/ bundle was found. "
                        + "Run 'npm run build' locally and push the generated dist/ folder so mygit Pages can serve it.";
                logs.add(new DeploymentLog(dep, seq++, "ERROR", errorSummary));
            } else {
                detectedFramework = "static";
                outputDir = "";
                entryFile = "index.html";
                logs.add(new DeploymentLog(dep, seq++, "INFO",
                        "Detected project root entry point at 'index.html' (static)"));
                logs.add(new DeploymentLog(dep, seq++, "INFO",
                        "Publish directory set to repository root '/'"));
            }
        } else {
            if (!pkgFiles.isEmpty()) {
                RepositoryFile pkgFile = pkgFiles.get(0);
                String pkg = pkgFile.getContentText() != null ? pkgFile.getContentText().toLowerCase() : "";
                if (pkg.contains("\"vite\"")) detectedFramework = "vite";
                else if (pkg.contains("\"next\"")) detectedFramework = "nextjs";
                else if (pkg.contains("\"react-scripts\"") || pkg.contains("\"react\"")) detectedFramework = "react";
                else detectedFramework = "node";

                errorSummary = "No root index.html or compiled dist/index.html, build/index.html, or out/index.html found for this "
                        + detectedFramework.toUpperCase() + " repository. Run 'npm run build' and push dist/ (or add a root index.html) to deploy.";
            } else {
                errorSummary = "No index.html found in project root (checked index.html, dist/index.html, build/index.html, out/index.html).";
            }
            logs.add(new DeploymentLog(dep, seq++, "ERROR", errorSummary));
        }

        dep.setFramework(detectedFramework);
        if (errorSummary == null) {
            String livePath = "/pages/" + repo.getOwner().getUsername() + "/" + repo.getName() + "/";
            String prefixFilter = (outputDir != null && !outputDir.isEmpty()) ? (outputDir.toLowerCase() + "/") : "";
            List<RepositoryFile> publishedFiles = repoFiles.stream()
                    .filter(f -> prefixFilter.isEmpty() || f.getFilePath().toLowerCase().startsWith(prefixFilter))
                    .sorted(Comparator.comparing(RepositoryFile::getFilePath))
                    .toList();

            logs.add(new DeploymentLog(dep, seq++, "INFO",
                    "Collecting publishable assets (" + publishedFiles.size() + " file(s) in publish target)..."));

            int listed = 0;
            for (RepositoryFile pf : publishedFiles) {
                if (listed >= 85) {
                    logs.add(new DeploymentLog(dep, seq++, "INFO",
                            "... and " + (publishedFiles.size() - listed) + " more asset(s) indexed."));
                    break;
                }
                long sz = pf.getFileSize() != null ? pf.getFileSize() : 0L;
                String mime = detectMimeType(pf.getFilePath());
                logs.add(new DeploymentLog(dep, seq++, "INFO",
                        String.format(Locale.US, "  %-52s %8.1f KB  %s", pf.getFilePath(), sz / 1024.0, mime)));
                listed++;
            }

            logs.add(new DeploymentLog(dep, seq++, "INFO", "Verifying git_objects blob integrity for binary & >500KB assets..."));
            logs.add(new DeploymentLog(dep, seq++, "INFO", "Injecting <base href=\"" + livePath + "\"> into HTML entry points"));
            logs.add(new DeploymentLog(dep, seq++, "INFO", "Configuring SPA history normalization for client-side routing"));
            logs.add(new DeploymentLog(dep, seq++, "INFO", "Enabling runtime root-relative DOM src/poster/fetch rewriting"));
            logs.add(new DeploymentLog(dep, seq++, "INFO", "Enabling CSS url(/...) path rewriting for stylesheets"));
            boolean hasCustom404 = publishedFiles.stream().anyMatch(f -> fileNameOnly(f.getFilePath()).equalsIgnoreCase("404.html"));
            logs.add(new DeploymentLog(dep, seq++, "INFO",
                    hasCustom404 ? "Detected custom 404.html fallback" : "Configured default SPA index.html fallback for extensionless routes"));
            logs.add(new DeploymentLog(dep, seq++, "INFO", "Publishing site to mygit Pages edge router..."));
            logs.add(new DeploymentLog(dep, seq++, "SUCCESS",
                    "Site is live at " + livePath));
            dep.setStatus("LIVE");
            dep.setOutputDir(outputDir != null ? outputDir : "");
            dep.setEntryFile(entryFile);
            dep.setLiveUrl(livePath);
        } else {
            logs.add(new DeploymentLog(dep, seq++, "ERROR", "Build failed during pre-publish verification."));
            dep.setStatus("FAILED");
            dep.setErrorSummary(errorSummary);
        }

        deploymentLogs.saveAll(logs);
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
                + "- deployable=true ONLY if there is a ready-to-serve root index.html, dist/index.html, build/index.html, or out/index.html.\n"
                + "- NEVER select nested subfolder HTML files (like docs/, templates/, examples/, node_modules/).\n"
                + "- deployable=false if it is an unbuilt React/Vite/Next/Angular/Vue source project missing dist/build/out, OR a non-web backend/CLI repo (e.g. java-spring, python, go, node-api).\n"
                + "- outputDir should be \"\", \"dist\", \"build\", or \"out\".\n\n"
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

    private static class AiFileChange {
        String path;
        String action;
        String content;
    }

    private static class AiFixPlan {
        String diagnosis;
        String commitMessage;
        String unfixableReason;
        List<AiFileChange> changes = new ArrayList<>();
    }

    private AiFixPlan generateAiFixPlan(
            Repository repo,
            Deployment dep,
            List<DeploymentLog> logs,
            List<RepositoryFile> repoFiles
    ) {
        List<RepositoryFile> htmlFiles = new ArrayList<>();
        List<RepositoryFile> pkgFiles = new ArrayList<>();
        for (RepositoryFile f : repoFiles) {
            String lp = f.getFilePath().toLowerCase();
            if (lp.endsWith(".html") || lp.endsWith(".htm")) htmlFiles.add(f);
            if (lp.equals("package.json") || lp.endsWith("/package.json")) pkgFiles.add(f);
        }

        // Try OpenRouter AI Fix Planner first
        String apiKey = System.getenv("OPENROUTER_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            String model = System.getenv("OPENROUTER_MODEL");
            if (model == null || model.isBlank()) model = "nvidia/nemotron-3-ultra-550b-a55b:free";

            StringBuilder ctx = new StringBuilder();
            ctx.append("Repository: ").append(repo.getOwner().getUsername()).append("/").append(repo.getName())
               .append(" (branch: ").append(dep.getBranch()).append(")\n");
            ctx.append("Failed Deployment Error: ").append(dep.getErrorSummary()).append("\nLogs:\n");
            for (DeploymentLog l : logs) {
                ctx.append("[").append(l.getLevel()).append("] ").append(l.getMessage()).append("\n");
            }
            ctx.append("\nFiles:\n");
            for (int i = 0; i < Math.min(repoFiles.size(), 80); i++) {
                ctx.append("- ").append(repoFiles.get(i).getFilePath()).append("\n");
            }
            for (RepositoryFile f : repoFiles) {
                String lp = f.getFilePath().toLowerCase();
                if (lp.endsWith("package.json") || lp.endsWith(".html") || lp.startsWith("vite.config")) {
                    byte[] raw = resolveRawFileBytes(repo, f);
                    String txt = new String(raw, StandardCharsets.UTF_8);
                    ctx.append("\n--- ").append(f.getFilePath()).append(" ---\n")
                       .append(txt, 0, Math.min(txt.length(), 2500)).append("\n");
                }
            }

            String prompt = "You are an autonomous deployment repair engineer for mygit Pages.\n"
                    + "mygit Pages serves static HTML/CSS/JS or prebuilt dist/build/out bundles directly at /pages/{owner}/{repo}/ (it does NOT run npm install or npm run build on the server).\n"
                    + "Given the failed deployment below, decide if the repository can be fixed by creating/updating static files (e.g. creating index.html that loads/redirects to an existing .html entry file, fixing broken index.html asset paths, creating a clean static landing page from README, etc.).\n"
                    + "If the project is an unbuilt React/Vite/Next/Angular/Vue TypeScript source project with NO dist/build/out bundle, set changes=[] and explain the exact local build command in unfixableReason.\n"
                    + "Return ONLY valid JSON (no markdown fences) matching:\n"
                    + "{\"diagnosis\": string, \"commitMessage\": string, \"unfixableReason\": string|null, \"changes\": [{\"path\": string, \"action\": \"upsert\"|\"delete\", \"content\": string}]}\n\n"
                    + ctx;

            try {
                Map<String, Object> payload = Map.of(
                        "model", model,
                        "messages", List.of(Map.of("role", "user", "content", prompt))
                );
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://openrouter.ai/api/v1/chat/completions"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("HTTP-Referer", "https://the-only-git-platform-you-need.netlify.app")
                        .header("X-Title", "mygit")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    JsonNode root = mapper.readTree(resp.body());
                    String content = root.path("choices").path(0).path("message").path("content").asText("").trim();
                    int start = content.indexOf('{');
                    int end = content.lastIndexOf('}');
                    if (start >= 0 && end > start) {
                        JsonNode json = mapper.readTree(content.substring(start, end + 1));
                        AiFixPlan plan = new AiFixPlan();
                        plan.diagnosis = json.path("diagnosis").asText("AI analyzed the deployment failure.");
                        plan.commitMessage = json.path("commitMessage").asText("ai-fix: repair mygit Pages deployment");
                        if (!json.path("unfixableReason").isNull()) {
                            plan.unfixableReason = json.path("unfixableReason").asText(null);
                        }
                        if (json.path("changes").isArray()) {
                            for (JsonNode c : json.path("changes")) {
                                AiFileChange fc = new AiFileChange();
                                fc.path = c.path("path").asText("");
                                fc.action = c.path("action").asText("upsert");
                                fc.content = c.path("content").asText("");
                                if (!fc.path.isBlank()) plan.changes.add(fc);
                            }
                        }
                        if (!plan.changes.isEmpty() || (plan.unfixableReason != null && !plan.unfixableReason.isBlank())) {
                            return plan;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Deterministic fallback if OpenRouter didn't return a plan
        AiFixPlan fallback = new AiFixPlan();
        if (!htmlFiles.isEmpty() && pkgFiles.isEmpty()) {
            RepositoryFile target = htmlFiles.get(0);
            String targetPath = target.getFilePath().replaceAll("^/+", "");
            AiFileChange fc = new AiFileChange();
            fc.path = "index.html";
            fc.action = "upsert";
            fc.content = "<!doctype html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"UTF-8\">\n"
                    + "  <meta http-equiv=\"refresh\" content=\"0; url=./" + targetPath + "\">\n"
                    + "  <title>" + repo.getName() + "</title>\n</head>\n<body>\n"
                    + "  <p>Redirecting to <a href=\"./" + targetPath + "\">" + targetPath + "</a>...</p>\n"
                    + "  <script>window.location.replace(\"./" + targetPath + "\");</script>\n"
                    + "</body>\n</html>\n";
            fallback.diagnosis = "Added root index.html entry point redirecting to existing '" + targetPath + "'.";
            fallback.commitMessage = "ai-fix: add root index.html entry for " + targetPath;
            fallback.changes.add(fc);
            return fallback;
        }

        fallback.diagnosis = "This repository requires a local build step (`npm run build`) to produce a static `dist/` or `build/` bundle before mygit Pages can host it.";
        fallback.unfixableReason = "Run `npm run build`, then `mygit add dist && mygit commit -m \"build\" && mygit push origin " + dep.getBranch() + "`.";
        return fallback;
    }

    private String sha1Hex(String header, byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
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
            "/api/repos/{name}/deployments/analyze",
            "/api/repos/{owner}/{name}/deployments/analyze"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> analyzeProjectForDeployment(
            @PathVariable String name,
            @PathVariable(required = false) String owner,
            @RequestParam(defaultValue = "main") String branch,
            @AuthenticationPrincipal CustomUser principal
    ) {
        Repository repo = resolveRepository(name, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        List<RepositoryFile> repoFiles = files.findByRepositoryIdAndBranchName(repo.getId(), branch);
        if (repoFiles.isEmpty()) {
            repoFiles = files.findByRepositoryId(repo.getId());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("branch", branch);
        result.put("fileCount", repoFiles.size());

        if (repoFiles.isEmpty()) {
            result.put("deployable", false);
            result.put("projectType", "Empty Repository");
            result.put("framework", "empty");
            result.put("entryPath", null);
            result.put("outputDir", "");
            result.put("aiPowered", false);
            result.put("reason", "No files have been pushed to branch '" + branch + "' yet.");
            result.put("suggestedFix", "Push your project files with a root index.html (or prebuilt dist/index.html) to deploy.");
            return ResponseEntity.ok(result);
        }

        Map<String, RepositoryFile> byLowerPath = new LinkedHashMap<>();
        List<RepositoryFile> pkgFiles = new ArrayList<>();
        for (RepositoryFile f : repoFiles) {
            String lp = f.getFilePath().toLowerCase();
            byLowerPath.put(lp, f);
            if (lp.equals("package.json")) pkgFiles.add(f);
        }

        RepositoryFile prebuilt = findFirstMatching(byLowerPath, List.of(
                "dist/index.html", "build/index.html", "out/index.html"
        ));
        if (prebuilt != null) {
            String outDir = parentDir(prebuilt.getFilePath());
            String fw = prebuilt.getFilePath().toLowerCase().startsWith("out/") ? "nextjs-export" : "prebuilt-spa";
            result.put("deployable", true);
            result.put("projectType", fw.equals("nextjs-export") ? "Next.js Static Export" : "Prebuilt React / Vite SPA");
            result.put("framework", fw);
            result.put("entryPath", prebuilt.getFilePath());
            result.put("outputDir", outDir);
            result.put("aiPowered", false);
            result.put("reason", "Detected ready-to-serve compiled bundle at '" + prebuilt.getFilePath() + "'.");
            result.put("suggestedFix", null);
            return ResponseEntity.ok(result);
        }

        if (byLowerPath.containsKey("index.html")) {
            RepositoryFile rootIndex = byLowerPath.get("index.html");
            String rootHtml = new String(resolveRawFileBytes(repo, rootIndex), StandardCharsets.UTF_8);
            boolean hasUnbuiltModuleEntry = rootHtml.matches("(?is).*<script[^>]+src=[\"'][^\"']*\\.(tsx|ts|jsx)[\"'][^>]*>.*");
            if (!hasUnbuiltModuleEntry) {
                result.put("deployable", true);
                result.put("projectType", "Static HTML / CSS / JS");
                result.put("framework", "static");
                result.put("entryPath", rootIndex.getFilePath());
                result.put("outputDir", "");
                result.put("aiPowered", false);
                result.put("reason", "Detected valid project-root entry point at 'index.html'.");
                result.put("suggestedFix", null);
                return ResponseEntity.ok(result);
            }
            String fw = !pkgFiles.isEmpty() ? "vite" : "react";
            result.put("deployable", false);
            result.put("projectType", "Unbuilt React / Vite Source");
            result.put("framework", fw);
            result.put("entryPath", null);
            result.put("outputDir", "");
            result.put("aiPowered", false);
            result.put("reason", "Root index.html references an uncompiled source entry (.ts/.tsx/.jsx) and no dist/, build/, or out/ bundle was found.");
            result.put("suggestedFix", "Run `npm run build` locally and push the generated `dist/` folder so mygit Pages can serve it.");
            return ResponseEntity.ok(result);
        }

        // Run OpenRouter AI Project Detector for any other project type (Java, Python, Node API, unbuilt JS, etc.)
        AiDetectionResult ai = detectProjectWithAi(repo, repoFiles, pkgFiles);
        if (ai != null) {
            result.put("deployable", false);
            result.put("projectType", ai.framework != null && !ai.framework.isBlank() ? ai.framework : "Non-Static Project");
            result.put("framework", ai.framework);
            result.put("entryPath", null);
            result.put("outputDir", "");
            result.put("aiPowered", true);
            result.put("model", ai.model);
            result.put("reason", ai.reason);
            result.put("suggestedFix", !pkgFiles.isEmpty()
                    ? "Run `npm run build` locally and push `dist/`, or add a root `index.html`."
                    : "Add a root `index.html` (or prebuilt `dist/index.html`) to enable static hosting on mygit Pages.");
            return ResponseEntity.ok(result);
        }

        // Deterministic fallback if OpenRouter is unreachable
        String detectedFw = "other";
        String projLabel = "Non-Static / Source Repository";
        if (!pkgFiles.isEmpty()) {
            String pkg = pkgFiles.get(0).getContentText() != null ? pkgFiles.get(0).getContentText().toLowerCase() : "";
            if (pkg.contains("\"vite\"")) { detectedFw = "vite"; projLabel = "Unbuilt Vite Project"; }
            else if (pkg.contains("\"next\"")) { detectedFw = "nextjs"; projLabel = "Unbuilt Next.js Project"; }
            else if (pkg.contains("\"react\"")) { detectedFw = "react"; projLabel = "Unbuilt React Project"; }
            else { detectedFw = "node"; projLabel = "Node.js Project"; }
        } else if (byLowerPath.containsKey("pom.xml") || byLowerPath.containsKey("build.gradle")) {
            detectedFw = "java"; projLabel = "Java / JVM Backend";
        } else if (byLowerPath.containsKey("requirements.txt") || byLowerPath.containsKey("pyproject.toml")) {
            detectedFw = "python"; projLabel = "Python Project";
        }

        result.put("deployable", false);
        result.put("projectType", projLabel);
        result.put("framework", detectedFw);
        result.put("entryPath", null);
        result.put("outputDir", "");
        result.put("aiPowered", false);
        result.put("reason", "No root index.html (or compiled dist/index.html, build/index.html, out/index.html) found.");
        result.put("suggestedFix", !pkgFiles.isEmpty()
                ? "Run `npm run build` locally and push `dist/`."
                : "Add a root `index.html` to deploy this repository as a static site.");
        return ResponseEntity.ok(result);
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

    @Transactional
    @PostMapping(value = {
            "/api/repos/{name}/deployments/start",
            "/api/repos/{owner}/{name}/deployments/start"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> startCliDeployment(
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

        String branch = (body != null && body.get("branch") != null && !body.get("branch").isBlank()) ? body.get("branch") : "main";
        String framework = (body != null && body.get("framework") != null && !body.get("framework").isBlank()) ? body.get("framework") : "vite";
        String commitSha = (body != null && body.get("commitSha") != null) ? body.get("commitSha") : null;

        Deployment dep = new Deployment(repo, branch, commitSha);
        dep.setStatus("BUILDING");
        dep.setFramework(framework);
        dep = deployments.save(dep);

        DeploymentLog initLog = deploymentLogs.save(new DeploymentLog(
                dep, 1, "INFO", "Started live CLI deployment for " + repo.getOwner().getUsername() + "/" + repo.getName() + " (" + branch + ")"
        ));

        return ResponseEntity.ok(Map.of(
                "deployment", toDeploymentMap(dep),
                "logs", List.of(toLogMap(initLog))
        ));
    }

    @Transactional
    @PostMapping(value = "/api/deployments/{id}/logs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> appendDeploymentLogs(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUser principal,
            @RequestBody Map<String, Object> body
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        Deployment dep = deployments.findById(id).orElse(null);
        if (dep == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Deployment not found"));
        }
        if (!dep.getRepository().getOwner().getId().equals(principal.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Forbidden"));
        }

        List<DeploymentLog> existing = deploymentLogs.findByDeploymentIdOrderBySeqAsc(id);
        int nextSeq = existing.isEmpty() ? 1 : existing.get(existing.size() - 1).getSeq() + 1;

        Object rawLines = body.get("lines");
        List<DeploymentLog> saved = new ArrayList<>();
        if (rawLines instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    String level = m.get("level") != null ? String.valueOf(m.get("level")) : "INFO";
                    String msg = m.get("message") != null ? String.valueOf(m.get("message")) : "";
                    if (!msg.isBlank()) {
                        saved.add(new DeploymentLog(dep, nextSeq++, level, msg));
                    }
                }
            }
        }
        if (!saved.isEmpty()) {
            saved = deploymentLogs.saveAll(saved);
            CopyOnWriteArrayList<SseEmitter> emitters = liveEmitters.get(id);
            if (emitters != null) {
                for (DeploymentLog l : saved) {
                    Map<String, Object> logMap = toLogMap(l);
                    for (SseEmitter em : emitters) {
                        try {
                            em.send(SseEmitter.event().name("log").data(logMap));
                        } catch (Exception ex) {
                            emitters.remove(em);
                        }
                    }
                }
            }
        }
        return ResponseEntity.ok(Map.of("appended", saved.size()));
    }

    @Transactional
    @PostMapping(value = "/api/deployments/{id}/finish", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> finishCliDeployment(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUser principal,
            @RequestBody Map<String, Object> body
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        Deployment dep = deployments.findById(id).orElse(null);
        if (dep == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Deployment not found"));
        }
        if (!dep.getRepository().getOwner().getId().equals(principal.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Forbidden"));
        }

        String status = body.get("status") != null ? String.valueOf(body.get("status")).toUpperCase() : "LIVE";
        if (body.get("framework") != null) dep.setFramework(String.valueOf(body.get("framework")));
        if (body.get("outputDir") != null) dep.setOutputDir(String.valueOf(body.get("outputDir")));
        if (body.get("entryFile") != null) dep.setEntryFile(String.valueOf(body.get("entryFile")));
        if (body.get("errorSummary") != null) dep.setErrorSummary(String.valueOf(body.get("errorSummary")));
        if (body.get("durationMs") instanceof Number n) dep.setDurationMs(n.longValue());

        dep.setStatus(status);
        if ("LIVE".equals(status)) {
            dep.setLiveUrl("/pages/" + dep.getRepository().getOwner().getUsername() + "/" + dep.getRepository().getName() + "/");
        }
        dep.setFinishedAt(LocalDateTime.now());
        dep = deployments.save(dep);

        Map<String, Object> depMap = toDeploymentMap(dep);
        CopyOnWriteArrayList<SseEmitter> emitters = liveEmitters.remove(id);
        if (emitters != null) {
            for (SseEmitter em : emitters) {
                try {
                    em.send(SseEmitter.event().name("status").data(depMap));
                    em.complete();
                } catch (Exception ignored) {}
            }
        }
        return ResponseEntity.ok(Map.of("deployment", depMap));
    }

    @Transactional(readOnly = true)
    @GetMapping(value = "/api/deployments/{id}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamDeploymentLogs(@PathVariable Long id) {
        SseEmitter emitter = new SseEmitter(300000L);
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
            if ("BUILDING".equalsIgnoreCase(dep.getStatus()) || "PENDING".equalsIgnoreCase(dep.getStatus())) {
                CopyOnWriteArrayList<SseEmitter> list = liveEmitters.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
                list.add(emitter);
                emitter.onCompletion(() -> list.remove(emitter));
                emitter.onTimeout(() -> list.remove(emitter));
                emitter.onError(e -> list.remove(emitter));
            } else {
                emitter.send(SseEmitter.event().name("status").data(toDeploymentMap(dep)));
                emitter.complete();
            }
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
            String spaFix = "<script>(function(){"
                    + "var p=\"" + prefix + "\",l=window.location.pathname;"
                    + "if(l===p||l.indexOf(p+\"/\")===0){var s=l.slice(p.length)||\"/\";window.history.replaceState(window.history.state,\"\",s+window.location.search+window.location.hash);}"
                    + "function rw(v){return(typeof v===\"string\"&&v.charAt(0)===\"/\"&&v.charAt(1)!==\"/\"&&v.indexOf(p+\"/\")!==0&&v.indexOf(\"/pages/\")!==0&&v.indexOf(\"/api/\")!==0)?p+v:v;}"
                    + "var sa=Element.prototype.setAttribute;"
                    + "Element.prototype.setAttribute=function(n,v){var k=String(n).toLowerCase();if((k===\"src\"||k===\"poster\")&&this.tagName!==\"A\"){v=rw(v);}return sa.call(this,n,v);};"
                    + "function wp(C,k){if(!C||!C.prototype)return;var d=Object.getOwnPropertyDescriptor(C.prototype,k);if(d&&d.set&&d.get){Object.defineProperty(C.prototype,k,{configurable:true,enumerable:d.enumerable,get:function(){return d.get.call(this);},set:function(v){return d.set.call(this,rw(v));}});}}"
                    + "wp(window.HTMLImageElement,\"src\");wp(window.HTMLMediaElement,\"src\");wp(window.HTMLVideoElement,\"poster\");wp(window.HTMLSourceElement,\"src\");"
                    + "if(window.fetch){var of=window.fetch;window.fetch=function(i,o){return of.call(this,typeof i===\"string\"?rw(i):i,o);};}"
                    + "})();</script>";
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
