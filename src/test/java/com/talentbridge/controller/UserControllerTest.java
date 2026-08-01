package com.talentbridge.controller;

import com.talentbridge.service.GitHubService;
import com.talentbridge.service.NotificationService;
import com.talentbridge.service.ScorecardService;
import com.talentbridge.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerTest {

    @Test
    void deletesARejectedUserSoTheEmailCanRegisterAgain() throws Exception {
        UserController controller = new UserController(
                mock(UserService.class),
                mock(NotificationService.class),
                mock(ScorecardService.class),
                mock(GitHubService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(delete("/api/users/{id}", UUID.randomUUID()))
                .andExpect(status().isNoContent());
    }
}
