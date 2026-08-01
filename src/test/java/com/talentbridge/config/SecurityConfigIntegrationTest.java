package com.talentbridge.config;

import com.talentbridge.controller.UserController;
import com.talentbridge.dto.response.UserResponse;
import com.talentbridge.entity.User;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.repository.UserRepository;
import com.talentbridge.security.JwtAuthenticationFilter;
import com.talentbridge.security.JwtTokenProvider;
import com.talentbridge.service.GitHubService;
import com.talentbridge.service.NotificationService;
import com.talentbridge.service.ScorecardService;
import com.talentbridge.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@TestPropertySource(properties = "app.frontend-url=http://localhost:3000")
class SecurityConfigIntegrationTest {

    @Autowired private MockMvc mvc;
    @MockBean private JwtTokenProvider tokenProvider;
    @MockBean private UserRepository userRepository;
    @MockBean private UserService userService;
    @MockBean private NotificationService notificationService;
    @MockBean private ScorecardService scorecardService;
    @MockBean private GitHubService gitHubService;

    @Test
    void letsPendingUsersReadTheirStatusButBlocksApplicationData() throws Exception {
        UUID userId = UUID.randomUUID();
        User pending = User.builder()
                .email("pending@example.com")
                .firstName("Pending")
                .lastName("Student")
                .role(UserRole.STUDENT)
                .status(UserStatus.PENDING)
                .build();
        pending.setId(userId);
        when(tokenProvider.validateToken("token")).thenReturn(true);
        when(tokenProvider.getUserIdFromToken("token")).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(pending));
        when(userService.getById(userId)).thenReturn(pending);
        when(userService.toResponse(pending)).thenReturn(UserResponse.builder().id(userId).build());

        mvc.perform(get("/api/users/me").header("Authorization", "Bearer token"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/students").header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden());
    }
}
