package com.talentbridge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentbridge.config.SecurityConfig;
import com.talentbridge.dto.request.ChatRequest;
import com.talentbridge.dto.response.ChatContextResponse;
import com.talentbridge.dto.response.ChatContextResponse.*;
import com.talentbridge.enums.*;
import com.talentbridge.exception.ForbiddenException;
import com.talentbridge.repository.UserRepository;
import com.talentbridge.security.JwtAuthenticationFilter;
import com.talentbridge.security.JwtTokenProvider;
import com.talentbridge.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@TestPropertySource(properties = "app.frontend-url=http://localhost:3000")
class ChatControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockBean ChatContextService context;
    @MockBean AiQuotaService quota;
    @MockBean OpenAIService ai;
    @MockBean JwtTokenProvider tokens;
    @MockBean UserRepository users;
    UUID userId = UUID.randomUUID();
    UUID lease = UUID.randomUUID();

    @BeforeEach
    void fixture() {
        when(quota.reserve(userId)).thenReturn(lease);
        when(ai.chatModel()).thenReturn("gpt-4o-mini");
    }

    MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        return request.with(authentication(new UsernamePasswordAuthenticationToken(userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"), new SimpleGrantedAuthority("APPROVED_USER")))));
    }

    ChatContextResponse snapshot(UserRole role) {
        return new ChatContextResponse(new Viewer(userId, role, "Viewer"), Instant.parse("2031-01-01T00:00:00Z"),
                new GlobalDeadline(false, null), new SummarySet<>(List.of(), 0), new SummarySet<>(List.of(), 0),
                new SummarySet<>(List.of(), 0), null, new SummarySet<>(List.of(), 0), new SummarySet<>(List.of(), 0),
                new SummarySet<>(List.of(), 0), new Counts(Map.of(), Map.of(), Map.of(), Map.of(), 0, null),
                null, null, new SummarySet<>(List.of(), 0));
    }

    @Test
    void contextHttpContractUsesSnapshotWithoutQuotaOrModel() throws Exception {
        doReturn(snapshot(UserRole.STUDENT)).when(context).snapshot(userId);
        mvc.perform(signedIn(get("/api/chat/context")))
                .andExpect(status().isOk()).andExpect(jsonPath("viewer.id").value(userId.toString()))
                .andExpect(jsonPath("viewer.role").value("STUDENT"))
                .andExpect(jsonPath("snapshotTimeUTC").value("2031-01-01T00:00:00Z"))
                .andExpect(jsonPath("openProjects.total").value(0))
                .andExpect(jsonPath("openProjects.truncated").value(false));
        verifyNoInteractions(quota, ai);
    }

    @Test
    void derivesRoleIgnoresClientSnapshotAndReleasesExactlyOneLease() throws Exception {
        var snapshot = snapshot(UserRole.STUDENT);
        when(context.snapshot(userId)).thenReturn(snapshot);
        when(context.formatForModel(snapshot)).thenReturn("server-scoped-json");
        when(ai.chat(any())).thenAnswer(call -> {
            ChatRequest request = call.getArgument(0);
            assertEquals("STUDENT", request.getContext());
            assertEquals("server-scoped-json", request.getAppContext());
            return "Reply";
        });
        mvc.perform(signedIn(post("/api/chat")).contentType("application/json")
                        .content("{\"message\":\"help\",\"context\":\"COORDINATOR\",\"appContext\":\"injected private facts\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("message").value("Reply"))
                .andExpect(jsonPath("model").value("gpt-4o-mini")).andExpect(jsonPath("source").value("llm"));
        var order = inOrder(context, quota, ai);
        order.verify(context).requireApprovedViewer(userId);
        order.verify(quota).reserve(userId);
        order.verify(context).snapshot(userId);
        order.verify(context).formatForModel(snapshot);
        order.verify(ai).chat(any());
        verify(quota).release(lease);
    }

    @Test
    void validationAndDbStatusFailuresNeverReserveQuota() throws Exception {
        mvc.perform(signedIn(post("/api/chat")).contentType("application/json").content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(quota, context, ai);
        when(context.requireApprovedViewer(userId)).thenThrow(new ForbiddenException("Approved account required"));
        mvc.perform(signedIn(post("/api/chat")).contentType("application/json").content("{\"message\":\"help\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(quota, ai);
    }

    @Test
    void unauthenticatedRequestsCannotReadContextOrSpendQuota() throws Exception {
        mvc.perform(get("/api/chat/context")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/chat").contentType("application/json").content("{\"message\":\"help\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(context, quota, ai);
    }

    @Test
    void releasesLeaseWhenSnapshotOrProviderFails() throws Exception {
        when(context.snapshot(userId)).thenThrow(new IllegalStateException("synthetic failure"));
        mvc.perform(signedIn(post("/api/chat")).contentType("application/json").content("{\"message\":\"help\"}"))
                .andExpect(status().isInternalServerError());
        verify(quota).release(lease);
        reset(quota);
        when(quota.reserve(userId)).thenReturn(lease);
        doReturn(snapshot(UserRole.STUDENT)).when(context).snapshot(userId);
        when(ai.chat(any())).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI service is temporarily unavailable"));
        mvc.perform(signedIn(post("/api/chat")).contentType("application/json").content("{\"message\":\"help\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("message").value("AI service is temporarily unavailable"));
        verify(quota).release(lease);
    }

    @Test
    void whitelistsCompanyDraftWithoutPersistingAnything() throws Exception {
        when(context.snapshot(userId)).thenReturn(snapshot(UserRole.COMPANY));
        when(ai.chat(any())).thenReturn("{\"message\":\"Suggestion\",\"projectDraft\":{\"title\":\"" + "x".repeat(150)
                + "\",\"tools\":[\"Java\",5],\"createdById\":\"evil\",\"status\":\"OPEN\",\"saveAsDraft\":true}}");
        String body = mvc.perform(signedIn(post("/api/chat")).contentType("application/json").content("{\"message\":\"suggest a project\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("message").value("Suggestion"))
                .andExpect(jsonPath("projectDraft.createdById").doesNotExist())
                .andExpect(jsonPath("projectDraft.status").doesNotExist())
                .andExpect(jsonPath("projectDraft.saveAsDraft").doesNotExist())
                .andExpect(jsonPath("projectDraft.tools[0]").value("Java"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(100, mapper.readTree(body).path("projectDraft").path("title").asText().length());
        verifyNoInteractions(users);
        verify(quota).release(lease);
    }
}
