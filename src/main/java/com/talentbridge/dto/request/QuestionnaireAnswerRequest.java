package com.talentbridge.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record QuestionnaireAnswerRequest(@NotNull @PositiveOrZero Long expectedRevision,
        @NotNull @Size(min = 5, max = 5) List<@NotNull @Valid Answer> answers) {
    public record Answer(@NotNull UUID questionId, @NotNull @Size(max = 2000) String text) {}
}
