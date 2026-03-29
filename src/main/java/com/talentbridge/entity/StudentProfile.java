package com.talentbridge.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "student_profiles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StudentProfile extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnoreProperties({"studentProfile", "companyProfile", "password",
            "githubAccessToken", "emailVerificationToken"})
    private User user;

    private Integer age;
    @Column(length = 255) private String university;
    @Column(length = 50)  private String yearOfStudy;
    @Column(length = 255) private String major;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "student_skills",
            joinColumns = @JoinColumn(name = "student_id"))
    @Column(name = "skill", length = 100)
    @Builder.Default
    private List<String> skills = new ArrayList<>();

    @Column(columnDefinition = "TEXT") private String pastExperience;
    @Column(columnDefinition = "TEXT") private String bio;
    @Column(length = 500) private String linkedinUrl;
    @Column(length = 500) private String portfolioUrl;
    @Column(columnDefinition = "NUMERIC") private Double gpa;
}