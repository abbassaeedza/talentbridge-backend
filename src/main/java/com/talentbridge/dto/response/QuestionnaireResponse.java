package com.talentbridge.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentbridge.enums.QuestionnaireState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record QuestionnaireResponse(UUID id, UUID submissionId, int assessmentVersion, String projectTitle,
        QuestionnaireState state, String answerState, long revision, int answeredCount,
        boolean editable, Instant opensAt, Instant closesAt, Instant serverNow, Instant savedAt,
        List<Question> questions) {
    public record Question(UUID id, int position, String prompt, JsonNode sourceReferences, String answer) {}
}
