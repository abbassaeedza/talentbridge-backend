package com.talentbridge.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
public class ProjectRequest {
    @NotBlank @Size(max = 100)
    private String title;

    @NotBlank @Size(max = 2000)
    private String description;

    @Size(max = 2000) private String scope;
    @Size(max = 2000) private String deliverables;
    @Size(max = 2000) private String evaluationCriteria;
    @Size(max = 20) private List<@Size(max = 100) String> tools;
    private LocalDateTime deadline;
    private UUID projectSupervisorId;
    @Size(max = 100) private String projectField;

    // Company-only internal reference name — not shown to students
    @Size(max = 100) private String internalName;
}
