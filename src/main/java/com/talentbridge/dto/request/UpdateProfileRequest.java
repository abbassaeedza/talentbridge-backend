package com.talentbridge.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class UpdateProfileRequest {
    // Common — all roles
    private String firstName;
    private String lastName;
    private String phoneNumber;

    // Student fields
    private String university;
    private String major;
    private String yearOfStudy;
    private String bio;
    private String linkedinUrl;
    private String portfolioUrl;
    private String pastExperience;
    private List<String> skills;
    private Double gpa;
    private Integer age;

    // Company fields
    private String companyName;
    private String industry;
    private String description;
    private String website;
    private String city;
    private String country;
}