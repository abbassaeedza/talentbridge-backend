package com.talentbridge.controller;
import com.talentbridge.dto.request.ChatRequest;
import com.talentbridge.dto.response.ChatResponse;
import com.talentbridge.repository.UserRepository;
import com.talentbridge.service.OpenAIService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/chat") @RequiredArgsConstructor
public class ChatController {
    private final OpenAIService openAIService;
    private final UserRepository userRepository;

    @PostMapping
    public ChatResponse chat(@AuthenticationPrincipal UUID userId,
                             @Valid @RequestBody ChatRequest req) {
        userRepository.findById(userId).orElseThrow();
        return ChatResponse.builder()
            .message(openAIService.chat(req))
            .model("gpt-4o-mini").build();
    }
}
