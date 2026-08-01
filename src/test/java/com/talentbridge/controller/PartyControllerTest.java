package com.talentbridge.controller;

import com.talentbridge.service.PartyService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PartyControllerTest {

    @Test
    void exposesConfiguredPartyRules() throws Exception {
        PartyController controller = new PartyController(mock(PartyService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/parties/rules"))
                .andExpect(status().isOk());
    }

    @Test
    void exposesASeparatePartySupervisorBrowseRoute() throws Exception {
        PartyService service = mock(PartyService.class);
        PartyController controller = new PartyController(service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/parties/browse"))
                .andExpect(status().isOk());

        verify(service).getAll();
    }
}
