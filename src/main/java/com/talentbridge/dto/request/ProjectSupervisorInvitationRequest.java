package com.talentbridge.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ProjectSupervisorInvitationRequest {
    @NotBlank @Email private String email;
}
