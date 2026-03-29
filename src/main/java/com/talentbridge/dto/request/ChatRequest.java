package com.talentbridge.dto.request;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.List;
@Data public class ChatRequest {
    @NotBlank private String message;
    private List<ChatMessageDto> history;
    private String context;
    private String projectId;
    @Data public static class ChatMessageDto { private String role; private String content; }
}
