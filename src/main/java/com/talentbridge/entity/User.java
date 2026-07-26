package com.talentbridge.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User extends BaseEntity {

    @Column(nullable = false, unique = true, length = 255) private String email;
    @JsonIgnore @Column(nullable = false)                  private String password;
    @Column(nullable = false, length = 100)                private String firstName;
    @Column(nullable = false, length = 100)                private String lastName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)                 private UserRole role;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)                 private UserStatus status;
    @Column(length = 50)                                   private String phoneNumber;
    @JsonIgnore @Column(columnDefinition = "TEXT")         private String githubAccessToken;
    @Column(length = 100)                                  private String githubUsername;
    @Column(columnDefinition = "TEXT")                     private String rejectionReason;
    @Builder.Default @Column(nullable = false)             private boolean emailVerified = false;
    @JsonIgnore @Column(length = 255)                      private String emailVerificationToken;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnoreProperties("user")   // ← breaks the User→StudentProfile→User loop
    private StudentProfile studentProfile;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnoreProperties("user")   // ← breaks the User→CompanyProfile→User loop
    private CompanyProfile companyProfile;

    public String getFullName() { return firstName + " " + lastName; }
}
