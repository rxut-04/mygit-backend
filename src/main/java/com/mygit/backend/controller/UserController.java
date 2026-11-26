package com.mygit.backend.controller;

import com.mygit.backend.model.User;
import com.mygit.backend.repository.RepositoryCommitRepository;
import com.mygit.backend.repository.UserRepository;
import com.mygit.backend.security.CustomUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
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
            @AuthenticationPrincipal CustomUser principal
    ) {
        User targetUser = null;
        if (username != null && !username.isBlank()) {
            targetUser = users.findByUsername(username).orElse(null);
        }
        if (targetUser == null && principal != null) {
            targetUser = users.findById(principal.getId()).orElse(null);
        }

        List<Object[]> rows = targetUser != null
                ? repoCommits.getContributionsByUserId(targetUser.getId())
                : List.of();

        Map<String, Integer> daily = new LinkedHashMap<>();
        int total = 0;
        for (Object[] r : rows) {
            if (r != null && r.length >= 2) {
                String day = String.valueOf(r[0]);
                int count = ((Number) r[1]).intValue();
                daily.put(day, count);
                total += count;
            }
        }

        // Calculate accurate consecutive-day currentStreak and longestStreak
        int currentStreak = 0;
        int longestStreak = 0;
        int tempStreak = 0;

        LocalDate today = LocalDate.now();
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
