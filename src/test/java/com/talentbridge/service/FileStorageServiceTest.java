package com.talentbridge.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FileStorageServiceTest {

    @Test
    void uploadsToAuthenticatedSupabaseStorageAndReturnsPublicUrl() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/storage/v1/object/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            apiKey.set(exchange.getRequestHeaders().getFirst("apikey"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();

        try {
            FileStorageService service = new FileStorageService();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ReflectionTestUtils.setField(service, "supabaseUrl", baseUrl);
            ReflectionTestUtils.setField(service, "supabaseSecretKey", "server-secret");
            ReflectionTestUtils.setField(service, "supabaseBucket", "talentbridge-files");
            MockMultipartFile file = new MockMultipartFile(
                "documents", "project brief.pdf", "application/pdf", "document-body".getBytes(StandardCharsets.UTF_8));

            String result = service.upload(file, "submissions/party-id");

            assertTrue(path.get().startsWith("/storage/v1/object/talentbridge-files/submissions/party-id/"));
            assertTrue(path.get().endsWith("_project_brief.pdf"));
            assertEquals("server-secret", apiKey.get());
            assertNull(authorization.get());
            assertEquals("application/pdf", contentType.get());
            assertEquals("document-body", body.get());
            assertEquals(baseUrl + path.get().replace("/storage/v1/object/", "/storage/v1/object/public/"), result);
        } finally {
            server.stop(0);
        }
    }
}
