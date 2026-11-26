package com.mygit.backend.controller;

import com.mygit.backend.model.*;
import com.mygit.backend.repository.*;
import com.mygit.backend.security.CustomUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@RestController
public class DashboardController {

    @Autowired private UserRepository users;
    @Autowired private RepositoryRepository repos;
    @Autowired private RepositoryCommitRepository commits;
    @Autowired private RepositoryFileRepository files;
    @Autowired private RepositoryStarRepository stars;
    @Autowired private PullRequestRepository pullRequests;
    @Autowired private PullRequestCommentRepository prComments;
    @Autowired private IssueRepository issues;
    @Autowired private IssueCommentRepository issueComments;
    @Autowired private PersonalAccessTokenRepository tokens;
    @Autowired private SshKeyRepository sshKeys;
    @Autowired private DiscussionRepository discussions;
    @Autowired private DiscussionCommentRepository discussionComments;
    @Autowired private NotificationStateRepository notificationStates;
    @Autowired private GitObjectRepository gitObjects;
    @Autowired private GitRefRepository gitRefs;

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String sha1Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    private String detectPrimaryLanguage(Long repoId) {
        List<RepositoryFile> repoFiles = files.findByRepositoryId(repoId);
        if (repoFiles.isEmpty()) return "Git";
        Map<String, Integer> counts = new HashMap<>();
        for (RepositoryFile f : repoFiles) {
            String path = f.getFilePath().toLowerCase();
            String lang = null;
            if (path.endsWith(".rs")) lang = "Rust";
            else if (path.endsWith(".ts") || path.endsWith(".tsx")) lang = "TypeScript";
            else if (path.endsWith(".js") || path.endsWith(".jsx")) lang = "JavaScript";
            else if (path.endsWith(".java")) lang = "Java";
            else if (path.endsWith(".go")) lang = "Go";
            else if (path.endsWith(".py")) lang = "Python";
            else if (path.endsWith(".cpp") || path.endsWith(".cc") || path.endsWith(".c") || path.endsWith(".h")) lang = "C/C++";
            else if (path.endsWith(".mdx") || path.endsWith(".md")) lang = "Markdown";
            else if (path.endsWith(".html") || path.endsWith(".css")) lang = "Web";
            if (lang != null) {
                counts.put(lang, counts.getOrDefault(lang, 0) + 1);
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Git");
    }

    @Transactional(readOnly = true)
    @GetMapping(value = "/api/dashboard/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getDashboardSummary(@AuthenticationPrincipal CustomUser principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }

        User user = users.findById(principal.getId()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        }

        Long userId = user.getId();
        List<Repository> userRepos = repos.findByOwnerId(userId);
        Set<Long> starredRepoIds = stars.findByUserIdWithRepo(userId).stream()
                .map(s -> s.getRepository().getId())
                .collect(Collectors.toSet());

        List<Map<String, Object>> enrichedRepos = new ArrayList<>();
        int publicCount = 0;
        int privateCount = 0;

        for (Repository r : userRepos) {
            if (r.isPrivate()) privateCount++;
            else publicCount++;

            long starCount = stars.countByRepositoryId(r.getId());
            long forkCount = repos.countByParentRepositoryId(r.getId());
            String lang = detectPrimaryLanguage(r.getId());
            List<RepositoryCommit> repoLatest = commits.findByRepositoryIdOrderByAuthoredAtDesc(r.getId());
            RepositoryCommit latestCommit = repoLatest.isEmpty() ? null : repoLatest.get(0);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("name", r.getName());
            item.put("description", r.getDescription());
            item.put("isPrivate", r.isPrivate());
            item.put("ownerUsername", user.getUsername());
            String forkedFrom = (r.getParentRepository() != null && r.getParentRepository().getOwner() != null)
                    ? r.getParentRepository().getOwner().getUsername() + "/" + r.getParentRepository().getName()
                    : null;
            item.put("forkedFrom", forkedFrom);
            item.put("starsCount", starCount);
            item.put("forksCount", forkCount);
            item.put("isStarred", starredRepoIds.contains(r.getId()));
            item.put("primaryLanguage", lang);
            item.put("createdAt", r.getCreatedAt());
            item.put("updatedAt", latestCommit != null && latestCommit.getAuthoredAt() != null
                    ? latestCommit.getAuthoredAt() : r.getUpdatedAt());
            item.put("lastCommitMessage", latestCommit != null ? latestCommit.getMessage() : null);
            item.put("lastCommitBranch", latestCommit != null ? "main" : null);
            enrichedRepos.add(item);
        }

        enrichedRepos.sort((a, b) -> {
            LocalDateTime da = (LocalDateTime) a.get("updatedAt");
            LocalDateTime db = (LocalDateTime) b.get("updatedAt");
            if (da == null && db == null) return 0;
            if (da == null) return 1;
            if (db == null) return -1;
            return db.compareTo(da);
        });

        long totalCommits = commits.countByOwnerId(userId);
        long commitsThisWeek = commits.countByOwnerIdSince(userId, LocalDateTime.now().minusDays(7));
        long totalStarsEarned = stars.countStarsEarnedByOwnerId(userId);

        List<RepositoryCommit> recentCommits = commits.findRecentByOwnerId(userId, PageRequest.of(0, 20));
        Map<String, Object> lastPush = null;
        if (!recentCommits.isEmpty()) {
            RepositoryCommit c = recentCommits.get(0);
            lastPush = new LinkedHashMap<>();
            lastPush.put("repoName", c.getRepository().getName());
            lastPush.put("branch", "main");
            lastPush.put("commitSha", c.getCommitSha());
            lastPush.put("message", c.getMessage());
            lastPush.put("authoredAt", c.getAuthoredAt());
        }

        List<PullRequest> userPrs = pullRequests.findAllRelatedToUser(userId);
        List<Map<String, Object>> prList = new ArrayList<>();
        long openPrCount = 0;
        for (PullRequest pr : userPrs) {
            if ("OPEN".equalsIgnoreCase(pr.getStatus())) openPrCount++;
            long cCount = prComments.findByPullRequestIdOrderByCreatedAtAsc(pr.getId()).size();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", pr.getId());
            m.put("prNumber", pr.getPrNumber());
            m.put("title", pr.getTitle());
            m.put("description", pr.getDescription());
            m.put("repo", pr.getRepository().getName());
            m.put("author", pr.getAuthor().getUsername());
            m.put("status", pr.getStatus());
            m.put("sourceBranch", pr.getSourceBranch());
            m.put("targetBranch", pr.getTargetBranch());
            m.put("comments", cCount);
            m.put("createdAt", pr.getCreatedAt());
            m.put("updatedAt", pr.getUpdatedAt());
            m.put("type", pr.getAuthor().getId().equals(userId) ? "created" : "review_requested");
            m.put("needsReview", "OPEN".equalsIgnoreCase(pr.getStatus()));
            prList.add(m);
        }

        List<Issue> userIssues = issues.findAllRelatedToUser(userId);
        List<Map<String, Object>> issueList = new ArrayList<>();
        long openIssueCount = 0;
        for (Issue issue : userIssues) {
            if ("OPEN".equalsIgnoreCase(issue.getStatus())) openIssueCount++;
            long cCount = issueComments.findByIssueIdOrderByCreatedAtAsc(issue.getId()).size();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", issue.getId());
            m.put("issueNumber", issue.getIssueNumber());
            m.put("title", issue.getTitle());
            m.put("description", issue.getDescription());
            m.put("repo", issue.getRepository().getName());
            m.put("author", issue.getAuthor().getUsername());
            m.put("status", issue.getStatus());
            List<String> labelList = (issue.getLabels() == null || issue.getLabels().isBlank())
                    ? List.of()
                    : Arrays.stream(issue.getLabels().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            m.put("labels", labelList);
            m.put("comments", cCount);
            m.put("createdAt", issue.getCreatedAt());
            m.put("updatedAt", issue.getUpdatedAt());
            m.put("type", issue.getAuthor().getId().equals(userId) ? "created" : "assigned");
            issueList.add(m);
        }

        // Build unified chronological Activity Timeline & Notifications from DB events
        List<Map<String, Object>> activity = new ArrayList<>();
        List<Map<String, Object>> notifications = new ArrayList<>();

        for (RepositoryCommit c : recentCommits) {
            Map<String, Object> act = new LinkedHashMap<>();
            act.put("id", "commit-" + c.getId());
            act.put("type", "push");
            act.put("repo", c.getRepository().getName());
            act.put("branch", "main");
            act.put("title", c.getMessage() != null ? c.getMessage() : "Committed changes");
            act.put("sha", c.getCommitSha());
            act.put("author", c.getAuthorName());
            act.put("timestamp", c.getAuthoredAt());
            activity.add(act);
        }

        for (PullRequest pr : userPrs) {
            Map<String, Object> act = new LinkedHashMap<>();
            act.put("id", "pr-" + pr.getId());
            act.put("type", "pr");
            act.put("status", pr.getStatus());
            act.put("repo", pr.getRepository().getName());
            act.put("number", pr.getPrNumber());
            act.put("title", pr.getTitle());
            act.put("author", pr.getAuthor().getUsername());
            act.put("timestamp", pr.getUpdatedAt() != null ? pr.getUpdatedAt() : pr.getCreatedAt());
            activity.add(act);

            Map<String, Object> notif = new LinkedHashMap<>();
            notif.put("id", "notif-pr-" + pr.getId());
            notif.put("type", "pr");
            notif.put("repo", pr.getRepository().getName());
            notif.put("message", "@" + pr.getAuthor().getUsername() + " " + pr.getStatus().toLowerCase()
                    + " PR #" + pr.getPrNumber() + ": '" + pr.getTitle() + "'");
            notif.put("timestamp", pr.getUpdatedAt() != null ? pr.getUpdatedAt() : pr.getCreatedAt());
            notif.put("unread", "OPEN".equalsIgnoreCase(pr.getStatus()));
            notif.put("category", "OPEN".equalsIgnoreCase(pr.getStatus()) ? "review" : "all");
            notifications.add(notif);
        }

        for (Issue issue : userIssues) {
            Map<String, Object> act = new LinkedHashMap<>();
            act.put("id", "issue-" + issue.getId());
            act.put("type", "issue");
            act.put("status", issue.getStatus());
            act.put("repo", issue.getRepository().getName());
            act.put("number", issue.getIssueNumber());
            act.put("title", issue.getTitle());
            act.put("author", issue.getAuthor().getUsername());
            act.put("timestamp", issue.getUpdatedAt() != null ? issue.getUpdatedAt() : issue.getCreatedAt());
            activity.add(act);

            Map<String, Object> notif = new LinkedHashMap<>();
            notif.put("id", "notif-issue-" + issue.getId());
            notif.put("type", "mention");
            notif.put("repo", issue.getRepository().getName());
            notif.put("message", "@" + issue.getAuthor().getUsername() + " " + issue.getStatus().toLowerCase()
                    + " issue #" + issue.getIssueNumber() + ": '" + issue.getTitle() + "'");
            notif.put("timestamp", issue.getUpdatedAt() != null ? issue.getUpdatedAt() : issue.getCreatedAt());
            notif.put("unread", "OPEN".equalsIgnoreCase(issue.getStatus()));
            notif.put("category", "mentioned");
            notifications.add(notif);
        }

        for (RepositoryStar s : stars.findStarsOnOwnerRepos(userId)) {
            Map<String, Object> act = new LinkedHashMap<>();
            act.put("id", "star-" + s.getId());
            act.put("type", "star");
            act.put("repo", s.getRepository().getName());
            act.put("title", "@" + s.getUser().getUsername() + " starred " + s.getRepository().getName());
            act.put("author", s.getUser().getUsername());
            act.put("timestamp", s.getCreatedAt());
            activity.add(act);

            Map<String, Object> notif = new LinkedHashMap<>();
            notif.put("id", "notif-star-" + s.getId());
            notif.put("type", "star");
            notif.put("repo", s.getRepository().getName());
            notif.put("message", "@" + s.getUser().getUsername() + " starred your repository " + s.getRepository().getName());
            notif.put("timestamp", s.getCreatedAt());
            notif.put("unread", false);
            notif.put("category", "all");
            notifications.add(notif);
        }

        Comparator<Map<String, Object>> byTimestampDesc = (a, b) -> {
            LocalDateTime ta = (LocalDateTime) a.get("timestamp");
            LocalDateTime tb = (LocalDateTime) b.get("timestamp");
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        };
        activity.sort(byTimestampDesc);
        notifications.sort(byTimestampDesc);

        Map<String, NotificationState> stateByKey = notificationStates.findByUserId(userId).stream()
                .collect(Collectors.toMap(NotificationState::getNotificationKey, s -> s, (a, b) -> a));

        List<Map<String, Object>> filteredNotifications = new ArrayList<>();
        for (Map<String, Object> n : notifications) {
            String key = String.valueOf(n.get("id"));
            NotificationState st = stateByKey.get(key);
            if (st != null && st.isMuted()) {
                continue;
            }
            if (st != null && st.isRead()) {
                n.put("unread", false);
            }
            filteredNotifications.add(n);
        }

        List<Map<String, Object>> starredReposList = new ArrayList<>();
        for (RepositoryStar s : stars.findByUserIdWithRepo(userId)) {
            Repository sr = s.getRepository();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", sr.getId());
            m.put("name", sr.getName());
            m.put("description", sr.getDescription());
            m.put("isPrivate", sr.isPrivate());
            m.put("ownerUsername", sr.getOwner().getUsername());
            m.put("starsCount", stars.countByRepositoryId(sr.getId()));
            m.put("forksCount", repos.countByParentRepositoryId(sr.getId()));
            m.put("isStarred", true);
            m.put("primaryLanguage", detectPrimaryLanguage(sr.getId()));
            m.put("updatedAt", sr.getUpdatedAt());
            starredReposList.add(m);
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("username", user.getUsername());
        profile.put("email", user.getEmail());
        profile.put("fullName", user.getFullName());
        profile.put("bio", user.getBio());
        profile.put("location", user.getLocation());
        profile.put("website", user.getWebsite());
        profile.put("createdAt", user.getCreatedAt());

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("emailOnPr", user.isEmailOnPr());
        settings.put("emailOnMention", user.isEmailOnMention());
        settings.put("weeklyDigest", user.isWeeklyDigest());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("repos", enrichedRepos);
        response.put("starredRepos", starredReposList);
        response.put("reposCount", userRepos.size());
        response.put("publicReposCount", publicCount);
        response.put("privateReposCount", privateCount);
        response.put("totalCommits", totalCommits);
        response.put("commitsThisWeek", commitsThisWeek);
        response.put("totalStarsEarned", totalStarsEarned);
        response.put("openPullRequestsCount", openPrCount);
        response.put("openIssuesCount", openIssueCount);
        response.put("lastPush", lastPush);
        response.put("pullRequests", prList);
        response.put("issues", issueList);
        response.put("activity", activity.stream().limit(40).toList());
        response.put("notifications", filteredNotifications.stream().limit(40).toList());
        response.put("profile", profile);
        response.put("settings", settings);

        return ResponseEntity.ok(response);
    }

    @Transactional
    @PatchMapping(value = "/api/user/profile", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateProfile(
            @AuthenticationPrincipal CustomUser principal,
            @RequestBody Map<String, String> body
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        User user = users.findById(principal.getId()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        }
        if (body.containsKey("fullName")) user.setFullName(body.get("fullName"));
        if (body.containsKey("bio")) user.setBio(body.get("bio"));
        if (body.containsKey("location")) user.setLocation(body.get("location"));
        if (body.containsKey("website")) user.setWebsite(body.get("website"));
        users.save(user);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("username", user.getUsername());
        profile.put("email", user.getEmail());
        profile.put("fullName", user.getFullName());
        profile.put("bio", user.getBio());
        profile.put("location", user.getLocation());
        profile.put("website", user.getWebsite());
        profile.put("createdAt", user.getCreatedAt());
        return ResponseEntity.ok(profile);
    }

    @Transactional(readOnly = true)
    @GetMapping(value = "/api/user/tokens", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listTokens(@AuthenticationPrincipal CustomUser principal) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        List<Map<String, Object>> list = tokens.findByUserIdOrderByCreatedAtDesc(principal.getId()).stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("tokenString", t.getTokenPreview());
            m.put("scopes", Arrays.stream(t.getScopes().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
            m.put("expires", t.getExpiresLabel());
            m.put("createdAt", t.getCreatedAt());
            m.put("lastUsedAt", t.getLastUsedAt());
            return m;
        }).toList();
        return ResponseEntity.ok(list);
    }

    @Transactional
    @PostMapping(value = "/api/user/tokens", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createToken(
            @AuthenticationPrincipal CustomUser principal,
            @RequestBody Map<String, Object> body
    ) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = users.findById(principal.getId()).orElseThrow();
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (name.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Token name is required"));
        String expires = String.valueOf(body.getOrDefault("expires", "30 days"));
        String scopes = String.valueOf(body.getOrDefault("scopes", "repo:read,repo:write"));

        String rawToken = "mygit_pat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String preview = rawToken.substring(0, 14) + "••••••••••" + rawToken.substring(rawToken.length() - 4);
        PersonalAccessToken patEntity = new PersonalAccessToken(user, name, preview, scopes, expires);
        patEntity.setTokenHash(sha256Hex(rawToken));
        PersonalAccessToken pat = tokens.save(patEntity);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id", pat.getId());
        res.put("name", pat.getName());
        res.put("tokenString", rawToken);
        res.put("scopes", Arrays.stream(pat.getScopes().split(",")).map(String::trim).toList());
        res.put("expires", pat.getExpiresLabel());
        res.put("createdAt", pat.getCreatedAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(res);
    }

    @Transactional
    @DeleteMapping(value = "/api/user/tokens/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deleteToken(@AuthenticationPrincipal CustomUser principal, @PathVariable Long id) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        PersonalAccessToken pat = tokens.findByIdAndUserId(id, principal.getId()).orElse(null);
        if (pat == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Token not found"));
        tokens.delete(pat);
        return ResponseEntity.ok(Map.of("message", "Token revoked"));
    }

    @Transactional(readOnly = true)
    @GetMapping(value = "/api/user/ssh-keys", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listSshKeys(@AuthenticationPrincipal CustomUser principal) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        List<Map<String, Object>> list = sshKeys.findByUserIdOrderByCreatedAtDesc(principal.getId()).stream().map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", k.getId());
            m.put("title", k.getTitle());
            m.put("fingerprint", k.getFingerprint());
            m.put("createdAt", k.getCreatedAt());
            m.put("lastUsedAt", k.getLastUsedAt());
            return m;
        }).toList();
        return ResponseEntity.ok(list);
    }

    @Transactional
    @PostMapping(value = "/api/user/ssh-keys", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> addSshKey(
            @AuthenticationPrincipal CustomUser principal,
            @RequestBody Map<String, String> body
    ) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = users.findById(principal.getId()).orElseThrow();
        String title = String.valueOf(body.getOrDefault("title", "")).trim();
        String key = String.valueOf(body.getOrDefault("publicKey", "")).trim();
        if (title.isEmpty() || key.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Title and public key are required"));
        }
        String fingerprint;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(key.getBytes(StandardCharsets.UTF_8));
            fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(hash).substring(0, 32);
        } catch (Exception e) {
            fingerprint = "SHA256:" + Integer.toHexString(key.hashCode());
        }
        SshKey saved = sshKeys.save(new SshKey(user, title, key, fingerprint));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id", saved.getId());
        res.put("title", saved.getTitle());
        res.put("fingerprint", saved.getFingerprint());
        res.put("createdAt", saved.getCreatedAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(res);
    }

    @Transactional
    @DeleteMapping(value = "/api/user/ssh-keys/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deleteSshKey(@AuthenticationPrincipal CustomUser principal, @PathVariable Long id) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        SshKey key = sshKeys.findByIdAndUserId(id, principal.getId()).orElse(null);
        if (key == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "SSH key not found"));
        sshKeys.delete(key);
        return ResponseEntity.ok(Map.of("message", "SSH key removed"));
    }

    @Transactional(readOnly = true)
    @GetMapping(value = "/api/discussions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listDiscussions() {
        List<Map<String, Object>> list = discussions.findAllByOrderByUpdatedAtDesc().stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("title", d.getTitle());
            m.put("body", d.getBody());
            m.put("category", d.getCategory());
            m.put("author", d.getAuthor().getUsername());
            m.put("replies", d.getRepliesCount());
            m.put("createdAt", d.getCreatedAt());
            m.put("updatedAt", d.getUpdatedAt());
            return m;
        }).toList();
        return ResponseEntity.ok(list);
    }

    @Transactional
    @PostMapping(value = "/api/discussions", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createDiscussion(
            @AuthenticationPrincipal CustomUser principal,
            @RequestBody Map<String, String> body
    ) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = users.findById(principal.getId()).orElseThrow();
        String title = String.valueOf(body.getOrDefault("title", "")).trim();
        if (title.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Discussion title is required"));
        String content = String.valueOf(body.getOrDefault("body", ""));
        String category = String.valueOf(body.getOrDefault("category", "General"));
        Discussion saved = discussions.save(new Discussion(user, title, content, category));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id", saved.getId());
        res.put("title", saved.getTitle());
        res.put("body", saved.getBody());
        res.put("category", saved.getCategory());
        res.put("author", user.getUsername());
        res.put("replies", saved.getRepliesCount());
        res.put("createdAt", saved.getCreatedAt());
        res.put("updatedAt", saved.getUpdatedAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(res);
    }

    @Transactional(readOnly = true)
    @GetMapping(value = "/api/discussions/{id}/comments", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listDiscussionComments(@PathVariable Long id) {
        if (!discussions.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Discussion not found"));
        }
        List<Map<String, Object>> list = discussionComments.findByDiscussionIdOrderByCreatedAtAsc(id).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("author", c.getAuthor().getUsername());
            m.put("body", c.getBody());
            m.put("createdAt", c.getCreatedAt());
            return m;
        }).toList();
        return ResponseEntity.ok(list);
    }

    @Transactional
    @PostMapping(value = "/api/discussions/{id}/comments", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> addDiscussionComment(
            @AuthenticationPrincipal CustomUser principal,
            @PathVariable Long id,
            @RequestBody Map<String, String> body
    ) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        Discussion d = discussions.findById(id).orElse(null);
        if (d == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Discussion not found"));
        String text = String.valueOf(body.getOrDefault("body", "")).trim();
        if (text.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Comment body is required"));

        User user = users.findById(principal.getId()).orElseThrow();
        DiscussionComment saved = discussionComments.save(new DiscussionComment(d, user, text));
        d.setRepliesCount(d.getRepliesCount() + 1);
        discussions.save(d);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id", saved.getId());
        res.put("author", user.getUsername());
        res.put("body", saved.getBody());
        res.put("createdAt", saved.getCreatedAt());
        res.put("replies", d.getRepliesCount());
        return ResponseEntity.status(HttpStatus.CREATED).body(res);
    }

    @Transactional
    @PatchMapping(value = "/api/user/settings", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateSettings(
            @AuthenticationPrincipal CustomUser principal,
            @RequestBody Map<String, Object> body
    ) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = users.findById(principal.getId()).orElseThrow();
        if (body.containsKey("emailOnPr")) user.setEmailOnPr(Boolean.parseBoolean(String.valueOf(body.get("emailOnPr"))));
        if (body.containsKey("emailOnMention")) user.setEmailOnMention(Boolean.parseBoolean(String.valueOf(body.get("emailOnMention"))));
        if (body.containsKey("weeklyDigest")) user.setWeeklyDigest(Boolean.parseBoolean(String.valueOf(body.get("weeklyDigest"))));
        users.save(user);
        return ResponseEntity.ok(Map.of(
                "emailOnPr", user.isEmailOnPr(),
                "emailOnMention", user.isEmailOnMention(),
                "weeklyDigest", user.isWeeklyDigest()
        ));
    }

    @Transactional
    @PostMapping(value = "/api/user/notifications/read-all", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> markAllNotificationsRead(
            @AuthenticationPrincipal CustomUser principal,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = users.findById(principal.getId()).orElseThrow();
        List<String> keys = new ArrayList<>();
        if (body != null && body.get("keys") instanceof List<?> rawList) {
            for (Object k : rawList) keys.add(String.valueOf(k));
        }
        for (String key : keys) {
            NotificationState st = notificationStates.findByUserIdAndNotificationKey(user.getId(), key)
                    .orElse(new NotificationState(user, key, true, false));
            st.setRead(true);
            notificationStates.save(st);
        }
        return ResponseEntity.ok(Map.of("message", "All notifications marked as read"));
    }

    @Transactional
    @PostMapping(value = "/api/user/notifications/{key}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> markNotificationRead(
            @AuthenticationPrincipal CustomUser principal,
            @PathVariable String key
    ) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = users.findById(principal.getId()).orElseThrow();
        NotificationState st = notificationStates.findByUserIdAndNotificationKey(user.getId(), key)
                .orElse(new NotificationState(user, key, true, false));
        st.setRead(true);
        notificationStates.save(st);
        return ResponseEntity.ok(Map.of("key", key, "unread", false));
    }

    @Transactional
    @PostMapping(value = "/api/user/notifications/{key}/mute", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> muteNotification(
            @AuthenticationPrincipal CustomUser principal,
            @PathVariable String key
    ) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = users.findById(principal.getId()).orElseThrow();
        NotificationState st = notificationStates.findByUserIdAndNotificationKey(user.getId(), key)
                .orElse(new NotificationState(user, key, true, true));
        st.setRead(true);
        st.setMuted(true);
        notificationStates.save(st);
        return ResponseEntity.ok(Map.of("key", key, "muted", true));
    }

    @Transactional
    @DeleteMapping(value = "/api/user", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deleteAccount(@AuthenticationPrincipal CustomUser principal) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = users.findById(principal.getId()).orElse(null);
        if (user == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        Long userId = user.getId();
        tokens.deleteAll(tokens.findByUserIdOrderByCreatedAtDesc(userId));
        sshKeys.deleteAll(sshKeys.findByUserIdOrderByCreatedAtDesc(userId));
        notificationStates.deleteByUserId(userId);
        stars.deleteAll(stars.findByUserIdWithRepo(userId));
        repos.deleteAll(repos.findByOwnerId(userId));
        users.delete(user);
        return ResponseEntity.ok(Map.of("message", "Account deleted"));
    }

    @Transactional
    @PostMapping(value = "/api/repos/import", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> importRepository(
            @AuthenticationPrincipal CustomUser principal,
            @RequestBody Map<String, Object> body
    ) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        User user = users.findById(principal.getId()).orElseThrow();

        String rawUrl = String.valueOf(body.getOrDefault("url", "")).trim();
        String reqName = String.valueOf(body.getOrDefault("name", "")).trim();
        boolean isPrivate = Boolean.parseBoolean(String.valueOf(body.getOrDefault("isPrivate", "false")));

        if (rawUrl.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Repository URL is required"));
        }

        String normalized = rawUrl.replaceAll("\\.git$", "").replaceAll("/+$", "");
        String[] parts = normalized.split("/");
        if (parts.length < 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid repository URL. Expected https://github.com/owner/repo"));
        }
        String remoteOwner = parts[parts.length - 2];
        String remoteRepo = parts[parts.length - 1];
        String repoName = reqName.isEmpty() ? remoteRepo : reqName;

        if (repos.existsByOwnerIdAndName(user.getId(), repoName)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Repository '" + repoName + "' already exists in your workspace"));
        }

        String zipUrl = "https://github.com/" + remoteOwner + "/" + remoteRepo + "/archive/HEAD.zip";
        byte[] zipBytes;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(zipUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "mygit-importer/1.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "Unable to fetch repository archive (HTTP " + resp.statusCode() + "). Ensure the GitHub repository is public."));
            }
            zipBytes = resp.body();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Failed to download repository: " + e.getMessage()));
        }

        Repository repo = repos.save(new Repository(
                repoName,
                "Imported from " + rawUrl,
                isPrivate,
                user
        ));

        String commitPayload = "import " + rawUrl + " " + System.currentTimeMillis();
        String commitSha = sha1Hex(commitPayload.getBytes(StandardCharsets.UTF_8));

        int importedFiles = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String fullPath = entry.getName();
                int slashIdx = fullPath.indexOf('/');
                String relPath = (slashIdx >= 0 && slashIdx + 1 < fullPath.length())
                        ? fullPath.substring(slashIdx + 1)
                        : fullPath;
                if (relPath.isEmpty() || relPath.startsWith(".git/")) continue;

                byte[] fileBytes = zis.readAllBytes();
                if (fileBytes.length > 512 * 1024) continue; // Skip >512KB binary/large files

                String content = new String(fileBytes, StandardCharsets.UTF_8);
                if (content.indexOf('\0') >= 0) continue; // Skip binary files

                String blobHeader = "blob " + fileBytes.length + "\0";
                byte[] blobFull = new byte[blobHeader.length() + fileBytes.length];
                System.arraycopy(blobHeader.getBytes(StandardCharsets.UTF_8), 0, blobFull, 0, blobHeader.length());
                System.arraycopy(fileBytes, 0, blobFull, blobHeader.length(), fileBytes.length);
                String blobSha = sha1Hex(blobFull);

                if (!gitObjects.existsByRepositoryIdAndSha(repo.getId(), blobSha)) {
                    gitObjects.save(new GitObject(repo, blobSha, "blob", blobFull));
                }

                String fileName = relPath.contains("/") ? relPath.substring(relPath.lastIndexOf('/') + 1) : relPath;
                String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "";
                files.save(new RepositoryFile(
                        repo,
                        "main",
                        relPath,
                        fileName,
                        ext,
                        (long) fileBytes.length,
                        commitSha,
                        blobSha,
                        content,
                        fileBytes,
                        false,
                        user
                ));
                importedFiles++;
                if (importedFiles >= 200) break;
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to unpack repository archive: " + e.getMessage()));
        }

        commits.save(new RepositoryCommit(
                repo,
                commitSha,
                null,
                commitSha,
                "Import repository from " + rawUrl + " (" + importedFiles + " files)",
                user.getUsername(),
                user.getEmail(),
                user.getUsername(),
                user.getEmail(),
                LocalDateTime.now()
        ));
        gitRefs.save(new GitRef(repo, "refs/heads/main", commitSha));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", repo.getId());
        result.put("name", repo.getName());
        result.put("description", repo.getDescription());
        result.put("isPrivate", repo.isPrivate());
        result.put("ownerUsername", user.getUsername());
        result.put("importedFiles", importedFiles);
        result.put("commitSha", commitSha);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
}
