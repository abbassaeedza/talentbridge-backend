package com.talentbridge.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "company_profiles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CompanyProfile extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnoreProperties({"studentProfile", "companyProfile", "password",
            "githubAccessToken", "emailVerificationToken"})
    private User user;

    @Column(nullable = false, length = 255) private String companyName;
    @Column(length = 100) private String industry;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(length = 500) private String website;
    @Column(length = 500) private String logoUrl;
    @Column(length = 100) private String registrationNumber;
    @Column(length = 100) private String country;
    @Column(length = 100) private String city;
}