package com.talentbridge.dto;

import com.talentbridge.dto.request.ChatRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsALongAssistantReplyReplayedAsHistory() {
        ChatRequest request = request("what are the deliverables", "assistant", "x".repeat(6000));

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsAUserMessageOverTheInputLimit() {
        ChatRequest request = request("y".repeat(2001), "assistant", "short reply");

        assertEquals(1, validator.validate(request).size());
    }

    private ChatRequest request(String message, String historyRole, String historyContent) {
        ChatRequest.ChatMessageDto turn = new ChatRequest.ChatMessageDto();
        turn.setRole(historyRole);
        turn.setContent(historyContent);
        ChatRequest request = new ChatRequest();
        request.setMessage(message);
        request.setContext("STUDENT");
        request.setHistory(List.of(turn));
        return request;
    }
}
