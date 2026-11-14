package com.talentbridge.controller;

import com.talentbridge.config.SecurityConfig;
import com.talentbridge.repository.CompanyProfileRepository;
import com.talentbridge.repository.ScorecardRepository;
import com.talentbridge.repository.UserRepository;
import com.talentbridge.security.JwtAuthenticationFilter;
import com.talentbridge.security.JwtTokenProvider;
import com.talentbridge.service.AuthService;
import com.talentbridge.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, AuthService.class})
@TestPropertySource(properties = {"app.frontend-url=http://localhost:3000", "app.demo-mode=true"})
class AuthControllerSecurityTest {
    @Autowired private MockMvc mvc;
    @MockBean private JwtTokenProvider tokenProvider;
    @MockBean private UserRepository userRepository;
    @MockBean private CompanyProfileRepository companyProfileRepository;
    @MockBean private ScorecardRepository scorecardRepository;
    @MockBean private NotificationService notificationService;

    @Test
    void anonymousDemoLoginIsGoneEvenWhenDemoModeIsEnabled() throws Exception {
        mvc.perform(post("/api/auth/demo-login")).andExpect(status().isNotFound());

        verifyNoInteractions(userRepository, tokenProvider);
    }

    @Test
    void normalLoginWithoutCredentialsCannotIssueCoordinatorTokens() throws Exception {
        mvc.perform(post("/api/auth/login")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userRepository, tokenProvider);
    }
}
