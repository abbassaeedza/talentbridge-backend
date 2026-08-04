package com.talentbridge.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UserResponse {
    private UUID id;
    private String email;
    private String fullName;
    private String firstName;
    private String lastName;
    private UserRole role;
    private UserStatus status;
    private String phoneNumber;
    private String githubUsername;
    private String rejectionReason;
    private boolean onboardingComplete;
    private LocalDateTime createdAt;
    private StudentProfileDto studentProfile;
    private CompanyProfileDto companyProfile;
    private SupervisorProfileResponse supervisorProfile;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long suspensionCount;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long rejectionCount;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private UUID partyId;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String partyName;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StudentProfileDto {
        private UUID id;
        private Integer age;
        private String university;
        private String yearOfStudy;
        private String major;
        private List<String> skills;
        private String pastExperience;
        private String bio;
        private String linkedinUrl;
        private String portfolioUrl;
        private Double gpa;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CompanyProfileDto {
        private UUID id;
        private String companyName;
        private String industry;
        private String description;
        private String website;
        private String logoUrl;
        private String country;
        private String city;
    }
}
