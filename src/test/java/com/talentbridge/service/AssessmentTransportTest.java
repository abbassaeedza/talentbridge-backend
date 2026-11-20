package com.talentbridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static com.talentbridge.service.AssessmentAIService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AssessmentTransportTest {
    OpenAIService service;
    HttpClient client;
    @BeforeEach void setup() {
        service=new OpenAIService(new ObjectMapper()); client=mock(HttpClient.class);
        ReflectionTestUtils.setField(service,"client",client);
        ReflectionTestUtils.setField(service,"evalModel","gpt-4o");
        ReflectionTestUtils.setField(service,"n8nWebhookUrl","http://127.0.0.1:12345/relay");
        ReflectionTestUtils.setField(service,"n8nWebhookSecret","synthetic-secret");
    }
    @Test void exactPromptBoundariesAreAcceptedAndOneExtraCharacterIsRejected() {
        for (String operation:List.of("questionnaire_generation","questionnaire_evaluation")) {
            int limit=operation.endsWith("generation")?64000:96000;
            assertNotNull(service.prepareAssessment(operation,"s","x".repeat(limit-1)));
            var failure=assertThrows(AssessmentException.class, () -> service.prepareAssessment(operation,"s","x".repeat(limit)));
            assertEquals("INPUT_TOO_LARGE",failure.getMessage());
        }
        verifyNoInteractions(client);
    }
    @Test void operationTimeoutsAreExplicitAndFrozenWithConfiguration() throws Exception {
        var request=service.prepareAssessment("questionnaire_generation","s","m");
        ReflectionTestUtils.setField(service,"n8nWebhookUrl","");
        when(client.send(any(),any())).thenAnswer(call -> {
            HttpRequest r=call.getArgument(0); assertEquals(Duration.ofSeconds(60),r.timeout().orElseThrow());
            assertEquals("synthetic-secret",r.headers().firstValue("X-TB-Secret").orElseThrow());
            assertEquals("application/json",r.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("/relay",r.uri().getPath());
            HttpResponse<byte[]> response=mock(); when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn("{\"message\":\"{}\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); return response;
        });
        assertEquals("{}",service.executeAssessment(request));
        ReflectionTestUtils.setField(service,"n8nWebhookUrl","http://127.0.0.1:12345/relay");
        var evaluation=service.prepareAssessment("questionnaire_evaluation","s","m");
        doAnswer(call -> {
            assertEquals(Duration.ofSeconds(90),((HttpRequest)call.getArgument(0)).timeout().orElseThrow()); throw new HttpTimeoutException("SENSITIVE");
        }).when(client).send(any(),any());
        var failure=assertThrows(AssessmentException.class, () -> service.executeAssessment(evaluation));
        assertEquals("RELAY_FAILURE",failure.getMessage()); assertNull(failure.getCause());
    }
    @ParameterizedTest @ValueSource(strings={"", "http://external.invalid/relay", "https://user:password@example.invalid/relay", "not a URI"})
    void rejectsInvalidRelayWithoutDirectFallback(String url) {
        ReflectionTestUtils.setField(service,"n8nWebhookUrl",url);
        assertThrows(AssessmentException.class, () -> service.prepareAssessment("questionnaire_generation","s","m")); verifyNoInteractions(client);
    }
    @Test void rejectsBadSecretModelAndTimeoutBeforeAnyHttp() {
        ReflectionTestUtils.setField(service,"n8nWebhookSecret","secret\nheader");
        assertThrows(AssessmentException.class, () -> service.prepareAssessment("questionnaire_generation","s","m"));
        ReflectionTestUtils.setField(service,"n8nWebhookSecret","synthetic"); ReflectionTestUtils.setField(service,"evalModel","arbitrary");
        assertThrows(AssessmentException.class, () -> service.prepareAssessment("questionnaire_generation","s","m"));
        ReflectionTestUtils.setField(service,"evalModel","gpt-4o"); ReflectionTestUtils.setField(service,"generationTimeoutSeconds",61);
        assertThrows(AssessmentException.class, () -> service.prepareAssessment("questionnaire_generation","s","m")); verifyNoInteractions(client);
    }
    @ParameterizedTest @ValueSource(strings={"{}", "{\"message\":5}", "{\"message\":\"{}\",\"secret\":\"raw\"}", "{\"message\":\"{}\"}{}", "{\"message\":\"a\",\"message\":\"b\"}"})
    void rejectsMalformedRelayEnvelopesWithoutLoggingBodyOrCause(String body) throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(OpenAIService.class);
        var logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            HttpResponse<byte[]> response=mock(); when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn(body.getBytes());
            doReturn(response).when(client).send(any(),any());
            var p=service.prepareAssessment("questionnaire_generation","SENSITIVE-PROMPT","SENSITIVE-ANSWER");
            var failure=assertThrows(AssessmentException.class, () -> service.executeAssessment(p));
            assertNull(failure.getCause()); assertFalse(failure.getMessage().contains(body)); assertTrue(logs.list.isEmpty());
        } finally { logger.detachAppender(logs); logs.stop(); }
    }
    @Test void actualBodyTimerCancelsSubscriptionAndOverflowCancelsBeforeAccumulation() throws Exception {
        ReflectionTestUtils.setField(service,"generationTimeoutSeconds",1);
        @SuppressWarnings("unchecked") HttpResponse.ResponseInfo info=mock(HttpResponse.ResponseInfo.class);
        Flow.Subscription subscription=mock(Flow.Subscription.class);
        when(client.send(any(),any())).thenAnswer(call -> {
            HttpResponse.BodyHandler<byte[]> handler=call.getArgument(1);
            var subscriber=handler.apply(info); subscriber.onSubscribe(subscription);
            assertThrows(ExecutionException.class, () -> subscriber.getBody().toCompletableFuture().get(3,TimeUnit.SECONDS));
            verify(subscription,timeout(500)).cancel(); throw new HttpTimeoutException("SENSITIVE");
        });
        var prepared=service.prepareAssessment("questionnaire_generation","s","m");
        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> assertThrows(AssessmentException.class, () -> service.executeAssessment(prepared)));
        reset(client,subscription);
        when(client.send(any(),any())).thenAnswer(call -> {
            HttpResponse.BodyHandler<byte[]> handler=call.getArgument(1); var subscriber=handler.apply(info); subscriber.onSubscribe(subscription);
            subscriber.onNext(List.of(ByteBuffer.wrap(new byte[262145])));
            assertThrows(ExecutionException.class, () -> subscriber.getBody().toCompletableFuture().get()); verify(subscription).cancel();
            throw new java.io.IOException("SENSITIVE");
        });
        assertThrows(AssessmentException.class, () -> service.executeAssessment(prepared));
    }
    @Test void realSocketStalledHeadersAndBodyBothFinishWithinConfiguredDeadline() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newCachedThreadPool(); var release=new CountDownLatch(1);
        server.setExecutor(executor);
        server.createContext("/headers", e -> { try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException x) { Thread.currentThread().interrupt(); } finally { e.close(); } });
        server.createContext("/body", e -> { e.sendResponseHeaders(200,0); e.getResponseBody().write('{'); e.getResponseBody().flush();
            try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException x) { Thread.currentThread().interrupt(); } finally { e.close(); } });
        server.start();
        try {
            ReflectionTestUtils.setField(service,"client",HttpClient.newHttpClient()); ReflectionTestUtils.setField(service,"generationTimeoutSeconds",1);
            for (String path:List.of("/headers","/body")) {
                ReflectionTestUtils.setField(service,"n8nWebhookUrl","http://127.0.0.1:"+server.getAddress().getPort()+path);
                var prepared=service.prepareAssessment("questionnaire_generation","s","m");
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertThrows(AssessmentException.class, () -> service.executeAssessment(prepared)));
            }
        } finally { release.countDown(); server.stop(0); executor.shutdownNow(); }
    }
}
