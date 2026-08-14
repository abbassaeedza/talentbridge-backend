package com.talentbridge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentbridge.dto.request.ChatRequest;
import com.talentbridge.entity.User;
import com.talentbridge.enums.UserRole;
import com.talentbridge.repository.UserRepository;
import com.talentbridge.service.OpenAIService;
import com.talentbridge.service.ChatContextService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    @Test
    void derivesCompanyContextAndReturnsProjectDraft() {
        OpenAIService ai = mock(OpenAIService.class);
        ChatContextService contextService = mock(ChatContextService.class);
        UserRepository users = mock(UserRepository.class);
        ChatController controller = new ChatController(ai, users, contextService, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        User company = mock(User.class);
        when(company.getRole()).thenReturn(UserRole.COMPANY);
        when(users.findById(userId)).thenReturn(Optional.of(company));
        when(contextService.build(company)).thenReturn("Project counts: total=3, assigned=1");
        ChatRequest request = new ChatRequest();
        request.setMessage("Create a React project");
        request.setContext("STUDENT_PROJECT_INQUIRY");
        when(ai.chat(request)).thenReturn("""
                {"message":"Draft ready","projectDraft":{"title":"Client portal","tools":["React"]}}
                """);

        var response = controller.chat(userId, request);

        assertEquals("COMPANY", request.getContext());
        assertEquals("Project counts: total=3, assigned=1", request.getAppContext());
        assertEquals("Draft ready", response.getMessage());
        assertEquals("Client portal", response.getProjectDraft().get("title"));
        verify(ai).chat(request);
    }
}
