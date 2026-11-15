package com.talentbridge.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentbridge.dto.request.ChatRequest;
import com.talentbridge.dto.response.ChatResponse;
import com.talentbridge.dto.response.ChatContextResponse;
import com.talentbridge.service.AiQuotaService;
import com.talentbridge.service.OpenAIService;
import com.talentbridge.service.ChatContextService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import com.talentbridge.enums.UserRole;

@RestController @RequestMapping("/api/chat") @RequiredArgsConstructor
public class ChatController {
    private final OpenAIService openAIService;
    private final ChatContextService chatContextService;
    private final ObjectMapper mapper;
    private final AiQuotaService quota;

    @GetMapping("/context")
    public ChatContextResponse context(@AuthenticationPrincipal UUID userId) {
        return chatContextService.snapshot(userId);
    }

    @PostMapping
    public ChatResponse chat(@AuthenticationPrincipal UUID userId,
                             @Valid @RequestBody ChatRequest req) {
        chatContextService.requireApprovedViewer(userId);
        UUID leaseId = quota.reserve(userId);
        try {
            var snapshot = chatContextService.snapshot(userId);
            req.setContext(snapshot.viewer().role().name());
            req.setAppContext(chatContextService.formatForModel(snapshot));
            String raw = openAIService.chat(req);
            String message = raw;
            Map<String, Object> projectDraft = null;
            if (snapshot.viewer().role() == UserRole.COMPANY) {
                try {
                    JsonNode json = mapper.readTree(raw);
                    if (json.path("message").isTextual()) message = json.path("message").asText();
                    if (json.path("projectDraft").isObject())
                        projectDraft = sanitizeDraft(json.path("projectDraft"));
                } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                    // Plain-text relay responses remain valid chat replies.
                }
            }
            return ChatResponse.builder().message(message).projectDraft(projectDraft)
                    .model(openAIService.chatModel()).source("llm").build();
        } finally {
            quota.release(leaseId);
        }
    }

    private Map<String, Object> sanitizeDraft(JsonNode node) {
        Map<String, Object> draft = new LinkedHashMap<>();
        for (String field : List.of("title", "description", "scope", "deliverables", "evaluationCriteria", "projectField")) {
            JsonNode value = node.path(field);
            int limit = "title".equals(field) || "projectField".equals(field) ? 100 : 2000;
            if (value.isTextual() && !value.asText().isBlank())
                draft.put(field, value.asText().substring(0, Math.min(limit, value.asText().length())));
        }
        if (node.path("tools").isArray()) {
            List<String> tools = new java.util.ArrayList<>();
            for (JsonNode tool : node.path("tools")) {
                if (tool.isTextual() && !tool.asText().isBlank())
                    tools.add(tool.asText().substring(0, Math.min(100, tool.asText().length())));
                if (tools.size() == 20) break;
            }
            if (!tools.isEmpty()) draft.put("tools", tools);
        }
        return draft.isEmpty() ? null : draft;
    }
}
