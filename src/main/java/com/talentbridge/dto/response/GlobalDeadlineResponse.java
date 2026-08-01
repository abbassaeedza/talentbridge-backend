package com.talentbridge.dto.response;

import java.time.LocalDateTime;

public record GlobalDeadlineResponse(boolean enabled, LocalDateTime deadline) { }
