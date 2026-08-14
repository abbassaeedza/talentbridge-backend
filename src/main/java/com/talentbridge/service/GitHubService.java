package com.talentbridge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentbridge.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class GitHubService {
    @Value("${github.api-url}") private String apiUrl;
    @Value("${github.oauth.client-id}") private String clientId;
    @Value("${github.oauth.client-secret}") private String clientSecret;

    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public Map<String, String> exchangeCodeForToken(String code) {
        try {
            String body = "client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret) + "&code=" + encode(code);
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://github.com/login/oauth/access_token"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            JsonNode json = mapper.readTree(send(request));
            String accessToken = json.path("access_token").asText();
            if (accessToken.isBlank()) {
                log.warn("GitHub rejected the OAuth code: {}", json.path("error").asText("unknown_error"));
                throw new BadRequestException("GitHub authorization failed. Please connect your account again.");
            }
            return Map.of("access_token", accessToken);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.error("GitHub token exchange failed", e);
            throw new BadRequestException("GitHub authorization failed. Please connect your account again.");
        }
    }

    public String getGitHubUsername(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new BadRequestException("GitHub did not return an access token");
        }
        try {
            String username = mapper.readTree(get(apiUrl + "/user", accessToken)).path("login").asText();
            if (username.isBlank()) {
                throw new BadRequestException("GitHub did not return an account username");
            }
            return username;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.warn("GitHub username verification failed: {}", e.getMessage());
            throw new BadRequestException("Could not verify the GitHub account. Please connect it again.");
        }
    }

    public String fetchRepositoryContent(String repoUrl, String accessToken) {
        try {
            String[] parts = parseRepoUrl(repoUrl);
            String owner = parts[0];
            String repo = parts[1];
            StringBuilder content = new StringBuilder();
            content.append("=== README ===\n").append(fetchFile(owner, repo, "README.md", accessToken)).append("\n\n");
            content.append("=== TREE ===\n").append(fetchTree(owner, repo, accessToken)).append("\n\n");
            content.append("=== BUILD FILES ===\n");
            for (String file : List.of("package.json", "pom.xml", "build.gradle", "requirements.txt", "Dockerfile")) {
                String fileContent = fetchFile(owner, repo, file, accessToken);
                if (!fileContent.isBlank()) content.append("--- ").append(file).append(" ---\n")
                        .append(fileContent, 0, Math.min(fileContent.length(), 1500)).append("\n\n");
            }
            return content.toString();
        } catch (Exception e) {
            log.error("Failed to fetch repo content from {}", repoUrl, e);
            return "Error fetching repository: " + e.getMessage();
        }
    }

    public ContributorData fetchContributorData(String repoUrl, String accessToken) {
        try {
            String[] parts = parseRepoUrl(repoUrl);
            JsonNode contributors = mapper.readTree(get(
                    apiUrl + "/repos/" + parts[0] + "/" + parts[1] + "/contributors", accessToken));
            List<String> stats = new ArrayList<>();
            Map<String, Integer> commitCounts = new LinkedHashMap<>();
            contributors.forEach(contributor -> {
                String login = contributor.path("login").asText();
                int commits = contributor.path("contributions").asInt();
                stats.add("Contributor: " + login + " | Commits: " + commits);
                commitCounts.put(login, commits);
            });
            return new ContributorData(stats, commitCounts);
        } catch (Exception e) {
            log.error("Failed to fetch contributor data", e);
            return new ContributorData(
                    List.of("Could not fetch contributor stats: " + e.getMessage()),
                    Map.of());
        }
    }

    private String fetchFile(String owner, String repo, String path, String token) {
        try {
            JsonNode node = mapper.readTree(get(apiUrl + "/repos/" + owner + "/" + repo + "/contents/" + path, token));
            if (node.has("content")) {
                byte[] decoded = Base64.getDecoder().decode(node.get("content").asText().replaceAll("\\s", ""));
                return new String(decoded, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private String fetchTree(String owner, String repo, String token) {
        try {
            JsonNode tree = mapper.readTree(get(apiUrl + "/repos/" + owner + "/" + repo + "/git/trees/HEAD?recursive=1", token));
            StringBuilder result = new StringBuilder();
            tree.path("tree").forEach(item -> {
                if ("blob".equals(item.path("type").asText())) result.append(item.path("path").asText()).append('\n');
            });
            return result.toString();
        } catch (Exception e) {
            return "Could not fetch tree: " + e.getMessage();
        }
    }

    private String get(String url, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "TalentBridge/1.0");
        if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);
        return send(request.GET().build());
    }

    private String send(HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("GitHub API " + response.statusCode() + " for " + request.uri());
        }
        return response.body();
    }

    private String[] parseRepoUrl(String repoUrl) {
        String clean = repoUrl.replace("https://github.com/", "").replace("http://github.com/", "").replaceAll("\\.git$", "");
        String[] parts = clean.split("/");
        if (parts.length < 2) throw new IllegalArgumentException("Invalid GitHub URL: " + repoUrl);
        return new String[]{parts[0], parts[1]};
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record ContributorData(List<String> stats, Map<String, Integer> commitCounts) {
    }
}
