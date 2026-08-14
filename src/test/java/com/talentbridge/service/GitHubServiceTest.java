package com.talentbridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitHubServiceTest {
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
