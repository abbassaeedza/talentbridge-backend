package com.talentbridge.dto.response;

import com.talentbridge.enums.NotificationType;

public record NotificationPreferenceResponse(NotificationType type, boolean emailEnabled) { }
