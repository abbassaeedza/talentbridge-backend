package com.talentbridge.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupControllerLifecycleTest {
    @Test
    void httpProbeWaitsForStartupRunnerEvenAfterThePortOpens() throws Exception {
        Gate gate = new Gate();
        AtomicInteger port = new AtomicInteger();
        SpringApplication app = new SpringApplication(TestApplication.class);
        app.addInitializers(context -> context.getBeanFactory().registerSingleton("startupGate", gate));
        app.addListeners((ApplicationListener<WebServerInitializedEvent>) event -> port.set(event.getWebServer().getPort()));
        var executor = Executors.newSingleThreadExecutor();
        var started = CompletableFuture.supplyAsync(() -> app.run("--server.port=0",
                "--spring.main.banner-mode=off", "--debug=false", "--logging.level.root=ERROR",
                "--logging.level.com.talentbridge=ERROR"), executor);
        try {
            assertTrue(gate.entered.await(20, TimeUnit.SECONDS));
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port.get() + "/api/health/startup"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            assertEquals(503, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
            gate.release.countDown();
            started.get(10, TimeUnit.SECONDS);
            assertEquals(200, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        } finally {
            gate.release.countDown();
            try { started.get(20, TimeUnit.SECONDS).close(); }
            finally { executor.shutdownNow(); }
        }
    }

    static class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class, SecurityAutoConfiguration.class})
    @Import(StartupController.class)
    static class TestApplication {
        @Bean
        ApplicationRunner delayedStartup(Gate gate) {
            return args -> {
                gate.entered.countDown();
                if (!gate.release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Startup test timed out");
            };
        }
    }
}
