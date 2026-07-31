package com.talentbridge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentbridge.dto.request.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class OpenAIService {
    @Value("${openai.api-key}") private String apiKey;
    @Value("${openai.base-url}") private String baseUrl;
    @Value("${openai.chat-model}") private String chatModel;
    @Value("${openai.evaluation-model}") private String evalModel;
    @Value("${openai.max-tokens}") private int maxTokens;
    @Value("${openai.timeout-seconds}") private int timeoutSeconds;
    @Value("${n8n.ai-webhook-url:}") private String n8nWebhookUrl;
    @Value("${n8n.webhook-secret:}") private String n8nWebhookSecret;

    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public String chat(ChatRequest req) {
        return call("chat", chatModel, buildChatSystem(req.getContext()),
                req.getMessage(), req.getHistory(), maxTokens);
    }

    public String evaluateRepository(String repoContent, String scope, String deliverables,
                                     List<String> contributorStats) {
        String system = """
            You are an expert software engineering evaluator for TalentBridge.
            Evaluate a student team's GitHub repository against their project brief.
            Return ONLY a valid JSON object - no markdown fences, no preamble:
            {
              "aiDetectionScore": <0-100>,
              "aiDetectionNotes": "<reasoning>",
              "codeQualityScore": <0-100>,
              "codeQualityNotes": "<notes>",
              "functionalityScore": <0-100>,
              "functionalityNotes": "<notes>",
              "scopeAlignmentScore": <0-100>,
              "scopeAlignmentNotes": "<notes>",
              "teamCollaborationScore": <0-100>,
              "teamCollaborationNotes": "<notes>",
              "totalScore": <weighted: AI=20%, quality=25%, func=25%, scope=20%, collab=10%>,
              "overallSummary": "<2-3 sentence summary>"
            }
            """;
        String user = String.format(
                "PROJECT SCOPE:\n%s\n\nDELIVERABLES:\n%s\n\nCONTRIBUTORS:\n%s\n\nREPO CONTENT:\n%s",
                scope != null ? scope : "Not specified",
                deliverables != null ? deliverables : "Not specified",
                String.join("\n", contributorStats),
                repoContent);
        return call("evaluation", evalModel, system, user, null, 2048);
    }

    private String buildChatSystem(String context) {
        if ("COMPANY_PROJECT_CREATION".equals(context)) {
            return """
                You are an AI assistant helping a company post a project on TalentBridge.
                Help them write a high-quality listing with clear scope, tools, deliverables, and evaluation criteria.
                Ask clarifying questions when the description is vague. Be concise and professional.
                """;
        }
        return """
            You are an AI assistant helping a university student understand a project on TalentBridge.
            Explain scope in simple language, break down technical requirements, and answer questions.
            Be encouraging and educational. Do NOT write code for them.
            """;
    }

    private String call(String operation, String model, String system, String userMessage,
                        List<ChatRequest.ChatMessageDto> history, int maxTokens) {
        try {
            if (!n8nWebhookUrl.isBlank()) {
                return callN8n(operation, model, system, userMessage, history, maxTokens);
            }
            return callOpenAI(model, system, userMessage, history, maxTokens);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("AI call interrupted");
            throw unavailable(e);
        } catch (Exception e) {
            log.error("AI call failed", e);
            throw unavailable(e);
        }
    }

    private String callOpenAI(String model, String system, String userMessage,
                              List<ChatRequest.ChatMessageDto> history,
                              int maxTokens) throws Exception {
        ArrayNode messages = mapper.createArrayNode();
        messages.add(message("system", system));
        if (history != null) {
            history.forEach(item -> messages.add(message(item.getRole(), item.getContent())));
        }
        messages.add(message("user", userMessage));

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.set("messages", messages);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.7);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.error("OpenAI request failed with status {}", response.statusCode());
            throw unavailable();
        }

        JsonNode content = mapper.readTree(response.body())
                .path("choices").path(0).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw unavailable();
        }
        return content.asText();
    }

    private String callN8n(String operation, String model, String system, String userMessage,
                           List<ChatRequest.ChatMessageDto> history,
                           int maxTokens) throws Exception {
        if (n8nWebhookSecret.isBlank()) {
            log.error("n8n AI relay is configured without a webhook secret");
            throw unavailable();
        }

        ArrayNode historyJson = mapper.createArrayNode();
        if (history != null) {
            history.forEach(item -> historyJson.add(message(item.getRole(), item.getContent())));
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("operation", operation);
        body.put("model", model);
        body.put("system", system);
        body.put("message", userMessage);
        body.set("history", historyJson);
        body.put("maxTokens", maxTokens);

        HttpRequest request = HttpRequest.newBuilder(URI.create(n8nWebhookUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("X-TalentBridge-Secret", n8nWebhookSecret)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.error("n8n AI relay failed with status {}", response.statusCode());
            throw unavailable();
        }

        JsonNode content = mapper.readTree(response.body()).path("message");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw unavailable();
        }
        return content.asText();
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AI service is temporarily unavailable");
    }

    private ResponseStatusException unavailable(Exception cause) {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AI service is temporarily unavailable",
                cause);
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }
}
