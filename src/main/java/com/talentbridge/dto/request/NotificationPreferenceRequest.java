package com.talentbridge.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class NotificationPreferenceRequest {
    @NotNull private Boolean emailEnabled;
}
