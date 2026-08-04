package com.talentbridge.dto.response;

import com.talentbridge.enums.SubmissionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionResponse {
    private UUID id;
    private UUID partyId;
    private String partyName;
    private UUID projectId;
    private String projectTitle;
    private String repoUrl;
    private String repoBranch;
    private List<String> documentUrls;
    private SubmissionStatus status;
    private LocalDateTime submittedAt;
    private String notes;
}
