package com.talentbridge.dto.response;
import lombok.*;
import java.util.Map;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ChatResponse {
    private String message;
    private String model;
    private Map<String, Object> projectDraft;
}
