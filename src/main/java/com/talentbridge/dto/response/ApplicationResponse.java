package com.talentbridge.dto.response;
import com.talentbridge.enums.ApplicationStatus;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ApplicationResponse {
    private UUID id; private UUID projectId; private String projectTitle; private String companyName;
    private UUID partyId; private String partyName; private Integer rankPosition;
    private String proposalText; private ApplicationStatus status;
    private String coordinatorNotes; private LocalDateTime createdAt;
}
