package com.talentbridge.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class SupervisorOnboardingRequest {
    @NotBlank @Size(max = 100) private String jobTitle;
    @NotBlank @Size(max = 255) private String department;
    @NotBlank @Size(max = 2000) private String bio;
    @Size(max = 500) private String linkedinUrl;
}
