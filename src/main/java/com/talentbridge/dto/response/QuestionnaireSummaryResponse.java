package com.talentbridge.dto.response;

import com.talentbridge.enums.QuestionnaireState;
import java.time.Instant;
import java.util.UUID;

public record QuestionnaireSummaryResponse(UUID id, UUID submissionId, int assessmentVersion,
        QuestionnaireState state, String answerState, long revision, int answeredCount,
        boolean editable, Instant opensAt, Instant closesAt, Instant serverNow) {}
