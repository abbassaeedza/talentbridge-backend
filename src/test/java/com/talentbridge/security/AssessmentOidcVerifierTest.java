package com.talentbridge.security;

import com.google.auth.oauth2.TokenVerifier;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.security.*;
import java.time.*;
import java.util.*;
import java.net.http.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class AssessmentOidcVerifierTest {
    final Instant now = Instant.parse("2026-11-21T00:00:00Z");
    final String audience = "https://synthetic.invalid", email = "worker@synthetic.iam.gserviceaccount.com";
    KeyPair key, other;
    AssessmentOidcVerifier verifier;

    @BeforeEach void setup() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        key = generator.generateKeyPair(); other = generator.generateKeyPair();
        var google = TokenVerifier.newBuilder().setIssuer("https://accounts.google.com").setAudience(audience)
                .setClock(() -> now.toEpochMilli()).setPublicKey(key.getPublic()).build();
        verifier = new AssessmentOidcVerifier(audience, email, Clock.fixed(now, ZoneOffset.UTC), google);
    }

    @Test void acceptsOnlyTheSignedDedicatedGoogleCaller() {
        assertTrue(verifier.verify(token(Map.of(), key.getPrivate(), true)));
        assertFalse(verifier.verify(token(Map.of(), other.getPrivate(), true)));
    }

    @Test void rejectsMissingExpiryExpiredWrongIssuerAudienceIdentityAndVerifiedClaim() {
        assertFalse(verifier.verify(token(Map.of(), key.getPrivate(), false)));
        for (Map<String,Object> claims : List.<Map<String,Object>>of(
                Map.of("iss", "accounts.google.com"), Map.of("aud", "other"), Map.of("email", "other@synthetic.invalid"),
                Map.of("email_verified", false), Map.of("email_verified", "true"), Map.of("exp", now.getEpochSecond()))) {
            assertFalse(verifier.verify(token(claims, key.getPrivate(), true)), claims.keySet().toString());
        }
        assertFalse(verifier.verify(null)); assertFalse(verifier.verify("not-a-token"));
        assertFalse(verifier.verify(Jwts.builder().setSubject("coordinator").setExpiration(Date.from(now.plusSeconds(60)))
                .signWith(io.jsonwebtoken.security.Keys.secretKeyFor(SignatureAlgorithm.HS256)).compact()));
    }

    @Test void emptyConfigurationFailsClosedWithoutDiscoveryOrAdc() {
        assertFalse(new AssessmentOidcVerifier("", "", Clock.fixed(now, ZoneOffset.UTC)).verify(token(Map.of(), key.getPrivate(), true)));
    }

    @Test void coldGoogleCertificatesHaveOneOverallHeaderAndBodyDeadlineAndCancelTheRequest() {
        HttpClient client=mock(); CompletableFuture<HttpResponse<byte[]>> pending=new CompletableFuture<>();
        when(client.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(pending);
        var transport=new GoogleCertificateTransport(client,Duration.ofMillis(80));
        var bounded=TokenVerifier.newBuilder().setAudience(audience).setIssuer("https://accounts.google.com")
                .setClock(()->now.toEpochMilli()).setHttpTransportFactory(()->transport).build();
        var cold=new AssessmentOidcVerifier(audience,email,Clock.fixed(now,ZoneOffset.UTC),bounded);
        long start=System.nanoTime();
        assertFalse(cold.verify(token(Map.of(),key.getPrivate(),true)));
        assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<1500);
        assertTrue(pending.isCancelled());
        verify(client,times(1)).sendAsync(argThat(r -> r.uri().toString().equals("https://www.googleapis.com/oauth2/v3/certs")),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @Test void malformedJsonTypesMissingClaimsOversizeAndNonRsaTokensFailClosed() {
        for (String value : List.of("not-json", "[]", "null", "{\"exp\":{}}")) {
            String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertFalse(verifier.verify(encoded+"."+encoded+".c2lnbmF0dXJl"));
            String header=Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"RS256\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertFalse(verifier.verify(header+"."+encoded+".c2lnbmF0dXJl"));
        }
        assertFalse(verifier.verify("x".repeat(16385)));
        for (String claim : List.of("email","email_verified")) assertFalse(verifier.verify(tokenWithout(claim)));
        assertFalse(verifier.verify(token(Map.of("exp","not-a-number"),key.getPrivate(),true)));
        assertFalse(verifier.verify(token(Map.of("aud",List.of(audience)),key.getPrivate(),true)));
        var ec=io.jsonwebtoken.security.Keys.keyPairFor(SignatureAlgorithm.ES256);
        assertFalse(verifier.verify(Jwts.builder().setIssuer("https://accounts.google.com").setAudience(audience)
                .claim("email",email).claim("email_verified",true).setExpiration(Date.from(now.plusSeconds(60)))
                .signWith(ec.getPrivate(),SignatureAlgorithm.ES256).compact()));
    }

    @Test void actualStalledCertificateBodyIsCancelledAfterHeadersWithoutAnyProviderCall() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var release=new CountDownLatch(1); var headersSeen=new java.util.concurrent.atomic.AtomicBoolean();
        var actual=new java.util.concurrent.atomic.AtomicReference<CompletableFuture<HttpResponse<byte[]>>>();
        server.createContext("/certs",exchange->{
            exchange.sendResponseHeaders(200,0); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            HttpClient boundary=mock(); var local=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            when(boundary.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(call->{
                HttpResponse.BodyHandler<byte[]> handler=call.getArgument(1);
                var pending=local.sendAsync(HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/certs")).build(),info->{headersSeen.set(true); return handler.apply(info);});
                actual.set(pending); return pending;
            });
            var transport=new GoogleCertificateTransport(boundary,Duration.ofMillis(500));
            var google=TokenVerifier.newBuilder().setAudience(audience).setIssuer("https://accounts.google.com")
                    .setClock(()->now.toEpochMilli()).setHttpTransportFactory(()->transport).build();
            long start=System.nanoTime();
            assertFalse(new AssessmentOidcVerifier(audience,email,Clock.fixed(now,ZoneOffset.UTC),google).verify(token(Map.of(),key.getPrivate(),true)));
            assertTrue(headersSeen.get(),"The body, not connection setup, must be stalled");
            // JDK HttpClient may complete exceptionally with nested cancellation instead of isCancelled().
            var cancelled=assertThrows(CompletionException.class,()->actual.get().join());
            assertInstanceOf(CancellationException.class,cancelled.getCause());
            assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<1500);
            verify(boundary,times(1)).sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void failedGoogleCertificateStatusIsFailClosedWithoutRetryBackoffOrRawBodyExposure() {
        HttpClient client=mock(); HttpResponse<byte[]> response=mock();
        when(response.statusCode()).thenReturn(500);
        when(client.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
        var google=TokenVerifier.newBuilder().setAudience(audience).setIssuer("https://accounts.google.com")
                .setClock(()->now.toEpochMilli()).setHttpTransportFactory(()->new GoogleCertificateTransport(client,Duration.ofMillis(80))).build();
        assertFalse(new AssessmentOidcVerifier(audience,email,Clock.fixed(now,ZoneOffset.UTC),google).verify(token(Map.of(),key.getPrivate(),true)));
        verify(client,times(1)).sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        verify(response,never()).body();
    }

    @Test void exactCertificateByteLimitCompletesWithoutCancellingTheActualSubscriber() throws Exception {
        HttpClient client=mock(); var subscription=mock(Flow.Subscription.class);
        byte[] expected=new byte[262144]; Arrays.fill(expected,0,131072,(byte)3); Arrays.fill(expected,131072,expected.length,(byte)9);
        streamCertificates(client,subscription,false);
        var response=new GoogleCertificateTransport(client,Duration.ofMillis(80))
                .buildRequest("GET","https://www.googleapis.com/oauth2/v3/certs").execute();
        assertEquals(200,response.getStatusCode()); assertEquals(expected.length,response.getContentLength());
        assertArrayEquals(expected,response.getContent().readAllBytes());
        verify(subscription,never()).cancel();
        verify(client,times(1)).sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @Test void certificateByteLimitPlusOneCancelsAndFailsWithoutPartialContentOrCause() {
        HttpClient client=mock(); var subscription=mock(Flow.Subscription.class);
        streamCertificates(client,subscription,true);
        var failure=assertThrows(java.io.IOException.class,()->new GoogleCertificateTransport(client,Duration.ofMillis(80))
                .buildRequest("GET","https://www.googleapis.com/oauth2/v3/certs").execute());
        assertEquals("GOOGLE_CERTIFICATES_UNAVAILABLE",failure.getMessage()); assertNull(failure.getCause());
        verify(subscription,times(1)).cancel();
        verify(client,times(1)).sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    void streamCertificates(HttpClient client,Flow.Subscription subscription,boolean overflow) {
        when(client.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(call->{
            HttpResponse.BodyHandler<byte[]> handler=call.getArgument(1);
            var subscriber=handler.apply(mock(HttpResponse.ResponseInfo.class)); subscriber.onSubscribe(subscription);
            byte[] offset=new byte[131073]; Arrays.fill(offset,(byte)3); offset[0]=7;
            byte[] second=new byte[131072]; Arrays.fill(second,(byte)9);
            subscriber.onNext(List.of(java.nio.ByteBuffer.wrap(offset,1,131072)));
            subscriber.onNext(List.of(java.nio.ByteBuffer.wrap(second)));
            if(overflow) subscriber.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[]{11})));
            else subscriber.onComplete();
            return subscriber.getBody().toCompletableFuture().thenApply(bytes->{
                HttpResponse<byte[]> response=mock(); when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn(bytes); return response;
            });
        });
    }

    String tokenWithout(String missing) {
        var claims=new HashMap<String,Object>(Map.of("iss","https://accounts.google.com","aud",audience,"exp",now.plusSeconds(60).getEpochSecond(),"email",email,"email_verified",true));
        claims.remove(missing); return Jwts.builder().setClaims(claims).signWith(key.getPrivate(),SignatureAlgorithm.RS256).compact();
    }

    String token(Map<String,Object> overrides, PrivateKey signingKey, boolean expiry) {
        Map<String,Object> claims = new HashMap<>(Map.of("iss", "https://accounts.google.com", "aud", audience,
                "email", email, "email_verified", true, "sub", "synthetic-service-account"));
        if (expiry) claims.put("exp", now.plusSeconds(60).getEpochSecond());
        claims.putAll(overrides);
        return Jwts.builder().setClaims(claims).signWith(signingKey, SignatureAlgorithm.RS256).compact();
    }
}
