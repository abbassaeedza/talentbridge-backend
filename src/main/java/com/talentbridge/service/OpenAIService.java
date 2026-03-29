package com.talentbridge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentbridge.dto.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class OpenAIService {
    @Value("${openai.api-key}")     private String apiKey;
    @Value("${openai.base-url}")    private String baseUrl;
    @Value("${openai.chat-model}")  private String chatModel;
    @Value("${openai.evaluation-model}") private String evalModel;
    @Value("${openai.max-tokens}")  private int maxTokens;
    @Value("${openai.timeout-seconds}") private int timeoutSeconds;

    private final ObjectMapper mapper = new ObjectMapper();

    private OkHttpClient client() {
        return new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    }

    public String chat(ChatRequest req, String userRole) {
        String system = buildChatSystem(req.getContext(), userRole);
        return call(chatModel, system, req.getMessage(), req.getHistory(), maxTokens);
    }

    public String evaluateRepository(String repoContent, String scope, String deliverables,
                                     List<String> contributorStats) {
        String system = """
            You are an expert software engineering evaluator for TalentBridge.
            Evaluate a student team's GitHub repository against their project brief.
            Return ONLY a valid JSON object — no markdown fences, no preamble:
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
        return call(evalModel, system, user, null, 2048);
    }

    private String buildChatSystem(String context, String userRole) {
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

    private String call(String model, String system, String userMessage,
                        List<ChatRequest.ChatMessageDto> history, int maxTok) {
        try {
            ArrayNode messages = mapper.createArrayNode();
            ObjectNode sysMsg = mapper.createObjectNode();
            sysMsg.put("role", "system"); sysMsg.put("content", system);
            messages.add(sysMsg);

            if (history != null) {
                for (ChatRequest.ChatMessageDto h : history) {
                    ObjectNode m = mapper.createObjectNode();
                    m.put("role", h.getRole()); m.put("content", h.getContent());
                    messages.add(m);
                }
            }

            ObjectNode userMsg = mapper.createObjectNode();
            userMsg.put("role", "user"); userMsg.put("content", userMessage);
            messages.add(userMsg);

            ObjectNode body = mapper.createObjectNode();
            body.put("model", model); body.set("messages", messages);
            body.put("max_tokens", maxTok); body.put("temperature", 0.7);

            RequestBody requestBody = RequestBody.create(
                mapper.writeValueAsString(body),
                MediaType.get("application/json; charset=utf-8"));

            Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(requestBody).build();

            try (Response response = client().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String err = response.body() != null ? response.body().string() : "no body";
                    log.error("OpenAI error {}: {}", response.code(), err);
                    throw new RuntimeException("OpenAI API error: " + response.code());
                }
                JsonNode json = mapper.readTree(response.body().string());
                return json.get("choices").get(0).get("message").get("content").asText();
            }
        } catch (Exception e) {
            log.error("OpenAI call failed", e);
            throw new RuntimeException("AI service unavailable: " + e.getMessage(), e);
        }
    }
}
