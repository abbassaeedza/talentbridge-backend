package com.talentbridge.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProjectSupervisorInvitationAcceptRequest {
    @NotBlank @Size(min = 8) private String password;
    @NotBlank private String firstName;
    @NotBlank private String lastName;
    private String phoneNumber;
    @NotBlank @Size(max = 100) private String jobTitle;
    @NotBlank @Size(max = 255) private String department;
    @NotBlank @Size(max = 2000) private String bio;
    @Size(max = 500) private String linkedinUrl;
}
