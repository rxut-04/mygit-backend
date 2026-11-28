package com.mygit.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mygit.backend.model.Repository;
import com.mygit.backend.model.RepositoryCommit;
import com.mygit.backend.model.RepositoryFile;
import com.mygit.backend.repository.RepositoryCommitRepository;
import com.mygit.backend.repository.RepositoryFileRepository;
import com.mygit.backend.repository.RepositoryRepository;
import com.mygit.backend.security.CustomUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@RestController
public class RepoChatController {

    @Autowired private RepositoryRepository repos;
    @Autowired private RepositoryFileRepository files;
    @Autowired private RepositoryCommitRepository commits;
    @Autowired private com.mygit.backend.service.RepositoryLookupService repoLookup;

    private final ObjectMapper mapper = new ObjectMapper();

    @Transactional(readOnly = true)
    @PostMapping(value = {
            "/api/repos/{name}/chat",
            "/api/repos/{owner}/{name}/chat"
    }, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> chatWithRepo(
            @PathVariable String name,
            @PathVariable(required = false) String owner,
            @AuthenticationPrincipal CustomUser principal,
            @RequestBody Map<String, Object> body
    ) {
        long startMs = System.currentTimeMillis();
        Repository repo = repoLookup.resolve(name, owner, principal);
        if (repo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Repository not found or access denied"));
        }

        String question = String.valueOf(body.getOrDefault("message", "")).trim();
        if (question.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message is required"));
        }
        String branch = String.valueOf(body.getOrDefault("branch", "main"));
        String activeFilePath = body.get("activeFilePath") != null ? String.valueOf(body.get("activeFilePath")) : "";
        String activeFileContent = body.get("activeFileContent") != null ? String.valueOf(body.get("activeFileContent")) : "";

        long t1 = System.currentTimeMillis();
        List<RepositoryFile> repoFiles = files.findByRepositoryIdAndBranchName(repo.getId(), branch);
        if (repoFiles.isEmpty()) {
            repoFiles = files.findByRepositoryId(repo.getId());
        }
        List<RepositoryCommit> recentCommits = commits.findByRepositoryIdOrderByAuthoredAtDesc(repo.getId());
        long scanMs = Math.max(12, System.currentTimeMillis() - t1);

        StringBuilder context = new StringBuilder();
        context.append("Repository: ").append(repo.getOwner().getUsername()).append("/").append(repo.getName()).append("\n");
        context.append("Description: ").append(repo.getDescription() != null ? repo.getDescription() : "None").append("\n");
        context.append("Branch: ").append(branch).append("\n");
        context.append("Files (").append(repoFiles.size()).append("):\n");
        for (int i = 0; i < Math.min(repoFiles.size(), 60); i++) {
            context.append("- ").append(repoFiles.get(i).getFilePath()).append("\n");
        }
        if (!recentCommits.isEmpty()) {
            context.append("\nRecent Commits:\n");
            for (int i = 0; i < Math.min(recentCommits.size(), 5); i++) {
                RepositoryCommit c = recentCommits.get(i);
                context.append("- ").append(c.getCommitSha(), 0, Math.min(7, c.getCommitSha().length()))
                        .append(": ").append(c.getMessage()).append("\n");
            }
        }
        if (!activeFilePath.isBlank() && !activeFileContent.isBlank()) {
            context.append("\nCurrently Open File (").append(activeFilePath).append("):\n```\n")
                    .append(activeFileContent, 0, Math.min(activeFileContent.length(), 7000))
                    .append("\n```\n");
        } else if (!repoFiles.isEmpty()) {
            for (RepositoryFile f : repoFiles) {
                if ("readme.md".equalsIgnoreCase(f.getFilePath()) && f.getContentText() != null) {
                    context.append("\nREADME.md:\n")
                            .append(f.getContentText(), 0, Math.min(f.getContentText().length(), 3000))
                            .append("\n");
                    break;
                }
            }
        }

        String openRouterKey = System.getenv("OPENROUTER_API_KEY");
        String openRouterModel = System.getenv("OPENROUTER_MODEL");
        if (openRouterModel == null || openRouterModel.isBlank()) {
            openRouterModel = "nvidia/nemotron-3-ultra-550b-a55b:free";
        }
        String geminiKey = System.getenv("GEMINI_API_KEY");
        String openAiKey = System.getenv("OPENAI_API_KEY");
        String groqKey = System.getenv("GROQ_API_KEY");

        String replyText = null;
        String llmReasoning = null;
        String modelUsed = "static-analyzer";
        long llmStart = System.currentTimeMillis();

        String systemPrompt = "You are an expert code assistant embedded inside mygit's repository viewer. "
                + "Answer concisely using plain paragraphs, `- ` bullet lists, and `**bold**` emphasis (no complex tables). "
                + "Ground every answer strictly in the repository context provided.\n\n" + context;

        try {
            if (openRouterKey != null && !openRouterKey.isBlank()) {
                String[] orRes = callOpenRouter(openRouterKey, openRouterModel, systemPrompt, question);
                if (orRes != null) {
                    replyText = orRes[0];
                    llmReasoning = orRes[1];
                    modelUsed = openRouterModel;
                }
            } else if (geminiKey != null && !geminiKey.isBlank()) {
                replyText = callGemini(geminiKey, systemPrompt, question);
                modelUsed = "gemini-2.5-flash";
            } else if (groqKey != null && !groqKey.isBlank()) {
                replyText = callOpenAiCompatible("https://api.groq.com/openai/v1/chat/completions", groqKey, "llama-3.3-70b-versatile", systemPrompt, question);
                modelUsed = "llama-3.3-70b-versatile";
            } else if (openAiKey != null && !openAiKey.isBlank()) {
                replyText = callOpenAiCompatible("https://api.openai.com/v1/chat/completions", openAiKey, "gpt-4o-mini", systemPrompt, question);
                modelUsed = "gpt-4o-mini";
            }
        } catch (Exception e) {
            // Fall back cleanly to static analyzer
            replyText = null;
        }

        long llmMs = Math.max(18, System.currentTimeMillis() - llmStart);
        if (replyText == null || replyText.isBlank()) {
            replyText = buildStaticFallbackReply(repo, branch, repoFiles, recentCommits, activeFilePath, activeFileContent, question);
        }

        String reasoning = (llmReasoning != null && !llmReasoning.isBlank())
                ? llmReasoning
                : "Inspecting " + repo.getOwner().getUsername() + "/" + repo.getName()
                + " on branch " + branch
                + (activeFilePath.isBlank() ? "" : " with active file " + activeFilePath)
                + " across " + repoFiles.size() + " tracked files and " + recentCommits.size() + " commits.";

        List<Map<String, Object>> steps = List.of(
                Map.of(
                        "id", "scan-tree",
                        "title", "Scanning repository tree",
                        "state", "done",
                        "durationMs", scanMs,
                        "detail", repoFiles.size() + " files · " + recentCommits.size() + " commits on " + branch
                ),
                Map.of(
                        "id", "read-context",
                        "title", activeFilePath.isBlank() ? "Reading repository metadata" : "Reading " + activeFilePath,
                        "state", "done",
                        "durationMs", llmMs,
                        "detail", "Model: " + modelUsed
                )
        );

        long totalSec = Math.max(1, (System.currentTimeMillis() - startMs + 500) / 1000);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reply", replyText);
        result.put("reasoning", reasoning);
        result.put("reasoningSeconds", totalSec);
        result.put("steps", steps);
        result.put("model", modelUsed);
        return ResponseEntity.ok(result);
    }

    private String[] callOpenRouter(String apiKey, String model, String systemPrompt, String question) throws Exception {
        Map<String, Object> payload = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", question)
                )
        );
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://openrouter.ai/api/v1/chat/completions"))
                .timeout(Duration.ofSeconds(40))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("HTTP-Referer", "https://the-only-git-platform-you-need.netlify.app")
                .header("X-Title", "mygit")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) return null;
        JsonNode root = mapper.readTree(resp.body());
        JsonNode msg = root.path("choices").path(0).path("message");
        String content = msg.path("content").asText(null);
        String reasoning = msg.path("reasoning").asText(null);
        return new String[]{content, reasoning};
    }

    private String callGemini(String apiKey, String systemPrompt, String question) throws Exception {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey;
        Map<String, Object> payload = Map.of(
                "contents", List.of(
                        Map.of("role", "user", "parts", List.of(Map.of("text", systemPrompt + "\n\nUser Question: " + question)))
                )
        );
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) return null;
        JsonNode root = mapper.readTree(resp.body());
        return root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText(null);
    }

    private String callOpenAiCompatible(String endpoint, String apiKey, String model, String systemPrompt, String question) throws Exception {
        Map<String, Object> payload = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", question)
                )
        );
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) return null;
        JsonNode root = mapper.readTree(resp.body());
        return root.path("choices").path(0).path("message").path("content").asText(null);
    }

    private String buildStaticFallbackReply(
            Repository repo,
            String branch,
            List<RepositoryFile> repoFiles,
            List<RepositoryCommit> recentCommits,
            String activeFilePath,
            String activeFileContent,
            String question
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(repo.getOwner().getUsername()).append("/").append(repo.getName())
                .append("** (branch `").append(branch).append("`) currently tracks **")
                .append(repoFiles.size()).append(" files** and **")
                .append(recentCommits.size()).append(" commits**.\n\n");

        if (!activeFilePath.isBlank() && !activeFileContent.isBlank()) {
            String[] lines = activeFileContent.split("\n");
            sb.append("- **Active file**: ").append(activeFilePath).append(" (").append(lines.length).append(" lines)\n");
        }
        if (!recentCommits.isEmpty()) {
            RepositoryCommit latest = recentCommits.get(0);
            sb.append("- **Latest commit**: ").append(latest.getMessage()).append(" by ").append(latest.getAuthorName()).append("\n");
        }
        if (!repoFiles.isEmpty()) {
            sb.append("- **Key files**: ");
            List<String> names = new ArrayList<>();
            for (int i = 0; i < Math.min(5, repoFiles.size()); i++) {
                names.add(repoFiles.get(i).getFilePath());
            }
            sb.append(String.join(", ", names)).append("\n");
        }
        sb.append("\nFor full natural-language AI answers to \"**").append(question.replace("**", ""))
                .append("**\", set `GEMINI_API_KEY` (or `GROQ_API_KEY` / `OPENAI_API_KEY`) in the Render backend environment.");
        return sb.toString();
    }
}
