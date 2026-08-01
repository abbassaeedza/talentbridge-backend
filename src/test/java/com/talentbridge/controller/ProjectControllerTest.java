package com.talentbridge.controller;

import com.talentbridge.service.ProjectService;
import com.talentbridge.dto.response.GlobalDeadlineResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectControllerTest {

    @Test
    void returnsADisabledGlobalDeadlineWhenNoGlobalDeadlineHasBeenSet() throws Exception {
        ProjectService service = mock(ProjectService.class);
        when(service.getGlobalDeadline()).thenReturn(new GlobalDeadlineResponse(false, null));
        ProjectController controller = new ProjectController(service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/projects/global-deadline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }
}
