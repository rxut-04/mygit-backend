package com.mygit.backend.controller;

import com.mygit.backend.model.*;
import com.mygit.backend.repository.*;
import com.mygit.backend.security.CustomUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.*;

@RestController
public class RepositoryAnalyticsController {

    @Autowired private RepositoryRepository repos;
    @Autowired private RepositoryCommitRepository commits;
    @Autowired private RepositoryFileRepository files;
    @Autowired private DeploymentRepository deployments;
    @Autowired private IssueRepository issues;
    @Autowired private PullRequestRepository pulls;
    @Autowired private RepositoryStarRepository stars;

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

    @Transactional(readOnly = true)
    @GetMapping(value = {
            "/api/repos/{name}/analytics",
            "/api/repos/{owner}/{name}/analytics"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getRepositoryAnalytics(
            @PathVariable String name,
            @PathVariable(required = false) String owner,
            @RequestParam(defaultValue = "main") String branch,
            @RequestParam(required = false) String tz,
            @AuthenticationPrincipal CustomUser principal
    ) {
        Repository repo = resolveRepository(name, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found"));
        }

        ZoneId zone = ZoneOffset.UTC;
        if (tz != null && !tz.isBlank()) {
            try {
                zone = ZoneId.of(tz.trim());
            } catch (Exception ignored) {}
        }

        List<RepositoryCommit> repoCommits = commits.findByRepositoryIdOrderByAuthoredAtDesc(repo.getId());
        List<RepositoryFile> repoFiles = files.findByRepositoryIdAndBranchName(repo.getId(), branch);
        if (repoFiles.isEmpty()) {
            repoFiles = files.findByRepositoryId(repo.getId());
        }
        List<Deployment> repoDeps = deployments.findByRepositoryIdOrderByCreatedAtDesc(repo.getId());
        List<Issue> repoIssues = issues.findByRepositoryIdOrderByCreatedAtDesc(repo.getId());
        List<PullRequest> repoPrs = pulls.findByRepositoryIdOrderByCreatedAtDesc(repo.getId());
        long starCount = stars.countByRepositoryId(repo.getId());

        // 1. Commit activity (Daily 30d, Weekly 12w, Monthly 12m), Heatmap (365d), Contributors, Time Patterns
        LocalDate today = LocalDate.now(zone);
        Map<LocalDate, Integer> dailyMap = new HashMap<>();
        Map<String, Integer> contribMap = new LinkedHashMap<>();
        Map<String, String> contribEmails = new HashMap<>();
        int[][] timeMatrix = new int[7][24]; // 0=Sun..6=Sat

        for (RepositoryCommit c : repoCommits) {
            LocalDateTime ts = c.getAuthoredAt();
            if (ts != null) {
                ZonedDateTime zdt = ts.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone);
                LocalDate d = zdt.toLocalDate();
                dailyMap.merge(d, 1, Integer::sum);
                int dow = zdt.getDayOfWeek().getValue() % 7; // Sun=0, Mon=1..Sat=6
                timeMatrix[dow][zdt.getHour()]++;
            }
            String author = (c.getAuthorName() != null && !c.getAuthorName().isBlank()) ? c.getAuthorName().trim() : "mygit user";
            contribMap.merge(author, 1, Integer::sum);
            if (c.getAuthorEmail() != null && !c.getAuthorEmail().isBlank()) {
                contribEmails.putIfAbsent(author, c.getAuthorEmail());
            }
        }

        // Daily (last 30 days)
        DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
        List<String> dailyLabels = new ArrayList<>();
        List<Integer> dailyValues = new ArrayList<>();
        for (int i = 29; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            dailyLabels.add(d.format(dayFmt));
            dailyValues.add(dailyMap.getOrDefault(d, 0));
        }

        // Weekly (last 12 weeks)
        List<String> weeklyLabels = new ArrayList<>();
        List<Integer> weeklyValues = new ArrayList<>();
        LocalDate startOfThisWeek = today.with(WeekFields.ISO.dayOfWeek(), 1);
        for (int w = 11; w >= 0; w--) {
            LocalDate ws = startOfThisWeek.minusWeeks(w);
            int sum = 0;
            for (int d = 0; d < 7; d++) {
                sum += dailyMap.getOrDefault(ws.plusDays(d), 0);
            }
            weeklyLabels.add(ws.format(dayFmt));
            weeklyValues.add(sum);
        }

        // Monthly (last 12 months)
        DateTimeFormatter monthFmt = DateTimeFormatter.ofPattern("MMM ''yy", Locale.ENGLISH);
        List<String> monthlyLabels = new ArrayList<>();
        List<Integer> monthlyValues = new ArrayList<>();
        YearMonth currentYm = YearMonth.from(today);
        for (int m = 11; m >= 0; m--) {
            YearMonth ym = currentYm.minusMonths(m);
            int sum = 0;
            for (Map.Entry<LocalDate, Integer> e : dailyMap.entrySet()) {
                if (YearMonth.from(e.getKey()).equals(ym)) {
                    sum += e.getValue();
                }
            }
            monthlyLabels.add(ym.format(monthFmt));
            monthlyValues.add(sum);
        }

        // Heatmap (last 365 days)
        Map<String, Integer> heatmapDays = new TreeMap<>();
        LocalDate yearAgo = today.minusDays(365);
        for (Map.Entry<LocalDate, Integer> e : dailyMap.entrySet()) {
            if (!e.getKey().isBefore(yearAgo)) {
                heatmapDays.put(e.getKey().toString(), e.getValue());
            }
        }

        // Contributors sorted desc
        int totalCommits = repoCommits.size();
        List<Map<String, Object>> contributors = contribMap.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(10)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("email", contribEmails.getOrDefault(e.getKey(), ""));
                    m.put("commits", e.getValue());
                    m.put("sharePct", totalCommits > 0 ? Math.round((e.getValue() * 1000.0) / totalCommits) / 10.0 : 0.0);
                    return m;
                }).toList();

        // 2. Language breakdown & repo size
        Map<String, Long> langBytes = new LinkedHashMap<>();
        Map<String, Integer> langFiles = new LinkedHashMap<>();
        long totalBytes = 0;
        for (RepositoryFile f : repoFiles) {
            long sz = f.getFileSize() != null ? Math.max(f.getFileSize(), 1L) : 1L;
            totalBytes += (f.getFileSize() != null ? f.getFileSize() : 0L);
            String lang = detectLanguage(f.getFilePath());
            if (lang == null) continue;
            langBytes.merge(lang, sz, Long::sum);
            langFiles.merge(lang, 1, Integer::sum);
        }
        long totalLangBytes = langBytes.values().stream().mapToLong(Long::longValue).sum();
        List<Map<String, Object>> languages = langBytes.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(8)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("color", languageColor(e.getKey()));
                    m.put("bytes", e.getValue());
                    m.put("files", langFiles.getOrDefault(e.getKey(), 0));
                    m.put("percentage", totalLangBytes > 0 ? Math.round((e.getValue() * 1000.0) / totalLangBytes) / 10.0 : 0.0);
                    return m;
                }).toList();

        // 3. Deployments (chronological for duration chart)
        List<Deployment> depsAsc = new ArrayList<>(repoDeps);
        Collections.reverse(depsAsc);
        int liveDeps = 0;
        int failedDeps = 0;
        List<String> depLabels = new ArrayList<>();
        List<Long> depDurations = new ArrayList<>();
        List<Map<String, Object>> depHistory = new ArrayList<>();
        for (Deployment d : depsAsc) {
            if ("LIVE".equalsIgnoreCase(d.getStatus())) liveDeps++;
            else if ("FAILED".equalsIgnoreCase(d.getStatus())) failedDeps++;
            depLabels.add("#" + d.getId());
            depDurations.add(d.getDurationMs() != null ? d.getDurationMs() : 0L);
            Map<String, Object> dh = new LinkedHashMap<>();
            dh.put("id", d.getId());
            dh.put("status", d.getStatus());
            dh.put("framework", d.getFramework());
            dh.put("durationMs", d.getDurationMs() != null ? d.getDurationMs() : 0L);
            depHistory.add(dh);
        }

        // 4. Issues & PRs
        long openIssues = repoIssues.stream().filter(i -> "OPEN".equalsIgnoreCase(i.getStatus())).count();
        long closedIssues = repoIssues.size() - openIssues;

        long openPrs = repoPrs.stream().filter(p -> "OPEN".equalsIgnoreCase(p.getStatus())).count();
        long mergedPrs = repoPrs.stream().filter(p -> "MERGED".equalsIgnoreCase(p.getStatus())).count();
        long closedPrs = repoPrs.stream().filter(p -> "CLOSED".equalsIgnoreCase(p.getStatus())).count();
        double avgMergeHours = repoPrs.stream()
                .filter(p -> "MERGED".equalsIgnoreCase(p.getStatus()) && p.getCreatedAt() != null && p.getUpdatedAt() != null)
                .mapToDouble(p -> Duration.between(p.getCreatedAt(), p.getUpdatedAt()).toMinutes() / 60.0)
                .average()
                .orElse(0.0);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("repo", repo.getName());
        resp.put("owner", repo.getOwner().getUsername());
        resp.put("branch", branch);
        resp.put("commitActivity", Map.of(
                "daily", Map.of("labels", dailyLabels, "values", dailyValues),
                "weekly", Map.of("labels", weeklyLabels, "values", weeklyValues),
                "monthly", Map.of("labels", monthlyLabels, "values", monthlyValues)
        ));
        resp.put("heatmap", Map.of("days", heatmapDays, "totalCommits", totalCommits));
        resp.put("contributors", contributors);
        resp.put("languages", languages);
        resp.put("repoSize", Map.of("totalFiles", repoFiles.size(), "totalBytes", totalBytes));
        resp.put("commitTimePatterns", Map.of("matrix", timeMatrix));
        resp.put("deployments", Map.of(
                "total", repoDeps.size(),
                "live", liveDeps,
                "failed", failedDeps,
                "labels", depLabels,
                "durations", depDurations,
                "history", depHistory
        ));
        resp.put("collaboration", Map.of(
                "stars", starCount,
                "issues", Map.of("open", openIssues, "closed", closedIssues, "total", repoIssues.size()),
                "pulls", Map.of(
                        "open", openPrs,
                        "merged", mergedPrs,
                        "closed", closedPrs,
                        "total", repoPrs.size(),
                        "avgMergeHours", Math.round(avgMergeHours * 10.0) / 10.0
                )
        ));

        return ResponseEntity.ok(resp);
    }

    private String detectLanguage(String path) {
        if (path == null) return null;
        String p = path.toLowerCase();
        if (p.endsWith(".ts") || p.endsWith(".tsx")) return "TypeScript";
        if (p.endsWith(".js") || p.endsWith(".jsx") || p.endsWith(".mjs") || p.endsWith(".cjs")) return "JavaScript";
        if (p.endsWith(".css") || p.endsWith(".scss") || p.endsWith(".sass")) return "CSS";
        if (p.endsWith(".html") || p.endsWith(".htm")) return "HTML";
        if (p.endsWith(".java")) return "Java";
        if (p.endsWith(".py")) return "Python";
        if (p.endsWith(".go")) return "Go";
        if (p.endsWith(".rs")) return "Rust";
        if (p.endsWith(".cpp") || p.endsWith(".cc") || p.endsWith(".c") || p.endsWith(".h")) return "C / C++";
        if (p.endsWith(".json")) return "JSON";
        if (p.endsWith(".md") || p.endsWith(".mdx")) return "Markdown";
        if (p.endsWith(".sql")) return "SQL";
        if (p.endsWith(".yml") || p.endsWith(".yaml")) return "YAML";
        if (p.endsWith(".sh")) return "Shell";
        return null;
    }

    private String languageColor(String lang) {
        return switch (lang) {
            case "TypeScript" -> "#3178c6";
            case "JavaScript" -> "#f1e05a";
            case "CSS" -> "#563d7c";
            case "HTML" -> "#e34c26";
            case "Java" -> "#b07219";
            case "Python" -> "#3572A5";
            case "Go" -> "#00ADD8";
            case "Rust" -> "#dea584";
            case "C / C++" -> "#f34b7d";
            case "JSON" -> "#292929";
            case "Markdown" -> "#083fa1";
            case "SQL" -> "#e38c00";
            default -> "#6e7681";
        };
    }
}
