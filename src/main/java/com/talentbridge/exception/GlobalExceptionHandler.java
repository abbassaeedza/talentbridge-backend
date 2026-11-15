package com.talentbridge.exception;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.*;

@RestControllerAdvice @Slf4j
public class GlobalExceptionHandler {
    @ExceptionHandler(AiQuotaExceededException.class)
    public ResponseEntity<QuotaError> aiQuota(AiQuotaExceededException e) {
        return ResponseEntity.status(429)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.getRetryAfterSeconds()))
                .body(new QuotaError(e));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ResourceNotFoundException e) {
        return ResponseEntity.status(404).body(new ApiError(404, e.getMessage()));
    }
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> badRequest(BadRequestException e) {
        return ResponseEntity.status(400).body(new ApiError(400, e.getMessage()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ApiError(400, "Request body is missing or invalid"));
    }
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> forbidden(ForbiddenException e) {
        return ResponseEntity.status(403).body(new ApiError(403, e.getMessage()));
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> accessDenied(AccessDeniedException e) {
        return ResponseEntity.status(403).body(new ApiError(403, "Access denied"));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        Map<String,String> errs = new LinkedHashMap<>();
        e.getBindingResult().getAllErrors().forEach(err ->
            errs.put(((FieldError)err).getField(), err.getDefaultMessage()));
        ApiError a = new ApiError(400, "Validation failed"); a.setFieldErrors(errs);
        return ResponseEntity.badRequest().body(a);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> generic(Exception e) {
        if (e instanceof ErrorResponse error) {
            int status = error.getStatusCode().value();
            return ResponseEntity.status(status).body(new ApiError(status, error.getBody().getDetail()));
        }
        log.error("Unhandled exception", e);
        return ResponseEntity.status(500).body(new ApiError(500, "An unexpected error occurred"));
    }

    public static class QuotaError extends ApiError {
        private final String reason;
        private final long retryAfterSeconds;

        public QuotaError(AiQuotaExceededException e) {
            super(429, e.getMessage());
            reason = e.getReason().name();
            retryAfterSeconds = e.getRetryAfterSeconds();
        }

        public String getReason() { return reason; }
        public long getRetryAfterSeconds() { return retryAfterSeconds; }
    }

    @Data
    public static class ApiError {
        private int status; private String message;
        private LocalDateTime timestamp = LocalDateTime.now();
        private Map<String,String> fieldErrors;
        public ApiError(int s, String m) { this.status=s; this.message=m; }
    }
}
