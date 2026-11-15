package com.talentbridge.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import lombok.AccessLevel;
import lombok.Getter;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

@Getter
public class UpdateProfileRequest {
    public static final String HTTP_URL = "(?i)^(https?://[^\\s/?#]+(?:[/?#][^\\s]*)?)?$";

    @Getter(AccessLevel.NONE)
    private final Set<String> supplied = new HashSet<>();

    @Size(max = 100) private String firstName;
    @Size(max = 100) private String lastName;
    @Size(max = 50) private String phoneNumber;
    @Size(max = 255) private String university;
    @Size(max = 255) private String major;
    @Size(max = 50) private String yearOfStudy;
    @Size(max = 2000) private String bio;
    @Size(max = 500) @Pattern(regexp = HTTP_URL) private String linkedinUrl;
    @Size(max = 500) @Pattern(regexp = HTTP_URL) private String portfolioUrl;
    @Size(max = 3000) private String pastExperience;
    @Size(max = 20) private List<@NotBlank @Size(max = 100) String> skills;
    @DecimalMin("0") @DecimalMax("4") private Double gpa;
    @Min(0) @Max(150) private Integer age;
    @Size(max = 255) private String companyName;
    @Size(max = 100) private String industry;
    @Size(max = 2000) private String description;
    @Size(max = 500) @Pattern(regexp = HTTP_URL) private String website;
    @Size(max = 100) private String city;
    @Size(max = 100) private String country;

    // Setters record presence so explicit null clears a field while omission preserves it.
    public void setFirstName(String value) { supplied.add("firstName"); firstName = required(value); }
    public void setLastName(String value) { supplied.add("lastName"); lastName = required(value); }
    public void setPhoneNumber(String value) { supplied.add("phoneNumber"); phoneNumber = value; }
    public void setUniversity(String value) { supplied.add("university"); university = required(value); }
    public void setMajor(String value) { supplied.add("major"); major = required(value); }
    public void setYearOfStudy(String value) { supplied.add("yearOfStudy"); yearOfStudy = required(value); }
    public void setBio(String value) { supplied.add("bio"); bio = value; }
    public void setLinkedinUrl(String value) { supplied.add("linkedinUrl"); linkedinUrl = value; }
    public void setPortfolioUrl(String value) { supplied.add("portfolioUrl"); portfolioUrl = value; }
    public void setPastExperience(String value) { supplied.add("pastExperience"); pastExperience = value; }
    public void setSkills(List<String> value) { supplied.add("skills"); skills = value; }
    public void setGpa(Double value) { supplied.add("gpa"); gpa = value; }
    public void setAge(Integer value) { supplied.add("age"); age = value; }
    public void setCompanyName(String value) { supplied.add("companyName"); companyName = required(value); }
    public void setIndustry(String value) { supplied.add("industry"); industry = value; }
    public void setDescription(String value) { supplied.add("description"); description = value; }
    public void setWebsite(String value) { supplied.add("website"); website = value; }
    public void setCity(String value) { supplied.add("city"); city = value; }
    public void setCountry(String value) { supplied.add("country"); country = value; }

    public boolean has(String field) { return supplied.contains(field); }
    public Set<String> fields() { return Set.copyOf(supplied); }

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unknown or non-editable profile field");
    }

    private String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Name must not be blank");
        return value.trim();
    }
}
