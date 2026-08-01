package com.talentbridge.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class GlobalDeadlineRequest {
    @NotNull private Boolean enabled;
    private LocalDateTime deadline;

    @AssertTrue(message = "deadline is required when the global deadline is enabled")
    public boolean isDeadlineProvidedWhenEnabled() {
        return !Boolean.TRUE.equals(enabled) || deadline != null;
    }
}
