package com.talentbridge.dto.response;

import com.talentbridge.enums.ProjectStatus;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProjectResponse {
    private UUID id;
    private String title;
    private String description;
    private String scope;
    private String deliverables;
    private String evaluationCriteria;
    private List<String> tools;
    private ProjectStatus status;
    private LocalDateTime deadline;
    private String companyName;
    private String companyIndustry;
    private UUID companyId;
    private String projectSupervisorName;
    private UUID projectSupervisorId;
    private String createdByName;
    private long applicantCount;
    private LocalDateTime createdAt;

    // Only populated for the company that owns the project
    // Null / omitted in all other contexts
    private String internalName;
}