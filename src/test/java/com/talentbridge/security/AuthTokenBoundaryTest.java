package com.talentbridge.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentbridge.config.SecurityConfig;
import com.talentbridge.controller.AuthController;
import com.talentbridge.controller.UserController;
import com.talentbridge.dto.response.UserResponse;
import com.talentbridge.entity.User;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.repository.*;
import com.talentbridge.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AuthController.class, UserController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class, AuthService.class})
@TestPropertySource(properties = {"app.frontend-url=http://localhost:3000", "app.demo-mode=true",
        "jwt.secret=synthetic-stage-two-test-signing-key-not-a-production-secret",
        "jwt.expiration-ms=60000", "jwt.refresh-expiration-ms=120000"})
class AuthTokenBoundaryTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PasswordEncoder encoder;
    @MockBean UserRepository users;
    @MockBean CompanyProfileRepository companies;
    @MockBean ScorecardRepository scorecards;
    @MockBean NotificationService notifications;
    @MockBean UserService userService;
    @MockBean ScorecardService scorecardService;
    @MockBean GitHubService github;
    User user;

    @BeforeEach
    void account() {
        user = User.builder().email("mira.shah@example.test").firstName("Mira").lastName("Shah")
                .password(encoder.encode("SyntheticPassword!"))
                .role(UserRole.STUDENT).status(UserStatus.APPROVED).build();
        user.setId(UUID.randomUUID());
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(users.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(users.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        when(userService.getById(user.getId())).thenReturn(user);
        when(userService.toResponse(user)).thenReturn(UserResponse.builder().id(user.getId()).build());
    }

    JsonNode login() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(mapper.writeValueAsString(java.util.Map.of("email", user.getEmail(), "password", "SyntheticPassword!"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    @Test
    void refreshTokenCannotAuthenticateAProtectedRequest() throws Exception {
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + login().path("refreshToken").asText()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accessTokenCannotRefreshASession() throws Exception {
        mvc.perform(post("/api/auth/refresh").contentType("application/json")
                        .content(mapper.writeValueAsString(java.util.Map.of("refreshToken", login().path("accessToken").asText()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousPasswordChangeIsRejectedBeforeTheService() throws Exception {
        mvc.perform(put("/api/auth/password").contentType("application/json")
                        .content("{\"currentPassword\":\"SyntheticPassword!\",\"newPassword\":\"ReplacementPassword!\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void managedDemoPasswordCannotBeChangedIndividually() throws Exception {
        mvc.perform(put("/api/auth/password").header("Authorization", "Bearer " + login().path("accessToken").asText())
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"SyntheticPassword!\",\"newPassword\":\"ReplacementPassword!\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void normalAccessAndRefreshStillWork() throws Exception {
        JsonNode session = login();
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + session.path("accessToken").asText()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/refresh").contentType("application/json")
                        .content(mapper.writeValueAsString(java.util.Map.of("refreshToken", session.path("refreshToken").asText()))))
                .andExpect(status().isOk());
    }

    @Test
    void passwordChangeInvalidatesBothOldTokensButNewPasswordCanSignIn() throws Exception {
        user.setEmail("ordinary.member@example.test");
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        JsonNode session = login();
        mvc.perform(put("/api/auth/password").header("Authorization", "Bearer " + session.path("accessToken").asText())
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"SyntheticPassword!\",\"newPassword\":\"ReplacementPassword!\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + session.path("accessToken").asText()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").contentType("application/json")
                        .content(mapper.writeValueAsString(java.util.Map.of("refreshToken", session.path("refreshToken").asText()))))
                .andExpect(status().isBadRequest());
        String renewed = mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(mapper.writeValueAsString(java.util.Map.of("email", user.getEmail(), "password", "ReplacementPassword!"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + mapper.readTree(renewed).path("accessToken").asText()))
                .andExpect(status().isOk());
    }
}
