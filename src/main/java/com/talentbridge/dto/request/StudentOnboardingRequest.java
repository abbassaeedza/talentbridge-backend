package com.talentbridge.dto.request;
import lombok.Data;
import jakarta.validation.constraints.*;
import java.util.List;
@Data public class StudentOnboardingRequest {
    @Min(0) @Max(150) private Integer age;
    @NotBlank @Size(max = 255) private String university;
    @NotBlank @Size(max = 50) private String yearOfStudy;
    @NotBlank @Size(max = 255) private String major;
    @Size(max = 20) private List<@NotBlank @Size(max = 100) String> skills;
    @Size(max = 3000) private String pastExperience;
    @Size(max = 2000) private String bio;
    @Size(max = 500) @Pattern(regexp = UpdateProfileRequest.HTTP_URL) private String linkedinUrl;
    @Size(max = 500) @Pattern(regexp = UpdateProfileRequest.HTTP_URL) private String portfolioUrl;
    @DecimalMin("0") @DecimalMax("4") private Double gpa;
}
