package com.talentbridge.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    @Test
    void preservesFrameworkHttpStatus() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<GlobalExceptionHandler.ApiError> response = handler.generic(
            new HttpRequestMethodNotSupportedException("GET"));

        assertEquals(405, response.getStatusCode().value());
        assertEquals(405, response.getBody().getStatus());
    }
}
