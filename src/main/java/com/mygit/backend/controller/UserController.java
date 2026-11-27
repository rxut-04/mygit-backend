package com.mygit.backend.controller;

import com.mygit.backend.model.RepositoryCommit;
import com.mygit.backend.model.User;
import com.mygit.backend.repository.RepositoryCommitRepository;
import com.mygit.backend.repository.UserRepository;
import com.mygit.backend.security.CustomUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

@RestController
public class UserController {

    @Autowired
    private UserRepository users;

    @Autowired
    private RepositoryCommitRepository repoCommits;

    /**
     * Get contribution activity for a user (or authenticated user).
     */
    @Transactional(readOnly = true)
    @GetMapping(value = {
            "/api/users/{username}/contributions",
            "/api/user/contributions"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getContributions(
            @PathVariable(required = false) String username,
            @RequestParam(required = false) String tz,
            @AuthenticationPrincipal CustomUser principal
    ) {
        User targetUser = null;
        if (username != null && !username.isBlank()) {
            targetUser = users.findByUsername(username).orElse(null);
        }
        if (targetUser == null && principal != null) {
            targetUser = users.findById(principal.getId()).orElse(null);
        }

        ZoneId zone = ZoneOffset.UTC;
        if (tz != null && !tz.isBlank()) {
            try {
                zone = ZoneId.of(tz.trim());
            } catch (Exception ignored) {}
        }

        LocalDateTime sinceUtc = LocalDateTime.now(ZoneOffset.UTC).minusDays(366);
        List<RepositoryCommit> commits = targetUser != null
                ? repoCommits.findByOwnerIdSince(targetUser.getId(), sinceUtc)
                : List.of();

        Map<String, Integer> daily = new TreeMap<>();
        int total = 0;
        for (RepositoryCommit c : commits) {
            LocalDateTime ts = c.getAuthoredAt();
            if (ts == null) continue;
            String day = ts.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate().toString();
            daily.merge(day, 1, Integer::sum);
            total++;
        }

        // Calculate accurate consecutive-day currentStreak and longestStreak in the user's timezone
        int currentStreak = 0;
        int longestStreak = 0;
        int tempStreak = 0;

        LocalDate today = LocalDate.now(zone);
        LocalDate check = daily.containsKey(today.toString()) ? today : today.minusDays(1);
        while (daily.containsKey(check.toString())) {
            currentStreak++;
            check = check.minusDays(1);
        }

        LocalDate prevDay = null;
        for (Map.Entry<String, Integer> entry : daily.entrySet()) {
            if (entry.getValue() <= 0) continue;
            LocalDate currDay = LocalDate.parse(entry.getKey());
            if (prevDay != null && prevDay.plusDays(1).equals(currDay)) {
                tempStreak++;
            } else {
                tempStreak = 1;
            }
            if (tempStreak > longestStreak) longestStreak = tempStreak;
            prevDay = currDay;
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("username", targetUser != null ? targetUser.getUsername() : "");
        resp.put("totalCommits", total);
        resp.put("currentStreak", currentStreak);
        resp.put("longestStreak", Math.max(longestStreak, currentStreak));
        resp.put("daily", daily);

        return ResponseEntity.ok(resp);
    }
}
