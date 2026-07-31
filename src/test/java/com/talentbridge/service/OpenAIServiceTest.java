package com.talentbridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.talentbridge.dto.request.ChatRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAIServiceTest {

    private HttpServer server;
    private OpenAIService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        service = new OpenAIService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "baseUrl",
                "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "chatModel", "gpt-4o-mini");
        ReflectionTestUtils.setField(service, "evalModel", "gpt-4o-mini");
        ReflectionTestUtils.setField(service, "maxTokens", 256);
        ReflectionTestUtils.setField(service, "timeoutSeconds", 5);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void returnsAssistantMessageFromChatCompletion() {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200,
                    "{\"choices\":[{\"message\":{\"content\":\"Demo reply\"}}]}");
        });
        ChatRequest request = new ChatRequest();
        request.setMessage("Explain the scope");
        request.setContext("STUDENT_PROJECT_INQUIRY");

        String result = service.chat(request);

        assertEquals("Demo reply", result);
        assertEquals("Bearer test-key", authorization.get());
        assertTrue(requestBody.get().contains("\"model\":\"gpt-4o-mini\""));
        assertTrue(requestBody.get().contains("Explain the scope"));
    }

    @Test
    void convertsUpstreamAuthenticationFailureToSafeServiceUnavailable() {
        server.createContext("/chat/completions", exchange -> respond(exchange, 401,
                "{\"error\":{\"message\":\"invalid test credential\"}}"));
        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.chat(request));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        assertEquals("AI service is temporarily unavailable", error.getReason());
        assertFalse(error.getReason().contains("credential"));
    }

    @Test
    void rejectsSuccessfulResponseWithoutAssistantContent() {
        server.createContext("/chat/completions", exchange -> respond(exchange, 200,
                "{\"choices\":[]}"));
        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.chat(request));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        assertEquals("AI service is temporarily unavailable", error.getReason());
    }

    @Test
    void restoresThreadInterruptWhenRequestIsInterrupted() {
        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        Thread.currentThread().interrupt();

        try {
            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> service.chat(request));

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
