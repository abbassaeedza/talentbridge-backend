package com.talentbridge.config;

import com.talentbridge.security.*;
import com.talentbridge.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AssessmentSecurityTest.Worker.class)
@Import({SecurityConfig.class, AssessmentSecurityConfig.class, JwtAuthenticationFilter.class, AssessmentSecurityTest.Worker.class})
@TestPropertySource(properties = "app.frontend-url=http://localhost:3000")
class AssessmentSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired FilterRegistrationBean<JwtAuthenticationFilter> registration;
    @MockBean AssessmentOidcVerifier google;
    @MockBean JwtTokenProvider app;
    @MockBean UserRepository users;

    @RestController static class Worker {
        @PostMapping("/internal/assessment-jobs/{id}") String run() { return "ran"; }
    }

    @Test void onlyGoogleAuthorizationCanReachWorkerAndAppFilterIsNotAServletFilter() throws Exception {
        assertFalse(registration.isEnabled());
        for (String token : new String[]{"", "Bearer app-coordinator", "Bearer malformed", "Basic spoofed"}) {
            mvc.perform(post("/internal/assessment-jobs/synthetic").header("Authorization", token)
                    .header("X-CloudTasks-TaskName", "spoofed")).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/internal/assessment-jobs/synthetic").header("X-Serverless-Authorization", "Bearer google"))
                .andExpect(status().isUnauthorized());
        when(google.verify("google")).thenReturn(true);
        mvc.perform(post("/internal/assessment-jobs/synthetic").header("Authorization", "Bearer google"))
                .andExpect(status().isOk()).andExpect(content().string("ran"));
        mvc.perform(get("/internal/assessment-jobs/synthetic").header("Authorization", "Bearer google"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(app, users);
    }
}
