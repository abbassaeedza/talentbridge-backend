package com.talentbridge.exception;

public class AiQuotaExceededException extends RuntimeException {
    public enum Reason { GLOBAL_DAILY_LIMIT, ACCOUNT_RATE_LIMIT, ACCOUNT_IN_FLIGHT }

    private final Reason reason;
    private final long retryAfterSeconds;

    public AiQuotaExceededException(Reason reason, long retryAfterSeconds) {
        super(switch (reason) {
            case GLOBAL_DAILY_LIMIT -> "AI chat daily limit reached";
            case ACCOUNT_RATE_LIMIT -> "AI chat account rate limit reached";
            case ACCOUNT_IN_FLIGHT -> "An AI chat request is already in progress";
        });
        this.reason = reason;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public Reason getReason() { return reason; }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
