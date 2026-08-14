package com.talentbridge.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.talentbridge.dto.request.ChatRequest;
import com.talentbridge.dto.response.ChatResponse;
import com.talentbridge.repository.UserRepository;
import com.talentbridge.service.OpenAIService;
import com.talentbridge.service.ChatContextService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import java.util.Map;
import com.talentbridge.enums.UserRole;

@RestController @RequestMapping("/api/chat") @RequiredArgsConstructor
public class ChatController {
    private final OpenAIService openAIService;
    private final UserRepository userRepository;
    private final ChatContextService chatContextService;
    private final ObjectMapper mapper;

    @PostMapping
    public ChatResponse chat(@AuthenticationPrincipal UUID userId,
                             @Valid @RequestBody ChatRequest req) {
        var user = userRepository.findById(userId).orElseThrow();
        req.setContext(user.getRole().name());
        req.setAppContext(chatContextService.build(user));
        String raw = openAIService.chat(req);
        String message = raw;
        Map<String, Object> projectDraft = null;
        if (user.getRole() == UserRole.COMPANY) {
            try {
                JsonNode json = mapper.readTree(raw);
                if (json.path("message").isTextual()) message = json.path("message").asText();
                if (json.path("projectDraft").isObject())
                    projectDraft = mapper.convertValue(json.path("projectDraft"),
                            new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {
                // Plain-text relay responses remain valid chat replies.
            }
        }
        return ChatResponse.builder()
            .message(message)
            .projectDraft(projectDraft)
            .model("gpt-4o-mini").build();
    }
}
