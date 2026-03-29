package com.talentbridge.dto.request;
import lombok.Data;
import java.util.List;
@Data public class StudentOnboardingRequest {
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
