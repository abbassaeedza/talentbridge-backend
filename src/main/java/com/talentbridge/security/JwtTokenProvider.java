package com.talentbridge.security;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.security.Key;
import java.util.Date;
import java.util.UUID;

@Component @Slf4j
public class JwtTokenProvider {
    @Value("${jwt.secret}") private String jwtSecret;
    @Value("${jwt.expiration-ms}") private long jwtExpiration;
    @Value("${jwt.refresh-expiration-ms}") private long refreshExpiration;

    private Key key() { return Keys.hmacShaKeyFor(jwtSecret.getBytes()); }

    public String generateAccessToken(UUID userId, String email, String role) {
        return Jwts.builder().setSubject(userId.toString())
            .claim("email", email).claim("role", role)
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + jwtExpiration))
            .signWith(key(), SignatureAlgorithm.HS256).compact();
    }

    public String generateRefreshToken(UUID userId) {
        return Jwts.builder().setSubject(userId.toString()).claim("type", "refresh")
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + refreshExpiration))
            .signWith(key(), SignatureAlgorithm.HS256).compact();
    }

    public UUID getUserIdFromToken(String token) {
        return UUID.fromString(claims(token).getSubject());
    }
    public String getRoleFromToken(String token) { return claims(token).get("role", String.class); }
    public boolean validateToken(String token) {
        try { claims(token); return true; }
        catch (JwtException | IllegalArgumentException e) { log.debug("Invalid JWT: {}", e.getMessage()); return false; }
    }
    private Claims claims(String token) {
        return Jwts.parserBuilder().setSigningKey(key()).build().parseClaimsJws(token).getBody();
    }
}
