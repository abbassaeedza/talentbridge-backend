package com.talentbridge.dto.response;

import java.util.Map;

public record GlobalDeadlinePreviewResponse(Map<String, Long> affectedCounts,
                                            long excludedFinishedCount) { }
