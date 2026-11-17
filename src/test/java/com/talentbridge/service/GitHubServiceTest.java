package com.talentbridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GitHubServiceTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "https://frontend.example.test/github/callback",
            "https://other.example.test/github/callback?next=/profile&label=a+b c#linked"
    })
    void sendsConfiguredRedirectUriInOAuthTokenExchange(String redirectUri) throws Exception {
        HttpClient client = mock(HttpClient.class);
        GitHubService service = oauthService(client, 200, "{\"access_token\":\"synthetic-token\"}");
        ReflectionTestUtils.setField(service, "redirectUri", redirectUri);

        assertEquals(Map.of("access_token", "synthetic-token"), service.exchangeCodeForToken("code+&= value"));

        ArgumentCaptor<HttpRequest> outgoing = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(outgoing.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest request = outgoing.getValue();
        assertEquals(URI.create("https://github.com/login/oauth/access_token"), request.uri());
        assertEquals("POST", request.method());
        assertEquals("application/x-www-form-urlencoded", request.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("application/json", request.headers().firstValue("Accept").orElseThrow());
        HttpResponse.BodySubscriber<String> body = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { body.onSubscribe(subscription); }
            public void onNext(ByteBuffer item) { body.onNext(List.of(item)); }
            public void onError(Throwable error) { body.onError(error); }
            public void onComplete() { body.onComplete(); }
        });
        Map<String, String> form = Arrays.stream(body.getBody().toCompletableFuture().join().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
        assertEquals(Map.of("client_id", "client+&= value", "client_secret", "secret+&= value",
                "code", "code+&= value", "redirect_uri", redirectUri), form);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"access_token\":\"\"}", "{\"access_token\":\"   \"}",
            "{\"error\":\"bad_verification_code\"}"})
    void rejectsOAuthResponsesWithoutAUsableToken(String body) throws Exception {
        GitHubService service = oauthService(mock(HttpClient.class), 200, body);

        var error = assertThrows(com.talentbridge.exception.BadRequestException.class,
                () -> service.exchangeCodeForToken("synthetic-code"));

        assertEquals("GitHub authorization failed. Please connect your account again.", error.getMessage());
    }

    @Test
    void rejectsOAuthTokenExchangeWhenGitHubRejectsTheRequest() throws Exception {
        GitHubService service = oauthService(mock(HttpClient.class), 401, "{\"message\":\"Bad credentials\"}");

        assertThrows(com.talentbridge.exception.BadRequestException.class,
                () -> service.exchangeCodeForToken("synthetic-code"));
    }

    @SuppressWarnings("unchecked")
    private GitHubService oauthService(HttpClient client, int status, String body) throws Exception {
        GitHubService service = new GitHubService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "client", client);
        ReflectionTestUtils.setField(service, "clientId", "client+&= value");
        ReflectionTestUtils.setField(service, "clientSecret", "secret+&= value");
        ReflectionTestUtils.setField(service, "redirectUri", "https://frontend.example.test/github/callback");
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        return service;
    }

    @Test
    void fetchesContributorStatsAndCountsOnce() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/acme/demo/contributors", exchange -> {
            requests.incrementAndGet();
            byte[] body = "[{\"login\":\"alice\",\"contributions\":7}]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            GitHubService service = new GitHubService(new ObjectMapper());
            ReflectionTestUtils.setField(service, "apiUrl", "http://localhost:" + server.getAddress().getPort());

            GitHubService.ContributorData result = service.fetchContributorData("https://github.com/acme/demo", null);

            assertEquals(1, requests.get());
            assertEquals(7, result.commitCounts().get("alice"));
            assertEquals("Contributor: alice | Commits: 7", result.stats().get(0));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsAUsernameLookupWhenGitHubRejectsTheToken() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/user", exchange -> {
            byte[] body = "{\"message\":\"Bad credentials\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            GitHubService service = new GitHubService(new ObjectMapper());
            ReflectionTestUtils.setField(service, "apiUrl", "http://localhost:" + server.getAddress().getPort());

            var error = assertThrows(com.talentbridge.exception.BadRequestException.class,
                    () -> service.getGitHubUsername("invalid-token"));

            assertEquals("Could not verify the GitHub account. Please connect it again.", error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsAnEmptyOAuthTokenBeforeSavingAUser() {
        GitHubService service = new GitHubService(new ObjectMapper());

        assertThrows(com.talentbridge.exception.BadRequestException.class,
                () -> service.getGitHubUsername(""));
    }
}
