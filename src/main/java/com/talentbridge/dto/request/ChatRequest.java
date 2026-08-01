package com.talentbridge.dto.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;
@Data public class ChatRequest {
    @NotBlank @Size(max = 2000) private String message;
    @Valid @Size(max = 10) private List<ChatMessageDto> history;
    private String context;
    private String projectId;
    @Data public static class ChatMessageDto {
        @NotBlank @Pattern(regexp = "user|assistant") private String role;
        @NotBlank @Size(max = 2000) private String content;
    }
}
