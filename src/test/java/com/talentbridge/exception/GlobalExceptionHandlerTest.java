package com.talentbridge.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {

    @Test
    void preservesFrameworkHttpStatus() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<GlobalExceptionHandler.ApiError> response = handler.generic(
            new HttpRequestMethodNotSupportedException("GET"));

        assertEquals(405, response.getStatusCode().value());
        assertEquals(405, response.getBody().getStatus());
    }

    @Test
    void quotaHandlerReturnsReasonAndMatchingRetryHeader() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new QuotaEndpoint())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/quota"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "17"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value("AI chat account rate limit reached"))
                .andExpect(jsonPath("$.reason").value("ACCOUNT_RATE_LIMIT"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(17))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void otherErrorsKeepTheirExistingFieldsAndNoQuotaHeader() throws Exception {
        var response = new GlobalExceptionHandler().badRequest(new BadRequestException("Existing message"));
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(response.getBody());
        assertEquals(400, response.getStatusCode().value());
        assertEquals("Existing message", json.path("message").asText());
        assertFalse(json.has("reason"));
        assertFalse(json.has("retryAfterSeconds"));
        assertFalse(response.getHeaders().containsKey(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void retryHeaderCannotAdvertiseZeroSeconds() {
        var failure = new AiQuotaExceededException(AiQuotaExceededException.Reason.ACCOUNT_IN_FLIGHT, 0);
        var response = new GlobalExceptionHandler().aiQuota(failure);
        assertEquals("1", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertEquals(1, response.getBody().getRetryAfterSeconds());
        assertEquals("ACCOUNT_IN_FLIGHT", response.getBody().getReason());
    }

    @RestController
    static class QuotaEndpoint {
        @GetMapping("/quota")
        public void quota() {
            throw new AiQuotaExceededException(AiQuotaExceededException.Reason.ACCOUNT_RATE_LIMIT, 17);
        }
    }
}
