package com.talentbridge.dto.response;

import lombok.*;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SupervisorProfileResponse {
    private UUID id;
    private String jobTitle;
    private String department;
    private String bio;
    private String linkedinUrl;
    private String profilePhotoUrl;
}
