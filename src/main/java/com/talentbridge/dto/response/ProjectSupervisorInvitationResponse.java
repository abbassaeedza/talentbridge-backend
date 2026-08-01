package com.talentbridge.dto.response;

import com.talentbridge.enums.ProjectSupervisorInvitationStatus;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProjectSupervisorInvitationResponse {
    private UUID id;
    private String email;
    private String companyName;
    private ProjectSupervisorInvitationStatus status;
    private LocalDateTime expiresAt;
}
