package com.talentbridge.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "supervisor_profiles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SupervisorProfile extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @JsonIgnore
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    @JsonIgnore
    private CompanyProfile company;

    @Column(length = 100, nullable = false) private String jobTitle;
    @Column(length = 255, nullable = false) private String department;
    @Column(columnDefinition = "TEXT") private String bio;
    @Column(length = 500) private String linkedinUrl;
    @Column(length = 500) private String profilePhotoUrl;
}
