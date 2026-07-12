package com.talentbridge.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
public class ProjectRequest {
    @NotBlank
    private String title;

    @NotBlank
    private String description;

    private String scope;
    private String deliverables;
    private String evaluationCriteria;
    private List<String> tools;
    private LocalDateTime deadline;
    private UUID projectSupervisorId;
    private String projectField;

    // Company-only internal reference name — not shown to students
    private String internalName;
}
