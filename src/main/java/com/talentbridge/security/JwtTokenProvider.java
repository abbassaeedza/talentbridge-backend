package com.talentbridge.security;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.security.Key;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenProvider {
    @Value("${jwt.secret}") private String jwtSecret;
    @Value("${jwt.expiration-ms}") private long jwtExpiration;
    @Value("${jwt.refresh-expiration-ms}") private long refreshExpiration;

    private Key key() { return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)); }

    public String generateAccessToken(UUID userId, String email, String role, int version) {
        return Jwts.builder().setSubject(userId.toString())
            .claim("email", email).claim("role", role).claim("type", "access").claim("ver", version)
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + jwtExpiration))
            .signWith(key(), SignatureAlgorithm.HS256).compact();
    }

    public String generateRefreshToken(UUID userId, int version) {
        return Jwts.builder().setSubject(userId.toString()).claim("type", "refresh").claim("ver", version)
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + refreshExpiration))
            .signWith(key(), SignatureAlgorithm.HS256).compact();
    }

    public TokenIdentity parseAccessToken(String token) {
        return parse(token, "access");
    }
    public TokenIdentity parseRefreshToken(String token) {
        return parse(token, "refresh");
    }
    private TokenIdentity parse(String token, String type) {
        Claims claims = Jwts.parserBuilder().setSigningKey(key()).require("type", type)
                .build().parseClaimsJws(token).getBody();
        Integer version = claims.get("ver", Integer.class);
        if (version == null || version < 0 || claims.getExpiration() == null || claims.getSubject() == null)
            throw new JwtException("Missing or invalid token claims");
        return new TokenIdentity(UUID.fromString(claims.getSubject()), version);
    }

    public record TokenIdentity(UUID userId, int version) {}
}
