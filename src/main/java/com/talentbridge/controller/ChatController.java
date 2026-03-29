package com.talentbridge.controller;
import com.talentbridge.dto.request.ChatRequest;
import com.talentbridge.dto.response.ChatResponse;
import com.talentbridge.entity.User;
import com.talentbridge.repository.UserRepository;
import com.talentbridge.service.OpenAIService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/chat") @RequiredArgsConstructor
public class ChatController {
    private final OpenAIService openAIService;
    private final UserRepository userRepository;

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@AuthenticationPrincipal UUID userId,
                                              @Valid @RequestBody ChatRequest req) {
        User user = userRepository.findById(userId).orElseThrow();
        return ResponseEntity.ok(ChatResponse.builder()
            .message(openAIService.chat(req, user.getRole().name()))
            .model("gpt-4o-mini").build());
    }
}
