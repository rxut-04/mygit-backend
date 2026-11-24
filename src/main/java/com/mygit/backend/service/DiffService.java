package com.mygit.backend.service;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DiffService {

    public static class FileDiffResult {
        private String filePath;
        private String status; // ADDED, MODIFIED, DELETED, UNCHANGED
        private int additions;
        private int deletions;
        private String diffText;

        public FileDiffResult(String filePath, String status, int additions, int deletions, String diffText) {
            this.filePath = filePath;
            this.status = status;
            this.additions = additions;
            this.deletions = deletions;
            this.diffText = diffText;
        }

        public String getFilePath() { return filePath; }
        public String getStatus() { return status; }
        public int getAdditions() { return additions; }
        public int getDeletions() { return deletions; }
        public String getDiffText() { return diffText; }
    }

    public FileDiffResult computeFileDiff(String filePath, String oldContent, String newContent) {
        if (oldContent == null && newContent == null) {
            return new FileDiffResult(filePath, "UNCHANGED", 0, 0, "");
        }

        if (oldContent == null) {
            String[] newLines = newContent.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            sb.append("@@ -0,0 +1,").append(newLines.length).append(" @@\n");
            for (String l : newLines) {
                sb.append("+").append(l).append("\n");
            }
            return new FileDiffResult(filePath, "ADDED", newLines.length, 0, sb.toString());
        }

        if (newContent == null) {
            String[] oldLines = oldContent.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            sb.append("@@ -1,").append(oldLines.length).append(" +0,0 @@\n");
            for (String l : oldLines) {
                sb.append("-").append(l).append("\n");
            }
            return new FileDiffResult(filePath, "DELETED", 0, oldLines.length, sb.toString());
        }

        if (oldContent.equals(newContent)) {
            return new FileDiffResult(filePath, "UNCHANGED", 0, 0, "");
        }

        // Simple Myers-style LCS diff
        String[] a = oldContent.split("\n", -1);
        String[] b = newContent.split("\n", -1);

        int[][] dp = new int[a.length + 1][b.length + 1];
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j < b.length; j++) {
                if (a[i].equals(b[j])) {
                    dp[i + 1][j + 1] = dp[i][j] + 1;
                } else {
                    dp[i + 1][j + 1] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }

        List<String> diffLines = new ArrayList<>();
        int i = a.length, j = b.length;
        int additions = 0, deletions = 0;

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && a[i - 1].equals(b[j - 1])) {
                diffLines.add(" " + a[i - 1]);
                i--;
                j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                diffLines.add("+" + b[j - 1]);
                additions++;
                j--;
            } else if (i > 0 && (j == 0 || dp[i][j - 1] < dp[i - 1][j])) {
                diffLines.add("-" + a[i - 1]);
                deletions++;
                i--;
            }
        }

        Collections.reverse(diffLines);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("@@ -1,%d +1,%d @@\n", a.length, b.length));
        for (String dl : diffLines) {
            sb.append(dl).append("\n");
        }

        return new FileDiffResult(filePath, "MODIFIED", additions, deletions, sb.toString());
    }
}
