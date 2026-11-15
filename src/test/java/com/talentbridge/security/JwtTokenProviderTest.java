package com.talentbridge.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {
    static final String SECRET = "synthetic-jwt-test-signing-key-not-a-production-secret";
    final JwtTokenProvider provider = new JwtTokenProvider();
    final UUID userId = UUID.fromString("38c1e428-0d9b-4344-a464-7c8e1ad530cd");

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(provider, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(provider, "jwtExpiration", 60000L);
        ReflectionTestUtils.setField(provider, "refreshExpiration", 120000L);
    }

    @Test
    void generatedTokensCarryPurposeAndExactCredentialVersion() {
        String access = provider.generateAccessToken(userId, "synthetic@example.test", "STUDENT", 7);
        String refresh = provider.generateRefreshToken(userId, 7);
        assertEquals(new JwtTokenProvider.TokenIdentity(userId, 7), provider.parseAccessToken(access));
        assertEquals(new JwtTokenProvider.TokenIdentity(userId, 7), provider.parseRefreshToken(refresh));
        assertThrows(JwtException.class, () -> provider.parseRefreshToken(access));
        assertThrows(JwtException.class, () -> provider.parseAccessToken(refresh));
    }

    @Test
    void rejectsLegacyAndMalformedVersionsRatherThanDefaultingToZero() {
        for (Object version : new Object[] {null, -1, "0", 0.5, 2147483648L}) {
            String token = signed("access", version, userId.toString(), new Date(System.currentTimeMillis() + 60000), SECRET);
            assertThrows(JwtException.class, () -> provider.parseAccessToken(token));
        }
    }

    @Test
    void requiresAnExplicitPurposeAndExpiration() {
        assertThrows(JwtException.class, () -> provider.parseAccessToken(signed(null, 0, userId.toString(),
                new Date(System.currentTimeMillis() + 60000), SECRET)));
        assertThrows(JwtException.class, () -> provider.parseAccessToken(signed("access", 0, userId.toString(), null, SECRET)));
    }

    @Test
    void rejectsAMissingSubjectAsAnInvalidTokenInsteadOfANullPointer() {
        assertThrows(JwtException.class, () -> provider.parseRefreshToken(signed("refresh", 0, null,
                new Date(System.currentTimeMillis() + 60000), SECRET)));
    }

    @Test
    void rejectsExpiredForgedAndMalformedTokens() {
        assertThrows(JwtException.class, () -> provider.parseAccessToken(signed("access", 0, userId.toString(), new Date(1), SECRET)));
        assertThrows(JwtException.class, () -> provider.parseAccessToken(signed("access", 0, userId.toString(),
                new Date(System.currentTimeMillis() + 60000), SECRET + "wrong")));
        assertThrows(JwtException.class, () -> provider.parseAccessToken("not.a.valid.jwt"));
        assertThrows(IllegalArgumentException.class, () -> provider.parseAccessToken(signed("access", 0, "not-a-uuid",
                new Date(System.currentTimeMillis() + 60000), SECRET)));
    }

    private String signed(String type, Object version, String subject, Date expiration, String secret) {
        return Jwts.builder().setSubject(subject).claim("type", type).claim("ver", version).setExpiration(expiration)
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256).compact();
    }
}
