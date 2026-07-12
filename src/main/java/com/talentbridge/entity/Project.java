package com.talentbridge.entity;

import com.talentbridge.enums.ProjectStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "projects",
        indexes = {
                @Index(name = "idx_project_status", columnList = "status"),
                @Index(name = "idx_project_company", columnList = "company_id")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Project extends BaseEntity {

    @Column(nullable = false, length = 500)
    private String title;

    // Company-only internal reference name — not shown to students
    @Column(name = "internal_name")
    private String internalName;

    @Column(name = "project_field", length = 100)
    private String projectField;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String scope;

    @Column(columnDefinition = "TEXT")
    private String deliverables;

    @Column(columnDefinition = "TEXT")
    private String evaluationCriteria;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "project_tools",
            joinColumns = @JoinColumn(name = "project_id"))
    @Column(name = "tool", length = 100)
    @Builder.Default
    private List<String> tools = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ProjectStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private CompanyProfile company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_supervisor_id")
    private User projectSupervisor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_id")
    private User approvedBy;

    private LocalDateTime deadline;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Application> applications = new ArrayList<>();

    // The party that was assigned
    @OneToOne(mappedBy = "assignedProject")
    private Party assignedParty;
}
