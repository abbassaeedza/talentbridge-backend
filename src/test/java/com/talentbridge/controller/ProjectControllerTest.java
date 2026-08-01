package com.talentbridge.controller;

import com.talentbridge.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectControllerTest {

    @Test
    void returnsNoContentWhenNoGlobalDeadlineHasBeenSet() throws Exception {
        ProjectController controller = new ProjectController(mock(ProjectService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/projects/global-deadline"))
                .andExpect(status().isNoContent());
    }
}
