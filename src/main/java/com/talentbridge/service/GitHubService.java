package com.talentbridge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class GitHubService {
    @Value("${github.api-url}")            private String apiUrl;
    @Value("${github.oauth.client-id}")    private String clientId;
    @Value("${github.oauth.client-secret}") private String clientSecret;

    private final ObjectMapper mapper = new ObjectMapper();

    private OkHttpClient client() {
        return new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    }

    public Map<String, String> exchangeCodeForToken(String code) {
        try {
            String body = "client_id=" + clientId + "&client_secret=" + clientSecret + "&code=" + code;
            RequestBody rb = RequestBody.create(body, MediaType.get("application/x-www-form-urlencoded"));
            Request req = new Request.Builder()
                .url("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json").post(rb).build();
            try (Response resp = client().newCall(req).execute()) {
                JsonNode json = mapper.readTree(resp.body().string());
                Map<String, String> result = new HashMap<>();
                result.put("access_token", json.path("access_token").asText());
                return result;
            }
        } catch (Exception e) {
            log.error("GitHub token exchange failed", e);
            throw new RuntimeException("GitHub OAuth failed: " + e.getMessage());
        }
    }

    public String getGitHubUsername(String accessToken) {
        try {
            JsonNode json = mapper.readTree(get(apiUrl + "/user", accessToken));
            return json.get("login").asText();
        } catch (Exception e) { log.error("Could not fetch GitHub username", e); return null; }
    }

    public String fetchRepositoryContent(String repoUrl, String accessToken) {
        try {
            String[] parts = parseRepoUrl(repoUrl);
            String owner = parts[0], repo = parts[1];
            StringBuilder content = new StringBuilder();
            content.append("=== README ===\n").append(fetchFile(owner, repo, "README.md", accessToken)).append("\n\n");
            content.append("=== TREE ===\n").append(fetchTree(owner, repo, accessToken)).append("\n\n");
            content.append("=== BUILD FILES ===\n");
            for (String f : List.of("package.json","pom.xml","build.gradle","requirements.txt","Dockerfile")) {
                String fc = fetchFile(owner, repo, f, accessToken);
                if (!fc.isBlank()) content.append("--- ").append(f).append(" ---\n")
                    .append(fc, 0, Math.min(fc.length(), 1500)).append("\n\n");
            }
            return content.toString();
        } catch (Exception e) {
            log.error("Failed to fetch repo content from {}", repoUrl, e);
            return "Error fetching repository: " + e.getMessage();
        }
    }

    public List<String> fetchContributorStats(String repoUrl, String accessToken) {
        try {
            String[] parts = parseRepoUrl(repoUrl);
            JsonNode contributors = mapper.readTree(get(apiUrl + "/repos/" + parts[0] + "/" + parts[1] + "/contributors", accessToken));
            List<String> stats = new ArrayList<>();
            contributors.forEach(c -> stats.add("Contributor: " + c.get("login").asText() + " | Commits: " + c.get("contributions").asInt()));
            return stats;
        } catch (Exception e) {
            log.error("Failed to fetch contributor stats", e);
            return List.of("Could not fetch contributor stats: " + e.getMessage());
        }
    }

    public Map<String, Integer> fetchCommitCountPerAuthor(String repoUrl, String accessToken) {
        try {
            String[] parts = parseRepoUrl(repoUrl);
            JsonNode contributors = mapper.readTree(get(apiUrl + "/repos/" + parts[0] + "/" + parts[1] + "/contributors", accessToken));
            Map<String, Integer> map = new LinkedHashMap<>();
            contributors.forEach(c -> map.put(c.get("login").asText(), c.get("contributions").asInt()));
            return map;
        } catch (Exception e) { log.error("Failed to fetch commit counts", e); return Map.of(); }
    }

    private String fetchFile(String owner, String repo, String path, String token) {
        try {
            JsonNode node = mapper.readTree(get(apiUrl + "/repos/" + owner + "/" + repo + "/contents/" + path, token));
            if (node.has("content")) {
                byte[] decoded = Base64.getDecoder().decode(node.get("content").asText().replaceAll("\\s", ""));
                return new String(decoded);
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String fetchTree(String owner, String repo, String token) {
        try {
            JsonNode tree = mapper.readTree(get(apiUrl + "/repos/" + owner + "/" + repo + "/git/trees/HEAD?recursive=1", token));
            StringBuilder sb = new StringBuilder();
            if (tree.has("tree")) tree.get("tree").forEach(item -> {
                if ("blob".equals(item.get("type").asText())) sb.append(item.get("path").asText()).append("\n");
            });
            return sb.toString();
        } catch (Exception e) { return "Could not fetch tree: " + e.getMessage(); }
    }

    private String get(String url, String token) throws Exception {
        Request.Builder rb = new Request.Builder().url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "TalentBridge/1.0");
        if (token != null && !token.isBlank()) rb.header("Authorization", "token " + token);
        try (Response resp = client().newCall(rb.get().build()).execute()) {
            if (!resp.isSuccessful()) throw new RuntimeException("GitHub API " + resp.code() + " for " + url);
            return resp.body().string();
        }
    }

    private String[] parseRepoUrl(String repoUrl) {
        String clean = repoUrl.replace("https://github.com/", "").replace("http://github.com/", "").replaceAll("\\.git$", "");
        String[] parts = clean.split("/");
        if (parts.length < 2) throw new IllegalArgumentException("Invalid GitHub URL: " + repoUrl);
        return new String[]{parts[0], parts[1]};
    }
}
