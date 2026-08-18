package com.talentbridge.dto.request;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
    @JsonIgnore private String appContext;
    private String projectId;
    @Data public static class ChatMessageDto {
        @NotBlank @Pattern(regexp = "user|assistant") private String role;
        // Replayed assistant turns can reach the full chat token budget; OpenAIService
        // trims every history entry to 2000 characters before the upstream call.
        @NotBlank @Size(max = 12000) private String content;
    }
}
